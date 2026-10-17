package com.toquepreciso.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.IntentFilter
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

private val BG = Color(0xFF050A14)
private val CARD = Color(0xFF0B1426)
private val LINE = Color(0xFF17325C)
private val INK = Color(0xFFEAF6FF)
private val MUT = Color(0xFF7F9BC4)
private val DARK = Color(0xFF030712)
private val OKC = Color(0xFF19C8FF)
private val BAD = Color(0xFFFF6B5B)
private val ORANGE = Color(0xFFFF7A1A)

class MainActivity : ComponentActivity() {
    private val tick = mutableIntStateOf(0)

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ -> tick.intValue++ }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { tick.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notif.ensure(this)
        try { Shizuku.addRequestPermissionResultListener(shizukuListener) } catch (e: Throwable) { }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = OKC, background = BG, surface = BG)) {
                Surface(modifier = Modifier.fillMaxSize(), color = BG) {
                    App(tick.intValue) {
                        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else openSettings(this, Settings.ACTION_APP_NOTIFICATION_SETTINGS, extraPkg = true)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tick.intValue++
    }

    override fun onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(shizukuListener) } catch (e: Throwable) { }
        super.onDestroy()
    }
}

private fun openSettings(ctx: Context, action: String, data: Boolean = false, extraPkg: Boolean = false) {
    val i = Intent(action)
    if (data) i.data = Uri.parse("package:${ctx.packageName}")
    if (extraPkg) i.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(i) } catch (e: Exception) { }
}

@Composable
fun App(tick: Int, askNotif: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { Store(ctx) }
    var level by remember { mutableStateOf(store.level) }
    var msg by remember { mutableStateOf("") }
    var local by remember { mutableIntStateOf(0) }
    var scanKey by remember { mutableIntStateOf(0) }
    var scanRes by remember { mutableStateOf<Applier.ScanResult?>(null) }
    val progress = remember { Animatable(0f) }
    val fill = remember { Animatable(0f) }
    val extras = remember {
        mutableStateMapOf<String, Boolean>().also { m -> EXTRAS.forEach { m[it.first] = store.extra(it.first) } }
    }
    var shown by remember { mutableStateOf(false) }
    var counts by remember { mutableStateOf<Map<Level, Int>>(emptyMap()) }
    val plan = counts[level] ?: 0
    var loading by remember { mutableStateOf(false) }
    var prog by remember { mutableStateOf(0f) }
    var phase by remember { mutableStateOf("") }
    var done by remember { mutableStateOf<Applier.ApplyResult?>(null) }
    var loadLevel by remember { mutableStateOf(Level.MEDIA) }
    val appliedLevel = remember(tick, local, done) { store.appliedLevel }

    val canSys = remember(tick, local) { Perms.canWriteSystem(ctx) }
    val canSec = remember(tick, local) { Perms.canWriteSecure(ctx) }
    val canOv = remember(tick, local) { Perms.canOverlay(ctx) }
    val canNot = remember(tick, local) { Perms.canNotify(ctx) }
    val shzUp = remember(tick, local) { Perms.shizukuUp() }
    var overlayOn by remember(tick, local) { mutableStateOf(OverlayService.running) }

    LaunchedEffect(Unit) { shown = true }
    LaunchedEffect(done) {
        if (done != null) {
            delay(4000)
            loading = false
        }
    }

    LaunchedEffect(scanKey) {
        scanRes = null
        progress.snapTo(0f)
        val job = launch { progress.animateTo(1f, tween(3200, easing = FastOutSlowInEasing)) }
        val r = withContext(Dispatchers.IO) { Applier.scan(ctx) }
        job.join()
        scanRes = r
        counts = withContext(Dispatchers.IO) { Level.values().associateWith { Applier.planSize(ctx, it) } }
    }

    fun report(r: Applier.ApplyResult, l: Level) {
        local++
        msg = if (r.failed == 0) "Sensibilidad ${l.label.lowercase()} aplicada (${r.ok} ajustes)"
        else "Aplicados ${r.ok} de ${r.ok + r.failed}; ${r.failed} no se conservaron (mira el registro)."
    }

    fun runApply(l: Level) {
        if (loading) return
        loading = true
        done = null
        prog = 0f
        phase = "Preparando"
        loadLevel = l
        scope.launch {
            var last = 0L
            val r = withContext(Dispatchers.IO) {
                Applier.apply(ctx, l, true) { f, t ->
                    val now = System.nanoTime()
                    if (now - last > 60_000_000L || f >= 1f) {
                        last = now
                        prog = f
                        phase = t
                    }
                }
            }
            prog = 1f
            done = r
            report(r, l)
        }
    }

    fun choose(l: Level) {
        level = l
        store.level = l
    }

    fun applyWithAnimation() {
        runApply(level)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BG, Color(0xFF07142B), BG)))
            .drawBehind {
                val c = Offset(size.width / 2f, 0f)
                drawCircle(
                    brush = Brush.radialGradient(listOf(Color(0x3319C8FF), Color.Transparent), center = c, radius = size.width * 0.9f),
                    radius = size.width * 0.9f,
                    center = c
                )
            }
    ) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(R.drawable.logo_emblem), null,
                Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .border(2.dp, Brush.sweepGradient(listOf(OKC, Color(0xFF2A6BFF), OKC)), CircleShape)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("AUXILIO DE MIRA", color = INK, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
                Text("Ajuste fino de toque para Free Fire", color = MUT, fontSize = 12.sp)
            }
            StatusChip(if (shzUp) "Shizuku" else "Sin Shizuku", shzUp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Ajustes del sistema", scanRes?.total?.toString() ?: "…", Modifier.weight(1f))
            StatTile("Compatibles", scanRes?.supported?.toString() ?: "…", Modifier.weight(1f))
            StatTile("Por botón", if (plan > 0) plan.toString() else "…", Modifier.weight(1f))
        }
        LiveTiles()

        // Escaneo
        Box(
            Modifier
                .fillMaxWidth()
                .clip(CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
                .background(CARD)
                .border(1.dp, LINE, CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
                .padding(12.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(scanRes == null)
                    Spacer(Modifier.width(8.dp))
                    val txt = scanRes?.let { "Escaneo listo: ${it.supported} compatibles (${it.total} ajustes en el sistema). Cada botón aplica $plan ajustes." }
                        ?: "Escaneando ajustes… ${(progress.value * 100).toInt()}%"
                    Text(txt, color = MUT, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress.value },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = OKC,
                    trackColor = LINE
                )
            }
        }

        // Sensibilidad
        Level.values().forEach { l -> LevelCard(l, l == level, counts[l], if (appliedLevel == l) "ACTIVO" else if (l == level) "SELECCIONADO, FALTA APLICAR" else null) { choose(l) } }

        // Permisos
        SectionTitle("Permisos")
        Box(Modifier.fillMaxWidth().clip(CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp)).background(CARD).padding(horizontal = 14.dp, vertical = 6.dp)) {
            Column {
                PermRow("Escritura de ajustes", canSys, "Permitir") {
                    openSettings(ctx, Settings.ACTION_MANAGE_WRITE_SETTINGS, data = true)
                }
                PermRow("Ajustes seguros (Shizuku)", canSec, "Conceder") {
                    when {
                        !shzUp -> msg = "Abre Shizuku y actívalo; luego vuelve y toca Conceder."
                        !Perms.shizukuGranted() -> Perms.shizukuAsk(1)
                        else -> scope.launch {
                            val ok = withContext(Dispatchers.IO) { Perms.grantAll(ctx) }
                            local++
                            msg = if (ok) "Permisos concedidos con Shizuku." else "No se pudieron conceder todos los permisos."
                        }
                    }
                }
                PermRow("Mostrar sobre otras apps", canOv, "Permitir") {
                    openSettings(ctx, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, data = true)
                }
                PermRow("Notificaciones", canNot, "Permitir") { askNotif() }
            }
        }

        // Opciones extra
        SectionTitle("Opciones extra (no cambian con los botones)")
        EXTRAS.forEachIndexed { i, (id, label) ->
            val a by animateFloatAsState(if (shown) 1f else 0f, tween(900, delayMillis = i * 200), label = "extra$i")
            Row(
                Modifier
                    .fillMaxWidth()
                    .offset(y = (12f * (1f - a)).dp)
                    .alpha(a)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CARD)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, color = INK, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = extras[id] == true,
                    onCheckedChange = { on ->
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { Applier.setExtra(ctx, id, on) }
                            extras[id] = if (ok) on else !on
                            if (!ok) {
                                if (id == "dnd") {
                                    msg = "Concede acceso a No molestar y vuelve a intentarlo."
                                    openSettings(ctx, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                } else msg = "No se pudo cambiar esa opción. Revisa los permisos."
                            }
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = OKC, checkedThumbColor = Color.White)
                )
            }
        }

        // Ventana flotante
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CARD).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Ventana flotante sobre el juego", color = INK, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = overlayOn,
                onCheckedChange = { on ->
                    if (on) {
                        if (!Perms.canOverlay(ctx)) {
                            msg = "Primero permite mostrar sobre otras apps."
                            openSettings(ctx, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, data = true)
                        } else {
                            ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))
                            overlayOn = true
                        }
                    } else {
                        ctx.stopService(Intent(ctx, OverlayService::class.java))
                        overlayOn = false
                    }
                },
                colors = SwitchDefaults.colors(checkedTrackColor = OKC, checkedThumbColor = Color.White)
            )
        }

        // Botones
        GhostButton("Restaurar valores originales") {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { Applier.restoreAll(ctx) }
                EXTRAS.forEach { extras[it.first] = false }
                msg = if (ok) "Valores originales restaurados." else "Algunos valores no se pudieron restaurar."
            }
        }
        GhostButton("Escanear de nuevo") { scanKey++ }

        var logOpen by remember { mutableStateOf(false) }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CARD)
                .border(1.dp, LINE, RoundedCornerShape(12.dp))
                .clickable { logOpen = !logOpen }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("REGISTRO (ADB)", color = MUT, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
            Text(if (logOpen) "Ocultar" else "Ver", color = OKC, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        AnimatedVisibility(logOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("adb logcat -s AuxilioDeMira", color = MUT, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                val logLines = remember(msg, scanRes, local, done) { Trace.lines().takeLast(25) }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CARD).padding(10.dp)) {
                    Text(
                        if (logLines.isEmpty()) "Sin registros todavía" else logLines.joinToString("\n"),
                        color = MUT, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                }
                GhostButton("Copiar registro") {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("registro", Trace.text()))
                    msg = "Registro copiado."
                }
            }
        }

        if (msg.isNotEmpty()) Text(msg, color = MUT, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(96.dp))
    }
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, BG)))
            .systemBarsPadding()
            .padding(16.dp)
    ) {
        val c = Color(level.color)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp))
                .background(Brush.horizontalGradient(listOf(c, c.copy(alpha = 0.7f))))
                .clickable { applyWithAnimation() }
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                (if (appliedLevel == level) "VOLVER A APLICAR " else "APLICAR SENSIBILIDAD ") + level.label.uppercase(),
                color = DARK, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, letterSpacing = 1.sp
            )
        }
    }
    if (loading) LoadingScreen(loadLevel, { prog }, { phase }, done) { loading = false }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 3.dp, height = 14.dp).background(OKC))
        Spacer(Modifier.width(8.dp))
        Text(t.uppercase(), color = MUT, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(LINE, Color.Transparent))))
    }
}

@Composable
private fun StatusChip(text: String, ok: Boolean) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(CARD)
            .border(1.dp, if (ok) OKC else BAD, RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (ok) OKC else BAD))
        Spacer(Modifier.width(6.dp))
        Text(text, color = INK, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .clip(CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
            .background(CARD)
            .border(1.dp, LINE, CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp))
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = OKC, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = MUT, fontSize = 10.sp)
    }
}

// Esquinas tipo HUD, como el marco del logo.
private fun Modifier.hud(color: Color): Modifier = drawBehind {
    val l = 14.dp.toPx()
    val w = 2.dp.toPx()
    val m = 6.dp.toPx()
    val x2 = size.width - m
    val y2 = size.height - m
    drawLine(color, Offset(m, m), Offset(m + l, m), w)
    drawLine(color, Offset(m, m), Offset(m, m + l), w)
    drawLine(color, Offset(x2, m), Offset(x2 - l, m), w)
    drawLine(color, Offset(x2, m), Offset(x2, m + l), w)
    drawLine(color, Offset(m, y2), Offset(m + l, y2), w)
    drawLine(color, Offset(m, y2), Offset(m, y2 - l), w)
    drawLine(color, Offset(x2, y2), Offset(x2 - l, y2), w)
    drawLine(color, Offset(x2, y2), Offset(x2, y2 - l), w)
}

@Composable
private fun SignalBars(n: Int, c: Color) {
    Canvas(Modifier.size(34.dp)) {
        val bw = size.width / 5f
        for (i in 0 until 3) {
            val h = size.height * (0.4f + 0.3f * i)
            drawRoundRect(
                if (i < n) c else LINE,
                topLeft = Offset(i * bw * 1.6f, size.height - h),
                size = Size(bw, h),
                cornerRadius = CornerRadius(3.dp.toPx())
            )
        }
    }
}

@Composable
private fun PulseDot(active: Boolean) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(
        1f, 0.25f,
        infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "alpha"
    )
    Box(Modifier.size(8.dp).alpha(if (active) a else 1f).clip(CircleShape).background(OKC))
}

@Composable
private fun LevelCard(l: Level, selected: Boolean, count: Int?, tag: String?, onClick: () -> Unit) {
    val c = Color(l.color)
    val border by animateColorAsState(if (selected) c else LINE, tween(450), label = "border")
    val scale by animateFloatAsState(
        if (selected) 1.02f else 1f,
        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow),
        label = "scale"
    )
    val glow by rememberInfiniteTransition(label = "glow").animateFloat(
        0.25f, 0.6f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "g"
    )
    val shape = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(if (selected) c.copy(alpha = 0.22f) else CARD, CARD)))
            .border(if (selected) 2.dp else 1.dp, border.copy(alpha = if (selected) 0.5f + glow * 0.5f else 1f), shape)
            .hud(if (selected) c else LINE)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SignalBars(l.ordinal + 1, c)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(l.label.uppercase(), color = INK, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, letterSpacing = 1.sp)
            Text(l.desc, color = MUT, fontSize = 12.sp)
            if (tag != null) Text(tag, color = c, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                val lit = when (l) { Level.BAJA -> 3; Level.MEDIA -> 6; Level.MAX -> 10 }
                for (i in 0 until 10) Box(Modifier.size(width = 10.dp, height = 4.dp).background(if (i < lit) c else LINE))
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(count?.toString() ?: "…", color = c, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            Text("comandos", color = MUT, fontSize = 10.sp)
        }
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) OKC else BAD))
        Spacer(Modifier.width(12.dp))
        Text(label, color = INK, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (ok) Text("Activo", color = OKC, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        else Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(ORANGE.copy(alpha = 0.18f))
                .border(1.dp, ORANGE, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) { Text(action, color = ORANGE, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun GhostButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CARD)
            .border(1.dp, LINE, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = INK, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
}

@Composable
private fun GhostChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CARD)
            .border(1.dp, LINE, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) { Text(text, color = INK, fontSize = 12.sp) }
}

@Composable
private fun LoadingScreen(l: Level, progOf: () -> Float, phaseOf: () -> String, done: Applier.ApplyResult?, onClose: () -> Unit) {
    val c = Color(l.color)
    val phase = phaseOf()
    val p by animateFloatAsState(progOf(), tween(250), label = "prog")
    val t = rememberInfiniteTransition(label = "load")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "spin")
    val pulse by t.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    val dots by t.animateFloat(0f, 4f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "dots")
    val dotCount by remember { derivedStateOf { dots.toInt().coerceIn(0, 3) } }
    val pct by remember { derivedStateOf { (p * 100).toInt() } }
    val pop by animateFloatAsState(
        if (done != null) 1f else 0f,
        spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessLow), label = "pop"
    )
    val phases = listOf("Preparando", "Aplicando comandos", "Reintentando por Shizuku", "Verificando")
    val idx = if (done != null) phases.size else phases.indexOf(phase).coerceAtLeast(0)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xEE030712))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .padding(32.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(CARD)
                .border(1.dp, if (done == null) LINE else c, RoundedCornerShape(24.dp))
                .hud(c)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = if (done == null) spin else 0f }) {
                    val sw = 6.dp.toPx()
                    if (done == null) {
                        drawArc(
                            brush = Brush.sweepGradient(listOf(Color.Transparent, c)),
                            startAngle = 0f, sweepAngle = 280f, useCenter = false,
                            topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw),
                            style = Stroke(sw, cap = StrokeCap.Round)
                        )
                    } else {
                        drawCircle(c, radius = (size.minDimension - sw) / 2f, style = Stroke(sw))
                    }
                }
                Image(
                    painterResource(R.drawable.logo_emblem), null,
                    Modifier.size(92.dp).graphicsLayer { val sc = if (done == null) pulse else 0.9f + 0.1f * pop; scaleX = sc; scaleY = sc }.clip(CircleShape)
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                if (done == null) "Aplicando sensibilidad ${l.label.lowercase()}" else "Sensibilidad ${l.label.lowercase()} aplicada",
                color = INK, fontWeight = FontWeight.Bold, fontSize = 16.sp
            )
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { p },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = c,
                trackColor = LINE
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                phases.forEachIndexed { i, _ ->
                    val col by animateColorAsState(
                        if (i < idx) c else if (i == idx) c.copy(alpha = 0.45f) else LINE,
                        tween(400), label = "step$i"
                    )
                    Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(col))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (done == null) phase + ".".repeat(dotCount) + " " + pct + "%"
                else "${done.ok} de ${done.ok + done.failed} comandos conservados",
                color = MUT, fontSize = 13.sp
            )
            if (done != null) {
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(c).clickable(onClick = onClose)
                        .padding(horizontal = 28.dp, vertical = 10.dp)
                ) { Text("Listo", color = DARK, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun LiveTiles() {
    val ctx = LocalContext.current
    var hz by remember { mutableStateOf(0f) }
    var temp by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            hz = dm.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 0f
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
            delay(2000)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("Pantalla ahora", if (hz > 0f) "${hz.toInt()} Hz" else "…", Modifier.weight(1f))
        StatTile("Temperatura", if (temp > 0f) "$temp °C" else "…", Modifier.weight(1f))
    }
}
