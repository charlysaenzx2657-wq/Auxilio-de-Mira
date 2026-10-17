package com.toquepreciso.app

import android.animation.ValueAnimator
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.math.abs

class OverlayService : Service() {
    companion object {
        @Volatile var running = false
        const val STOP = "com.toquepreciso.app.STOP"
    }

    private val C_BG = 0xFF12182C.toInt()
    private val C_CARD = 0xFF1C2542.toInt()
    private val C_LINE = 0xFF2C3760.toInt()
    private val C_INK = 0xFFEEF2FF.toInt()
    private val C_MUT = 0xFF93A0C8.toInt()
    private val C_DARK = 0xFF0B0F1D.toInt()

    private lateinit var wm: WindowManager
    private lateinit var store: Store
    private val h = Handler(Looper.getMainLooper())
    private var built = false

    private lateinit var panel: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var plp: WindowManager.LayoutParams
    private lateinit var blp: WindowManager.LayoutParams
    private lateinit var vals: LinearLayout
    private lateinit var dot: View
    private lateinit var nameTv: TextView
    private lateinit var subTv: TextView
    private lateinit var pill: View
    private lateinit var status: TextView
    private lateinit var collapseBtn: TextView
    private val levelTvs = mutableListOf<TextView>()
    private val refreshers = mutableListOf<() -> Unit>()
    private var pulse: ValueAnimator? = null
    private var px = 0
    private var py = 0

    private val segW: Int get() = (dp(262) - dp(26)) / 3
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private val applyRun = Runnable {
        Thread {
            val r = Applier.apply(this, store.level, false)
            h.post { flash(if (r.failed == 0) "Valores aplicados" else "Parcial: ${r.failed} fallaron") }
        }.start()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startFg()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!built) {
            build()
            built = true
        }
        running = true
        return START_STICKY
    }

    private fun startFg() {
        Notif.ensure(this)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, Notif.CH_STATE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Ventana de sensibilidad activa")
            .setContentText("Toca para abrir la app")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Cerrar ventana", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST else 0
        ServiceCompat.startForeground(this, 1, n, type)
    }

    // ---------- helpers de vistas ----------
    private fun shape(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun tv(text: String, size: Float = 12f, color: Int = C_INK, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
    }

    private fun btn(text: String, size: Int, onClick: () -> Unit) = tv(text, 15f, C_INK, true).apply {
        gravity = Gravity.CENTER
        background = shape(C_LINE, 9)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        setOnClickListener { onClick() }
    }

    private fun lp(w: Int, hh: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, hh, weight)

    private fun panelBg() = shape(Color.argb((store.opacity / 100f * 255).toInt(), 18, 24, 44), 18)

    // ---------- construcción ----------
    private fun build() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        store = Store(this)
        px = if (store.posX >= 0) store.posX else dp(8)
        py = if (store.posY >= 0) store.posY else dp(120)
        val lvl = store.level

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = panelBg()
            pivotX = 0f
            pivotY = 0f
        }

        // cabecera: punto + título + nombre, y botones desplegar / minimizar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dot = View(this).apply {
            layoutParams = lp(dp(8), dp(8)).apply { rightMargin = dp(8) }
            background = oval(lvl.color)
        }
        nameTv = tv(lvl.label, 12f, lvl.color, true)
        collapseBtn = btn("+", 26) { toggleValues() }
        val minBtn = btn("−", 26) { minimize() }.apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(5)
        }
        header.addView(dot)
        header.addView(tv("Sensibilidad ", 12f, C_INK, true))
        header.addView(nameTv)
        header.addView(View(this).apply { layoutParams = lp(0, 1, 1f) })
        header.addView(collapseBtn)
        header.addView(minBtn)
        panel.addView(header)

        // selector de tres botones con pastilla deslizante
        val trio = FrameLayout(this).apply {
            background = shape(C_CARD, 13)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        pill = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(segW, dp(32))
            background = shape(lvl.color, 10)
            translationX = (segW * lvl.ordinal).toFloat()
        }
        val rowBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32))
        }
        Level.values().forEach { l ->
            val t = tv(l.label, 12f, if (l == lvl) C_DARK else C_INK, true).apply {
                gravity = Gravity.CENTER
                layoutParams = lp(segW, ViewGroup.LayoutParams.MATCH_PARENT)
                setOnClickListener { select(l) }
            }
            levelTvs += t
            rowBtns.addView(t)
        }
        trio.addView(pill)
        trio.addView(rowBtns)
        panel.addView(trio)

        status = tv("", 11f, C_MUT).apply {
            alpha = 0f
            layoutParams = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        panel.addView(status)

        // valores (desplegable)
        vals = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        subTv = sub("Valores de ${lvl.label}")
        vals.addView(subTv)
        Knobs.all.forEach { vals.addView(knobRow(it)) }
        vals.addView(sub("Ventana"))
        vals.addView(opacityRow())
        vals.addView(snapRow())
        panel.addView(vals)

        bubble = tv(lvl.label.take(1), 18f, C_DARK, true).apply {
            gravity = Gravity.CENTER
            background = oval(lvl.color)
            visibility = View.GONE
        }

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        plp = WindowManager.LayoutParams(dp(262), ViewGroup.LayoutParams.WRAP_CONTENT, type, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = px; y = py
        }
        blp = WindowManager.LayoutParams(dp(50), dp(50), type, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = px; y = py
        }
        wm.addView(panel, plp)
        wm.addView(bubble, blp)

        makeDraggable(header, null)
        makeDraggable(bubble) { restoreFromBubble() }

        // entrada animada
        panel.alpha = 0f
        panel.translationX = -dp(28).toFloat()
        panel.animate().alpha(1f).translationX(0f).setDuration(1300).setInterpolator(OvershootInterpolator(1.1f)).start()
    }

    private fun sub(text: String) = tv(text, 11f, C_MUT).apply {
        setPadding(0, dp(10), 0, dp(2))
    }

    private fun knobRow(k: Knob): LinearLayout {
        val out = tv("", 12f, C_INK, true).apply {
            gravity = Gravity.CENTER
            layoutParams = lp(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val refresh = { out.text = k.fmt(store.value(store.level, k)) }
        refresh()
        refreshers += refresh
        fun step(d: Int) {
            val l = store.level
            val nv = (store.value(l, k) + k.step * d).coerceIn(k.min, k.max)
            store.setValue(l, k, nv)
            refresh()
            out.animate().scaleX(1.3f).scaleY(1.3f).setDuration(220).withEndAction {
                out.animate().scaleX(1f).scaleY(1f).setDuration(320).start()
            }.start()
            h.removeCallbacks(applyRun)
            h.postDelayed(applyRun, 450)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(tv(k.label, 11f).apply { layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            addView(btn("−", 26) { step(-1) })
            addView(out)
            addView(btn("+", 26) { step(1) })
        }
    }

    private fun opacityRow(): LinearLayout {
        val out = tv("${store.opacity}%", 12f, C_INK, true).apply {
            gravity = Gravity.CENTER
            layoutParams = lp(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        fun step(d: Int) {
            store.opacity = (store.opacity + 20 * d).coerceIn(40, 100)
            out.text = "${store.opacity}%"
            panel.background = panelBg()
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(tv("Opacidad", 11f).apply { layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            addView(btn("−", 26) { step(-1) })
            addView(out)
            addView(btn("+", 26) { step(1) })
        }
    }

    private fun snapRow(): LinearLayout {
        val t = tv("", 11f, C_DARK, true).apply {
            gravity = Gravity.CENTER
            layoutParams = lp(dp(44), dp(24))
        }
        fun paint() {
            t.text = if (store.snap) "Sí" else "No"
            t.background = shape(if (store.snap) 0xFF18B8A6.toInt() else C_LINE, 12)
            t.setTextColor(if (store.snap) C_DARK else C_INK)
        }
        paint()
        t.setOnClickListener { store.snap = !store.snap; paint() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(tv("Pegar al borde", 11f).apply { layoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            addView(t)
        }
    }

    // ---------- lógica ----------
    private fun select(l: Level) {
        store.level = l
        refreshLevel(true)
        refreshers.forEach { it() }
        flash("Aplicando…")
        Thread {
            val r = Applier.apply(this, l, true)
            h.post { flash(if (r.failed == 0) "Aplicado: ${r.ok} ajustes" else "Aplicado: ${r.ok} de ${r.ok + r.failed}") }
        }.start()
    }

    private fun refreshLevel(animate: Boolean) {
        val l = store.level
        nameTv.text = l.label
        nameTv.setTextColor(l.color)
        (dot.background as GradientDrawable).setColor(l.color)
        (bubble.background as GradientDrawable).setColor(l.color)
        bubble.text = l.label.take(1)
        subTv.text = "Valores de ${l.label}"
        (pill.background as GradientDrawable).setColor(l.color)
        val tx = (segW * l.ordinal).toFloat()
        if (animate) pill.animate().translationX(tx).setDuration(650).setInterpolator(OvershootInterpolator(1.3f)).start()
        else pill.translationX = tx
        levelTvs.forEachIndexed { i, t -> t.setTextColor(if (i == l.ordinal) C_DARK else C_INK) }
    }

    private fun flash(msg: String) {
        status.text = msg
        status.animate().cancel()
        status.alpha = 0f
        status.animate().setStartDelay(0).alpha(1f).setDuration(350).withEndAction {
            status.animate().alpha(0f).setStartDelay(1800).setDuration(600).start()
        }.start()
    }

    private fun toggleValues() {
        TransitionManager.beginDelayedTransition(panel, AutoTransition().apply { duration = 500 })
        val open = vals.visibility == View.GONE
        vals.visibility = if (open) View.VISIBLE else View.GONE
        collapseBtn.text = if (open) "−" else "+"
        h.postDelayed({ clamp(); updatePos(); savePos() }, 700)
    }

    private fun minimize() {
        panel.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).setDuration(450).withEndAction {
            panel.visibility = View.GONE
            panel.alpha = 1f
            panel.scaleX = 1f
            panel.scaleY = 1f
            showBubble()
            clamp()
            updatePos()
        }.start()
    }

    private fun showBubble() {
        bubble.visibility = View.VISIBLE
        bubble.scaleX = 0.3f
        bubble.scaleY = 0.3f
        bubble.alpha = 0f
        bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(600)
            .setInterpolator(OvershootInterpolator(2f)).withEndAction { startPulse() }.start()
    }

    private fun startPulse() {
        pulse?.cancel()
        pulse = ValueAnimator.ofFloat(1f, 1.1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                val f = it.animatedValue as Float
                bubble.scaleX = f
                bubble.scaleY = f
            }
            start()
        }
    }

    private fun restoreFromBubble() {
        pulse?.cancel()
        bubble.animate().cancel()
        bubble.visibility = View.GONE
        panel.visibility = View.VISIBLE
        clamp()
        updatePos()
        panel.alpha = 0f
        panel.scaleX = 0.3f
        panel.scaleY = 0.3f
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(600).setInterpolator(OvershootInterpolator(1.4f)).start()
    }

    // ---------- posición y arrastre ----------
    private fun curW() = if (bubble.visibility == View.VISIBLE) dp(50) else dp(262)
    private fun curH() = if (bubble.visibility == View.VISIBLE) dp(50) else (if (panel.height > 0) panel.height else dp(130))

    private fun clamp() {
        val dm = resources.displayMetrics
        px = px.coerceIn(dp(4), (dm.widthPixels - curW() - dp(4)).coerceAtLeast(dp(4)))
        py = py.coerceIn(dp(4), (dm.heightPixels - curH() - dp(4)).coerceAtLeast(dp(4)))
    }

    private fun updatePos() {
        plp.x = px; plp.y = py
        blp.x = px; blp.y = py
        try {
            wm.updateViewLayout(panel, plp)
            wm.updateViewLayout(bubble, blp)
        } catch (e: Exception) { }
    }

    private fun savePos() {
        store.posX = px
        store.posY = py
    }

    private fun snapIfNeeded() {
        clamp()
        if (!store.snap) {
            updatePos()
            savePos()
            return
        }
        val dm = resources.displayMetrics
        val w = curW()
        val target = if (px + w / 2 < dm.widthPixels / 2) dp(8) else dm.widthPixels - w - dp(8)
        ValueAnimator.ofInt(px, target).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                px = it.animatedValue as Int
                updatePos()
            }
            start()
        }
        h.postDelayed({ savePos() }, 650)
    }

    private fun makeDraggable(handle: View, onTap: (() -> Unit)?) {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = px; oy = py; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (abs(dx) + abs(dy) > dp(6)) moved = true
                    if (moved) {
                        px = ox + dx.toInt()
                        py = oy + dy.toInt()
                        clamp()
                        updatePos()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved && onTap != null) onTap() else if (moved) snapIfNeeded()
                }
            }
            true
        }
    }

    override fun onDestroy() {
        running = false
        pulse?.cancel()
        h.removeCallbacksAndMessages(null)
        if (built) {
            try { wm.removeView(panel) } catch (e: Exception) { }
            try { wm.removeView(bubble) } catch (e: Exception) { }
        }
        super.onDestroy()
    }
}
