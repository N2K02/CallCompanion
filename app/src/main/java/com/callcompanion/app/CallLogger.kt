package com.pcl.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Private diagnostics for update activity. Each launch starts a fresh session. */
object CallLogger {
    private val lock = Any()
    private lateinit var appContext: Context
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun init(context: Context) {
        appContext = context.applicationContext
        synchronized(lock) {
            _lines.value = emptyList()
            appContext.getSharedPreferences("call_activity_logs", Context.MODE_PRIVATE)
                .edit().remove("lines").apply()
        }
    }

    fun log(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        synchronized(lock) { _lines.value = (_lines.value + "[$stamp] $message").takeLast(100) }
    }

    fun clear() = synchronized(lock) { _lines.value = emptyList() }
}