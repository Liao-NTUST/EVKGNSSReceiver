package com.example.evkgnssreceiver

import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal enum class NtripState(val label: String) {
    DISCONNECTED("未連線"),
    WAITING_FOR_FIX("等待 EVK 定位（需要 GGA 才能連線）"),
    CONNECTING("連線中"),
    STREAMING("接收修正資料中"),
    RECONNECTING("連線中斷，準備重試"),
    FAILED("連線失敗")
}

/** fatal = true 表示重試也不會成功（帳密錯誤、掛載點錯誤），不自動重連。 */
internal class NtripException(message: String, val fatal: Boolean) : IOException(message)

/**
 * NTRIP Client（NTRIP 1.0 請求，PointPerfect 會自動判斷版本）。
 *
 * - 連線前先等到 EVK 有定位，才有 GGA 可送
 * - 每 10 秒上傳 GGA（PointPerfect 30 秒沒收到 GGA 會斷線）
 * - 收到的 SPARTN bytes 原封不動交給 onCorrection，由呼叫端寫進 EVK USB
 * - 網路中斷自動重連；帳密或掛載點錯誤則停止
 *
 * 所有 callback 都在背景執行緒呼叫。
 */
internal class NtripClient(
    private val settings: NtripSettings,
    private val ggaProvider: () -> String?,
    private val onCorrection: (ByteArray) -> Unit,
    private val onState: (NtripState, String) -> Unit
) {
    @Volatile
    private var running = false

    @Volatile
    private var socket: Socket? = null

    private var thread: Thread? = null

    // 本次連線開始串流的時間，用來判斷是否重設重試間隔
    @Volatile
    private var streamStartedAt = 0L

    fun start() {
        if (running) return
        running = true
        thread = Thread(::runLoop, "ntrip-client").apply { start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        // 關閉 TLS socket 可能需要網路 I/O，不在主執行緒執行
        Thread(::closeSocket, "ntrip-close").start()
    }

    // --------------------------------------------------
    // 連線主迴圈
    // --------------------------------------------------

    private fun runLoop() {
        var retryDelayMs = INITIAL_RETRY_MS
        var finalState = NtripState.DISCONNECTED
        var finalMessage = "已中斷"

        while (running) {
            streamStartedAt = 0L
            var reason = "連線中斷"
            try {
                val gga = waitForGga() ?: break
                session(gga)
            } catch (e: NtripException) {
                if (running && e.fatal) {
                    finalState = NtripState.FAILED
                    finalMessage = e.message ?: "未知錯誤"
                    running = false
                    break
                }
                reason = e.message ?: reason
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "NTRIP session ended", e)
                reason = describe(e)
            } finally {
                closeSocket()
            }

            if (!running) break
            // 已正常串流一段時間後才斷線，重試間隔從頭計算
            val started = streamStartedAt
            if (started > 0 && System.currentTimeMillis() - started > 60_000) {
                retryDelayMs = INITIAL_RETRY_MS
            }
            onState(NtripState.RECONNECTING, "$reason，${retryDelayMs / 1000} 秒後重試")
            try {
                Thread.sleep(retryDelayMs)
            } catch (_: InterruptedException) {
                break
            }
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
        }
        closeSocket()
        onState(finalState, finalMessage)
    }

    private fun waitForGga(): String? {
        var announced = false
        while (running) {
            ggaProvider()?.let { return it }
            if (!announced) {
                onState(NtripState.WAITING_FOR_FIX, "EVK 尚未 3D 定位，定位後會自動連線")
                announced = true
            }
            Thread.sleep(1000)
        }
        return null
    }

    /** 執行一次連線；串流期間持續阻塞，連線結束時以例外返回。 */
    private fun session(firstGga: String) {
        onState(NtripState.CONNECTING, "${settings.host}:${settings.port}/${mountpoint()}")
        val s = openSocket()
        s.soTimeout = HEADER_TIMEOUT_MS
        val output = s.getOutputStream()
        val input = BufferedInputStream(s.getInputStream(), 8192)

        synchronized(output) {
            output.write(request(mountpoint()).toByteArray(StandardCharsets.US_ASCII))
            output.write(firstGga.toByteArray(StandardCharsets.US_ASCII))
            output.flush()
        }
        val chunked = readStreamHeader(input)

        s.soTimeout = DATA_TIMEOUT_MS
        streamStartedAt = System.currentTimeMillis()
        onState(NtripState.STREAMING, mountpoint())

        val ggaThread = Thread({ ggaLoop(output) }, "ntrip-gga").apply {
            isDaemon = true
            start()
        }
        try {
            if (chunked) readChunked(input) else readRaw(input)
        } catch (e: SocketTimeoutException) {
            throw IOException("${DATA_TIMEOUT_MS / 1000} 秒沒有收到修正資料")
        } finally {
            ggaThread.interrupt()
        }
    }

    private fun ggaLoop(output: OutputStream) {
        try {
            while (running && !Thread.currentThread().isInterrupted) {
                Thread.sleep(GGA_INTERVAL_MS)
                val gga = ggaProvider() ?: continue // 暫時沒定位就跳過；Caster 30 秒後才會斷線
                synchronized(output) {
                    output.write(gga.toByteArray(StandardCharsets.US_ASCII))
                    output.flush()
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: IOException) {
            Log.w(TAG, "GGA upload failed", e)
            closeSocket() // 讓讀取端立即結束並重連
        }
    }

    // --------------------------------------------------
    // Socket / TLS
    // --------------------------------------------------

    private fun openSocket(): Socket {
        val plain = Socket()
        socket = plain
        plain.connect(InetSocketAddress(settings.host, settings.port), CONNECT_TIMEOUT_MS)
        if (!settings.useTls) return plain

        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val tls = factory.createSocket(plain, settings.host, settings.port, true) as SSLSocket
        socket = tls
        tls.soTimeout = HEADER_TIMEOUT_MS
        tls.startHandshake()
        // SSLSocket 不會自動驗證主機名稱，必須自行檢查憑證
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(settings.host, tls.session)) {
            throw NtripException("TLS 憑證與主機名稱不符：${settings.host}", fatal = true)
        }
        return tls
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun mountpoint() = settings.mountpoint.trim().trimStart('/')

    private fun request(path: String): String {
        val credentials = Base64.getEncoder().encodeToString(
            "${settings.username}:${settings.password}".toByteArray(StandardCharsets.UTF_8)
        )
        return "GET /$path HTTP/1.0\r\n" +
                "Host: ${settings.host}\r\n" +
                "User-Agent: $USER_AGENT\r\n" +
                "Accept: */*\r\n" +
                "Authorization: Basic $credentials\r\n" +
                "Connection: close\r\n" +
                "\r\n"
    }

    // --------------------------------------------------
    // 回應解析
    // --------------------------------------------------

    /** 回傳 true 表示 body 使用 HTTP chunked 傳輸。 */
    private fun readStreamHeader(input: BufferedInputStream): Boolean {
        val status = readLine(input) ?: throw IOException("Caster 沒有回應就關閉連線")
        when {
            status.startsWith("ICY 200") -> {
                skipLeadingNewlines(input)
                return false
            }
            status.startsWith("SOURCETABLE 200") -> throw NtripException(
                "找不到掛載點「${mountpoint()}」，請到 NTRIP 設定確認 Mountpoint", fatal = true
            )
        }

        val code = status.split(' ').getOrNull(1)?.toIntOrNull()
            ?: throw IOException("無法辨識的回應：$status")
        val headers = readHeaders(input)
        when (code) {
            200 -> {
                if (headers["content-type"]?.contains("sourcetable") == true) {
                    throw NtripException(
                        "找不到掛載點「${mountpoint()}」，請到 NTRIP 設定確認 Mountpoint", fatal = true
                    )
                }
                return headers["transfer-encoding"]?.contains("chunked") == true
            }
            401 -> throw NtripException("帳號或密碼錯誤（401），請確認 Thingstream Credentials", fatal = true)
            403 -> throw NtripException("沒有權限（403），請確認 Thing 已啟用 PointPerfect 方案", fatal = true)
            404 -> throw NtripException("找不到掛載點「${mountpoint()}」（404）", fatal = true)
            else -> throw IOException("Caster 回應：$status")
        }
    }

    private fun readRaw(input: InputStream) {
        val buffer = ByteArray(4096)
        while (running) {
            val length = input.read(buffer)
            if (length < 0) throw IOException("Caster 關閉連線")
            if (length > 0) onCorrection(buffer.copyOf(length))
        }
    }

    private fun readChunked(input: BufferedInputStream) {
        val buffer = ByteArray(4096)
        while (running) {
            val sizeLine = readLine(input) ?: throw IOException("Caster 關閉連線")
            if (sizeLine.isBlank()) continue
            var remaining = sizeLine.substringBefore(';').trim().toInt(16)
            if (remaining == 0) throw IOException("Caster 結束串流")
            while (remaining > 0) {
                val length = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (length < 0) throw IOException("Caster 關閉連線")
                remaining -= length
                if (length > 0) onCorrection(buffer.copyOf(length))
            }
            readLine(input) // chunk 結尾的 CRLF
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "找不到主機 ${settings.host}，請確認網路或 Server"
        is java.net.ConnectException -> "無法連到 ${settings.host}:${settings.port}"
        is SocketTimeoutException -> "連線逾時"
        is javax.net.ssl.SSLException -> "TLS 錯誤：${e.message}"
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        private const val TAG = "EVK_NTRIP"
        private const val USER_AGENT = "NTRIP EVKGNSSReceiver/1.0"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val HEADER_TIMEOUT_MS = 15_000
        private const val DATA_TIMEOUT_MS = 30_000
        private const val GGA_INTERVAL_MS = 10_000L
        private const val INITIAL_RETRY_MS = 2_000L
        private const val MAX_RETRY_MS = 30_000L

        internal fun readLine(input: InputStream, maxLength: Int = 4096): String? {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value < 0) return if (bytes.size() == 0) null else bytes.toString("ISO-8859-1")
                if (value == '\n'.code) break
                if (value != '\r'.code) bytes.write(value)
                if (bytes.size() > maxLength) throw IOException("回應的單行過長")
            }
            return bytes.toString("ISO-8859-1")
        }

        internal fun readHeaders(input: InputStream): Map<String, String> {
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim().lowercase()
                }
            }
            return headers
        }

        /** 有些 Caster 會在 ICY 200 OK 後多送一個空行；SPARTN 封包以 0x73 開頭，不會被誤刪。 */
        private fun skipLeadingNewlines(input: BufferedInputStream) {
            repeat(2) {
                input.mark(1)
                val value = input.read()
                if (value != '\r'.code && value != '\n'.code) {
                    input.reset()
                    return
                }
            }
        }

        /**
         * 下載 Caster 的 Sourcetable，用來確認帳密並列出可用的掛載點。
         * 會進行網路連線，必須在背景執行緒呼叫。
         */
        fun fetchMountpoints(settings: NtripSettings): List<String> {
            val probe = NtripClient(settings, { null }, {}, { _, _ -> })
            probe.running = true
            try {
                val s = probe.openSocket()
                s.soTimeout = HEADER_TIMEOUT_MS
                val output = s.getOutputStream()
                output.write(probe.request("").toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                val input = BufferedInputStream(s.getInputStream())
                val status = readLine(input) ?: throw IOException("Caster 沒有回應")
                val code = status.split(' ').getOrNull(1)?.toIntOrNull()
                if (code == 401) throw NtripException("帳號或密碼錯誤（401）", fatal = true)
                if (!status.startsWith("SOURCETABLE 200") && code != 200) {
                    throw IOException("Caster 回應：$status")
                }
                readHeaders(input)

                val mountpoints = mutableListOf<String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.startsWith("ENDSOURCETABLE")) break
                    if (line.startsWith("STR;")) {
                        val fields = line.split(';')
                        val name = fields.getOrNull(1).orEmpty()
                        val format = fields.getOrNull(3).orEmpty()
                        if (name.isNotEmpty()) {
                            mountpoints += if (format.isNotEmpty()) "$name｜$format" else name
                        }
                    }
                }
                return mountpoints
            } finally {
                probe.running = false
                probe.closeSocket()
            }
        }
    }
}
