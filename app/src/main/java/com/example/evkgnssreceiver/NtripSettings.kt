package com.example.evkgnssreceiver

import android.content.Context

/** Thingstream PointPerfect 提供的 NTRIP 連線參數。 */
internal data class NtripSettings(
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val mountpoint: String,
    val username: String,
    val password: String
) {
    val isComplete: Boolean
        get() = host.isNotBlank() && port in 1..65535 && mountpoint.isNotBlank() &&
                username.isNotBlank() && password.isNotEmpty()
}

/**
 * NTRIP 設定儲存。
 *
 * Server、Port、TLS、Mountpoint、Username 存在 App 私有的 SharedPreferences。
 * 密碼只在使用者勾選「記住密碼」時才寫入；否則只留在記憶體，App 結束就消失。
 * ntrip_settings.xml 已在 backup / data extraction rules 中排除，不會被雲端備份。
 */
internal object NtripSettingsStore {
    const val DEFAULT_HOST = "ppntrip.services.u-blox.com"
    const val PLAIN_PORT = 2101
    const val TLS_PORT = 2102
    const val DEFAULT_MOUNTPOINT = "NEAR-SPARTN"

    private const val PREFS = "ntrip_settings"
    private const val KEY_PASSWORD = "password"

    @Volatile
    private var sessionPassword: String? = null

    fun load(context: Context): NtripSettings {
        val prefs = prefs(context)
        return NtripSettings(
            host = prefs.getString("host", DEFAULT_HOST) ?: DEFAULT_HOST,
            port = prefs.getInt("port", TLS_PORT),
            useTls = prefs.getBoolean("use_tls", true),
            mountpoint = prefs.getString("mountpoint", DEFAULT_MOUNTPOINT) ?: DEFAULT_MOUNTPOINT,
            username = prefs.getString("username", "") ?: "",
            password = sessionPassword ?: prefs.getString(KEY_PASSWORD, null) ?: ""
        )
    }

    fun isPasswordRemembered(context: Context): Boolean =
        prefs(context).contains(KEY_PASSWORD)

    fun save(context: Context, settings: NtripSettings, rememberPassword: Boolean) {
        sessionPassword = settings.password
        prefs(context).edit().apply {
            putString("host", settings.host)
            putInt("port", settings.port)
            putBoolean("use_tls", settings.useTls)
            putString("mountpoint", settings.mountpoint)
            putString("username", settings.username)
            if (rememberPassword) putString(KEY_PASSWORD, settings.password)
            else remove(KEY_PASSWORD)
        }.apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
