package com.goldenprice.mobile

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log

class MonitorService : Service() {
	private lateinit var repository: GoldRepository
	private lateinit var notifications: GoldNotifications
	private lateinit var overlay: PriceOverlay
	private var listening: Boolean = false
	private val listener: (GoldState) -> Unit = { state: GoldState ->
		if (listening) { notifications.update(state); overlay.update(state) }
	}

	override fun onCreate() {
		super.onCreate()
		repository = GoldRepository.get(this)
		notifications = GoldNotifications(this)
		overlay = PriceOverlay(this)
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
		try {
			val notification = notifications.live(repository.state)
			if (Build.VERSION.SDK_INT >= 34) startForeground(GoldNotifications.LIVE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
			else startForeground(GoldNotifications.LIVE_ID, notification)
		} catch (error: RuntimeException) {
			Log.e("MonitorService", "前台服务启动失败", error)
			stopSelf()
			return START_NOT_STICKY
		}
		val preferences = getSharedPreferences("overlay", MODE_PRIVATE)
		when (intent?.action) {
			SHOW_OVERLAY -> preferences.edit().putBoolean("enabled", true).apply()
			HIDE_OVERLAY -> preferences.edit().putBoolean("enabled", false).apply()
		}
		if (preferences.getBoolean("enabled", false) && Settings.canDrawOverlays(this)) overlay.show() else overlay.hide()
		overlay.update(repository.state)
		if (!listening) {
			listening = true
			repository.alertSink = notifications
			repository.addListener(listener)
			repository.start()
		}
		return START_STICKY
	}

	override fun onDestroy() {
		listening = false
		repository.removeListener(listener)
		repository.alertSink = null
		repository.stop()
		overlay.hide()
		stopForeground(STOP_FOREGROUND_REMOVE)
		super.onDestroy()
	}

	override fun onBind(intent: Intent?): IBinder? = null

	companion object {
		const val START: String = "com.goldenprice.mobile.START"
		const val STOP: String = "com.goldenprice.mobile.STOP"
		const val SHOW_OVERLAY: String = "com.goldenprice.mobile.SHOW_OVERLAY"
		const val HIDE_OVERLAY: String = "com.goldenprice.mobile.HIDE_OVERLAY"
	}
}
