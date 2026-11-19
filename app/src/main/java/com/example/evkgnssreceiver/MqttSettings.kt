package com.example.evkgnssreceiver

import android.content.Context

/** 手機連線 PC MQTT Broker 的參數。 */
internal data class MqttSettings(
    val host: String,
    val port: Int,
    val username: String,
    val password: String
) {
    val isComplete: Boolean
        get() = host.isNotBlank() && port in 1..65535 &&
                username.isNotBlank() && password.isNotEmpty()
}

/**
 * MQTT 設定儲存。沿用原本的 SharedPreferences 名稱與 key，舊設定不會遺失。
 * 密碼只在勾選「記住密碼」時寫入；否則只留在記憶體。
 */
internal object MqttSettingsStore {
    private const val PREFS = "mqtt_settings"
    private const val KEY_PASSWORD = "broker_password"

    @Volatile
    private var sessionPassword: String? = null

    fun load(context: Context): MqttSettings {
        val prefs = prefs(context)
        return MqttSettings(
            host = prefs.getString("broker_host", "192.168.135.29") ?: "",
            port = prefs.getInt("broker_port", 1883),
            username = prefs.getString("broker_username", "gnssuser") ?: "",
            password = sessionPassword ?: prefs.getString(KEY_PASSWORD, null) ?: ""
        )
    }

    fun isPasswordRemembered(context: Context): Boolean =
        prefs(context).contains(KEY_PASSWORD)

    fun save(context: Context, settings: MqttSettings, rememberPassword: Boolean) {
        sessionPassword = settings.password
        prefs(context).edit().apply {
            putString("broker_host", settings.host)
            putInt("broker_port", settings.port)
            putString("broker_username", settings.username)
            if (rememberPassword) putString(KEY_PASSWORD, settings.password)
            else remove(KEY_PASSWORD)
        }.apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
