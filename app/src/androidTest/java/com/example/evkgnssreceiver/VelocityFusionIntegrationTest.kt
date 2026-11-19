package com.example.evkgnssreceiver

import android.hardware.SensorManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class VelocityFusionIntegrationTest {
    @Test
    fun navPvtVelocityReachesFusionJsonWithConsistentAxesAndTime() {
        val parser = UbxNavPvtParser()
        val fusion = AndroidVelocityFusion(fixedDelayMs = 20)
        val arrivals = mapOf(1000L to 0, 1100L to 100, 1240L to 200)
        for (t in 900L..1240L step 20) {
            val ns = t * 1_000_000
            fusion.onOrientation(ns, floatArrayOf(0f, 0f, 0f, 1f), SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
            fusion.onAcceleration(ImuSample(ns, 0f, 0f, GRAVITY_MPS2.toFloat()))
            val towMs = arrivals[t] ?: continue
            parser.feed(navPvtPacket(towMs)) { pvt ->
                assertEquals(0.05, pvt.getDouble("speed_accuracy_mps"), 1e-9)
                val output = fusion.onNavPvt(pvt, ns)
                if (t != 1240L) {
                    assertEquals("WAITING_FOR_TIME_ALIGNMENT", output.getString("mode"))
                } else {
                    assertEquals("FUSED_RTK_FIXED", output.getString("mode"))
                    assertEquals(1_180_000_000L, output.getLong("gnss_epoch_elapsed_ns"))
                    assertEquals(ns, output.getLong("estimate_elapsed_ns"))
                    val velocity = output.getJSONObject("velocity_enu_mps")
                    assertEquals(3.0, velocity.getDouble("east"), 1e-6)
                    assertEquals(4.0, velocity.getDouble("north"), 1e-6)
                    assertEquals(2.0, velocity.getDouble("up"), 1e-6)
                }
            }
        }
        assertEquals(3L, parser.validPacketCount)
    }

    private fun navPvtPacket(towMs: Int): ByteArray {
        val packet = ByteArray(100)
        val bb = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(0, 0x62B5.toShort())
        bb.put(2, 0x01)
        bb.put(3, 0x07)
        bb.putShort(4, 92)
        bb.putInt(6, towMs)
        bb.put(6 + 20, 3)
        bb.put(6 + 21, 0x81.toByte())
        bb.putInt(6 + 24, 1_215_000_000)
        bb.putInt(6 + 28, 250_000_000)
        bb.putInt(6 + 48, 4000)
        bb.putInt(6 + 52, 3000)
        bb.putInt(6 + 56, -2000)
        bb.putInt(6 + 68, 50)
        var a = 0
        var b = 0
        for (i in 2 until packet.size - 2) {
            a += packet[i].toInt() and 0xFF
            b += a
        }
        packet[98] = a.toByte()
        packet[99] = b.toByte()
        return packet
    }
}
