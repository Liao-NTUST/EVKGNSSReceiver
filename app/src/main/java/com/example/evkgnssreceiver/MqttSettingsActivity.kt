package com.example.evkgnssreceiver

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** MQTT 設定頁面：Broker Host、Port、Username、Password。 */
class MqttSettingsActivity : AppCompatActivity() {

    private lateinit var hostLayout: TextInputLayout
    private lateinit var portLayout: TextInputLayout
    private lateinit var usernameLayout: TextInputLayout
    private lateinit var passwordLayout: TextInputLayout
    private lateinit var hostInput: TextInputEditText
    private lateinit var portInput: TextInputEditText
    private lateinit var usernameInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var rememberPasswordCheck: MaterialCheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mqtt_settings)
        applySystemBarInsets(findViewById(R.id.root))
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        hostLayout = findViewById(R.id.hostLayout)
        portLayout = findViewById(R.id.portLayout)
        usernameLayout = findViewById(R.id.usernameLayout)
        passwordLayout = findViewById(R.id.passwordLayout)
        hostInput = findViewById(R.id.hostInput)
        portInput = findViewById(R.id.portInput)
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        rememberPasswordCheck = findViewById(R.id.rememberPasswordCheck)

        if (savedInstanceState == null) {
            val saved = MqttSettingsStore.load(this)
            hostInput.setText(saved.host)
            portInput.setText(saved.port.toString())
            usernameInput.setText(saved.username)
            passwordInput.setText(saved.password)
            rememberPasswordCheck.isChecked = MqttSettingsStore.isPasswordRemembered(this)
        }

        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            readForm()?.let {
                MqttSettingsStore.save(this, it, rememberPasswordCheck.isChecked)
                finish()
            }
        }
    }

    private fun readForm(): MqttSettings? {
        listOf(hostLayout, portLayout, usernameLayout, passwordLayout).forEach { it.error = null }
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull()
        val username = usernameInput.text.toString().trim()
        val password = passwordInput.text.toString()
        when {
            host.isEmpty() -> hostLayout.error = "請輸入 Broker IP / Host"
            port == null || port !in 1..65535 -> portLayout.error = "Port 必須介於 1～65535"
            username.isEmpty() -> usernameLayout.error = "請輸入 Username"
            password.isEmpty() -> passwordLayout.error = "請輸入 Password"
            else -> return MqttSettings(host, port, username, password)
        }
        return null
    }
}
