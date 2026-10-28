package com.pcpowercard.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogWriter {
    private val buffer = ArrayDeque<String>()
    private const val MAX_LINES = 200
    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun log(msg: String) {
        val ts = sdf.format(Date())
        val entry = "[$ts] $msg"
        buffer.addLast(entry)
        while (buffer.size > MAX_LINES) buffer.removeFirst()
    }

    fun getLogText(): String = buffer.joinToString("\n")

    fun clear() = buffer.clear()
}
