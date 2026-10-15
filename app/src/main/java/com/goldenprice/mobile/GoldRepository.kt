package com.goldenprice.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.TreeMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

interface AlertSink {
	fun canNotify(): Boolean
	fun notify(hits: List<AlertTask>, quote: Quote): Boolean
}

class GoldRepository private constructor(context: Context) {
	private val database: GoldDatabase = GoldDatabase(context.applicationContext)
	private val api: GoldApi = GoldApi()
	private val worker = Executors.newSingleThreadScheduledExecutor()
	private val historyWorker = Executors.newSingleThreadExecutor()
	private val main: Handler = Handler(Looper.getMainLooper())
	private val listeners = CopyOnWriteArraySet<(GoldState) -> Unit>()
	private var polling: ScheduledFuture<*>? = null
	private var session: TradingSession = TradingSession.current()
	private val points: TreeMap<Long, PricePoint> = TreeMap()
	private var alerts: List<AlertTask> = emptyList()
	private var quote: Quote? = null
	private var monitoring: Boolean = false
	private var historyLoaded: Boolean = false
	private var historyBusy: Boolean = false
	private var lastHistory: Long = -30_000
	private var historyError: String? = null
	private var quoteError: String? = null
	@Volatile var alertSink: AlertSink? = null
	@Volatile var state: GoldState = GoldState()
		private set

	init {
		worker.execute {
			try {
				database.loadPoints(session).forEach { points[it.timestampMs] = it }
				alerts = database.loadAlerts()
				quote = database.loadQuote()?.takeIf { session.contains(it.point) }
				database.prune(session)
			} catch (error: Exception) { quoteError = "缓存读取失败：${error.message}"; Log.e("GoldRepository", "缓存读取失败", error) }
			publish()
		}
	}

	fun addListener(listener: (GoldState) -> Unit) {
		listeners.add(listener)
		main.post { if (listeners.contains(listener)) listener(state) }
	}

	fun removeListener(listener: (GoldState) -> Unit) { listeners.remove(listener) }

	fun start() {
		worker.execute {
			if (monitoring) return@execute
			monitoring = true
			polling = worker.scheduleWithFixedDelay({ poll() }, 0, 1, TimeUnit.SECONDS)
			publish()
		}
	}

	fun stop() {
		worker.execute {
			monitoring = false
			polling?.cancel(false)
			polling = null
			publish()
		}
	}

	private fun poll() {
		if (!monitoring) return
		try {
			val current: TradingSession = TradingSession.current()
			if (current != session) {
				session = current
				points.clear()
				quote = null
				historyLoaded = false
				historyError = null
				lastHistory = -30_000
				database.prune(session)
			}
			val fresh: Quote = api.latest()
			processAlerts(fresh)
			if (session.contains(fresh.point)) {
				database.savePoints(session, listOf(fresh.point))
				points[fresh.point.timestampMs] = fresh.point
			}
			database.saveQuote(fresh)
			quote = fresh
			quoteError = null
		} catch (error: Exception) {
			quoteError = "获取失败，将自动重试：${error.message}"
			Log.w("GoldRepository", "获取行情失败", error)
		}
		requestHistory()
		publish()
	}

	private fun requestHistory() {
		if (historyLoaded || historyBusy || SystemClock.elapsedRealtime() - lastHistory < 30_000) return
		historyBusy = true
		lastHistory = SystemClock.elapsedRealtime()
		val requestedSession: TradingSession = session
		historyWorker.execute {
			val result: Result<List<PricePoint>> = runCatching { api.history(requestedSession) }
			worker.execute {
				historyBusy = false
				if (requestedSession != session) return@execute
				try {
					val history: List<PricePoint> = result.getOrThrow()
					database.savePoints(session, history, history = true)
					history.forEach { points.putIfAbsent(it.timestampMs, it) }
					historyLoaded = history.isNotEmpty()
					historyError = null
				} catch (error: Exception) {
					historyError = error.message ?: "未知错误"
					Log.w("GoldRepository", "历史走势获取失败", error)
				}
				publish()
			}
		}
	}

	private fun processAlerts(fresh: Quote) {
		val sink: AlertSink? = alertSink
		val canNotify: Boolean = sink?.canNotify() == true
		val hits: MutableList<AlertTask> = mutableListOf()
		val updated: List<AlertTask> = alerts.map { task: AlertTask ->
			val (observed, hit) = task.observe(fresh.point)
			if (hit && canNotify) observed.copy(remaining = observed.remaining - 1).also { hits.add(it) } else observed
		}
		if (updated == alerts) return
		database.saveAlerts(updated)
		if (hits.isNotEmpty() && sink?.notify(hits, fresh) != true) {
			// 通知被拒绝时恢复额度，下一笔报价可重试。
			database.saveAlerts(alerts)
		} else alerts = updated
	}

	fun saveTask(task: AlertTask, complete: (Result<Unit>) -> Unit) = change(complete) {
		val saved: AlertTask = database.upsertAlert(task.seeded(quote?.point.takeIf { monitoring }))
		alerts = (alerts.filterNot { it.id == saved.id } + saved).sortedBy { it.id }
	}

	fun toggleTask(id: Long, complete: (Result<Unit>) -> Unit) = change(complete) {
		val task: AlertTask = alerts.first { it.id == id }
		val updated: AlertTask = database.upsertAlert(task.copy(enabled = !task.enabled).seeded(quote?.point.takeIf { monitoring }))
		alerts = alerts.map { if (it.id == id) updated else it }
	}

	fun deleteTask(id: Long, complete: (Result<Unit>) -> Unit) = change(complete) {
		database.deleteAlert(id)
		alerts = alerts.filterNot { it.id == id }
	}

	private fun change(complete: (Result<Unit>) -> Unit, block: () -> Unit) {
		worker.execute {
			val result: Result<Unit> = runCatching(block)
			publish()
			main.post { complete(result) }
		}
	}

	private fun publish() {
		val status: String = when {
			!monitoring -> "监测已停止 · 已加载本地缓存"
			quoteError != null -> quoteError!!
			else -> (quote?.let { "报价时间 ${timeText(it.point.timestampMs)}" } ?: "正在获取报价…") + when {
				historyError != null -> " · 历史加载失败，30 秒后重试"
				!historyLoaded -> " · 补充历史中"
				else -> ""
			}
		}
		state = GoldState(session, points.values.toList(), quote, alerts.toList(), status, monitoring, quoteError ?: historyError)
		val snapshot: GoldState = state
		main.post { listeners.forEach { listener -> listener(snapshot) } }
	}

	companion object {
		@Volatile private var instance: GoldRepository? = null
		fun get(context: Context): GoldRepository = instance ?: synchronized(this) {
			instance ?: GoldRepository(context.applicationContext).also { instance = it }
		}
	}
}
