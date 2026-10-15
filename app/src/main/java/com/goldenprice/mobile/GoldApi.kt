package com.goldenprice.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class GoldApi {
	private fun fetch(endpoint: String): Any {
		val connection: HttpURLConnection = URI("https://ms.jr.jd.com/gw/generic/hj/h5/m/$endpoint?reqData=%7B%7D").toURL().openConnection() as HttpURLConnection
		try {
			connection.requestMethod = "POST"
			connection.connectTimeout = 10_000
			connection.readTimeout = 10_000
			connection.setFixedLengthStreamingMode(0)
			connection.doOutput = true
			connection.outputStream.use { it.flush() }
			check(connection.responseCode in 200..299) { "行情接口 HTTP ${connection.responseCode}" }
			val root: JSONObject = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
			val result: JSONObject = root.optJSONObject("resultData") ?: error("响应缺少 resultData")
			check(root.optBoolean("success") && result.optString("status", "SUCCESS") == "SUCCESS") { root.optString("resultMsg", "行情接口返回失败") }
			return result.get("datas")
		} finally { connection.disconnect() }
	}

	fun latest(): Quote {
		val data: JSONObject = fetch("latestPrice") as JSONObject
		val point: PricePoint = PricePoint(data.getString("time").toLong(), data.getString("price").toDouble())
		check(point.price.isFinite() && point.price > 0) { "接口返回无效价格" }
		return Quote(point, data.optString("upAndDownAmt", "--"), data.optString("upAndDownRate", "--"))
	}

	fun history(session: TradingSession): List<PricePoint> {
		val rows: JSONArray = fetch("todayPrices") as JSONArray
		return (0 until rows.length()).map { index: Int ->
			val values: JSONArray = rows.getJSONObject(index).getJSONArray("value")
			// 高低点可能含第三项 lowKey/highKey，只读取时间和价格。
			val date: LocalDateTime = LocalDateTime.parse(values.getString(0), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
			PricePoint(date.atZone(TradingSession.ZONE).toInstant().toEpochMilli(), values.getString(1).toDouble())
		}.filter(session::contains).sortedBy { it.timestampMs }
	}
}
