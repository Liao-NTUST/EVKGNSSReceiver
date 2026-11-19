package com.example.evkgnssreceiver

import android.hardware.GeomagneticField
import android.hardware.SensorManager
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Connects the sensor callbacks and the USB reader, which run on different threads. */
internal class AndroidVelocityFusion(private val fixedDelayMs: Long = 0) {

    /** 畫面用的即時速度（真北 ENU），sinceGnssMs 為距離上次 GNSS 速度修正的時間。 */
    data class LiveVelocity(
        val east: Double,
        val north: Double,
        val up: Double,
        val sinceGnssMs: Double
    )

    private val clock = GnssEpochClock(fixedDelayMs)
    private val filter = VelocityFusion()
    private var rotationNs = Long.MIN_VALUE
    private var deviceToMagneticEnu: DoubleArray? = null
    private var latestImuNs: Long? = null
    private var declinationRad: Double? = null

    @Synchronized
    fun onOrientation(timestampNs: Long, values: FloatArray, accuracy: Int) {
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
            values.size < 3 || values.take(4).any { !it.isFinite() }
        ) {
            clearImu()
            return
        }
        val matrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix, values)
        rotationNs = timestampNs
        deviceToMagneticEnu = DoubleArray(9) { matrix[it].toDouble() }
    }

    @Synchronized
    fun onAcceleration(sample: ImuSample) {
        val rotation = deviceToMagneticEnu ?: return
        if (abs(sample.timestampNs - rotationNs) > 50_000_000L ||
            sample.timestampNs <= (latestImuNs ?: Long.MIN_VALUE)
        ) return
        filter.addImu(sample.timestampNs, doubleArrayOf(
            sample.x.toDouble(), sample.y.toDouble(), sample.z.toDouble()
        ), rotation)
        latestImuNs = sample.timestampNs
    }

    /**
     * 以最新 IMU 樣本推算當下速度，供畫面以 IMU 頻率更新。
     * 只讀取濾波器狀態，不會改變融合結果，也不影響 MQTT 輸出。
     */
    @Synchronized
    fun liveVelocity(nowNs: Long): LiveVelocity? {
        val latest = latestImuNs ?: return null
        if (nowNs - latest > MAX_IMU_GAP_NS) return null
        val declination = declinationRad ?: return null
        val estimate = filter.predict() ?: return null
        val (east, north, up) = estimate.velocityEnu
        return LiveVelocity(
            east = cos(declination) * east + sin(declination) * north,
            north = -sin(declination) * east + cos(declination) * north,
            up = up,
            sinceGnssMs = (estimate.timestampNs - estimate.gnssUpdateNs) / 1e6
        )
    }

    /** 手機頂端（Y 軸）指向的真北方位角，0–360°。手機平放時最準確。 */
    @Synchronized
    fun phoneAzimuthTrueDeg(): Double? {
        val r = deviceToMagneticEnu ?: return null
        val magnetic = Math.toDegrees(atan2(r[1], r[4]))
        val declination = declinationRad?.let { Math.toDegrees(it) } ?: 0.0
        return ((magnetic + declination) % 360 + 360) % 360
    }

    @Synchronized
    fun clearImu() {
        deviceToMagneticEnu = null
        latestImuNs = null
        filter.clearImu()
    }

    @Synchronized
    fun onNavPvt(json: JSONObject, arrivalNs: Long): JSONObject {
        val oldGeneration = clock.generation
        val epochNs = clock.observe(json.getLong("gps_itow_ms"), arrivalNs)
        if (clock.generation != oldGeneration) filter.resetEstimate()

        val imuReady = latestImuNs?.let { arrivalNs - it <= MAX_IMU_GAP_NS } == true
        val speedAccuracy = json.optDouble("speed_accuracy_mps")
        val e = json.optDouble("velocity_e_mps")
        val n = json.optDouble("velocity_n_mps")
        val d = json.optDouble("velocity_d_mps")
        val usableVelocity = json.optBoolean("fix_ok") && json.optInt("fix_type") == 3 &&
            speedAccuracy in 0.0..2.0 && e.isFinite() && n.isFinite() && d.isFinite()

        if (usableVelocity && declinationRad == null) {
            val lat = json.optDouble("latitude_deg")
            val lon = json.optDouble("longitude_deg")
            if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                val field = GeomagneticField(
                    lat.toFloat(), lon.toFloat(), json.optDouble("altitude_ellipsoid_m", 0.0).toFloat(),
                    System.currentTimeMillis()
                )
                declinationRad = Math.toRadians(field.declination.toDouble())
            }
        }

        val declination = declinationRad
        val correction = if (imuReady && usableVelocity && epochNs != null && declination != null) {
            val velocity = doubleArrayOf(
                cos(declination) * e - sin(declination) * n,
                sin(declination) * e + cos(declination) * n,
                -d
            )
            filter.update(epochNs, velocity, speedAccuracy)
        } else null
        val estimate = correction ?: if (imuReady) filter.predict() else null
        val mode = when {
            !imuReady -> "WAITING_FOR_IMU_ATTITUDE"
            correction != null -> when (json.optString("rtk_status")) {
                "RTK_FIXED" -> "FUSED_RTK_FIXED"
                "RTK_FLOAT" -> "FUSED_RTK_FLOAT"
                else -> "FUSED_GNSS"
            }
            estimate != null -> "IMU_PREDICTED"
            epochNs == null -> "WAITING_FOR_TIME_ALIGNMENT"
            !usableVelocity -> "WAITING_FOR_GNSS_VELOCITY"
            else -> "WAITING_FOR_IMU_HISTORY"
        }

        return JSONObject().apply {
            put("mode", mode)
            put("time_alignment", "USB_MIN_ARRIVAL_APPROXIMATE")
            put("fixed_delay_ms", fixedDelayMs)
            put("usb_received_elapsed_ns", arrivalNs)
            put("gnss_epoch_elapsed_ns", epochNs ?: JSONObject.NULL)
            put("estimated_delay_ms", epochNs?.let { (arrivalNs - it) / 1e6 } ?: JSONObject.NULL)
            put("estimate_elapsed_ns", estimate?.timestampNs ?: JSONObject.NULL)
            put("last_gnss_update_elapsed_ns", estimate?.gnssUpdateNs ?: JSONObject.NULL)
            put("velocity_enu_mps", if (estimate != null && declination != null) {
                val (east, north, up) = estimate.velocityEnu
                JSONObject()
                    .put("east", cos(declination) * east + sin(declination) * north)
                    .put("north", -sin(declination) * east + cos(declination) * north)
                    .put("up", up)
            } else JSONObject.NULL)
            put("accelerometer_bias_device_mps2", estimate?.biasDevice?.let { (x, y, z) ->
                JSONObject().put("x", x).put("y", y).put("z", z)
            } ?: JSONObject.NULL)
        }
    }
}
