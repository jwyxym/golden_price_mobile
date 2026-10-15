package com.goldenprice.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class PriceOverlay(private val context: Context) {
	private val windows: WindowManager = context.getSystemService(WindowManager::class.java)
	private val prefs = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)
	private var root: LinearLayout? = null
	private var price: TextView? = null
	private var timestamp: TextView? = null
	private var params: WindowManager.LayoutParams? = null
	private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

	fun show() {
		if (root != null || !Settings.canDrawOverlays(context)) return
		val panel: LinearLayout = LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
			setPadding(dp(12), dp(8), dp(6), dp(8))
			background = GradientDrawable().apply { setColor(Color.argb(128, 0, 0, 0)); cornerRadius = dp(12).toFloat() }
		}
		val labels: LinearLayout = OverlayLabels(context).apply {
			orientation = LinearLayout.VERTICAL
			setOnClickListener {
				runCatching { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
			}
		}
		price = TextView(context).apply { text = "黄金 --"; textSize = 23f; setTextColor(Color.rgb(255, 230, 0)) }
		timestamp = TextView(context).apply { text = "连接中…"; textSize = 10f; setTextColor(Color.LTGRAY) }
		labels.addView(price); labels.addView(timestamp)
		panel.addView(labels)
		val close: TextView = TextView(context).apply {
			text = "×"; textSize = 22f; gravity = Gravity.CENTER; contentDescription = "隐藏悬浮窗"
			setTextColor(Color.WHITE)
			setOnClickListener { prefs.edit().putBoolean("enabled", false).apply(); hide() }
		}
		panel.addView(close, LinearLayout.LayoutParams(dp(32), dp(40)))
		val layout: WindowManager.LayoutParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
			WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
			gravity = Gravity.TOP or Gravity.START
			x = prefs.getInt("x", dp(16)); y = prefs.getInt("y", dp(180))
		}
		var downX: Float = 0f
		var downY: Float = 0f
		var originX: Int = 0
		var originY: Int = 0
		labels.contentDescription = "当前黄金价格，可拖动，点击打开应用"
		labels.setOnTouchListener { view: View, event: MotionEvent ->
			when (event.actionMasked) {
				MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; originX = layout.x; originY = layout.y }
				MotionEvent.ACTION_MOVE -> {
					layout.x = originX + (event.rawX - downX).toInt(); layout.y = originY + (event.rawY - downY).toInt()
					clamp(panel, layout)
					runCatching { windows.updateViewLayout(panel, layout) }.onFailure { hide() }
				}
				MotionEvent.ACTION_UP -> {
					prefs.edit().putInt("x", layout.x).putInt("y", layout.y).apply()
					if (abs(event.rawX - downX) < dp(6) && abs(event.rawY - downY) < dp(6)) {
						view.performClick()
					}
				}
			}
			true
		}
		try {
			windows.addView(panel, layout)
			root = panel; params = layout
			panel.post { clamp(panel, layout); runCatching { if (root === panel) windows.updateViewLayout(panel, layout) } }
		} catch (_: RuntimeException) { root = null; price = null; timestamp = null }
	}

	private fun clamp(panel: View, layout: WindowManager.LayoutParams) {
		val width: Int = context.resources.displayMetrics.widthPixels
		val height: Int = context.resources.displayMetrics.heightPixels
		layout.x = layout.x.coerceIn(0, (width - panel.width).coerceAtLeast(0))
		layout.y = layout.y.coerceIn(0, (height - panel.height).coerceAtLeast(0))
	}

	fun update(state: GoldState) {
		if (!Settings.canDrawOverlays(context)) { hide(); return }
		price?.text = state.quote?.let { "黄金 ${priceText(it.point.price)}" } ?: "黄金 --"
		timestamp?.text = if (state.error != null) "连接异常，自动重试中" else state.quote?.let { timeText(it.point.timestampMs) } ?: "连接中…"
	}

	fun hide() {
		root?.let { runCatching { windows.removeViewImmediate(it) } }
		root = null; price = null; timestamp = null; params = null
	}
}

private class OverlayLabels(context: Context) : LinearLayout(context) {
	override fun performClick(): Boolean { super.performClick(); return true }
}
