package com.goldenprice.mobile

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import kotlin.math.roundToLong

class GoldDatabase(context: Context) : SQLiteOpenHelper(context, "gold-history.sqlite3", null, 1) {
	init { setWriteAheadLoggingEnabled(true) }

	override fun onCreate(db: SQLiteDatabase) {
		db.execSQL("CREATE TABLE price_points (trading_day TEXT NOT NULL, timestamp REAL NOT NULL, price REAL NOT NULL CHECK(price > 0), PRIMARY KEY(trading_day, timestamp))")
		db.execSQL("CREATE TABLE price_alert_tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, target_cents INTEGER NOT NULL, notification_limit INTEGER NOT NULL, remaining INTEGER NOT NULL, last_price_cents INTEGER, last_timestamp REAL, direction TEXT NOT NULL, enabled INTEGER NOT NULL)")
		db.execSQL("CREATE TABLE app_state (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
	}

	override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
		error("不支持的数据库版本：$oldVersion → $newVersion")
	}

	fun loadPoints(session: TradingSession): List<PricePoint> = readableDatabase.rawQuery(
		"SELECT timestamp, price FROM price_points WHERE trading_day=? AND timestamp>=? AND timestamp<? ORDER BY timestamp",
		arrayOf(session.day, (session.startMs / 1000.0).toString(), (session.endMs / 1000.0).toString())
	).use { cursor: Cursor -> buildList {
		while (cursor.moveToNext()) {
			val point: PricePoint = PricePoint((cursor.getDouble(0) * 1000).roundToLong(), cursor.getDouble(1))
			if (session.contains(point)) add(point)
		}
	} }

	fun loadAlerts(): List<AlertTask> = readableDatabase.rawQuery(
		"SELECT id, target_cents, notification_limit, remaining, direction, enabled, last_price_cents, last_timestamp FROM price_alert_tasks ORDER BY id", null
	).use { cursor: Cursor -> buildList {
		while (cursor.moveToNext()) add(AlertTask(cursor.getLong(0), cursor.getLong(1), cursor.getInt(2), cursor.getInt(3),
			AlertDirection.fromKey(cursor.getString(4)), cursor.getInt(5) != 0,
			if (cursor.isNull(6)) null else cursor.getLong(6), if (cursor.isNull(7)) null else (cursor.getDouble(7) * 1000).roundToLong()))
	} }

	private fun alertValues(alert: AlertTask): ContentValues = ContentValues().apply {
		put("target_cents", alert.targetCents); put("notification_limit", alert.limit); put("remaining", alert.remaining)
		put("direction", alert.direction.key); put("enabled", if (alert.enabled) 1 else 0)
		if (alert.lastCents == null) putNull("last_price_cents") else put("last_price_cents", alert.lastCents)
		if (alert.lastTimestampMs == null) putNull("last_timestamp") else put("last_timestamp", alert.lastTimestampMs / 1000.0)
	}

	fun upsertAlert(alert: AlertTask): AlertTask {
		val db: SQLiteDatabase = writableDatabase
		return if (alert.id == 0L) alert.copy(id = db.insertOrThrow("price_alert_tasks", null, alertValues(alert))) else {
			check(db.update("price_alert_tasks", alertValues(alert), "id=?", arrayOf(alert.id.toString())) == 1) { "该提醒任务已删除" }
			alert
		}
	}

	fun deleteAlert(id: Long) { writableDatabase.delete("price_alert_tasks", "id=?", arrayOf(id.toString())) }

	fun saveAlerts(alerts: List<AlertTask>) = transaction { db: SQLiteDatabase ->
		alerts.forEach { alert: AlertTask -> db.update("price_alert_tasks", alertValues(alert), "id=?", arrayOf(alert.id.toString())) }
	}

	fun prune(session: TradingSession) {
		writableDatabase.delete("price_points", "trading_day<>?", arrayOf(session.day))
	}

	fun savePoints(session: TradingSession, points: List<PricePoint>, history: Boolean = false) = transaction { db: SQLiteDatabase ->
		points.filter(session::contains).forEach { point: PricePoint ->
			val values: ContentValues = ContentValues().apply {
				put("trading_day", session.day); put("timestamp", point.timestampMs / 1000.0); put("price", point.price)
			}
			db.insertWithOnConflict("price_points", null, values, if (history) SQLiteDatabase.CONFLICT_IGNORE else SQLiteDatabase.CONFLICT_REPLACE)
		}
	}

	fun saveQuote(quote: Quote) {
		val json: JSONObject = JSONObject().put("time", quote.point.timestampMs).put("price", quote.point.price).put("change", quote.change).put("rate", quote.rate)
		val values: ContentValues = ContentValues().apply { put("key", "latest_quote"); put("value", json.toString()) }
		writableDatabase.insertWithOnConflict("app_state", null, values, SQLiteDatabase.CONFLICT_REPLACE)
	}

	fun loadQuote(): Quote? = readableDatabase.rawQuery("SELECT value FROM app_state WHERE key='latest_quote'", null).use { cursor: Cursor ->
		if (!cursor.moveToFirst()) null else {
			val json: JSONObject = JSONObject(cursor.getString(0))
			Quote(PricePoint(json.getLong("time"), json.getDouble("price")), json.getString("change"), json.getString("rate"))
		}
	}

	private inline fun transaction(block: (SQLiteDatabase) -> Unit) {
		val db: SQLiteDatabase = writableDatabase
		db.beginTransaction()
		try { block(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
	}
}
