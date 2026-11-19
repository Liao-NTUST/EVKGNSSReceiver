package com.example.evkgnssreceiver

import java.io.ByteArrayOutputStream

/** 傳給 EVK-X20P 的 UBX 設定指令。 */
internal object UbxCommands {

    const val CLASS_CFG = 0x06
    const val ID_CFG_VALSET = 0x8A

    // u-blox X20 HPG 2.11 Interface Description
    private const val CFG_USBINPROT_SPARTN = 0x10770005 // L，預設 1
    private const val CFG_SPARTN_USE_SOURCE = 0x20a70001 // E1，0 = IP（預設），1 = L-Band
    private const val CFG_RATE_MEAS = 0x30210001 // U2，量測間隔（ms）
    private const val CFG_RATE_NAV = 0x30210002 // U2，每幾次量測輸出一次定位解

    /** App 提供的更新率選項；ZED-X20P RTK 最高可到 25 Hz。 */
    val SUPPORTED_RATES_HZ = listOf(1, 2, 5, 10)

    /**
     * 確保 USB 介面接受 SPARTN 輸入，且 SPARTN 來源為 IP（NTRIP）。
     * 只寫入 RAM layer，EVK 重新上電後恢復原設定，不會動到 Flash。
     */
    fun enableSpartnInputOnUsb(): ByteArray = valSetRam(
        CFG_USBINPROT_SPARTN to 1,
        CFG_SPARTN_USE_SOURCE to 0
    )

    /**
     * 設定定位更新率：量測間隔 = 1000 / hz ms，每次量測都輸出定位解。
     * 只寫入 RAM layer，EVK 重新上電後恢復原設定。
     */
    fun setNavigationRate(hz: Int): ByteArray {
        require(hz in 1..25) { "rate must be 1..25 Hz" }
        return valSetRam(
            CFG_RATE_MEAS to 1000 / hz,
            CFG_RATE_NAV to 1
        )
    }

    /** 依 key ID 的大小欄位（bits 28–30）寫入 1、2 或 4 bytes 的值。 */
    private fun valSetRam(vararg items: Pair<Int, Int>): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(0x00) // version
        payload.write(0x01) // layers: RAM
        payload.write(0x00) // reserved
        payload.write(0x00)
        for ((key, value) in items) {
            payload.write(key and 0xFF)
            payload.write((key ushr 8) and 0xFF)
            payload.write((key ushr 16) and 0xFF)
            payload.write((key ushr 24) and 0xFF)
            val bytes = when ((key ushr 28) and 0x07) {
                1, 2 -> 1 // L（1 bit 以 1 byte 傳送）、U1/I1/E1/X1
                3 -> 2 // U2/I2/E2/X2
                4 -> 4 // U4/I4/E4/X4/R4
                else -> throw IllegalArgumentException("unsupported key size: $key")
            }
            for (index in 0 until bytes) payload.write((value ushr (8 * index)) and 0xFF)
        }
        return frame(CLASS_CFG, ID_CFG_VALSET, payload.toByteArray())
    }

    fun frame(messageClass: Int, messageId: Int, payload: ByteArray): ByteArray {
        val packet = ByteArray(payload.size + 8)
        packet[0] = 0xB5.toByte()
        packet[1] = 0x62
        packet[2] = messageClass.toByte()
        packet[3] = messageId.toByte()
        packet[4] = (payload.size and 0xFF).toByte()
        packet[5] = ((payload.size ushr 8) and 0xFF).toByte()
        payload.copyInto(packet, 6)
        var ckA = 0
        var ckB = 0
        for (index in 2 until packet.size - 2) {
            ckA = (ckA + (packet[index].toInt() and 0xFF)) and 0xFF
            ckB = (ckB + ckA) and 0xFF
        }
        packet[packet.size - 2] = ckA.toByte()
        packet[packet.size - 1] = ckB.toByte()
        return packet
    }
}
