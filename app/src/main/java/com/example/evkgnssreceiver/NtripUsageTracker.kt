package com.example.evkgnssreceiver

import android.content.Context
import android.os.SystemClock
import java.util.Locale

/**
 * App 端的 NTRIP 使用時間計數器。
 *
 * 只計算「真的在接收修正資料（STREAMING）」的時間；等待定位、重連等待不計入。
 * - 本次：按下 NTRIP Connect 之後的累計時間
 * - 本期：自上次按「重設本期」之後的累計，可對齊 Thingstream 的計費週期
 * - 總計：App 安裝後的總累計
 *
 * 這只是 App 自己的估算，實際用量以 Thingstream 後台為準。
 */
internal class NtripUsageTracker(context: Context) {

    data class Snapshot(
        val streaming: Boolean,
        val sessionMs: Long,
        val periodMs: Long,
        val totalMs: Long,
        val periodStartEpochMs: Long
    )

    private val prefs = context.applicationContext
        .getSharedPreferences("ntrip_usage", Context.MODE_PRIVATE)

    private var segmentCheckpointMs: Long? = null
    private var sessionMs = 0L

    init {
        if (!prefs.contains(KEY_PERIOD_START)) {
            prefs.edit().putLong(KEY_PERIOD_START, System.currentTimeMillis()).apply()
        }
    }

    /** 使用者按下 Connect 時呼叫，重設「本次」。 */
    @Synchronized
    fun beginSession() {
        checkpoint()
        sessionMs = 0
    }

    /** 進入 STREAMING 時呼叫。 */
    @Synchronized
    fun startStreaming() {
        if (segmentCheckpointMs == null) segmentCheckpointMs = SystemClock.elapsedRealtime()
    }

    /** 離開 STREAMING 時呼叫。 */
    @Synchronized
    fun stopStreaming() {
        checkpoint()
        segmentCheckpointMs = null
    }

    /** 把目前累積的時間寫入儲存，避免 App 被系統終止時遺失。 */
    @Synchronized
    fun checkpoint() {
        val last = segmentCheckpointMs ?: return
        val now = SystemClock.elapsedRealtime()
        val delta = (now - last).coerceAtLeast(0)
        segmentCheckpointMs = now
        if (delta == 0L) return
        sessionMs += delta
        prefs.edit()
            .putLong(KEY_PERIOD_MS, prefs.getLong(KEY_PERIOD_MS, 0) + delta)
            .putLong(KEY_TOTAL_MS, prefs.getLong(KEY_TOTAL_MS, 0) + delta)
            .apply()
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val pending = segmentCheckpointMs?.let {
            (SystemClock.elapsedRealtime() - it).coerceAtLeast(0)
        } ?: 0L
        return Snapshot(
            streaming = segmentCheckpointMs != null,
            sessionMs = sessionMs + pending,
            periodMs = prefs.getLong(KEY_PERIOD_MS, 0) + pending,
            totalMs = prefs.getLong(KEY_TOTAL_MS, 0) + pending,
            periodStartEpochMs = prefs.getLong(KEY_PERIOD_START, System.currentTimeMillis())
        )
    }

    /** 重設「本期」，總計不受影響。 */
    @Synchronized
    fun resetPeriod() {
        checkpoint()
        prefs.edit()
            .putLong(KEY_PERIOD_MS, 0)
            .putLong(KEY_PERIOD_START, System.currentTimeMillis())
            .apply()
    }

    companion object {
        private const val KEY_PERIOD_MS = "period_ms"
        private const val KEY_TOTAL_MS = "total_ms"
        private const val KEY_PERIOD_START = "period_start_epoch_ms"

        fun format(durationMs: Long): String {
            val totalSeconds = durationMs / 1000
            return String.format(
                Locale.US, "%d:%02d:%02d",
                totalSeconds / 3600, (totalSeconds / 60) % 60, totalSeconds % 60
            )
        }
    }
}
