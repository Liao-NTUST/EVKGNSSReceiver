package com.example.evkgnssreceiver

import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * NTRIP 設定頁面：輸入 Thingstream PointPerfect 提供的
 * Server、Port、Mountpoint 與 Credentials（Username / Password）。
 */
class NtripSettingsActivity : AppCompatActivity() {

    private lateinit var hostLayout: TextInputLayout
    private lateinit var portLayout: TextInputLayout
    private lateinit var mountpointLayout: TextInputLayout
    private lateinit var usernameLayout: TextInputLayout
    private lateinit var passwordLayout: TextInputLayout
    private lateinit var hostInput: TextInputEditText
    private lateinit var portInput: TextInputEditText
    private lateinit var mountpointInput: TextInputEditText
    private lateinit var usernameInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var tlsSwitch: MaterialSwitch
    private lateinit var rememberPasswordCheck: MaterialCheckBox
    private lateinit var testButton: MaterialButton
    private lateinit var resultText: TextView

    @Volatile
    private var testThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ntrip_settings)
        applySystemBarInsets(findViewById(R.id.root))
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        hostLayout = findViewById(R.id.hostLayout)
        portLayout = findViewById(R.id.portLayout)
        mountpointLayout = findViewById(R.id.mountpointLayout)
        usernameLayout = findViewById(R.id.usernameLayout)
        passwordLayout = findViewById(R.id.passwordLayout)
        hostInput = findViewById(R.id.hostInput)
        portInput = findViewById(R.id.portInput)
        mountpointInput = findViewById(R.id.mountpointInput)
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        tlsSwitch = findViewById(R.id.tlsSwitch)
        rememberPasswordCheck = findViewById(R.id.rememberPasswordCheck)
        testButton = findViewById(R.id.testButton)
        resultText = findViewById(R.id.resultText)

        if (savedInstanceState == null) {
            val saved = NtripSettingsStore.load(this)
            hostInput.setText(saved.host)
            portInput.setText(saved.port.toString())
            tlsSwitch.isChecked = saved.useTls
            mountpointInput.setText(saved.mountpoint)
            usernameInput.setText(saved.username)
            passwordInput.setText(saved.password)
            rememberPasswordCheck.isChecked = NtripSettingsStore.isPasswordRemembered(this)
        }

        tlsSwitch.setOnCheckedChangeListener { _, checked ->
            // 只在使用者沿用預設 Port 時自動切換，避免覆蓋自訂值
            when (portInput.text.toString().trim()) {
                NtripSettingsStore.PLAIN_PORT.toString(),
                NtripSettingsStore.TLS_PORT.toString(), "" -> portInput.setText(
                    (if (checked) NtripSettingsStore.TLS_PORT else NtripSettingsStore.PLAIN_PORT)
                        .toString()
                )
            }
        }

        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            readForm()?.let {
                NtripSettingsStore.save(this, it, rememberPasswordCheck.isChecked)
                finish()
            }
        }
        testButton.setOnClickListener { readForm()?.let { testConnection(it) } }
    }

    override fun onDestroy() {
        testThread?.interrupt()
        testThread = null
        super.onDestroy()
    }

    private fun readForm(): NtripSettings? {
        listOf(hostLayout, portLayout, mountpointLayout, usernameLayout, passwordLayout)
            .forEach { it.error = null }
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull()
        val mountpoint = mountpointInput.text.toString().trim().trimStart('/')
        val username = usernameInput.text.toString().trim()
        val password = passwordInput.text.toString()

        when {
            host.isEmpty() -> hostLayout.error = "請輸入 Server"
            port == null || port !in 1..65535 -> portLayout.error = "Port 必須介於 1～65535"
            mountpoint.isEmpty() -> mountpointLayout.error = "請輸入 Mountpoint"
            username.isEmpty() -> usernameLayout.error = "請輸入 Username"
            password.isEmpty() -> passwordLayout.error = "請輸入 Password"
            else -> return NtripSettings(host, port, tlsSwitch.isChecked, mountpoint, username, password)
        }
        return null
    }

    private fun testConnection(settings: NtripSettings) {
        if (testThread != null) return
        testButton.isEnabled = false
        resultText.text = "正在連線 ${settings.host}:${settings.port} 取得 Sourcetable…"
        testThread = Thread {
            var mountpoints: List<String> = emptyList()
            val message = try {
                mountpoints = NtripClient.fetchMountpoints(settings)
                if (mountpoints.isEmpty()) "連線成功，但 Sourcetable 沒有列出掛載點。"
                else "連線成功，共 ${mountpoints.size} 個掛載點，點選即可套用。"
            } catch (e: Exception) {
                "測試失敗：${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                testThread = null
                if (isFinishing || isDestroyed) return@runOnUiThread
                testButton.isEnabled = true
                resultText.text = message
                if (mountpoints.isNotEmpty()) showMountpoints(mountpoints)
            }
        }.apply { start() }
    }

    private fun showMountpoints(mountpoints: List<String>) {
        MaterialAlertDialogBuilder(this)
            .setTitle("選擇 Mountpoint")
            .setItems(mountpoints.toTypedArray()) { _, which ->
                mountpointInput.setText(mountpoints[which].substringBefore('｜'))
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
