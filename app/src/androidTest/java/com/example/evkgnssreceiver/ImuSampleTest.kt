package com.example.evkgnssreceiver

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImuSampleTest {
    @Test
    fun serializesLatestSampleAndMissingSensor() {
        val imu = imuJson(ImuSample(123L, 1f, -2f, 9.5f), null)
        val acceleration = imu.getJSONObject("accelerometer_mps2")
        assertEquals(123L, acceleration.getLong("timestamp_ns"))
        assertEquals(1.0, acceleration.getDouble("x"), 0.0)
        assertEquals(-2.0, acceleration.getDouble("y"), 0.0)
        assertEquals(9.5, acceleration.getDouble("z"), 0.0)
        assertTrue(imu.has("gyroscope_radps") && imu.isNull("gyroscope_radps"))
    }
}
