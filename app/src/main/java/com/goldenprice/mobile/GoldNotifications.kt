package com.goldenprice.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.provider.Settings
import android.os.Build
import android.util.Log
import android.widget.RemoteViews

class GoldNotifications(private val context: Context) : AlertSink {
	private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

	init {
		manager.createNotificationChannel(NotificationChannel(LIVE_CHANNEL, "实时价格与走势", NotificationManager.IMPORTANCE_LOW).apply {
			description = "持续显示黄金报价及当日走势，静默刷新"
			setSound(null, null)
		})
		manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "黄金价位提醒", NotificationManager.IMPORTANCE_HIGH).apply {
			description = "达到预设价位时提醒，通知次数由任务设置决定"
			setSound(Settings.System.DEFAULT_NOTIFICATION_URI, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
			enableVibration(true)
		})
	}

	private fun openApp(): PendingIntent = PendingIntent.getActivity(context, 0,
		Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

	fun live(state: GoldState): Notification {
		val title: String = state.quote?.let { "黄金 ${priceText(it.point.price)}" } ?: "黄金行情 · 正在加载"
		val compact: RemoteViews = RemoteViews(context.packageName, R.layout.notification_compact).apply {
			setTextViewText(R.id.notification_price, title)
			setImageViewBitmap(R.id.notification_chart, GoldChart.bitmap(state, compact = true))
		}
		val expanded: RemoteViews = RemoteViews(context.packageName, R.layout.notification_chart).apply {
			setTextViewText(R.id.notification_price, title + (state.quote?.let { "  ${it.rate}" } ?: ""))
			setTextViewText(R.id.notification_status, state.status)
			setImageViewBitmap(R.id.notification_chart, GoldChart.bitmap(state))
		}
		val stop: PendingIntent = PendingIntent.getService(context, 1, Intent(context, MonitorService::class.java).setAction(MonitorService.STOP),
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
		val builder: Notification.Builder = Notification.Builder(context, LIVE_CHANNEL)
			.setSmallIcon(R.drawable.ic_gold).setContentTitle(title).setContentText(state.status)
			.setContentIntent(openApp()).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
			.setVisibility(Notification.VISIBILITY_PUBLIC).setStyle(Notification.DecoratedCustomViewStyle())
			.setCustomContentView(compact).setCustomBigContentView(expanded)
			.addAction(Notification.Action.Builder(null, "打开应用", openApp()).build())
			.addAction(Notification.Action.Builder(null, "停止监测", stop).build())
		if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
		return builder.build()
	}

	fun update(state: GoldState) {
		try { manager.notify(LIVE_ID, live(state)) }
		catch (error: RuntimeException) { Log.w("GoldNotifications", "更新行情通知失败", error) }
	}

	override fun canNotify(): Boolean = notificationsAllowed(context) &&
		(manager.getNotificationChannel(ALERT_CHANNEL)?.importance ?: NotificationManager.IMPORTANCE_NONE) > NotificationManager.IMPORTANCE_NONE

	fun alertStatus(): String = when {
		!notificationsAllowed(context) -> "未允许通知"
		!canNotify() -> "价位提醒频道已关闭"
		(manager.getNotificationChannel(ALERT_CHANNEL)?.importance ?: 0) < NotificationManager.IMPORTANCE_HIGH -> "价位提醒未开启高重要性，可能不弹横幅"
		else -> "价位提醒已开启；横幅、声音仍由系统设置控制"
	}

	fun testAlert(): Boolean = postAlert(TEST_ID, "黄金价位提醒 · 测试", "这是一条测试提醒，不消耗任务次数。若通知栏有消息但没有横幅，请进入提醒设置开启横幅，并检查勿扰模式。")

	private fun postAlert(id: Int, title: String, message: String): Boolean {
		if (!canNotify()) return false
		return try {
			manager.notify(id, Notification.Builder(context, ALERT_CHANNEL)
				.setSmallIcon(R.drawable.ic_gold).setContentTitle(title)
				.setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
				.setCategory(Notification.CATEGORY_EVENT)
				.setContentIntent(openApp()).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build())
			true
		} catch (error: RuntimeException) { Log.e("GoldNotifications", "发送提醒失败", error); false }
	}

	override fun notify(hits: List<AlertTask>, quote: Quote): Boolean {
		if (!canNotify()) return false
		val message: String = "最新价 ${priceText(quote.point.price)}\n" + hits.joinToString("\n") {
				"#${it.id} ${it.direction.label} ${it.targetText}，剩余 ${it.remaining} 次"
			} + "\n报价时间 ${timeText(quote.point.timestampMs)}"
		return postAlert(ALERT_ID, "黄金价位提醒 · ${hits.size} 个任务", message)
	}

	companion object {
		const val LIVE_CHANNEL: String = "gold_live"
		const val ALERT_CHANNEL: String = "gold_alerts"
		const val LIVE_ID: Int = 1001
		const val ALERT_ID: Int = 2001
		private const val TEST_ID: Int = 2002
		fun notificationsAllowed(context: Context): Boolean =
			(Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
				context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
	}
}
