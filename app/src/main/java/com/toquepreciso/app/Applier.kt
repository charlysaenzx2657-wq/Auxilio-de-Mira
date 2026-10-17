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
import kotlin.math.abs

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

    // Escribe, vuelve a leer el valor y deja todo en el registro (ADB y app).
    private fun set(ctx: Context, s: Store, t: String, key: String, value: String): Boolean {
        remember(ctx, s, t, key)
        val before = get(ctx, t, key)
        var wrote = rawPut(ctx, t, key, value)
        var via = ""
        if (!wrote) {
            wrote = Perms.shellPut(t, key, value)
            if (wrote) via = " (vía Shizuku)"
        }
        val after = get(ctx, t, key)
        val a = after?.toFloatOrNull()
        val b = value.toFloatOrNull()
        val same = after != null && (after == value || (a != null && b != null && abs(a - b) < 0.0001f))
        val st = when {
            !wrote -> "FALLÓ"
            same -> "OK"
            else -> "IGNORADO (el sistema no lo conservó)"
        }
        Trace.log("settings put $t $key $value | antes=$before después=$after | $st$via")
        return wrote && same
    }

    private fun restoreKey(ctx: Context, s: Store, t: String, key: String): Boolean {
        val b = s.backup()
        val id = "$t:$key"
        if (!b.has(id)) return true
        val v = if (b.isNull(id)) null else b.getString(id)
        if (v != null && get(ctx, t, key) == v) { b.remove(id); s.saveBackup(b); return true }
        val ok = if (v == null) {
            try { ctx.contentResolver.delete(uriFor(t), "name=?", arrayOf(key)) >= 0 } catch (e: Exception) { false }
        } else (rawPut(ctx, t, key, v) || Perms.shellPut(t, key, v))
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

    private val OEM = Regex("touch_sensitiv|high_touch_sens|glove|touch_boost|game_touch|gaming_touch|touch_response")

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
        Trace.log("=== ESCANEO: $total ajustes en el sistema ===")
        var n = 0
        for ((t, m) in maps) for ((k, v) in m) {
            if (CATALOG.containsMatchIn(k) && n < 400) { n++; Trace.log("candidato $t:$k=$v") }
        }
        val extra = maps.flatMap { (t, m) -> m.keys.filter { factorKey(it) != null || HAPTIC.containsMatchIn(it) }.map { t to it } }
        Trace.log("Conocidos: $known, del fabricante: ${oem.size}, de escaneo (fricción, velocidad, umbral, respuesta, vibración): ${extra.size}, candidatos listados: $n")
        return ScanResult(total, known + oem.size + extra.size, oem)
    }

    private fun supportedRate(ctx: Context, want: Float): Float {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val rates = dm.getDisplay(Display.DEFAULT_DISPLAY)?.supportedModes?.map { it.refreshRate } ?: return want
        if (rates.isEmpty()) return want
        return rates.filter { it <= want + 1f }.maxOrNull() ?: rates.min()
    }

    private val FRICTION = Regex("friction|damping|deceler|decay|inertia|overscroll")
    private val THRESH = Regex("slop|tap_duration|double_tap|press_timeout|(touch|tap|gesture|scroll|swipe|pointer|input|drag)_([a-z_]*_)?(threshold|timeout|delay|duration|distance)")
    private val RATE = Regex("(touch|input|pointer|gesture)_([a-z_]*_)?(rate|sampling|polling|boost|response)|sampling_rate|polling_rate")
    private val HAPTIC = Regex("haptic|touch_vibrat|tap_vibrat")
    private val EXCL = Regex("enable|enabled|mode|state|count|_id|version|timestamp|last_|setup|user_|bluetooth|wifi|account")
    private val VELOCITY = Regex("scroll_speed|scroll_velocity|fling|velocity|swipe|scroll_scale|scroll_multiplier|touch_speed|drag_speed|acceleration|pointer_(scale|sens[a-z_]*)")

    // Devuelve el control (fr, sv, th o ra) que gobierna una clave, o null si no es de ninguna familia.
    private fun factorKey(k: String): String? = when {
        FRICTION.containsMatchIn(k) -> "fr"
        VELOCITY.containsMatchIn(k) -> "sv"
        THRESH.containsMatchIn(k) && !EXCL.containsMatchIn(k) -> "th"
        RATE.containsMatchIn(k) && !EXCL.containsMatchIn(k) -> "ra"
        else -> null
    }
    private val CATALOG = Regex("touch|scroll|fling|friction|gesture|pointer|tap_|press|slop|velocity|sensitiv|haptic|vibrat|swipe|drag|damping|deceler|inertia|sampling|polling|palm|edge|finger|hover|double_tap|click|game")

    data class Cand(val t: String, val k: String, val v: String?, val cat: String) {
        val id: String get() = "$t:$k"
    }

    fun catOf(k: String): String = when {
        FRICTION.containsMatchIn(k) -> "Fricción"
        VELOCITY.containsMatchIn(k) -> "Velocidad"
        else -> "Otros"
    }

    // Lista ampliada de candidatas (sin las que la app ya maneja por su cuenta).
    fun candidates(ctx: Context): List<Cand> {
        val known = KNOWN.map { "${it.first}:${it.second}" }.toSet()
        val out = mutableListOf<Cand>()
        for (t in listOf(SYS, SEC, GLB)) for ((k, v) in readAll(ctx, t)) {
            if ("$t:$k" in known || !CATALOG.containsMatchIn(k)) continue
            out += Cand(t, k, v, catOf(k))
        }
        return out.sortedWith(compareBy({ it.cat }, { it.k }, { it.t }))
    }

    // Multiplica por un factor las claves numéricas ELEGIDAS, tomando como base el valor original.
    private fun scaleKeys(ctx: Context, s: Store, re: Regex, factor: Float, name: String): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()
        for (t in listOf(SYS, SEC, GLB)) for ((k, v) in readAll(ctx, t)) {
            if (!re.containsMatchIn(k) || v == null || "$t:$k" !in s.picked() || (re === VELOCITY && FRICTION.containsMatchIn(k))) continue
            remember(ctx, s, t, k)
            val orig = s.backup().optString("$t:$k", v)
            val base = orig.toFloatOrNull() ?: continue
            if (orig == "0" || orig == "1") continue
            val nv = base * factor
            out += Triple(t, k, if (orig.contains('.')) String.format(java.util.Locale.US, "%.5f", nv) else nv.toInt().toString())
        }
        if (out.isEmpty()) Trace.log("$name: ninguna clave elegida (elígelas en la lista de candidatas)")
        else Trace.log("$name: ${out.size} claves -> " + out.joinToString { it.second })
        return out
    }

    private data class W(val t: String, val k: String, val v: String)

    // Arma la lista completa de ajustes del botón: los conocidos + todo lo que encontró el escaneo.
    private fun buildPlan(ctx: Context, s: Store, level: Level): List<W> {
        fun v(id: String) = s.value(level, Knobs.all.first { it.id == id })
        val out = mutableListOf<W>()
        out += W(SYS, "pointer_speed", v("ptr").toInt().toString())
        out += W(SEC, "long_press_timeout", v("lp").toInt().toString())
        out += W(SEC, "multi_press_timeout", v("dt").toInt().toString())
        val an = v("an").toString()
        out += W(GLB, "window_animation_scale", an)
        out += W(GLB, "transition_animation_scale", an)
        out += W(GLB, "animator_duration_scale", an)
        if (!s.extra("hz")) {
            val r = supportedRate(ctx, v("hz")).toString()
            out += W(SYS, "min_refresh_rate", r)
            out += W(SYS, "peak_refresh_rate", r)
        }
        val hp = v("hp").toInt().coerceIn(0, 2)
        out += W(SYS, "haptic_feedback_enabled", if (hp == 0) "0" else "1")
        out += W(SYS, "haptic_feedback_intensity", listOf("0", "1", "3")[hp])

        val maps = listOf(SYS, SEC, GLB).associateWith { readAll(ctx, it) }
        val b = s.backup()
        val known = KNOWN.map { "${it.first}:${it.second}" }.toSet()
        for ((t, m) in maps) for ((k, cur) in m) {
            val id = "$t:$k"
            if (id in known || cur == null) continue
            if (OEM.containsMatchIn(k) && (cur == "0" || cur == "1")) {
                out += W(t, k, if (level == Level.BAJA) "0" else "1")
                continue
            }
            if (HAPTIC.containsMatchIn(k) && (cur == "0" || cur == "1" || cur == "2" || cur == "3")) {
                out += W(t, k, if (cur == "0" || cur == "1") (if (hp == 0) "0" else "1") else listOf("0", "1", "3")[hp])
                continue
            }
            val fk = factorKey(k) ?: continue
            val factor = if (fk == "ra") 2f - v("th") else v(fk)
            val orig = if (b.has(id) && !b.isNull(id)) b.getString(id) else cur
            val base = orig.toFloatOrNull() ?: continue
            if (base <= 0f || orig == "1") continue
            val nv = base * factor
            out += W(t, k, if (orig.contains('.')) String.format(java.util.Locale.US, "%.5f", nv) else nv.toInt().toString())
        }
        return out
    }

    fun planSize(ctx: Context, level: Level? = null): Int {
        val s = Store(ctx)
        return buildPlan(ctx, s, level ?: s.level).size
    }

    // Escribe todo: directo cuando Android deja, y en lotes por terminal (Shizuku) cuando lo bloquea.
    private fun execute(ctx: Context, s: Store, plan: List<W>, onProgress: ((Float, String) -> Unit)? = null): Pair<Int, List<String>> {
        val b = s.backup()
        for (w in plan) {
            val id = "${w.t}:${w.k}"
            if (!b.has(id)) b.put(id, get(ctx, w.t, w.k) ?: JSONObject.NULL)
        }
        s.saveBackup(b)
        val before = plan.associate { "${it.t}:${it.k}" to get(ctx, it.t, it.k) }
        val direct = HashMap<W, Boolean>()
        plan.forEachIndexed { i, w ->
            direct[w] = rawPut(ctx, w.t, w.k, w.v)
            if (i % 5 == 0) onProgress?.invoke(0.05f + 0.55f * (i + 1) / plan.size, "Aplicando comandos")
        }
        val blocked = plan.filter { direct[it] != true }
        if (blocked.isNotEmpty()) {
            Trace.log("Android bloqueó ${blocked.size} escrituras directas; se reintentan por terminal (Shizuku)")
            blocked.chunked(30).forEachIndexed { ci, chunk ->
                val cmd = chunk.joinToString("; ") { "cmd settings put ${it.t} ${it.k} '${it.v}'" }
                val (code, out) = Perms.shOut(cmd)
                if (code != 0) Trace.err("terminal (lote de ${chunk.size}) código $code $out")
                onProgress?.invoke(0.6f + 0.2f * minOf(1f, (ci + 1) * 30f / blocked.size), "Reintentando por Shizuku")
            }
        }
        var ok = 0
        val failed = mutableListOf<String>()
        for (w in plan) {
            val id = "${w.t}:${w.k}"
            if ((ok + failed.size) % 5 == 0) onProgress?.invoke(0.8f + 0.2f * (ok + failed.size + 1) / plan.size, "Verificando")
            val after = get(ctx, w.t, w.k)
            val a = after?.toFloatOrNull()
            val c = w.v.toFloatOrNull()
            val same = after != null && (after == w.v || (a != null && c != null && abs(a - c) < 0.0001f))
            val via = if (direct[w] == true) "" else " (vía Shizuku)"
            Trace.log("settings put ${w.t} ${w.k} ${w.v} | antes=${before[id]} después=$after | ${if (same) "OK" else "FALLÓ/IGNORADO"}$via")
            if (same) ok++ else failed += id
        }
        return ok to failed
    }

    @Synchronized
    fun apply(ctx: Context, level: Level, notify: Boolean, onProgress: ((Float, String) -> Unit)? = null): ApplyResult {
        val s = Store(ctx)
        Trace.log("=== APLICAR nivel ${level.label} ===")
        onProgress?.invoke(0f, "Preparando")
        val plan = buildPlan(ctx, s, level)
        val (ok, failed) = execute(ctx, s, plan, onProgress)
        val bad = failed.size
        Trace.log("Resultado: $ok bien, $bad fallaron" + if (failed.isEmpty()) "" else " -> " + failed.joinToString(", "))
        if (notify) {
            if (bad == 0) Notif.applied(ctx, "Sensibilidad ${level.label.lowercase()} aplicada", "$ok ajustes se aplicaron correctamente.")
            else Notif.applied(ctx, "Sensibilidad ${level.label.lowercase()} aplicada", "$ok de ${ok + bad} ajustes se conservaron. Detalle en el registro (ADB).")
        }
        onProgress?.invoke(1f, "Listo")
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

    // Ejecuta un comando de terminal por Shizuku. Devuelve (código de salida, salida).
    fun shOut(cmd: String): Pair<Int, String> = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
        p.waitFor() to out.trim()
    } catch (e: Throwable) { -1 to "Shizuku no disponible: ${e.javaClass.simpleName}" }

    private fun sh(cmd: String): Boolean = shOut(cmd).first == 0

    fun shellPut(t: String, key: String, value: String): Boolean {
        val (code, out) = shOut("cmd settings put $t $key '$value'")
        if (code != 0) Trace.err("terminal: settings put $t $key $value -> código $code $out")
        return code == 0
    }

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
