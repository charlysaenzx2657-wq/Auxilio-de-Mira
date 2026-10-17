package com.toquepreciso.app

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Registro de todo lo que hace la app: sale por ADB (adb logcat -s AuxilioDeMira) y se ve dentro de la app.
object Trace {
    const val TAG = "AuxilioDeMira"
    private val buf = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(msg: String) {
        Log.i(TAG, msg)
        add(msg)
    }

    fun err(msg: String) {
        Log.e(TAG, msg)
        add("ERROR $msg")
    }

    private fun add(msg: String) = synchronized(buf) {
        buf.addLast("${fmt.format(Date())} $msg")
        while (buf.size > 300) buf.removeFirst()
    }

    fun lines(): List<String> = synchronized(buf) { buf.toList() }
    fun text(): String = lines().joinToString("\n")
}
