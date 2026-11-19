package com.example.evkgnssreceiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VelocityFusionTest {
    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    private fun ms(value: Long) = value * 1_000_000

    @Test
    fun delayedGnssUpdatesTheSampleEpochAndReplaysAcceleration() {
        val fusion = VelocityFusion()
        for (t in 0L..1000L step 20) {
            val acceleration = if (t >= 600) 2.0 else 0.0
            fusion.addImu(ms(t), doubleArrayOf(acceleration, 0.0, GRAVITY_MPS2), identity)
        }
        val result = fusion.update(ms(600), DoubleArray(3), 0.05)!!
        assertEquals(ms(1000), result.timestampNs)
        assertEquals(0.8, result.velocityEnu[0], 1e-9)
    }

    @Test
    fun estimatesBiasInPhoneAxesWhileUsingWorldVelocity() {
        val fusion = VelocityFusion()
        val rotated = doubleArrayOf(0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        for (t in 0L..12000L step 20) {
            fusion.addImu(ms(t), doubleArrayOf(0.30, -0.20, GRAVITY_MPS2 + 0.1), rotated)
            if (t % 200 == 0L) fusion.update(ms(t), DoubleArray(3), 0.05)
        }
        val estimate = fusion.predict()!!
        assertEquals(0.30, estimate.biasDevice[0], 0.035)
        assertEquals(-0.20, estimate.biasDevice[1], 0.035)
        assertEquals(0.1, estimate.biasDevice[2], 0.035)
        assertEquals(0.0, estimate.velocityEnu[1], 0.035)
    }

    @Test
    fun refusesToIntegrateOverImuGapsOrCoastIndefinitely() {
        val fusion = VelocityFusion()
        val stationary = doubleArrayOf(0.0, 0.0, GRAVITY_MPS2)
        fusion.addImu(0, stationary, identity)
        assertNotNull(fusion.update(0, DoubleArray(3), 0.05))
        fusion.addImu(ms(500), stationary, identity)
        assertNull(fusion.predict())
        assertNotNull(fusion.update(ms(500), DoubleArray(3), 0.05))
        for (t in 520L..2600L step 20) fusion.addImu(ms(t), stationary, identity)
        assertNull(fusion.predict())
    }

    @Test
    fun alignsGpsWeekRolloverAndCompensatesArrivalJitterAndFixedDelay() {
        val clock = GnssEpochClock(fixedDelayMs = 20)
        assertNull(clock.observe(604_799_800, ms(1000)))
        assertNull(clock.observe(604_799_900, ms(1100)))
        assertEquals(ms(1180), clock.observe(0, ms(1235)))
        assertNull(clock.observe(0, ms(1236)))
        assertEquals(ms(1280), clock.observe(100, ms(1300)))
    }
}
