package com.example.evkgnssreceiver

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 將連續 USB Byte stream 重組成 UBX 封包，驗證 Checksum，
 * 並將 UBX-NAV-PVT (92-byte payload) 轉成 JSON。
 * 呼叫 feed() 的執行緒應固定為同一條 USB 接收執行緒。
 */
class UbxNavPvtParser {
    private val pending = ArrayList<Byte>()

    var validPacketCount: Long = 0
        private set

    fun feed(bytes: ByteArray, onJson: (JSONObject) -> Unit) {
        for (byte in bytes) pending.add(byte)

        while (pending.size >= 8) {
            // Sync chars: B5 62
            if ((pending[0].toInt() and 0xFF) != 0xB5 ||
                (pending[1].toInt() and 0xFF) != 0x62) {
                pending.removeAt(0)
                continue
            }

            val payloadLength = (pending[4].toInt() and 0xFF) or
                    ((pending[5].toInt() and 0xFF) shl 8)
            if (payloadLength > 2048) {
                pending.removeAt(0)
                continue
            }
            val packetLength = payloadLength + 8
            if (pending.size < packetLength) break

            val packet = ByteArray(packetLength) { pending[it] }
            if (!checksumValid(packet)) {
                pending.removeAt(0)
                continue
            }

            val messageClass = packet[2].toInt() and 0xFF
            val messageId = packet[3].toInt() and 0xFF
            if (messageClass == 0x01 && messageId == 0x07 && payloadLength == 92) {
                val json = parseNavPvt(packet)
                validPacketCount++
                onJson(json)
            }
            pending.subList(0, packetLength).clear()
        }
    }

    private fun checksumValid(packet: ByteArray): Boolean {
        var ckA = 0
        var ckB = 0
        for (index in 2 until packet.size - 2) {
            ckA = (ckA + (packet[index].toInt() and 0xFF)) and 0xFF
            ckB = (ckB + ckA) and 0xFF
        }
        return ckA == (packet[packet.size - 2].toInt() and 0xFF) &&
                ckB == (packet[packet.size - 1].toInt() and 0xFF)
    }

    private fun parseNavPvt(packet: ByteArray): JSONObject {
        val bb = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        // +6: UBX header 長度。下列 offset 都是相對 Payload 起點。
        fun u1(offset: Int): Int = bb.get(6 + offset).toInt() and 0xFF
        fun u2(offset: Int): Int = bb.getShort(6 + offset).toInt() and 0xFFFF
        fun u4(offset: Int): Long = bb.getInt(6 + offset).toLong() and 0xFFFFFFFFL
        fun i4(offset: Int): Int = bb.getInt(6 + offset)
        fun jsonValue(value: Any?): Any = value ?: JSONObject.NULL

        val flags = u1(21)
        val fixType = u1(20)
        val fixOk = (flags and 0x01) != 0
        val hasPosition = fixOk && fixType in 2..4
        val differential = (flags and 0x02) != 0
        val rtkStatus = when ((flags ushr 6) and 0x03) {
            1 -> "RTK_FLOAT"
            2 -> "RTK_FIXED"
            else -> "NONE"
        }

        val valid = u1(11)
        val gnssUtc: String? = if ((valid and 0x03) == 0x03) {
            try {
                LocalDateTime.of(
                    u2(4), u1(6), u1(7), u1(8), u1(9), u1(10)
                ).plusNanos(i4(16).toLong())
                    .toInstant(ZoneOffset.UTC).toString()
            } catch (_: Exception) {
                null
            }
        } else null

        return JSONObject().apply {
            put("schema_version", "1.0")
            put("device_id", "EVK_01")
            put("source", "UBX-NAV-PVT")
            put("received_at_utc", Instant.now().toString())
            put("gnss_time_utc", jsonValue(gnssUtc))
            put("gps_itow_ms", u4(0))
            put("latitude_deg", jsonValue(if (hasPosition) i4(28) * 1e-7 else null))
            put("longitude_deg", jsonValue(if (hasPosition) i4(24) * 1e-7 else null))
            put("altitude_ellipsoid_m", jsonValue(if (hasPosition) i4(32) / 1000.0 else null))
            put("altitude_msl_m", jsonValue(if (hasPosition) i4(36) / 1000.0 else null))
            put("horizontal_accuracy_m", jsonValue(if (hasPosition) u4(40) / 1000.0 else null))
            put("vertical_accuracy_m", jsonValue(if (hasPosition) u4(44) / 1000.0 else null))
            put("pdop", u2(76) * 0.01)
            put("ground_speed_mps", jsonValue(if (hasPosition) i4(60) / 1000.0 else null))
            put("heading_of_motion_deg", jsonValue(if (hasPosition) i4(64) * 1e-5 else null))
            put("fix_type", fixType)
            put("fix_ok", fixOk)
            put("rtk_status", rtkStatus)
            put("satellites_used", u1(23))
            put("differential_solution", differential)
        }
    }
}
