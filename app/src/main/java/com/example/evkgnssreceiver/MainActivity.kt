package com.example.evkgnssreceiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.atan2
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {

    // --------------------------------------------------
    // 0. 畫面狀態
    // --------------------------------------------------

    /** 狀態燈與文字的顏色等級。 */
    private enum class Level(val colorRes: Int) {
        GOOD(R.color.status_good),
        WARN(R.color.status_warn),
        BAD(R.color.status_bad),
        IDLE(R.color.status_idle)
    }

    private enum class MqttUiState { IDLE, CONNECTING, CONNECTED, FAILED }

    /** 定位狀態卡片要顯示的內容，由 USB 執行緒產生、主執行緒顯示。 */
    private data class RtkInfo(
        val level: Level,
        val headline: String,
        val hint: String,
        val satellites: String,
        val accuracy: String,
        val correctionAge: String
    )

    // --------------------------------------------------
    // 1. USB 相關變數
    // --------------------------------------------------

    private val usbPermissionAction =
        "com.example.evkgnssreceiver.USB_PERMISSION"

    private lateinit var usbManager: UsbManager

    private val sensorManager by lazy {
        getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    private var velocityFusion: AndroidVelocityFusion? = null

    @Volatile
    private var latestAcceleration: ImuSample? = null

    @Volatile
    private var latestAngularVelocity: ImuSample? = null

    private val imuListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                velocityFusion?.onOrientation(event.timestamp, event.values, event.accuracy)
                return
            }
            val (x, y, z) = event.values
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return

            val sample = ImuSample(event.timestamp, x, y, z)
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    latestAcceleration = sample
                    velocityFusion?.onAcceleration(sample)
                }
                Sensor.TYPE_GYROSCOPE -> latestAngularVelocity = sample
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    // 目前開啟的 EVK USB Serial Port
    // USB 讀取執行緒與 NTRIP 執行緒都會使用，因此標記 Volatile
    @Volatile
    private var serialPort: UsbSerialPort? = null

    // NTRIP 修正資料與 UBX 指令共用的 USB 寫入鎖
    private val usbWriteLock = Any()

    // 已送出、等待 EVK 回覆 ACK/NAK 的 UBX-CFG-VALSET（依送出順序）
    private val pendingValsetAcks = ConcurrentLinkedQueue<(Boolean) -> Unit>()

    // EVK 更新率設定的狀態，與實際量到的 NAV-PVT 頻率
    @Volatile
    private var rateConfigStatus = "尚未送出"

    @Volatile
    private var measuredRateHz: Double? = null

    private val recentItowMs = ArrayDeque<Long>()

    // 背景 USB 接收執行緒
    private var readThread: Thread? = null

    @Volatile
    private var reading = false

    // USB 卡片顯示的狀態
    @Volatile
    private var usbLevel = Level.IDLE

    @Volatile
    private var usbStateLabel = "未連接"

    @Volatile
    private var usbMessage = "按「連接 EVK」開始接收定位資料"

    // --------------------------------------------------
    // 2. 畫面元件
    // --------------------------------------------------

    private lateinit var root: View

    private lateinit var rtkCard: MaterialCardView
    private lateinit var rtkHeadline: TextView
    private lateinit var rtkHint: TextView
    private lateinit var metricSatellites: TextView
    private lateinit var metricAccuracy: TextView
    private lateinit var metricCorrectionAge: TextView

    private lateinit var usbDot: View
    private lateinit var usbState: TextView
    private lateinit var usbDetail: TextView
    private lateinit var usbToggleButton: MaterialButton
    private lateinit var usbDelayButton: MaterialButton
    private lateinit var usbRateButton: MaterialButton

    private lateinit var mqttDot: View
    private lateinit var mqttState: TextView
    private lateinit var mqttDetail: TextView
    private lateinit var mqttToggleButton: MaterialButton

    private lateinit var ntripDot: View
    private lateinit var ntripStateText: TextView
    private lateinit var ntripDetail: TextView
    private lateinit var ntripToggleButton: MaterialButton
    private lateinit var usageTitle: TextView
    private lateinit var usageSession: TextView
    private lateinit var usagePeriod: TextView
    private lateinit var usagePeriodLabel: TextView
    private lateinit var usageTotal: TextView

    private lateinit var rawToggleButton: MaterialButton
    private lateinit var rawDataText: TextView
    private var rawVisible = false

    // 即時速度與方位
    private lateinit var motionToggleButton: MaterialButton
    private lateinit var motionContent: View
    private lateinit var motionSpeed: TextView
    private lateinit var motionSpeedMps: TextView
    private lateinit var motionSource: TextView
    private lateinit var motionHeading: TextView
    private lateinit var motionDetail: TextView
    private lateinit var compassView: CompassView
    private var motionExpanded = false
    private var compassFollowPhone = false
    private var activityStarted = false

    // --------------------------------------------------
    // 3. MQTT Publisher
    // --------------------------------------------------

    private val mqttPublisher = MqttPublisher()

    @Volatile
    private var mqttUiState = MqttUiState.IDLE

    @Volatile
    private var mqttMessage = "尚未連線"

    // --------------------------------------------------
    // 3-1. NTRIP（PointPerfect SPARTN）
    // --------------------------------------------------

    @Volatile
    private var ntripClient: NtripClient? = null

    // 讓「切換目前連線」與「開始／停止計時」不會交錯執行
    private val ntripLock = Any()

    @Volatile
    private var ntripState = NtripState.DISCONNECTED

    @Volatile
    private var ntripMessage = ""

    private val ntripBytesReceived = AtomicLong(0)
    private val ntripBytesForwarded = AtomicLong(0)
    private val ntripForwardErrors = AtomicLong(0)

    // EVK 對 SPARTN 輸入設定（UBX-CFG-VALSET）的回覆
    @Volatile
    private var spartnConfigStatus = "尚未送出"

    private val ntripUsage by lazy { NtripUsageTracker(this) }

    private data class GgaSample(val sentence: String, val elapsedMs: Long)

    // 由最新 NAV-PVT 產生的 GGA，提供給 NTRIP Caster
    @Volatile
    private var latestGga: GgaSample? = null

    // --------------------------------------------------
    // 4. GNSS 資料與統計
    // --------------------------------------------------

    @Volatile
    private var totalBytes = 0L

    @Volatile
    private var navPvtCount = 0L

    @Volatile
    private var lastNavPvtElapsedMs = 0L

    // 解析完整 UBX-NAV-PVT 封包
    private var navPvtParser = UbxNavPvtParser()

    // 最新一筆 GNSS JSON
    @Volatile
    private var latestJson = "等待 NAV-PVT JSON..."

    // 最新的定位狀態
    @Volatile
    private var latestRtk: RtkInfo? = null

    /** 最新一筆 NAV-PVT 的 GNSS 速度（真北 ENU），融合尚未就緒時作為備援顯示。 */
    private data class GnssVelocity(
        val east: Double,
        val north: Double,
        val up: Double,
        val speedAccuracy: Double,
        val rtkStatus: String,
        val elapsedMs: Long
    )

    @Volatile
    private var latestGnssVelocity: GnssVelocity? = null

    // 融合模組最新的狀態，例如 FUSED_RTK_FIXED、WAITING_FOR_IMU_ATTITUDE
    @Volatile
    private var latestFusionMode = ""

    // 每 0.5 秒刷新畫面；每 10 秒把 NTRIP 使用時間寫入儲存
    private val uiHandler = Handler(Looper.getMainLooper())
    private var tickCount = 0
    private val uiTicker = object : Runnable {
        override fun run() {
            if (++tickCount % 20 == 0) ntripUsage.checkpoint()
            refreshUi()
            uiHandler.postDelayed(this, 500)
        }
    }

    // 展開「即時速度與方位」時，以 10 Hz 更新速度與羅盤
    private val motionTicker = object : Runnable {
        override fun run() {
            refreshMotion()
            uiHandler.postDelayed(this, 100)
        }
    }

    // --------------------------------------------------
    // 5. USB 權限接收器
    // --------------------------------------------------

    private val permissionReceiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: Context,
            intent: Intent
        ) {
            if (intent.action != usbPermissionAction) return

            val granted = intent.getBooleanExtra(
                UsbManager.EXTRA_PERMISSION_GRANTED,
                false
            )

            if (granted) {
                setUsbStatus("連線中", "USB 權限已允許", Level.WARN)
                connectToEvk()
            } else {
                setUsbStatus("未連接", "USB 權限遭到拒絕，請再按一次「連接 EVK」並允許", Level.BAD)
            }
        }
    }

    // --------------------------------------------------
    // 6. Activity 生命週期
    // --------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        usbManager = getSystemService(
            Context.USB_SERVICE
        ) as UsbManager

        // 註冊 USB 權限回應
        val filter = IntentFilter(usbPermissionAction)

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                permissionReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(
                permissionReceiver,
                filter
            )
        }

        setContentView(R.layout.activity_main)
        bindViews()
        uiHandler.post(uiTicker)
    }

    override fun onStart() {
        super.onStart()
        for (type in listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_ROTATION_VECTOR)) {
            sensorManager.getDefaultSensor(type)?.let { sensor ->
                sensorManager.registerListener(imuListener, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        activityStarted = true
        updateMotionTicker()
    }

    override fun onResume() {
        super.onResume()
        // 從設定頁回來時立即反映新設定
        refreshUi()
    }

    override fun onStop() {
        activityStarted = false
        updateMotionTicker()
        sensorManager.unregisterListener(imuListener)
        latestAcceleration = null
        latestAngularVelocity = null
        velocityFusion?.clearImu()
        super.onStop()
    }

    override fun onDestroy() {

        uiHandler.removeCallbacks(uiTicker)
        uiHandler.removeCallbacks(motionTicker)

        mqttPublisher.disconnect()

        stopUsbReading()

        unregisterReceiver(permissionReceiver)

        super.onDestroy()
    }

    // --------------------------------------------------
    // 7. 畫面綁定與按鈕
    // --------------------------------------------------

    private fun bindViews() {

        root = findViewById(R.id.root)
        applySystemBarInsets(root)

        rtkCard = findViewById(R.id.rtkCard)
        rtkHeadline = findViewById(R.id.rtkHeadline)
        rtkHint = findViewById(R.id.rtkHint)
        metricSatellites = findViewById(R.id.metricSatellites)
        metricAccuracy = findViewById(R.id.metricAccuracy)
        metricCorrectionAge = findViewById(R.id.metricCorrectionAge)

        usbDot = findViewById(R.id.usbDot)
        usbState = findViewById(R.id.usbState)
        usbDetail = findViewById(R.id.usbDetail)
        usbToggleButton = findViewById(R.id.usbToggleButton)
        usbDelayButton = findViewById(R.id.usbDelayButton)
        usbRateButton = findViewById(R.id.usbRateButton)

        mqttDot = findViewById(R.id.mqttDot)
        mqttState = findViewById(R.id.mqttState)
        mqttDetail = findViewById(R.id.mqttDetail)
        mqttToggleButton = findViewById(R.id.mqttToggleButton)

        ntripDot = findViewById(R.id.ntripDot)
        ntripStateText = findViewById(R.id.ntripState)
        ntripDetail = findViewById(R.id.ntripDetail)
        ntripToggleButton = findViewById(R.id.ntripToggleButton)
        usageTitle = findViewById(R.id.usageTitle)
        usageSession = findViewById(R.id.usageSession)
        usagePeriod = findViewById(R.id.usagePeriod)
        usagePeriodLabel = findViewById(R.id.usagePeriodLabel)
        usageTotal = findViewById(R.id.usageTotal)

        rawToggleButton = findViewById(R.id.rawToggleButton)
        rawDataText = findViewById(R.id.rawDataText)

        // EVK USB
        usbToggleButton.setOnClickListener {
            if (reading) {
                stopUsbReading()
                setUsbStatus("未連接", "EVK USB 已中斷", Level.IDLE)
            } else {
                connectToEvk()
            }
        }
        usbDelayButton.setOnClickListener { showDelayDialog() }
        usbRateButton.setOnClickListener { showRateDialog() }

        // MQTT
        mqttToggleButton.setOnClickListener {
            when (mqttUiState) {
                MqttUiState.CONNECTING, MqttUiState.CONNECTED -> {
                    mqttPublisher.disconnect()
                    mqttUiState = MqttUiState.IDLE
                    mqttMessage = "已中斷連線"
                    refreshUi()
                }
                else -> connectMqtt()
            }
        }
        findViewById<MaterialButton>(R.id.mqttSettingsButton).setOnClickListener {
            startActivity(Intent(this, MqttSettingsActivity::class.java))
        }

        // NTRIP
        ntripToggleButton.setOnClickListener {
            if (ntripClient != null) disconnectNtrip("已手動中斷")
            else connectNtrip()
        }
        findViewById<MaterialButton>(R.id.ntripSettingsButton).setOnClickListener {
            startActivity(Intent(this, NtripSettingsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.usageResetButton).setOnClickListener {
            confirmResetUsage()
        }

        // 即時速度與方位
        motionToggleButton = findViewById(R.id.motionToggleButton)
        motionContent = findViewById(R.id.motionContent)
        motionSpeed = findViewById(R.id.motionSpeed)
        motionSpeedMps = findViewById(R.id.motionSpeedMps)
        motionSource = findViewById(R.id.motionSource)
        motionHeading = findViewById(R.id.motionHeading)
        motionDetail = findViewById(R.id.motionDetail)
        compassView = findViewById(R.id.compassView)

        val prefs = getPreferences(Context.MODE_PRIVATE)
        motionExpanded = prefs.getBoolean("motion_expanded", false)
        compassFollowPhone = prefs.getBoolean("compass_follow_phone", false)

        motionToggleButton.setOnClickListener {
            motionExpanded = !motionExpanded
            prefs.edit().putBoolean("motion_expanded", motionExpanded).apply()
            applyMotionExpanded()
        }

        val compassModeGroup = findViewById<MaterialButtonToggleGroup>(R.id.compassModeGroup)
        compassModeGroup.check(
            if (compassFollowPhone) R.id.compassFollowPhoneButton else R.id.compassNorthUpButton
        )
        compassModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            compassFollowPhone = checkedId == R.id.compassFollowPhoneButton
            prefs.edit().putBoolean("compass_follow_phone", compassFollowPhone).apply()
            refreshMotion()
        }
        applyMotionExpanded()

        // 原始資料
        rawToggleButton.setOnClickListener {
            rawVisible = !rawVisible
            rawDataText.visibility = if (rawVisible) View.VISIBLE else View.GONE
            refreshUi()
        }

        refreshUi()
    }

    /** 主執行緒呼叫：依目前狀態更新整個畫面。 */
    private fun refreshUi() {

        if (!::root.isInitialized) return

        // 連接 EVK 時保持螢幕常亮，方便在戶外觀察
        root.keepScreenOn = reading

        // ---------- 定位狀態 ----------
        val currentRtk = latestRtk
        val rtk = when {
            !reading -> RtkInfo(Level.IDLE, "EVK 未連接", "連接 EVK 後開始顯示", "—", "—", "—")
            currentRtk == null -> RtkInfo(Level.WARN, "等待定位資料", "尚未收到 NAV-PVT", "—", "—", "—")
            SystemClock.elapsedRealtime() - lastNavPvtElapsedMs > 3_000 -> currentRtk.copy(
                level = Level.BAD,
                headline = "資料中斷",
                hint = "超過 3 秒沒有收到 NAV-PVT，請檢查 EVK 連線"
            )
            else -> currentRtk
        }
        val rtkColor = color(rtk.level)
        rtkCard.strokeColor = rtkColor
        rtkHeadline.text = rtk.headline
        rtkHeadline.setTextColor(rtkColor)
        rtkHint.text = rtk.hint
        metricSatellites.text = rtk.satellites
        metricAccuracy.text = rtk.accuracy
        metricCorrectionAge.text = rtk.correctionAge

        // ---------- EVK USB ----------
        setDot(usbDot, usbLevel)
        usbState.text = usbStateLabel
        usbState.setTextColor(color(usbLevel))
        usbDetail.text = if (reading) {
            val measured = measuredRateHz?.let { String.format(Locale.US, "%.1f Hz", it) } ?: "—"
            "更新率 實測 $measured（設定 ${savedRateHz()} Hz：$rateConfigStatus）\n" +
                    "NAV-PVT ${navPvtCount} 筆｜共收到 ${formatBytes(totalBytes)}"
        } else {
            usbMessage
        }
        usbToggleButton.text = if (reading) "中斷 EVK" else "連接 EVK"
        usbDelayButton.text = "延遲補償 ${savedDelayMs()} ms"
        usbRateButton.text = "更新率 ${savedRateHz()} Hz"

        // ---------- MQTT ----------
        val mqttLevel: Level
        val mqttLabel: String
        when (mqttUiState) {
            MqttUiState.CONNECTED -> if (mqttPublisher.isConnected()) {
                mqttLevel = Level.GOOD; mqttLabel = "已連線"
            } else {
                mqttLevel = Level.BAD; mqttLabel = "連線中斷"
            }
            MqttUiState.CONNECTING -> { mqttLevel = Level.WARN; mqttLabel = "連線中" }
            MqttUiState.FAILED -> { mqttLevel = Level.BAD; mqttLabel = "連線失敗" }
            MqttUiState.IDLE -> { mqttLevel = Level.IDLE; mqttLabel = "未連線" }
        }
        setDot(mqttDot, mqttLevel)
        mqttState.text = mqttLabel
        mqttState.setTextColor(color(mqttLevel))
        val mqttSettings = MqttSettingsStore.load(this)
        mqttDetail.text = buildString {
            append("Broker ${mqttSettings.host}:${mqttSettings.port}")
            if (mqttUiState == MqttUiState.CONNECTED) {
                append("\n已發布 ${mqttPublisher.publishedCount.get()} 筆")
                val failures = mqttPublisher.failedCount.get()
                if (failures > 0) append("｜失敗 $failures 筆")
            } else if (mqttMessage.isNotBlank()) {
                append("\n$mqttMessage")
            }
        }
        mqttToggleButton.text = when (mqttUiState) {
            MqttUiState.CONNECTING, MqttUiState.CONNECTED -> "中斷"
            else -> "連線"
        }

        // ---------- NTRIP ----------
        val ntripLevel = when (ntripState) {
            NtripState.STREAMING -> Level.GOOD
            NtripState.WAITING_FOR_FIX, NtripState.CONNECTING, NtripState.RECONNECTING -> Level.WARN
            NtripState.FAILED -> Level.BAD
            NtripState.DISCONNECTED -> Level.IDLE
        }
        setDot(ntripDot, ntripLevel)
        ntripStateText.text = when (ntripState) {
            NtripState.STREAMING -> "接收中"
            NtripState.WAITING_FOR_FIX -> "等待定位"
            NtripState.CONNECTING -> "連線中"
            NtripState.RECONNECTING -> "重新連線"
            NtripState.FAILED -> "連線失敗"
            NtripState.DISCONNECTED -> "未連線"
        }
        ntripStateText.setTextColor(color(ntripLevel))

        val ntripActive = ntripClient != null
        ntripDetail.text = buildString {
            if (ntripMessage.isNotBlank()) append(ntripMessage)
            if (ntripActive || ntripBytesReceived.get() > 0) {
                if (isNotEmpty()) append('\n')
                append("修正資料 ${formatBytes(ntripBytesForwarded.get())} 已轉送 EVK")
                val errors = ntripForwardErrors.get()
                if (errors > 0) append("（失敗 $errors 次）")
                append("\nGGA ${if (currentGga() != null) "可用" else "等待 3D 定位"}")
                append("｜EVK 設定：$spartnConfigStatus")
            }
            if (!ntripActive && !reading) {
                if (isNotEmpty()) append('\n')
                append("請先連接 EVK（修正資料要經由 USB 傳給 EVK）")
            }
        }
        ntripToggleButton.text = if (ntripActive) "NTRIP 中斷" else "NTRIP 連線"
        ntripToggleButton.isEnabled = ntripActive || reading

        val usage = ntripUsage.snapshot()
        usageTitle.text = if (usage.streaming) "使用時間・計時中" else "使用時間"
        usageSession.text = NtripUsageTracker.format(usage.sessionMs)
        usagePeriod.text = NtripUsageTracker.format(usage.periodMs)
        usageTotal.text = NtripUsageTracker.format(usage.totalMs)
        usagePeriodLabel.text = "本期（${
            Instant.ofEpochMilli(usage.periodStartEpochMs)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("M/d"))
        } 起）"

        // ---------- 原始資料 ----------
        rawToggleButton.text =
            if (rawVisible) "▾ 隱藏原始資料" else "▸ 顯示原始資料（最新 JSON）"
        if (rawVisible) {
            rawDataText.text = latestJson
        }
    }

    private fun setDot(dot: View, level: Level) {
        dot.backgroundTintList = ColorStateList.valueOf(color(level))
    }

    private fun color(level: Level): Int = ContextCompat.getColor(this, level.colorRes)

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0)
    }

    private fun snack(message: String, actionText: String? = null, action: (() -> Unit)? = null) {
        val bar = Snackbar.make(root, message, Snackbar.LENGTH_LONG)
        if (actionText != null && action != null) bar.setAction(actionText) { action() }
        bar.show()
    }

    // --------------------------------------------------
    // 7-1. EVK 固定延遲補償
    // --------------------------------------------------

    private fun savedDelayMs(): Long =
        getPreferences(Context.MODE_PRIVATE).getLong("gnss_delay_ms", 0)

    private fun showDelayDialog() {

        val view = layoutInflater.inflate(R.layout.dialog_gnss_delay, null)
        val layout = view.findViewById<TextInputLayout>(R.id.delayLayout)
        val input = view.findViewById<TextInputEditText>(R.id.delayInput)
        input.setText(savedDelayMs().toString())

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("EVK 固定延遲補償")
            .setMessage("補償 USB 傳輸造成的固定延遲，用於 IMU 與 GNSS 時間對齊。不確定時請保持 0。")
            .setView(view)
            .setPositiveButton("儲存", null)
            .setNegativeButton("取消", null)
            .show()

        // 自行處理「儲存」，輸入不合法時不關閉對話框
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().trim().ifBlank { "0" }.toLongOrNull()
            if (value == null || value !in 0L..1000L) {
                layout.error = "請輸入 0–1000"
                return@setOnClickListener
            }
            getPreferences(Context.MODE_PRIVATE).edit().putLong("gnss_delay_ms", value).apply()
            dialog.dismiss()
            refreshUi()
            if (reading) snack("下次連接 EVK 時生效")
        }
    }

    // --------------------------------------------------
    // 7-2. MQTT 連線
    // --------------------------------------------------

    private fun connectMqtt() {

        val settings = MqttSettingsStore.load(this)
        if (!settings.isComplete) {
            snack("請先輸入 MQTT 設定（含密碼）", "前往設定") {
                startActivity(Intent(this, MqttSettingsActivity::class.java))
            }
            return
        }

        mqttUiState = MqttUiState.CONNECTING
        mqttMessage = "正在連線…"
        refreshUi()

        mqttPublisher.connect(
            host = settings.host,
            port = settings.port,
            username = settings.username,
            password = settings.password
        ) { success, message ->
            // 若使用者已按中斷，忽略遲來的結果
            if (mqttUiState != MqttUiState.CONNECTING) return@connect
            mqttUiState = if (success) MqttUiState.CONNECTED else MqttUiState.FAILED
            mqttMessage = message
            runOnUiThread { refreshUi() }
        }
    }

    // --------------------------------------------------
    // 8. 連接 EVK-X20P
    // --------------------------------------------------

    private fun setUsbStatus(label: String, message: String, level: Level) {
        usbStateLabel = label
        usbMessage = message
        usbLevel = level
        runOnUiThread { refreshUi() }
    }

    private fun connectToEvk() {

        if (reading) {
            snack("EVK USB 已經連線")
            return
        }

        val delayMs = savedDelayMs()

        // 掃描 Android 支援的 USB Serial 裝置
        val drivers = UsbSerialProber
            .getDefaultProber()
            .findAllDrivers(usbManager)

        if (drivers.isEmpty()) {
            setUsbStatus("未連接", "找不到 USB Serial 裝置，請確認 OTG 線與 EVK 電源", Level.BAD)
            return
        }

        // 尋找 u-blox EVK 的 USB VID / PID
        val driver: UsbSerialDriver =
            drivers.firstOrNull {

                it.device.vendorId == 0x1546 &&
                        it.device.productId == 0x01AB

            } ?: run {

                setUsbStatus("未連接", "未找到 EVK USB 裝置，請確認 VID/PID", Level.BAD)

                return
            }

        val device = driver.device

        // Android 尚未取得 USB 權限
        if (!usbManager.hasPermission(device)) {

            setUsbStatus("等待授權", "請在系統視窗允許 USB 存取", Level.WARN)

            val intent = Intent(usbPermissionAction).apply {
                setPackage(packageName)
            }

            val permissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_MUTABLE
            )

            usbManager.requestPermission(
                device,
                permissionIntent
            )

            return
        }

        val connection = usbManager.openDevice(device)

        if (connection == null) {
            setUsbStatus("未連接", "無法開啟 EVK USB 裝置", Level.BAD)
            return
        }

        try {

            val port = driver.ports.first()

            port.open(connection)

            // 原本測試成功的 Serial 參數
            port.setParameters(
                38400,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )

            serialPort = port

            // 每次重新連接 USB 時重設統計
            totalBytes = 0L
            navPvtCount = 0L
            pendingValsetAcks.clear()
            synchronized(recentItowMs) { recentItowMs.clear() }
            measuredRateHz = null
            rateConfigStatus = "尚未送出"
            navPvtParser = UbxNavPvtParser().apply {
                onAck = { acked, cls, id ->
                    // EVK 依收到順序回覆，交給對應的指令處理
                    if (cls == UbxCommands.CLASS_CFG && id == UbxCommands.ID_CFG_VALSET) {
                        pendingValsetAcks.poll()?.invoke(acked)
                    }
                }
            }
            latestGga = null
            latestRtk = null
            latestGnssVelocity = null
            latestFusionMode = ""
            spartnConfigStatus = "尚未送出"
            latestJson = "等待 NAV-PVT JSON..."

            reading = true

            setUsbStatus("已連線", "EVK USB 已連線", Level.GOOD)

            val fusion = AndroidVelocityFusion(delayMs)
            velocityFusion = fusion
            startReading(port, fusion)

            // 依設定把 EVK 更新率寫入 RAM（預設 10 Hz）
            applyNavigationRate()

        } catch (e: Exception) {

            try {
                serialPort?.close()
            } catch (_: Exception) {
            }

            serialPort = null
            connection.close()

            setUsbStatus("未連接", "EVK USB 連線失敗：${e.message}", Level.BAD)
        }
    }

    // --------------------------------------------------
    // 9. USB 背景執行緒：接收 GNSS 並發布 MQTT
    // --------------------------------------------------

    private fun startReading(port: UsbSerialPort, fusion: AndroidVelocityFusion) {
        val parser = navPvtParser

        readThread = Thread {

            val buffer = ByteArray(4096)

            try {

                while (reading && !Thread.currentThread().isInterrupted) {

                    // 從 EVK 讀取 USB Bytes
                    // 最多等待 1000 毫秒
                    val length = port.read(
                        buffer,
                        1000
                    )
                    val arrivalNs = SystemClock.elapsedRealtimeNanos()

                    if (Thread.currentThread().isInterrupted) break
                    if (length <= 0) continue

                    // 只取這次實際收到的 Bytes
                    val received = buffer.copyOf(length)

                    totalBytes += received.size

                    // 解析完整 UBX-NAV-PVT 封包
                    parser.feed(received) { json ->
                        // shortcut: 每筆 GNSS 只附上最新樣本，需要完整 IMU 序列時再獨立記錄。
                        json.put("imu", imuJson(latestAcceleration, latestAngularVelocity))
                        val fusionJson = fusion.onNavPvt(json, arrivalNs)
                        json.put("fusion", fusionJson)
                        latestFusionMode = fusionJson.optString("mode")
                        latestGnssVelocity = gnssVelocity(json)

                        // 產生 GGA 給 NTRIP Caster（沒有 3D 定位時為 null）
                        NmeaGga.fromNavPvt(json)?.let {
                            latestGga = GgaSample(it, SystemClock.elapsedRealtime())
                        }
                        latestRtk = describeRtk(json)
                        lastNavPvtElapsedMs = SystemClock.elapsedRealtime()
                        updateMeasuredRate(json.optLong("gps_itow_ms", -1))
                        navPvtCount = parser.validPacketCount
                        json.put("ntrip", ntripJson())

                        // 保留最新一筆 JSON 供畫面顯示
                        latestJson = json.toString(2)

                        // 將完整 JSON 發布到 MQTT
                        // MQTT 未連線時 publishJson 會直接返回
                        mqttPublisher.publishJson(
                            json.toString()
                        )
                    }
                    // 畫面由 uiTicker 每 0.5 秒刷新，這裡不必每次都切換到主執行緒
                }

            } catch (e: Exception) {

                runOnUiThread {
                    if (velocityFusion === fusion) {
                        setUsbStatus("未連接", "USB 接收錯誤：${e.message}", Level.BAD)
                    }
                }

            } finally {

                try {
                    port.close()
                } catch (_: Exception) {
                }

                runOnUiThread {
                    if (velocityFusion === fusion) {
                        reading = false
                        serialPort = null
                        readThread = null
                        velocityFusion = null
                        if (usbLevel == Level.GOOD) {
                            setUsbStatus("未連接", "EVK USB 已中斷", Level.IDLE)
                        }
                        disconnectNtrip("EVK USB 已中斷，NTRIP 自動停止以節省額度")
                        refreshUi()
                    }
                }
            }

        }.apply {
            start()
        }
    }

    // --------------------------------------------------
    // 9-1. NTRIP：連線、轉送 SPARTN
    // --------------------------------------------------

    private fun connectNtrip() {

        if (ntripClient != null) return

        // SPARTN 必須透過 USB 交給 EVK；GGA 也來自 EVK
        if (!reading || serialPort == null) {
            snack("請先連接 EVK USB")
            return
        }

        val settings = NtripSettingsStore.load(this)
        if (!settings.isComplete) {
            snack("請先輸入 NTRIP 設定（含密碼）", "前往設定") {
                startActivity(Intent(this, NtripSettingsActivity::class.java))
            }
            return
        }

        ntripBytesReceived.set(0)
        ntripBytesForwarded.set(0)
        ntripForwardErrors.set(0)

        // 確保 EVK 的 USB 介面接受 SPARTN，且來源設為 IP（只寫 RAM）
        spartnConfigStatus = "等待回覆"
        val spartnSent = sendValset(UbxCommands.enableSpartnInputOnUsb()) { acked ->
            spartnConfigStatus = if (acked) "已確認接受 SPARTN"
            else "拒絕設定（NAK），請用 u-center 檢查 CFG-USBINPROT-SPARTN"
        }
        if (!spartnSent) spartnConfigStatus = "送出失敗"

        ntripUsage.beginSession()

        lateinit var client: NtripClient
        client = NtripClient(
            settings = settings,
            ggaProvider = ::currentGga,
            onCorrection = { bytes -> forwardCorrection(client, bytes) },
            onState = { state, message -> onNtripState(client, state, message) }
        )
        synchronized(ntripLock) {
            ntripClient = client
            ntripState = NtripState.CONNECTING
            ntripMessage = "${settings.host}:${settings.port}"
        }
        client.start()
        refreshUi()
    }

    private fun disconnectNtrip(reason: String) {

        val client = synchronized(ntripLock) {
            val current = ntripClient ?: return
            ntripClient = null
            ntripUsage.stopStreaming()
            ntripState = NtripState.DISCONNECTED
            ntripMessage = reason
            current
        }
        client.stop()
        runOnUiThread { refreshUi() }
    }

    /** NtripClient 背景執行緒呼叫。 */
    private fun onNtripState(client: NtripClient, state: NtripState, message: String) {

        synchronized(ntripLock) {
            // 忽略已中斷的舊連線送來的狀態
            if (ntripClient !== client) return

            if (state == NtripState.STREAMING) ntripUsage.startStreaming()
            else ntripUsage.stopStreaming()

            ntripState = state
            ntripMessage = message
        }

        runOnUiThread {
            if (state == NtripState.DISCONNECTED || state == NtripState.FAILED) {
                synchronized(ntripLock) {
                    if (ntripClient === client) ntripClient = null
                }
            }
            refreshUi()
        }
    }

    /** NtripClient 背景執行緒呼叫：把 SPARTN bytes 原封不動寫入 EVK。 */
    private fun forwardCorrection(client: NtripClient, bytes: ByteArray) {

        if (ntripClient !== client) return
        ntripBytesReceived.addAndGet(bytes.size.toLong())
        if (writeToEvk(bytes)) ntripBytesForwarded.addAndGet(bytes.size.toLong())
        else ntripForwardErrors.incrementAndGet()
    }

    private fun writeToEvk(bytes: ByteArray): Boolean {

        val port = serialPort ?: return false
        return try {
            synchronized(usbWriteLock) {
                port.write(bytes, 1000)
            }
            true
        } catch (e: Exception) {
            Log.w("EVK_NTRIP", "USB write failed", e)
            false
        }
    }

    /** 只提供 5 秒內的 GGA，避免把過時位置送給 Caster。 */
    private fun currentGga(): String? {

        val sample = latestGga ?: return null
        return sample.sentence.takeIf {
            SystemClock.elapsedRealtime() - sample.elapsedMs <= 5_000
        }
    }

    private fun ntripJson(): JSONObject = JSONObject()
        .put("state", ntripState.name)
        .put("bytes_received", ntripBytesReceived.get())
        .put("bytes_forwarded_to_evk", ntripBytesForwarded.get())
        .put("forward_errors", ntripForwardErrors.get())

    private fun describeRtk(json: JSONObject): RtkInfo {

        val fixOk = json.optBoolean("fix_ok")
        val fixType = json.optInt("fix_type")
        val (level, headline, hint) = when {
            !fixOk || fixType !in 2..4 ->
                Triple(Level.BAD, "未定位", "等待衛星訊號，請確認天線在開闊處")
            json.optString("rtk_status") == "RTK_FIXED" ->
                Triple(Level.GOOD, "RTK FIXED", "已成功 RTK 定位（公分級）")
            json.optString("rtk_status") == "RTK_FLOAT" ->
                Triple(Level.WARN, "RTK FLOAT", "已收到修正資料，收斂中…")
            json.optBoolean("differential_solution") ->
                Triple(Level.WARN, "DGNSS", "已套用修正，尚未進入 RTK")
            fixType == 2 ->
                Triple(Level.BAD, "2D 定位", "可見衛星不足")
            else ->
                Triple(Level.WARN, "3D 定位", "尚未使用修正資料；連線 NTRIP 可提升精度")
        }

        val horizontal = json.optDouble("horizontal_accuracy_m")
        val accuracy = when {
            !horizontal.isFinite() -> "—"
            horizontal < 1.0 -> String.format(Locale.US, "%.1f cm", horizontal * 100)
            else -> String.format(Locale.US, "%.2f m", horizontal)
        }
        val correctionAge = if (json.isNull("correction_age_s")) "無"
        else "${json.getString("correction_age_s")} s"

        return RtkInfo(
            level, headline, hint,
            satellites = json.optInt("satellites_used").toString(),
            accuracy = accuracy,
            correctionAge = correctionAge
        )
    }

    /** 送出 VALSET，並登記收到 ACK/NAK 時要做的事。 */
    private fun sendValset(command: ByteArray, onResult: (Boolean) -> Unit): Boolean {

        synchronized(usbWriteLock) {
            if (serialPort == null) return false
            pendingValsetAcks.add(onResult)
            if (writeToEvk(command)) return true
            pendingValsetAcks.remove(onResult)
            return false
        }
    }

    // --------------------------------------------------
    // 9-1-1. EVK 更新率
    // --------------------------------------------------

    private fun savedRateHz(): Int =
        getPreferences(Context.MODE_PRIVATE).getInt("nav_rate_hz", 10)
            .takeIf { it in UbxCommands.SUPPORTED_RATES_HZ } ?: 10

    private fun applyNavigationRate() {

        val hz = savedRateHz()
        // 先標記為等待中，避免 ACK 很快回來時被覆蓋
        rateConfigStatus = "等待回覆"
        val sent = sendValset(UbxCommands.setNavigationRate(hz)) { acked ->
            rateConfigStatus = if (acked) "已確認" else "EVK 拒絕（NAK）"
            runOnUiThread { refreshUi() }
        }
        if (!sent) rateConfigStatus = "送出失敗"
        synchronized(recentItowMs) { recentItowMs.clear() }
        measuredRateHz = null
        refreshUi()
    }

    private fun showRateDialog() {

        val rates = UbxCommands.SUPPORTED_RATES_HZ
        val current = rates.indexOf(savedRateHz()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("EVK 定位更新率")
            .setSingleChoiceItems(rates.map { "$it Hz" }.toTypedArray(), current) { dialog, which ->
                getPreferences(Context.MODE_PRIVATE).edit().putInt("nav_rate_hz", rates[which]).apply()
                dialog.dismiss()
                if (reading) applyNavigationRate() else refreshUi()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** USB 執行緒呼叫：用最近 10 筆 iTOW 算出實際更新率。 */
    private fun updateMeasuredRate(itowMs: Long) {

        if (itowMs < 0) return
        synchronized(recentItowMs) {
            val last = recentItowMs.lastOrNull()
            // 時間倒退或中斷超過 3 秒就重新計算
            if (last != null && (itowMs <= last || itowMs - last > 3_000)) recentItowMs.clear()
            recentItowMs.addLast(itowMs)
            while (recentItowMs.size > 11) recentItowMs.removeFirst()
            measuredRateHz = if (recentItowMs.size >= 3) {
                (recentItowMs.size - 1) * 1000.0 / (recentItowMs.last() - recentItowMs.first())
            } else null
        }
    }

    // --------------------------------------------------
    // 9-2. 即時速度與方位（GNSS + IMU）
    // --------------------------------------------------

    private fun gnssVelocity(json: JSONObject): GnssVelocity? {

        if (!json.optBoolean("fix_ok") || json.optInt("fix_type") !in 3..4) return null
        val north = json.optDouble("velocity_n_mps")
        val east = json.optDouble("velocity_e_mps")
        val down = json.optDouble("velocity_d_mps")
        if (!north.isFinite() || !east.isFinite() || !down.isFinite()) return null
        return GnssVelocity(
            east = east,
            north = north,
            up = -down,
            speedAccuracy = json.optDouble("speed_accuracy_mps", 0.5),
            rtkStatus = json.optString("rtk_status"),
            elapsedMs = SystemClock.elapsedRealtime()
        )
    }

    private fun applyMotionExpanded() {

        motionContent.visibility = if (motionExpanded) View.VISIBLE else View.GONE
        motionToggleButton.text =
            if (motionExpanded) "▾ 即時速度與方位（GNSS + IMU）" else "▸ 即時速度與方位（GNSS + IMU）"
        updateMotionTicker()
    }

    /** 只在畫面可見且區塊展開時以 10 Hz 更新，節省電力。 */
    private fun updateMotionTicker() {

        uiHandler.removeCallbacks(motionTicker)
        if (activityStarted && motionExpanded) uiHandler.post(motionTicker)
    }

    private fun refreshMotion() {

        if (!::compassView.isInitialized) return

        val nowMs = SystemClock.elapsedRealtime()
        val gnss = latestGnssVelocity?.takeIf { nowMs - it.elapsedMs <= 2_000 }
        val live = velocityFusion?.liveVelocity(SystemClock.elapsedRealtimeNanos())

        // 優先使用融合結果；融合尚未就緒時退回 GNSS 速度
        val east: Double
        val north: Double
        val up: Double
        val sourceText: String
        val sourceLevel: Level
        when {
            live != null && live.sinceGnssMs <= 1_500 -> {
                east = live.east; north = live.north; up = live.up
                sourceText = "GNSS + IMU 融合" + rtkSuffix(gnss?.rtkStatus)
                sourceLevel = Level.GOOD
            }
            live != null -> {
                east = live.east; north = live.north; up = live.up
                sourceText = "IMU 推算中（%.1f 秒未收到 GNSS 速度）".format(Locale.US, live.sinceGnssMs / 1000)
                sourceLevel = Level.WARN
            }
            gnss != null -> {
                east = gnss.east; north = gnss.north; up = gnss.up
                sourceText = "僅 GNSS（${fusionWaitingReason()}）"
                sourceLevel = Level.WARN
            }
            else -> {
                motionSpeed.text = "—"
                motionSpeedMps.text = ""
                motionSource.text = if (!reading) "請先連接 EVK" else fusionWaitingReason()
                motionSource.setTextColor(color(Level.IDLE))
                motionHeading.text = "方位 —"
                motionDetail.text = "需要 EVK 3D 定位才能計算速度與方位"
                compassView.update(null, dialRotation(), false, color(Level.IDLE))
                return
            }
        }

        val speed = hypot(east, north)
        val courseDeg = ((Math.toDegrees(atan2(east, north)) % 360) + 360) % 360

        // 速度低於誤差範圍時，方向主要是雜訊
        val threshold = maxOf(0.3, 2 * (gnss?.speedAccuracy ?: 0.25))
        val moving = speed >= threshold

        motionSpeed.text = String.format(Locale.US, "%.1f", speed * 3.6)
        motionSpeedMps.text = String.format(Locale.US, "%.2f m/s", speed)
        motionSource.text = sourceText
        motionSource.setTextColor(color(sourceLevel))
        motionHeading.text = if (moving) {
            String.format(Locale.US, "方位 %.0f°（%s）", courseDeg, CompassView.directionName(courseDeg))
        } else {
            "幾乎靜止，方位不可靠"
        }
        motionDetail.text = buildString {
            append(String.format(Locale.US, "垂直速度 %+.2f m/s", up))
            gnss?.let {
                append(String.format(
                    Locale.US, "｜GNSS 原始 %.1f km/h ±%.2f m/s",
                    hypot(it.east, it.north) * 3.6, it.speedAccuracy
                ))
            }
            if (compassFollowPhone) {
                append("\n跟隨手機：手機平放時方位最準確")
            }
        }
        compassView.update(courseDeg.toFloat(), dialRotation(), moving, color(Level.GOOD))
    }

    /** 北方朝上時為 0；跟隨手機時為手機頂端的真北方位角。 */
    private fun dialRotation(): Float =
        if (compassFollowPhone) velocityFusion?.phoneAzimuthTrueDeg()?.toFloat() ?: 0f else 0f

    private fun rtkSuffix(rtkStatus: String?): String = when (rtkStatus) {
        "RTK_FIXED" -> "・RTK FIXED"
        "RTK_FLOAT" -> "・RTK FLOAT"
        else -> ""
    }

    private fun fusionWaitingReason(): String = when (latestFusionMode) {
        "WAITING_FOR_IMU_ATTITUDE" -> "等待手機姿態感測器"
        "WAITING_FOR_TIME_ALIGNMENT" -> "GNSS 與 IMU 時間對齊中"
        "WAITING_FOR_GNSS_VELOCITY" -> "等待 GNSS 3D 速度"
        "WAITING_FOR_IMU_HISTORY" -> "累積 IMU 資料中"
        "" -> "等待定位資料"
        else -> "融合準備中"
    }

    private fun confirmResetUsage() {

        MaterialAlertDialogBuilder(this)
            .setTitle("重設本期 NTRIP 計時？")
            .setMessage("建議在 Thingstream 計費週期開始時重設。總計不受影響。")
            .setPositiveButton("重設") { _, _ ->
                ntripUsage.resetPeriod()
                refreshUi()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // --------------------------------------------------
    // 10. 停止 USB 接收
    // --------------------------------------------------

    private fun stopUsbReading() {

        // 沒有 EVK 就無法使用修正資料，先停止 NTRIP 避免浪費額度
        disconnectNtrip("EVK USB 已中斷，NTRIP 自動停止以節省額度")

        reading = false
        velocityFusion = null

        readThread?.interrupt()
        readThread = null

        try {
            serialPort?.close()
        } catch (_: Exception) {
        }

        serialPort = null
        latestRtk = null
    }
}
