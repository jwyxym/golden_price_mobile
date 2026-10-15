package com.goldenprice.mobile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
	private lateinit var repository: GoldRepository
	private lateinit var notifications: GoldNotifications
	private lateinit var price: TextView
	private lateinit var change: TextView
	private lateinit var status: TextView
	private lateinit var permissions: TextView
	private lateinit var chart: GoldChartView
	private lateinit var tasks: LinearLayout
	private lateinit var startButton: Button
	private lateinit var stopButton: Button
	private var taskKey: String = ""
	private var pendingAction: String = MonitorService.START
	private var editor: AlertDialog? = null
	private val listener: (GoldState) -> Unit = { render(it) }
	private val gold: Int = Color.rgb(255, 230, 0)
	private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		pendingAction = savedInstanceState?.getString("pendingAction") ?: MonitorService.START
		repository = GoldRepository.get(this)
		notifications = GoldNotifications(this)
		val scroll: ScrollView = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Color.rgb(16, 20, 28)) }
		val body: LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(28)) }
		scroll.addView(body)
		scroll.setOnApplyWindowInsetsListener { view: View, insets: WindowInsets ->
			if (Build.VERSION.SDK_INT >= 30) {
				val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
				view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
			} else {
				@Suppress("DEPRECATION")
				view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
			}
			insets
		}
		body.addView(label("黄金行情", 24f, Color.WHITE))
		body.addView(label("京东金融报价 · 北京时间", 12f, Color.LTGRAY))
		price = label("--", 42f, gold).also { body.addView(it) }
		change = label("等待获取最新报价", 14f, Color.LTGRAY).also { body.addView(it) }
		status = label("正在读取本地缓存…", 11f, Color.LTGRAY).also { body.addView(it) }
		chart = GoldChartView(this).also { body.addView(it, LinearLayout.LayoutParams(-1, dp(250)).apply { topMargin = dp(12) }) }
		body.addView(label("09:10 至次日 01:59，之后保留到下一轮 09:10。\n按住图表查看点位价格和时间。", 11f, Color.LTGRAY))
		val monitorRow: LinearLayout = row()
		startButton = button("开始监测") { startMonitoring() }
		startButton.backgroundTintList = ColorStateList.valueOf(gold); startButton.setTextColor(Color.BLACK)
		stopButton = button("停止监测") { stopService(Intent(this, MonitorService::class.java)) }
		addButton(monitorRow, startButton); addButton(monitorRow, stopButton); body.addView(monitorRow)
		val permissionRow: LinearLayout = row()
		addButton(permissionRow, button("通知授权") { requestNotifications() })
		addButton(permissionRow, button("悬浮价格") { requestOverlay() })
		addButton(permissionRow, button("隐藏悬浮窗") {
			getSharedPreferences("overlay", MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
			if (repository.state.monitoring) startService(Intent(this, MonitorService::class.java).setAction(MonitorService.HIDE_OVERLAY))
		})
		body.addView(permissionRow)
		val alertRow: LinearLayout = row()
		addButton(alertRow, button("测试提醒") { testNotification() })
		addButton(alertRow, button("提醒设置") { openAlertSettings() })
		body.addView(alertRow)
		permissions = label("", 11f, Color.LTGRAY).also { body.addView(it) }
		val taskHeader: LinearLayout = row().apply { gravity = Gravity.CENTER_VERTICAL }
		taskHeader.addView(label("价位提醒任务", 20f, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
		taskHeader.addView(button("新建任务") { editTask(null) })
		body.addView(taskHeader)
		body.addView(label("涨到、跌到或双向触发；持续停在目标价不重复。\n后台提醒需要保持监测开启。", 11f, Color.LTGRAY))
		tasks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
		body.addView(tasks)
		setContentView(scroll)
		scroll.requestApplyInsets()
	}

	private fun label(value: String, size: Float, color: Int): TextView = TextView(this).apply {
		text = value; textSize = size; setTextColor(color); setPadding(0, dp(3), 0, dp(3))
	}

	private fun row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
	private fun button(title: String, action: () -> Unit): Button = Button(this).apply {
		text = title; textSize = 12f; isAllCaps = false
		backgroundTintList = ColorStateList.valueOf(Color.rgb(39, 50, 70)); setTextColor(Color.WHITE)
		setOnClickListener { action() }
	}
	private fun addButton(row: LinearLayout, button: Button) { row.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) }) }
	private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

	override fun onStart() { super.onStart(); repository.addListener(listener) }
	override fun onStop() { repository.removeListener(listener); super.onStop() }
	override fun onResume() { super.onResume(); updatePermissionStatus() }
	override fun onDestroy() { editor?.dismiss(); super.onDestroy() }
	override fun onSaveInstanceState(outState: Bundle) { outState.putString("pendingAction", pendingAction); super.onSaveInstanceState(outState) }

	private fun render(state: GoldState) {
		price.text = state.quote?.let { priceText(it.point.price) } ?: "--"
		change.text = state.quote?.let { "较昨收 ${it.change} (${it.rate})" } ?: "等待获取最新报价"
		status.text = state.status
		chart.state = state
		startButton.isEnabled = !state.monitoring
		stopButton.isEnabled = state.monitoring
		updatePermissionStatus()
		val key: String = state.alerts.joinToString(";") { "${it.id},${it.targetCents},${it.direction},${it.enabled},${it.remaining},${it.limit}" }
		if (key == taskKey && tasks.childCount > 0) return
		taskKey = key
		tasks.removeAllViews()
		if (state.alerts.isEmpty()) { tasks.addView(label("暂无任务，点击“新建任务”添加提醒。", 14f, Color.LTGRAY)); return }
		state.alerts.forEach { task: AlertTask ->
			val card: LinearLayout = LinearLayout(this).apply {
				orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(8))
				background = GradientDrawable().apply { setColor(Color.rgb(28, 36, 50)); cornerRadius = dp(12).toFloat() }
			}
			card.addView(label("#${task.id}  目标 ${task.targetText}", 20f, gold))
			card.addView(label("${task.direction.label} · ${task.status} · 剩余 ${task.remaining} / ${task.limit} 次", 12f, Color.LTGRAY))
			val actions: LinearLayout = row()
			addButton(actions, button("编辑") { editTask(task) })
			addButton(actions, button(if (task.enabled) "暂停" else "继续") { repository.toggleTask(task.id, ::showResult) })
			addButton(actions, button("删除") {
				AlertDialog.Builder(this).setTitle("删除任务 #${task.id}？").setMessage("目标价 ${task.targetText}，${task.direction.label}")
					.setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> repository.deleteTask(task.id, ::showResult) }.show()
			})
			card.addView(actions)
			tasks.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
		}
	}

	private fun showResult(result: Result<Unit>) {
		if (isFinishing || isDestroyed) return
		result.exceptionOrNull()?.let { toast(it.message ?: "操作失败") }
	}

	private fun editTask(initial: AlertTask?) {
		val form: LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
		form.addView(label("提醒价格", 13f, Color.LTGRAY))
		val target: EditText = EditText(this).apply {
			inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
			filters = arrayOf(InputFilter.LengthFilter(20)); setTextColor(Color.WHITE)
			setText(initial?.targetText ?: repository.state.quote?.let { priceText(it.point.price) }.orEmpty())
		}
		form.addView(target)
		form.addView(label("触发方向", 13f, Color.LTGRAY))
		val direction: Spinner = Spinner(this).apply {
			adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, AlertDirection.entries.map { it.label }).apply {
				setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
			}
			setSelection((initial?.direction ?: AlertDirection.BOTH).ordinal)
		}
		form.addView(direction)
		form.addView(label("通知次数", 13f, Color.LTGRAY))
		val count: EditText = EditText(this).apply {
			inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(InputFilter.LengthFilter(10))
			setTextColor(Color.WHITE); setText((initial?.limit ?: 1).toString())
		}
		form.addView(count)
		val enabled: Switch = Switch(this).apply { text = "启用任务"; isChecked = initial?.enabled ?: true; setTextColor(Color.WHITE) }
		form.addView(enabled)
		form.addView(label("保存后重新计数。暂停 / 继续保留剩余次数。\n首笔报价仅建立基准，之后触价才提醒。", 11f, Color.LTGRAY))
		val dialog: AlertDialog = AlertDialog.Builder(this).setTitle(if (initial == null) "新建提醒任务" else "编辑任务 #${initial.id}")
			.setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
		editor?.dismiss(); editor = dialog
		dialog.show()
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
			try {
				val task: AlertTask = AlertTask.create(target.text.toString(), count.text.toString(), AlertDirection.entries[direction.selectedItemPosition], enabled.isChecked, initial?.id ?: 0)
				dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
				repository.saveTask(task) { result: Result<Unit> ->
					if (!dialog.isShowing || isDestroyed) return@saveTask
					dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
					if (result.isSuccess) dialog.dismiss() else toast(result.exceptionOrNull()?.message ?: "保存失败")
				}
			} catch (error: IllegalArgumentException) { target.error = error.message }
		}
	}

	private fun updatePermissionStatus() {
		if (!::permissions.isInitialized) return
		permissions.text = "通知：${if (GoldNotifications.notificationsAllowed(this)) "已允许" else "未允许"}  ·  悬浮窗：${if (Settings.canDrawOverlays(this)) "已允许" else "未允许"}"
		permissions.append("\n${notifications.alertStatus()}")
	}

	private fun testNotification() {
		if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
			askNotificationPermission(NOTIFICATIONS_TEST)
			return
		}
		if (!notifications.canNotify()) {
			toast("${notifications.alertStatus()}，请先开启")
			openAlertSettings()
			return
		}
		if (notifications.testAlert()) toast("测试提醒已提交；若没有横幅，请在提醒设置中开启横幅并检查勿扰模式")
		else toast("测试提醒发送失败，请检查系统通知设置")
	}

	private fun openAlertSettings() {
		try {
			startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
				.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
				.putExtra(Settings.EXTRA_CHANNEL_ID, GoldNotifications.ALERT_CHANNEL))
		} catch (_: android.content.ActivityNotFoundException) { openNotificationSettings() }
	}

	private fun startMonitoring(action: String = MonitorService.START) {
		pendingAction = action
		if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
			askNotificationPermission(NOTIFICATIONS_START)
			return
		}
		if (!GoldNotifications.notificationsAllowed(this)) {
			toast("请先在系统设置中允许通知，以显示走势和价位提醒")
			openNotificationSettings()
			return
		}
		try { startForegroundService(Intent(this, MonitorService::class.java).setAction(action)) }
		catch (error: RuntimeException) { toast("无法开启监测：${error.message}") }
	}

	private fun requestNotifications() {
		if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
			askNotificationPermission(NOTIFICATIONS_ONLY)
		} else openNotificationSettings()
	}

	private fun askNotificationPermission(requestCode: Int) {
		if (Build.VERSION.SDK_INT < 33) { openNotificationSettings(); return }
		val preferences = getSharedPreferences("permissions", MODE_PRIVATE)
		if (preferences.getBoolean("notifications_requested", false) && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
			toast("请在系统设置中启用通知")
			openNotificationSettings()
		} else {
			preferences.edit().putBoolean("notifications_requested", true).apply()
			requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestCode)
		}
	}

	private fun openNotificationSettings() {
		startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
	}

	private fun requestOverlay() {
		if (Settings.canDrawOverlays(this)) { startMonitoring(MonitorService.SHOW_OVERLAY); return }
		AlertDialog.Builder(this).setTitle("显示悬浮价格")
			.setMessage("需要允许“显示在其他应用上层”。悬浮窗显示最新金价，可拖动、点击打开应用或单独关闭。")
			.setNegativeButton("取消", null).setPositiveButton("去授权") { _, _ ->
				@Suppress("DEPRECATION")
				startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), OVERLAY_PERMISSION)
			}.show()
	}

	@Deprecated("Uses platform permission result for API 26 compatibility")
	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		super.onActivityResult(requestCode, resultCode, data)
		if (requestCode == OVERLAY_PERMISSION) {
			if (Settings.canDrawOverlays(this)) startMonitoring(MonitorService.SHOW_OVERLAY) else toast("尚未允许悬浮窗，仍可使用应用和通知栏图表")
		}
	}

	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		updatePermissionStatus()
		if (requestCode == NOTIFICATIONS_START && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startMonitoring(pendingAction)
		else if (requestCode == NOTIFICATIONS_TEST && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) testNotification()
		else if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) toast("通知未获授权；可点击“通知授权”进入设置")
	}

	companion object {
		private const val NOTIFICATIONS_START: Int = 101
		private const val NOTIFICATIONS_ONLY: Int = 102
		private const val OVERLAY_PERMISSION: Int = 103
		private const val NOTIFICATIONS_TEST: Int = 104
	}
}
