package com.goldenprice.mobile

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

data class PricePoint(val timestampMs: Long, val price: Double) {
	val cents: Long get() = (price * 100.0).roundToLong()
}

data class Quote(val point: PricePoint, val change: String, val rate: String)

data class TradingSession(val day: String, val startMs: Long, val endMs: Long) {
	fun contains(point: PricePoint): Boolean = point.price.isFinite() && point.price > 0 && point.timestampMs in startMs until endMs
	fun fraction(timestampMs: Long): Float = ((timestampMs - startMs).toDouble() / (endMs - startMs - 1)).toFloat()

	companion object {
		val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
		fun current(now: ZonedDateTime = ZonedDateTime.now(ZONE)): TradingSession {
			val day: LocalDate = now.withZoneSameInstant(ZONE).minusHours(9).minusMinutes(10).toLocalDate()
			return TradingSession(day.toString(), day.atTime(9, 10).atZone(ZONE).toInstant().toEpochMilli(),
				day.plusDays(1).atTime(2, 0).atZone(ZONE).toInstant().toEpochMilli())
		}
	}
}

enum class AlertDirection(val key: String, val label: String) {
	BOTH("both", "双向提醒"), UP("up", "涨到提醒"), DOWN("down", "跌到提醒");
	companion object {
		fun fromKey(key: String): AlertDirection = entries.firstOrNull { it.key == key } ?: BOTH
	}
}

data class AlertTask(
	val id: Long = 0,
	val targetCents: Long,
	val limit: Int,
	val remaining: Int = limit,
	val direction: AlertDirection = AlertDirection.BOTH,
	val enabled: Boolean = true,
	val lastCents: Long? = null,
	val lastTimestampMs: Long? = null
) {
	val targetText: String get() = BigDecimal.valueOf(targetCents, 2).toPlainString()
	val status: String get() = when { !enabled -> "已暂停"; remaining == 0 -> "已完成"; else -> "监测中" }

	fun seeded(point: PricePoint?): AlertTask = copy(lastCents = point?.cents, lastTimestampMs = point?.timestampMs)

	fun observe(point: PricePoint): Pair<AlertTask, Boolean> {
		if (!enabled || remaining == 0 || !point.price.isFinite() || point.price <= 0 ||
			(lastTimestampMs != null && point.timestampMs <= lastTimestampMs)) return this to false
		val up: Boolean = lastCents != null && lastCents < targetCents && point.cents >= targetCents
		val down: Boolean = lastCents != null && lastCents > targetCents && point.cents <= targetCents
		val hit: Boolean = when (direction) { AlertDirection.BOTH -> up || down; AlertDirection.UP -> up; AlertDirection.DOWN -> down }
		return copy(lastCents = point.cents, lastTimestampMs = point.timestampMs) to hit
	}

	companion object {
		fun create(price: String, count: String, direction: AlertDirection, enabled: Boolean, id: Long = 0): AlertTask {
			require(price.trim().matches(Regex("[0-9]+(\\.[0-9]{1,2})?"))) { "请输入正数价格，最多两位小数" }
			val cents: Long = try { BigDecimal(price.trim()).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact() }
			catch (_: ArithmeticException) { throw IllegalArgumentException("价格超出有效范围") }
			val limit: Int = count.trim().toIntOrNull() ?: throw IllegalArgumentException("通知次数必须是正整数")
			require(cents > 0 && limit > 0) { "价格和通知次数必须大于零" }
			return AlertTask(id = id, targetCents = cents, limit = limit, direction = direction, enabled = enabled)
		}
	}
}

data class GoldState(
	val session: TradingSession = TradingSession.current(),
	val points: List<PricePoint> = emptyList(),
	val quote: Quote? = null,
	val alerts: List<AlertTask> = emptyList(),
	val status: String = "正在读取本地缓存…",
	val monitoring: Boolean = false,
	val error: String? = null
)

fun priceText(price: Double): String = String.format(Locale.CHINA, "%.2f", price)
fun timeText(timestampMs: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.CHINA)
	.format(Instant.ofEpochMilli(timestampMs).atZone(TradingSession.ZONE))
