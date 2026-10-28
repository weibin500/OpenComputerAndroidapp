package com.pcpowercard.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.pcpowercard.R

class UptimeTimer(context: Context, private val onUpdate: (String) -> Unit) {
    private val resources = context.resources
    private var bootTime: Long? = null
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 1000)
        }
    }

    private fun update() {
        val start = bootTime ?: return
        val secs = (System.currentTimeMillis() - start) / 1000
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        onUpdate(
            if (h > 0) {
                resources.getString(R.string.uptime_format, h, m, s)
            } else {
                resources.getString(R.string.uptime_minutes_format, m, s)
            }
        )
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    fun markOn() {
        if (bootTime == null) {
            bootTime = System.currentTimeMillis()
            start()
        }
    }

    fun markOff() {
        bootTime = null
        stop()
        onUpdate(resources.getString(R.string.status_unknown))
    }

    fun reset() {
        stop()
        onUpdate(resources.getString(R.string.status_unknown))
    }
}
