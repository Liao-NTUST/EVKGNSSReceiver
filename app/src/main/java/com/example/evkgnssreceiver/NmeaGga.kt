package com.example.evkgnssreceiver

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

/**
 * 由 UBX-NAV-PVT JSON 產生 NMEA GGA。
 *
 * PointPerfect NTRIP 依 GGA 位置提供當地的 SPARTN 修正資料，
 * 且 30 秒內沒收到 GGA 就會中斷連線，所以 GGA 必須反映 EVK 的即時位置。
 */
internal object NmeaGga {

    fun fromNavPvt(json: JSONObject): String? {
        if (!json.optBoolean("fix_ok")) return null
        val lat = json.optDouble("latitude_deg")
        val lon = json.optDouble("longitude_deg")
        val altMsl = json.optDouble("altitude_msl_m")
        val altEllipsoid = json.optDouble("altitude_ellipsoid_m")
        if (!lat.isFinite() || !lon.isFinite() || !altMsl.isFinite()) return null

        val time = if (json.isNull("gnss_time_utc")) Instant.now() else try {
            Instant.parse(json.getString("gnss_time_utc"))
        } catch (_: Exception) {
            Instant.now()
        }
        val utc = time.atOffset(ZoneOffset.UTC)
        val hhmmss = String.format(
            Locale.US, "%02d%02d%02d.%02d",
            utc.hour, utc.minute, utc.second, utc.nano / 10_000_000
        )

        val quality = when (json.optString("rtk_status")) {
            "RTK_FIXED" -> 4
            "RTK_FLOAT" -> 5
            else -> if (json.optBoolean("differential_solution")) 2 else 1
        }
        val satellites = json.optInt("satellites_used").coerceIn(0, 99)
        // NAV-PVT 只有 PDOP，以 PDOP 當作 HDOP 的保守近似值。
        val hdop = json.optDouble("pdop", 99.9).coerceIn(0.0, 99.9)
        val geoidSeparation = if (altEllipsoid.isFinite()) altEllipsoid - altMsl else 0.0

        val body = String.format(
            Locale.US,
            "GPGGA,%s,%s,%s,%s,%s,%d,%02d,%.1f,%.3f,M,%.3f,M,,",
            hhmmss,
            coordinate(abs(lat), 2), if (lat >= 0) "N" else "S",
            coordinate(abs(lon), 3), if (lon >= 0) "E" else "W",
            quality, satellites, hdop, altMsl, geoidSeparation
        )
        return "\$$body*${checksum(body)}\r\n"
    }

    private fun coordinate(degrees: Double, degreeDigits: Int): String {
        var whole = degrees.toInt()
        var minutes = (degrees - whole) * 60.0
        // 避免四捨五入後出現 60.0000000 分
        if (minutes >= 59.99999995) {
            whole += 1
            minutes = 0.0
        }
        return String.format(Locale.US, "%0${degreeDigits}d%010.7f", whole, minutes)
    }

    private fun checksum(body: String): String {
        var value = 0
        for (char in body) value = value xor char.code
        return String.format(Locale.US, "%02X", value)
    }
}
