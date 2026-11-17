package com.example.evkgnssreceiver

import org.json.JSONObject

internal data class ImuSample(
    val timestampNs: Long,
    val x: Float,
    val y: Float,
    val z: Float
) {
    fun toJson(): JSONObject = JSONObject()
        .put("timestamp_ns", timestampNs)
        .put("x", x.toDouble())
        .put("y", y.toDouble())
        .put("z", z.toDouble())
}

internal fun imuJson(acceleration: ImuSample?, angularVelocity: ImuSample?): JSONObject =
    JSONObject()
        .put("accelerometer_mps2", acceleration?.toJson() ?: JSONObject.NULL)
        .put("gyroscope_radps", angularVelocity?.toJson() ?: JSONObject.NULL)
