package com.goldenprice.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object GoldChart {
	private val gold: Int = Color.rgb(255, 230, 0)
	private val muted: Int = Color.rgb(181, 191, 206)

	fun bitmap(state: GoldState, compact: Boolean = false): Bitmap {
		val image: Bitmap = Bitmap.createBitmap(if (compact) 320 else 640, if (compact) 64 else 240, Bitmap.Config.ARGB_8888)
		draw(Canvas(image), image.width.toFloat(), image.height.toFloat(), state, null, compact)
		return image
	}

	fun area(width: Float, height: Float): RectF {
		val unit: Float = min(width / 400f, height / 220f)
		return RectF(12f * unit, 24f * unit, width - 60f * unit, height - 28f * unit)
	}

	fun nearest(state: GoldState, width: Float, height: Float, x: Float, y: Float): Int? {
		val plot: RectF = area(width, height)
		if (state.points.isEmpty() || !plot.contains(x, y) || plot.width() <= 0) return null
		val timestamp: Long = state.session.startMs + ((x - plot.left) / plot.width() * (state.session.endMs - state.session.startMs - 1)).toLong()
		if (timestamp < state.points.first().timestampMs || timestamp > state.points.last().timestampMs) return null
		val search: Int = state.points.binarySearchBy(timestamp) { it.timestampMs }
		if (search >= 0) return search
		val index: Int = -search - 1
		return when {
			index == 0 -> 0
			index == state.points.size -> index - 1
			abs(state.points[index - 1].timestampMs - timestamp) <= abs(state.points[index].timestampMs - timestamp) -> index - 1
			else -> index
		}
	}

	fun draw(canvas: Canvas, width: Float, height: Float, state: GoldState, selected: Int?, compact: Boolean = false) {
		canvas.drawColor(Color.argb(128, 0, 0, 0))
		val unit: Float = min(width / 400f, height / 220f).coerceAtLeast(0.3f)
		val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
		val plot: RectF = if (compact) RectF(2f, 3f, width - 2f, height - 3f) else area(width, height)
		fun text(text: String, x: Float, baseline: Float, color: Int = muted, size: Float = 10f) {
			paint.color = color; paint.style = Paint.Style.FILL; paint.textSize = size * unit
			canvas.drawText(text, x, baseline, paint)
		}
		if (!compact) {
			text("09:10", plot.left, height - 5f * unit)
			paint.textSize = 10f * unit
			text("次日 01:59", plot.right - paint.measureText("次日 01:59"), height - 5f * unit)
		}
		if (state.points.isEmpty()) {
			if (!compact) text("等待交易时段的报价…", plot.left + 50f * unit, plot.centerY(), size = 12f)
			return
		}
		val maximum: PricePoint = state.points.maxBy { it.price }
		val minimum: PricePoint = state.points.minBy { it.price }
		val padding: Double = max((maximum.price - minimum.price) * 0.2, 0.05)
		val low: Double = minimum.price - padding
		val high: Double = maximum.price + padding
		fun x(point: PricePoint): Float = plot.left + state.session.fraction(point.timestampMs) * plot.width()
		fun y(point: PricePoint): Float = plot.bottom - ((point.price - low) / (high - low)).toFloat() * plot.height()
		if (!compact) repeat(3) { index: Int ->
			val lineY: Float = plot.top + index * plot.height() / 2
			paint.color = Color.argb(130, 70, 80, 100); paint.style = Paint.Style.STROKE; paint.strokeWidth = unit
			paint.pathEffect = DashPathEffect(floatArrayOf(4f * unit, 4f * unit), 0f)
			canvas.drawLine(plot.left, lineY, plot.right, lineY, paint)
			paint.pathEffect = null
			text(priceText(high - index * (high - low) / 2), plot.right + 5f * unit, lineY + 3f * unit)
		}
		val path: Path = Path()
		val sampled: List<PricePoint> = sample(state.points, state.session, plot.width().toInt().coerceAtLeast(1))
		sampled.forEachIndexed { index: Int, point: PricePoint -> if (index == 0) path.moveTo(x(point), y(point)) else path.lineTo(x(point), y(point)) }
		if (sampled.size > 1) {
			val fill: Path = Path(path).apply { lineTo(x(sampled.last()), plot.bottom); lineTo(x(sampled.first()), plot.bottom); close() }
			paint.style = Paint.Style.FILL
			paint.shader = LinearGradient(0f, plot.top, 0f, plot.bottom, Color.argb(65, 255, 230, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
			canvas.drawPath(fill, paint)
			paint.shader = null; paint.color = gold; paint.style = Paint.Style.STROKE; paint.strokeWidth = max(unit * 1.7f, 1.5f)
			canvas.drawPath(path, paint)
		}
		paint.style = Paint.Style.FILL; paint.color = gold
		canvas.drawCircle(x(state.points.last()), y(state.points.last()), if (compact) 2f else 3f * unit, paint)
		if (compact) return
		fun marker(point: PricePoint, label: String, color: Int, above: Boolean) {
			paint.color = color; canvas.drawCircle(x(point), y(point), 3.5f * unit, paint)
			paint.textSize = 10f * unit
			val labelWidth: Float = paint.measureText(label) + 12f * unit
			val left: Float = (x(point) - labelWidth / 2).coerceIn(plot.left, max(plot.left, plot.right - labelWidth))
			val top: Float = (y(point) + (if (above) -25f else 7f) * unit).coerceIn(0f, max(0f, plot.bottom - 22f * unit))
			paint.color = Color.argb(220, 20, 26, 38)
			canvas.drawRoundRect(RectF(left, top, left + labelWidth, top + 22f * unit), 4f * unit, 4f * unit, paint)
			text(label, left + 6f * unit, top + 15f * unit, color)
		}
		if (maximum.price == minimum.price) marker(maximum, "最高/最低 ${priceText(maximum.price)}", gold, true) else {
			marker(maximum, "最高 ${priceText(maximum.price)}", Color.rgb(240, 113, 120), true)
			marker(minimum, "最低 ${priceText(minimum.price)}", Color.rgb(86, 201, 154), false)
		}
		val point: PricePoint = state.points.getOrNull(selected ?: -1) ?: return
		paint.color = muted; paint.strokeWidth = unit; paint.style = Paint.Style.STROKE
		canvas.drawLine(x(point), plot.top, x(point), plot.bottom, paint)
		canvas.drawLine(plot.left, y(point), plot.right, y(point), paint)
		paint.style = Paint.Style.FILL; paint.color = Color.WHITE
		canvas.drawCircle(x(point), y(point), 4f * unit, paint)
		val boxWidth: Float = 150f * unit
		val bx: Float = (if (x(point) + boxWidth + 12f * unit < plot.right) x(point) + 12f * unit else x(point) - boxWidth - 12f * unit).coerceAtLeast(plot.left)
		val by: Float = (y(point) - 54f * unit).coerceIn(plot.top, max(plot.top, plot.bottom - 46f * unit))
		paint.color = Color.argb(245, 20, 26, 38)
		canvas.drawRoundRect(RectF(bx, by, bx + boxWidth, by + 46f * unit), 5f * unit, 5f * unit, paint)
		text("价格：${priceText(point.price)}", bx + 8f * unit, by + 17f * unit, gold, 11f)
		text(timeText(point.timestampMs), bx + 8f * unit, by + 36f * unit, Color.WHITE)
	}

	// 每个横向像素保留首点、末点和高低点，避免全天逐秒点位拖慢绘制。
	private fun sample(points: List<PricePoint>, session: TradingSession, columns: Int): List<PricePoint> {
		if (points.size <= columns * 4) return points
		val output: MutableList<PricePoint> = ArrayList(columns * 4)
		var start: Int = 0
		while (start < points.size) {
			val column: Int = (session.fraction(points[start].timestampMs) * columns).toInt()
			var end: Int = start + 1
			var minIndex: Int = start
			var maxIndex: Int = start
			while (end < points.size && (session.fraction(points[end].timestampMs) * columns).toInt() == column) {
				if (points[end].price < points[minIndex].price) minIndex = end
				if (points[end].price > points[maxIndex].price) maxIndex = end
				end++
			}
			listOf(start, minIndex, maxIndex, end - 1).distinct().sorted().forEach { output.add(points[it]) }
			start = end
		}
		return output
	}
}

class GoldChartView(context: Context) : View(context) {
	var state: GoldState = GoldState()
		set(value) { field = value; invalidate() }
	private var pointer: Pair<Float, Float>? = null

	init { contentDescription = "黄金价格走势图，按住或拖动查看点位价格和时间"; isClickable = true }

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val index: Int? = pointer?.let { GoldChart.nearest(state, width.toFloat(), height.toFloat(), it.first, it.second) }
		GoldChart.draw(canvas, width.toFloat(), height.toFloat(), state, index)
	}

	override fun onTouchEvent(event: MotionEvent): Boolean {
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
				parent.requestDisallowInterceptTouchEvent(true)
				pointer = event.x to event.y
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				pointer = null; parent.requestDisallowInterceptTouchEvent(false)
				if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
			}
		}
		invalidate()
		return true
	}

	override fun performClick(): Boolean { super.performClick(); return true }
}
