package com.example.evkgnssreceiver

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * targetSdk 35 以上預設 edge-to-edge，畫面會延伸到狀態列與導覽列下方。
 * 把系統列（與鍵盤）的高度加到根 View 的 padding，內容就不會被遮住。
 */
internal fun applySystemBarInsets(root: View) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
        )
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
}
