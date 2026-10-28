package com.pcpowercard.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pcpowercard.R
import com.pcpowercard.data.Device
import com.pcpowercard.data.DeviceStatus
import com.pcpowercard.mqtt.MqttManager
import com.pcpowercard.util.LogWriter
import com.pcpowercard.util.UptimeTimer

class DeviceActivity : AppCompatActivity() {

    private lateinit var mqtt: MqttManager
    private lateinit var statusPower: TextView
    private lateinit var statusWifi: TextView
    private lateinit var statusMqtt: TextView
    private lateinit var tempVal: TextView
    private lateinit var uptimeVal: TextView
    private lateinit var onlineVal: TextView
    private lateinit var tsVal: TextView
    private lateinit var deviceTitle: TextView
    private lateinit var deviceMac: TextView
    private lateinit var deviceState: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var btnOn: Button
    private lateinit var btnOff: Button
    private lateinit var btnReset: Button
    private lateinit var uptimeTimer: UptimeTimer

    private var currentPower = "OFF"
    private lateinit var mac: String
    private lateinit var deviceName: String

    private val uiHandler = Handler(Looper.getMainLooper())
    private val logTicker = object : Runnable {
        override fun run() {
            logView.text = LogWriter.getLogText()
            logScroll.fullScroll(ScrollView.FOCUS_DOWN)
            mqtt.checkConnect()
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.title_device)

        mac = intent.getStringExtra("mac") ?: ""
        deviceName = intent.getStringExtra("name") ?: "Device"
        val sn = intent.getStringExtra("sn") ?: "nosn"

        deviceTitle = findViewById(R.id.tvDeviceTitle)
        deviceMac = findViewById(R.id.tvDeviceMac)
        deviceState = findViewById(R.id.tvDeviceState)
        statusPower = findViewById(R.id.tvPowerVal)
        statusWifi = findViewById(R.id.tvWifiVal)
        statusMqtt = findViewById(R.id.tvMqttVal)
        tempVal = findViewById(R.id.tvTempVal)
        uptimeVal = findViewById(R.id.tvUptimeVal)
        onlineVal = findViewById(R.id.tvOnlineVal)
        tsVal = findViewById(R.id.tvTsVal)
        logView = findViewById(R.id.tvLog)
        logScroll = findViewById(R.id.scrollLog)
        btnOn = findViewById(R.id.btnPowerOn)
        btnOff = findViewById(R.id.btnPowerOff)
        btnReset = findViewById(R.id.btnReset)

        deviceTitle.text = deviceName
        deviceMac.text = getString(R.string.mac_format, mac)

        btnOn.setOnClickListener {
            showConfirm(R.string.confirm_power_on) { mqtt.publishPower("ON") }
        }
        btnOff.setOnClickListener {
            showConfirm(R.string.confirm_power_off) { mqtt.publishPower("OFF") }
        }
        btnReset.setOnClickListener {
            showConfirm(R.string.confirm_reset) { mqtt.publishReset() }
        }

        LogWriter.log("Enter device $deviceName (MAC $mac)")
        setButtonsEnabled(false, getString(R.string.reason_connecting))

        initMqtt(sn)
        uptimeTimer = UptimeTimer(this) { updateUptime(it) }
        setUptime(getString(R.string.status_unknown))

        uiHandler.postDelayed(logTicker, 300)
    }

    private fun initMqtt(sn: String) {
        mqtt = MqttManager()
        mqtt.setTopicPrefix(Device.topicPrefix(sn, mac))
        mqtt.subscribeStatus(
            onStatus = { status -> onUpdateDeviceStatus(status) },
            onConnected = {
                statusMqtt.text = getString(R.string.status_up)
                setButtonsEnabled(true)
            },
            onDisconnected = {
                statusMqtt.text = getString(R.string.status_down)
                setButtonsEnabled(false, getString(R.string.reason_disconnected))
            }
        )
        mqtt.connect()
    }

    private fun onUpdateDeviceStatus(status: DeviceStatus) {
        runOnUiThread {
            currentPower = status.power
            statusPower.text = localizedPower(status.power)
            statusWifi.text = localizedConnection(status.wifi)
            statusMqtt.text = localizedConnection(status.mqtt)
            tempVal.text = status.temp?.let { String.format("%.1f ℃", it) }
                ?: getString(R.string.status_unknown)
            onlineVal.text = getString(
                if (status.online) R.string.status_online else R.string.status_offline
            )
            tsVal.text = formatDeviceTime(status.time)
            deviceState.text = getString(R.string.device_state_format, localizedPower(status.power))
            if (status.power == "ON") uptimeTimer.markOn() else uptimeTimer.markOff()
        }
    }

    private fun updateUptime(text: String) = setUptime(text)
    private fun setUptime(text: String) {
        runOnUiThread { uptimeVal.text = text }
    }

    private fun setButtonsEnabled(enabled: Boolean, reason: String? = null) {
        runOnUiThread {
            btnOn.isEnabled = enabled
            btnOff.isEnabled = enabled
            btnReset.isEnabled = enabled
            if (!enabled) {
                deviceState.text = getString(
                    R.string.device_state_reason_format,
                    localizedPower(currentPower),
                    reason ?: getString(R.string.reason_disconnected)
                )
            }
        }
    }

    private fun showConfirm(messageRes: Int, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.title_confirm)
            .setMessage(messageRes)
            .setPositiveButton(R.string.action_confirm) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.action_cancel) { d, _ -> d.cancel() }
            .show()
    }

    private fun localizedPower(power: String): String =
        getString(if (power == "ON") R.string.status_on else R.string.status_off)

    private fun localizedConnection(status: String): String =
        getString(if (status == "UP") R.string.status_up else R.string.status_down)

    private fun formatDeviceTime(time: String): String {
        if (time.isBlank() || time == "N/A") return getString(R.string.time_not_synced)
        val weekdays = mapOf(
            "Mon " to "周一 ",
            "Tue " to "周二 ",
            "Wed " to "周三 ",
            "Thu " to "周四 ",
            "Fri " to "周五 ",
            "Sat " to "周六 ",
            "Sun " to "周日 "
        )
        return weekdays.entries.firstOrNull { time.startsWith(it.key) }
            ?.let { time.replaceFirst(it.key, it.value) }
            ?: time
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(logTicker)
        uptimeTimer.reset()
        mqtt.disconnect()
    }
}
