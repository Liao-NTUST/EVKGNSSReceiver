package com.example.evkgnssreceiver

import java.io.ByteArrayOutputStream

/** 傳給 EVK-X20P 的 UBX 設定指令。 */
internal object UbxCommands {

    const val CLASS_CFG = 0x06
    const val ID_CFG_VALSET = 0x8A

    // u-blox X20 HPG 2.11 Interface Description
    private const val CFG_USBINPROT_SPARTN = 0x10770005 // L，預設 1
    private const val CFG_SPARTN_USE_SOURCE = 0x20a70001 // E1，0 = IP（預設），1 = L-Band

    /**
     * 確保 USB 介面接受 SPARTN 輸入，且 SPARTN 來源為 IP（NTRIP）。
     * 只寫入 RAM layer，EVK 重新上電後恢復原設定，不會動到 Flash。
     */
    fun enableSpartnInputOnUsb(): ByteArray = valSetRam(
        CFG_USBINPROT_SPARTN to 1,
        CFG_SPARTN_USE_SOURCE to 0
    )

    /** 只支援 1 byte 大小的 key（L、U1、E1 等）。 */
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
            payload.write(value and 0xFF)
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
