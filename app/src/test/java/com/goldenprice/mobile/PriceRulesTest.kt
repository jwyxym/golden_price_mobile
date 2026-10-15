package com.goldenprice.mobile

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class PriceRulesTest {
	private fun session(time: String): TradingSession = TradingSession.current(ZonedDateTime.parse(time + "+08:00[Asia/Shanghai]"))
	private fun task(direction: AlertDirection = AlertDirection.BOTH): AlertTask =
		AlertTask.create("900.00", "2", direction, true).seeded(PricePoint(1000, 899.0))

	@Test fun tradingDayChangesAtNineTenAndSurvivesMidnight() {
		assertEquals("2026-10-14", session("2026-10-15T09:09:59").day)
		assertEquals("2026-10-15", session("2026-10-15T09:10:00").day)
		assertEquals("2026-10-15", session("2026-10-16T00:00:00").day)
		assertEquals("2026-10-15", session("2026-10-16T09:09:59").day)
		assertEquals("2026-10-16", session("2026-10-16T09:10:00").day)
	}

	@Test fun sessionIncludesLastMinuteButExcludesTwoAm() {
		val day: TradingSession = session("2026-10-15T12:00:00")
		assertFalse(day.contains(PricePoint(day.startMs - 1, 900.0)))
		assertTrue(day.contains(PricePoint(day.startMs, 900.0)))
		assertTrue(day.contains(PricePoint(day.endMs - 1, 900.0)))
		assertFalse(day.contains(PricePoint(day.endMs, 900.0)))
	}

	@Test fun riseIncludesJumpingAcrossTarget() {
		assertTrue(task(AlertDirection.UP).observe(PricePoint(2000, 900.0)).second)
		assertTrue(task(AlertDirection.UP).observe(PricePoint(2000, 901.0)).second)
		assertFalse(task(AlertDirection.DOWN).observe(PricePoint(2000, 901.0)).second)
	}

	@Test fun fallOnlyTriggersDownOrBoth() {
		val baseline: PricePoint = PricePoint(1000, 901.0)
		assertTrue(task(AlertDirection.DOWN).seeded(baseline).observe(PricePoint(2000, 899.0)).second)
		assertTrue(task().seeded(baseline).observe(PricePoint(2000, 900.0)).second)
		assertFalse(task(AlertDirection.UP).seeded(baseline).observe(PricePoint(2000, 899.0)).second)
	}

	@Test fun stayingAtTargetOrLeavingItDoesNotRepeat() {
		val touched: AlertTask = task().observe(PricePoint(2000, 900.0)).first
		assertFalse(touched.observe(PricePoint(3000, 900.0)).second)
		assertFalse(touched.observe(PricePoint(3000, 901.0)).second)
		val left: AlertTask = touched.observe(PricePoint(3000, 901.0)).first
		assertTrue(left.observe(PricePoint(4000, 900.0)).second)
	}

	@Test fun firstQuoteOnlySeedsDirection() {
		val unseeded: AlertTask = AlertTask.create("900", "1", AlertDirection.BOTH, true)
		assertFalse(unseeded.observe(PricePoint(2000, 900.0)).second)
		assertEquals(90000L, unseeded.observe(PricePoint(2000, 900.0)).first.lastCents)
	}

	@Test fun duplicateStalePausedAndExhaustedTasksDoNotTrigger() {
		assertFalse(task().observe(PricePoint(1000, 901.0)).second)
		assertFalse(task().observe(PricePoint(500, 901.0)).second)
		assertFalse(task().copy(enabled = false).observe(PricePoint(2000, 901.0)).second)
		assertFalse(task().copy(remaining = 0).observe(PricePoint(2000, 901.0)).second)
		assertEquals(2, task().copy(enabled = false).remaining)
	}

	@Test fun pricesUseExactCents() {
		assertEquals(90010L, AlertTask.create("900.10", "1", AlertDirection.BOTH, true).targetCents)
		assertEquals("900.10", AlertTask.create("900.1", "1", AlertDirection.BOTH, true).targetText)
	}

	@Test(expected = IllegalArgumentException::class)
	fun rejectExtraDecimalPlaces() { AlertTask.create("900.001", "1", AlertDirection.BOTH, true) }

	@Test(expected = IllegalArgumentException::class)
	fun rejectZeroCount() { AlertTask.create("900", "0", AlertDirection.BOTH, true) }
}
