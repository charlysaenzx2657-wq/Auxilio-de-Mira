package com.toquepreciso.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import rikka.shizuku.Shizuku

object Applier {
    const val SYS = "system"
    const val SEC = "secure"
    const val GLB = "global"

    data class ScanResult(val total: Int, val supported: Int, val oem: List<Pair<String, String>>)
    data class ApplyResult(val ok: Int, val failed: Int)

    private val KNOWN = listOf(
        SYS to "pointer_speed", SEC to "long_press_timeout", SEC to "multi_press_timeout",
        GLB to "window_animation_scale", GLB to "transition_animation_scale", GLB to "animator_duration_scale",
        SYS to "min_refresh_rate", SYS to "peak_refresh_rate",
        SYS to "haptic_feedback_enabled", SYS to "haptic_feedback_intensity",
        SYS to "accelerometer_rotation",
        SEC to "back_gesture_inset_scale_left", SEC to "back_gesture_inset_scale_right"
    )

    private fun uriFor(t: String): Uri = when (t) {
        SYS -> Settings.System.CONTENT_URI
        SEC -> Settings.Secure.CONTENT_URI
        else -> Settings.Global.CONTENT_URI
    }

    fun get(ctx: Context, t: String, key: String): String? = try {
        when (t) {
            SYS -> Settings.System.getString(ctx.contentResolver, key)
            SEC -> Settings.Secure.getString(ctx.contentResolver, key)
            else -> Settings.Global.getString(ctx.contentResolver, key)
        }
    } catch (e: Exception) { null }

    private fun rawPut(ctx: Context, t: String, key: String, value: String): Boolean = try {
        when (t) {
            SYS -> Settings.System.putString(ctx.contentResolver, key, value)
            SEC -> Settings.Secure.putString(ctx.contentResolver, key, value)
            else -> Settings.Global.putString(ctx.contentResolver, key, value)
        }
    } catch (e: Exception) { false }

    // Guarda el valor original la primera vez que se toca una clave.
    private fun remember(ctx: Context, s: Store, t: String, key: String) {
        val b = s.backup()
        val id = "$t:$key"
        if (!b.has(id)) {
            b.put(id, get(ctx, t, key) ?: JSONObject.NULL)
            s.saveBackup(b)
        }
    }

    private fun set(ctx: Context, s: Store, t: String, key: String, value: String): Boolean {
        remember(ctx, s, t, key)
        return rawPut(ctx, t, key, value)
    }

    private fun restoreKey(ctx: Context, s: Store, t: String, key: String): Boolean {
        val b = s.backup()
        val id = "$t:$key"
        if (!b.has(id)) return true
        val v = if (b.isNull(id)) null else b.getString(id)
        val ok = if (v == null) {
            try { ctx.contentResolver.delete(uriFor(t), "name=?", arrayOf(key)) >= 0 } catch (e: Exception) { false }
        } else rawPut(ctx, t, key, v)
        if (ok) { b.remove(id); s.saveBackup(b) }
        return ok
    }

    fun readAll(ctx: Context, t: String): Map<String, String?> {
        val m = LinkedHashMap<String, String?>()
        try {
            ctx.contentResolver.query(uriFor(t), arrayOf("name", "value"), null, null, null)?.use { c ->
                while (c.moveToNext()) m[c.getString(0)] = c.getString(1)
            }
        } catch (e: Exception) { }
        return m
    }

    private val OEM = Regex("touch_sensitiv|high_touch_sens")

    private fun oemFrom(maps: Map<String, Map<String, String?>>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for ((t, m) in maps) for ((k, v) in m) {
            if (OEM.containsMatchIn(k) && (v == "0" || v == "1")) out += t to k
        }
        return out
    }

    fun oemKeys(ctx: Context) = oemFrom(listOf(SYS, SEC, GLB).associateWith { readAll(ctx, it) })

    fun scan(ctx: Context): ScanResult {
        val maps = listOf(SYS, SEC, GLB).associateWith { readAll(ctx, it) }
        val total = maps.values.sumOf { it.size }
        val known = KNOWN.count { (t, k) -> maps[t]?.containsKey(k) == true }
        val oem = oemFrom(maps)
        return ScanResult(total, known + oem.size, oem)
    }

    private fun supportedRate(ctx: Context, want: Float): Float {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val rates = dm.getDisplay(Display.DEFAULT_DISPLAY)?.supportedModes?.map { it.refreshRate } ?: return want
        if (rates.isEmpty()) return want
        return rates.filter { it <= want + 1f }.maxOrNull() ?: rates.min()
    }

    fun apply(ctx: Context, level: Level, notify: Boolean): ApplyResult {
        val s = Store(ctx)
        fun v(id: String) = s.value(level, Knobs.all.first { it.id == id })
        var ok = 0
        var bad = 0
        fun put(t: String, k: String, x: String) { if (set(ctx, s, t, k, x)) ok++ else bad++ }

        put(SYS, "pointer_speed", v("ptr").toInt().toString())
        put(SEC, "long_press_timeout", v("lp").toInt().toString())
        put(SEC, "multi_press_timeout", v("dt").toInt().toString())
        val an = v("an").toString()
        put(GLB, "window_animation_scale", an)
        put(GLB, "transition_animation_scale", an)
        put(GLB, "animator_duration_scale", an)
        if (!s.extra("hz")) {
            val r = supportedRate(ctx, v("hz")).toString()
            put(SYS, "min_refresh_rate", r)
            put(SYS, "peak_refresh_rate", r)
        }
        val hp = v("hp").toInt().coerceIn(0, 2)
        put(SYS, "haptic_feedback_enabled", if (hp == 0) "0" else "1")
        put(SYS, "haptic_feedback_intensity", listOf("0", "1", "3")[hp])
        oemKeys(ctx).forEach { (t, k) -> put(t, k, if (level == Level.BAJA) "0" else "1") }

        if (notify) {
            if (bad == 0) Notif.applied(ctx, "Sensibilidad ${level.label.lowercase()} aplicada", "$ok ajustes se aplicaron correctamente.")
            else Notif.applied(ctx, "Aplicada parcialmente", "$bad ajustes fallaron. Abre la app y revisa los permisos.")
        }
        return ApplyResult(ok, bad)
    }

    fun setExtra(ctx: Context, id: String, on: Boolean): Boolean {
        val s = Store(ctx)
        s.setExtra(id, on)
        val ok = when (id) {
            "edge" -> toggle(ctx, s, on, listOf(SEC to "back_gesture_inset_scale_left", SEC to "back_gesture_inset_scale_right"), "0.5")
            "rot" -> toggle(ctx, s, on, listOf(SYS to "accelerometer_rotation"), "0")
            "hz" -> {
                val keys = listOf(SYS to "min_refresh_rate", SYS to "peak_refresh_rate")
                val r = toggle(ctx, s, on, keys, supportedRate(ctx, 1000f).toString())
                if (!on) apply(ctx, s.level, false)
                r
            }
            "dnd" -> dnd(ctx, s, on)
            else -> false
        }
        if (!ok) s.setExtra(id, !on)
        return ok
    }

    private fun toggle(ctx: Context, s: Store, on: Boolean, keys: List<Pair<String, String>>, value: String): Boolean {
        var ok = true
        for ((t, k) in keys) ok = (if (on) set(ctx, s, t, k, value) else restoreKey(ctx, s, t, k)) && ok
        return ok
    }

    private fun dnd(ctx: Context, s: Store, on: Boolean): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return false
        if (on) {
            if (s.dndPrev == -1) s.dndPrev = nm.currentInterruptionFilter
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        } else {
            val prev = s.dndPrev
            nm.setInterruptionFilter(if (prev <= 0) NotificationManager.INTERRUPTION_FILTER_ALL else prev)
            s.dndPrev = -1
        }
        return true
    }

    fun restoreAll(ctx: Context): Boolean {
        val s = Store(ctx)
        var ok = true
        s.backup().keys().asSequence().toList().forEach { id ->
            val parts = id.split(":", limit = 2)
            ok = restoreKey(ctx, s, parts[0], parts[1]) && ok
        }
        if (s.extra("dnd")) dnd(ctx, s, false)
        EXTRAS.forEach { s.setExtra(it.first, false) }
        Notif.applied(ctx, "Valores originales restaurados", if (ok) "Todo volvió a como estaba." else "Algunos valores no se pudieron restaurar.")
        return ok
    }
}

object Notif {
    const val CH_STATE = "estado"
    const val CH_DONE = "aplicado"

    fun ensure(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATE, "Ventana activa", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Sensibilidad aplicada", NotificationManager.IMPORTANCE_DEFAULT))
    }

    @SuppressLint("MissingPermission")
    fun applied(ctx: Context, title: String, text: String) {
        ensure(ctx)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setTimeoutAfter(8000)
            .setContentIntent(open)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(2, n) } catch (e: SecurityException) { }
    }
}

object Perms {
    fun canWriteSystem(ctx: Context) = Settings.System.canWrite(ctx)
    fun canWriteSecure(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    fun canOverlay(ctx: Context) = Settings.canDrawOverlays(ctx)
    fun canNotify(ctx: Context) = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun shizukuUp(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }
    fun shizukuGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }
    fun shizukuAsk(code: Int) { try { Shizuku.requestPermission(code) } catch (e: Throwable) { } }

    private fun sh(cmd: String): Boolean = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        p.waitFor() == 0
    } catch (e: Throwable) { false }

    // Concede por Shizuku los permisos especiales que la app necesita.
    fun grantAll(ctx: Context): Boolean {
        val pkg = ctx.packageName
        var ok = true
        for (c in listOf(
            "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
            "appops set $pkg WRITE_SETTINGS allow",
            "appops set $pkg SYSTEM_ALERT_WINDOW allow"
        )) ok = sh(c) && ok
        if (Build.VERSION.SDK_INT >= 33) sh("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        return ok
    }
}
