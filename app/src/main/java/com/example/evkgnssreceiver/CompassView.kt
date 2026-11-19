package com.example.evkgnssreceiver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 羅盤：刻度盤標示方位，箭頭指向移動方向。
 *
 * - courseDeg：移動方位，真北為 0°、順時針增加；null 表示沒有資料（不畫箭頭）
 * - dialRotationDeg：畫面正上方所對應的方位。0 = 北方朝上；
 *   跟隨手機模式時傳入手機頂端的方位角，刻度盤會反向旋轉，讓「北」指向真實的北方
 * - active = false 時箭頭變灰（例如速度太慢，方位不可靠）
 */
class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var targetCourse: Float? = null
    private var shownCourse: Float? = null
    private var targetDial = 0f
    private var shownDial = 0f
    private var arrowActive = false
    private var arrowColor = Color.GRAY

    private val onSurface = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY
    )
    private val outline = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorOutline, Color.GRAY
    )
    private val northColor = ContextCompat.getColor(context, R.color.status_bad)
    private val idleColor = ContextCompat.getColor(context, R.color.status_idle)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = outline
        strokeWidth = dp(2f)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = sp(18f)
    }
    private val degreePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = outline
        textSize = sp(11f)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = onSurface
    }
    private val arrowPath = Path()

    fun update(courseDeg: Float?, dialRotationDeg: Float, active: Boolean, color: Int) {
        targetCourse = courseDeg
        targetDial = dialRotationDeg
        arrowActive = active
        arrowColor = color

        // 以最短角度平滑轉動，避免箭頭在 359°↔0° 之間繞一圈
        shownDial = approach(shownDial, targetDial)
        shownCourse = when {
            courseDeg == null -> null
            shownCourse == null -> courseDeg
            else -> approach(shownCourse!!, courseDeg)
        }
        contentDescription = if (courseDeg == null) "移動方位：無資料"
        else "移動方位 ${courseDeg.toInt()} 度，${directionName(courseDeg.toDouble())}"
        invalidate()
    }

    private fun approach(current: Float, target: Float): Float {
        val delta = ((target - current) % 360 + 540) % 360 - 180
        return if (kotlin.math.abs(delta) < 0.5f) target else current + delta * 0.45f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val preferred = dp(240f).toInt()
        val width = resolveSize(preferred, widthMeasureSpec)
        val height = resolveSize(preferred, heightMeasureSpec)
        val size = min(width, height)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - dp(4f)

        canvas.drawCircle(cx, cy, radius, ringPaint)

        // 刻度：每 15° 一格，45° 較長；文字保持正向
        for (angle in 0 until 360 step 15) {
            val screen = Math.toRadians((angle - shownDial).toDouble())
            val major = angle % 45 == 0
            val inner = radius - if (major) dp(14f) else dp(8f)
            tickPaint.color = if (angle == 0) northColor else outline
            tickPaint.strokeWidth = if (major) dp(2.5f) else dp(1.2f)
            canvas.drawLine(
                cx + (inner * sin(screen)).toFloat(), cy - (inner * cos(screen)).toFloat(),
                cx + (radius * sin(screen)).toFloat(), cy - (radius * cos(screen)).toFloat(),
                tickPaint
            )
        }

        val labelRadius = radius - dp(30f)
        for ((angle, label) in listOf(0 to "北", 90 to "東", 180 to "南", 270 to "西")) {
            val screen = Math.toRadians((angle - shownDial).toDouble())
            labelPaint.color = if (angle == 0) northColor else onSurface
            val x = cx + (labelRadius * sin(screen)).toFloat()
            val y = cy - (labelRadius * cos(screen)).toFloat() - (labelPaint.ascent() + labelPaint.descent()) / 2
            canvas.drawText(label, x, y, labelPaint)
        }
        for (angle in listOf(45, 135, 225, 315)) {
            val screen = Math.toRadians((angle - shownDial).toDouble())
            val x = cx + (labelRadius * sin(screen)).toFloat()
            val y = cy - (labelRadius * cos(screen)).toFloat() - (degreePaint.ascent() + degreePaint.descent()) / 2
            canvas.drawText("$angle°", x, y, degreePaint)
        }

        // 移動方向箭頭
        shownCourse?.let { course ->
            canvas.save()
            canvas.rotate(course - shownDial, cx, cy)
            val tip = radius - dp(44f)
            val halfWidth = radius * 0.16f
            val tail = radius * 0.42f
            arrowPath.reset()
            arrowPath.moveTo(cx, cy - tip)
            arrowPath.lineTo(cx + halfWidth, cy + tail)
            arrowPath.lineTo(cx, cy + tail * 0.55f)
            arrowPath.lineTo(cx - halfWidth, cy + tail)
            arrowPath.close()
            arrowPaint.color = if (arrowActive) arrowColor else idleColor
            arrowPaint.alpha = if (arrowActive) 255 else 140
            canvas.drawPath(arrowPath, arrowPaint)
            canvas.restore()
        }

        canvas.drawCircle(cx, cy, dp(5f), hubPaint)
    }

    private fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    companion object {
        private val DIRECTIONS = listOf("北", "東北", "東", "東南", "南", "西南", "西", "西北")

        /** 方位角轉成 8 方位中文名稱。 */
        fun directionName(degrees: Double): String {
            val index = (((degrees % 360 + 360) % 360 + 22.5) / 45).toInt() % 8
            return DIRECTIONS[index]
        }
    }
}
