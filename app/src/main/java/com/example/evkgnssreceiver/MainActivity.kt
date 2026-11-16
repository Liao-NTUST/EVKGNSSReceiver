
package com.example.evkgnssreceiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

class MainActivity : AppCompatActivity() {

    // --------------------------------------------------
    // 1. USB 相關變數
    // --------------------------------------------------

    private val usbPermissionAction =
        "com.example.evkgnssreceiver.USB_PERMISSION"

    private lateinit var usbManager: UsbManager

    // 目前開啟的 EVK USB Serial Port
    private var serialPort: UsbSerialPort? = null

    // 背景 USB 接收執行緒
    private var readThread: Thread? = null

    @Volatile
    private var reading = false

    // --------------------------------------------------
    // 2. 畫面元件
    // --------------------------------------------------

    private lateinit var statusText: TextView
    private lateinit var dataText: TextView
    private lateinit var mqttStatusText: TextView

    // MQTT 四個可編輯欄位
    private lateinit var mqttHostInput: EditText
    private lateinit var mqttPortInput: EditText
    private lateinit var mqttUsernameInput: EditText
    private lateinit var mqttPasswordInput: EditText

    // --------------------------------------------------
    // 3. MQTT Publisher
    // --------------------------------------------------

    private val mqttPublisher = MqttPublisher()

    // 用來儲存上次使用的 Host、Port、Username
    // 不會儲存 MQTT 密碼
    private val mqttSettings by lazy {
        getSharedPreferences(
            "mqtt_settings",
            Context.MODE_PRIVATE
        )
    }

    // --------------------------------------------------
    // 4. GNSS 資料與統計
    // --------------------------------------------------

    private var totalBytes = 0L

    // 解析完整 UBX-NAV-PVT 封包
    private var navPvtParser = UbxNavPvtParser()

    // 最新一筆 GNSS JSON
    private var latestJson = "等待 NAV-PVT JSON..."

    // --------------------------------------------------
    // 5. USB 權限接收器
    // --------------------------------------------------

    private val permissionReceiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: Context,
            intent: Intent
        ) {
            if (intent.action != usbPermissionAction) return

            val granted = intent.getBooleanExtra(
                UsbManager.EXTRA_PERMISSION_GRANTED,
                false
            )

            if (granted) {
                showStatus("USB 權限已允許")
                connectToEvk()
            } else {
                showStatus("USB 權限遭到拒絕")
            }
        }
    }

    // --------------------------------------------------
    // 6. Activity 啟動
    // --------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbManager = getSystemService(
            Context.USB_SERVICE
        ) as UsbManager

        // 註冊 USB 權限回應
        val filter = IntentFilter(usbPermissionAction)

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                permissionReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(
                permissionReceiver,
                filter
            )
        }

        createUi()
    }

    // --------------------------------------------------
    // 7. 建立 Android App 畫面
    // --------------------------------------------------

    private fun createUi() {

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 24)
        }

        val title = TextView(this).apply {
            text = "EVK GNSS Receiver"
            textSize = 24f
        }

        statusText = TextView(this).apply {
            text = "USB：尚未連接"
            textSize = 16f
            setPadding(0, 12, 0, 8)
        }

        mqttStatusText = TextView(this).apply {
            text = "MQTT：尚未連線"
            textSize = 16f
            setPadding(0, 4, 0, 12)
        }

        // --------------------------------------------------
        // MQTT Broker IP / Host
        // --------------------------------------------------

        mqttHostInput = EditText(this).apply {

            hint = "MQTT Broker IP / Host"

            setSingleLine(true)

            inputType =
                InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_URI

            // 讀取上次使用的 Broker 位址
            setText(
                mqttSettings.getString(
                    "broker_host",
                    "192.168.135.29"
                )
            )
        }

        // --------------------------------------------------
        // MQTT Port
        // --------------------------------------------------

        mqttPortInput = EditText(this).apply {

            hint = "MQTT Port"

            setSingleLine(true)

            inputType = InputType.TYPE_CLASS_NUMBER

            // 讀取上次使用的 Port
            setText(
                mqttSettings.getInt(
                    "broker_port",
                    1883
                ).toString()
            )
        }

        // --------------------------------------------------
        // MQTT Username（新增可編輯）
        // --------------------------------------------------

        mqttUsernameInput = EditText(this).apply {

            hint = "MQTT Username"

            setSingleLine(true)

            inputType = InputType.TYPE_CLASS_TEXT

            // 預設 gnssuser，但使用者可以直接修改
            setText(
                mqttSettings.getString(
                    "broker_username",
                    "gnssuser"
                )
            )
        }

        // --------------------------------------------------
        // MQTT Password
        // --------------------------------------------------

        mqttPasswordInput = EditText(this).apply {

            hint = "MQTT Password"

            setSingleLine(true)

            // 使用密碼輸入模式，畫面以圓點遮蔽
            inputType =
                InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        // --------------------------------------------------
        // MQTT 連線按鈕
        // --------------------------------------------------

        val mqttConnectButton = Button(this).apply {

            text = "連接 PC MQTT"

            setOnClickListener {

                // 取得使用者在畫面輸入的四個參數
                val host =
                    mqttHostInput.text.toString().trim()

                val port =
                    mqttPortInput.text.toString().trim()
                        .toIntOrNull()

                val username =
                    mqttUsernameInput.text.toString().trim()

                val password =
                    mqttPasswordInput.text.toString()

                // 先驗證輸入是否合理
                when {

                    host.isEmpty() -> {
                        mqttStatusText.text =
                            "MQTT：請輸入 Broker IP / Host"
                    }

                    port == null || port !in 1..65535 -> {
                        mqttStatusText.text =
                            "MQTT：Port 必須介於 1～65535"
                    }

                    username.isEmpty() -> {
                        mqttStatusText.text =
                            "MQTT：請輸入 Username"
                    }

                    password.isEmpty() -> {
                        mqttStatusText.text =
                            "MQTT：請輸入 Password"
                    }

                    else -> {

                        // 記住 Host、Port、Username
                        // 不會儲存 Password
                        mqttSettings.edit()
                            .putString("broker_host", host)
                            .putInt("broker_port", port)
                            .putString(
                                "broker_username",
                                username
                            )
                            .apply()

                        mqttStatusText.text =
                            "MQTT：正在連線 $host:$port ..."

                        // 使用使用者輸入的參數連接 Broker
                        mqttPublisher.connect(
                            host = host,
                            port = port,
                            username = username,
                            password = password
                        ) { success, message ->

                            runOnUiThread {
                                mqttStatusText.text = message
                            }
                        }
                    }
                }
            }
        }

        // --------------------------------------------------
        // MQTT 中斷連線按鈕
        // --------------------------------------------------

        val mqttDisconnectButton = Button(this).apply {

            text = "中斷 PC MQTT"

            setOnClickListener {
                mqttPublisher.disconnect()
                mqttStatusText.text = "MQTT：已中斷連線"
            }
        }

        // --------------------------------------------------
        // EVK USB 連線按鈕
        // --------------------------------------------------

        val usbConnectButton = Button(this).apply {

            text = "連接 EVK USB"

            setOnClickListener {
                connectToEvk()
            }
        }

        val usbDisconnectButton = Button(this).apply {

            text = "中斷 EVK USB"

            setOnClickListener {
                stopUsbReading()
                showStatus("EVK USB 已中斷")
            }
        }

        // --------------------------------------------------
        // GNSS JSON 顯示區域
        // --------------------------------------------------

        dataText = TextView(this).apply {

            text = "等待 GNSS 資料..."

            textSize = 13f

            setTextIsSelectable(true)
        }

        val scrollView = ScrollView(this).apply {
            addView(dataText)
        }

        // --------------------------------------------------
        // 把元件加入畫面
        // --------------------------------------------------

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(mqttStatusText)

        layout.addView(mqttHostInput)
        layout.addView(mqttPortInput)
        layout.addView(mqttUsernameInput)
        layout.addView(mqttPasswordInput)

        layout.addView(mqttConnectButton)
        layout.addView(mqttDisconnectButton)

        layout.addView(usbConnectButton)
        layout.addView(usbDisconnectButton)

        layout.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(layout)
    }

    // --------------------------------------------------
    // 8. 連接 EVK-X20P
    // --------------------------------------------------

    private fun connectToEvk() {

        if (reading) {
            showStatus("EVK USB 已經連線")
            return
        }

        // 掃描 Android 支援的 USB Serial 裝置
        val drivers = UsbSerialProber
            .getDefaultProber()
            .findAllDrivers(usbManager)

        if (drivers.isEmpty()) {
            showStatus("找不到支援的 USB Serial 裝置")
            return
        }

        // 尋找 u-blox EVK 的 USB VID / PID
        val driver: UsbSerialDriver =
            drivers.firstOrNull {

                it.device.vendorId == 0x1546 &&
                        it.device.productId == 0x01AB

            } ?: run {

                showStatus(
                    "未找到 EVK USB 裝置，請確認 VID/PID"
                )

                return
            }

        val device = driver.device

        // Android 尚未取得 USB 權限
        if (!usbManager.hasPermission(device)) {

            showStatus("正在請求 USB 權限...")

            val intent = Intent(usbPermissionAction).apply {
                setPackage(packageName)
            }

            val permissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_MUTABLE
            )

            usbManager.requestPermission(
                device,
                permissionIntent
            )

            return
        }

        val connection = usbManager.openDevice(device)

        if (connection == null) {
            showStatus("無法開啟 EVK USB 裝置")
            return
        }

        try {

            val port = driver.ports.first()

            port.open(connection)

            // 原本測試成功的 Serial 參數
            port.setParameters(
                38400,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )

            serialPort = port

            // 每次重新連接 USB 時重設統計
            totalBytes = 0L
            navPvtParser = UbxNavPvtParser()
            latestJson = "等待 NAV-PVT JSON..."

            reading = true

            showStatus("EVK USB 已連線")

            startReading(port)

        } catch (e: Exception) {

            try {
                serialPort?.close()
            } catch (_: Exception) {
            }

            serialPort = null
            connection.close()

            showStatus("EVK USB 連線失敗：${e.message}")
        }
    }

    // --------------------------------------------------
    // 9. USB 背景執行緒：接收 GNSS 並發布 MQTT
    // --------------------------------------------------

    private fun startReading(port: UsbSerialPort) {

        readThread = Thread {

            val buffer = ByteArray(4096)

            try {

                while (reading) {

                    // 從 EVK 讀取 USB Bytes
                    // 最多等待 1000 毫秒
                    val length = port.read(
                        buffer,
                        1000
                    )

                    if (length <= 0) continue

                    // 只取這次實際收到的 Bytes
                    val received = buffer.copyOf(length)

                    totalBytes += received.size

                    // 解析完整 UBX-NAV-PVT 封包
                    navPvtParser.feed(received) { json ->

                        // 保留最新一筆 JSON 供畫面顯示
                        latestJson = json.toString(2)

                        // 將完整 JSON 發布到 MQTT
                        // MQTT 未連線時 publishJson 會直接返回
                        mqttPublisher.publishJson(
                            json.toString()
                        )
                    }

                    // 讀取目前統計數值
                    val currentBytes = totalBytes
                    val currentPackets =
                        navPvtParser.validPacketCount
                    val currentJson = latestJson

                    val mqttConnected =
                        mqttPublisher.connected

                    val mqttPublished =
                        mqttPublisher.publishedCount.get()

                    val mqttFailures =
                        mqttPublisher.failedCount.get()

                    // UI 更新必須在主執行緒進行
                    runOnUiThread {

                        dataText.text = """
                            Total received bytes: $currentBytes

                            Valid NAV-PVT packets: $currentPackets

                            MQTT connected: $mqttConnected

                            MQTT published (QoS 1 ack): $mqttPublished

                            MQTT publish failures: $mqttFailures

                            Latest USB read: $length bytes

                            Latest JSON:
                            $currentJson
                        """.trimIndent()
                    }
                }

            } catch (e: Exception) {

                if (reading) {
                    showStatus(
                        "USB 接收錯誤：${e.message}"
                    )
                }

            } finally {

                reading = false

                try {
                    port.close()
                } catch (_: Exception) {
                }

                serialPort = null
            }

        }.apply {
            start()
        }
    }

    // --------------------------------------------------
    // 10. 顯示 USB 狀態
    // --------------------------------------------------

    private fun showStatus(message: String) {

        runOnUiThread {
            statusText.text = "USB：$message"
        }
    }

    // --------------------------------------------------
    // 11. 停止 USB 接收
    // --------------------------------------------------

    private fun stopUsbReading() {

        reading = false

        readThread?.interrupt()
        readThread = null

        try {
            serialPort?.close()
        } catch (_: Exception) {
        }

        serialPort = null
    }

    // --------------------------------------------------
    // 12. Activity 關閉時清理資源
    // --------------------------------------------------

    override fun onDestroy() {

        mqttPublisher.disconnect()

        stopUsbReading()

        unregisterReceiver(permissionReceiver)

        super.onDestroy()
    }
}
