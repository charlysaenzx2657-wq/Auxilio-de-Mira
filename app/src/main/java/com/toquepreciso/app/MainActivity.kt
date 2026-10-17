package com.toquepreciso.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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

private val BG = Color(0xFF12182C)
private val CARD = Color(0xFF1C2542)
private val LINE = Color(0xFF2C3760)
private val INK = Color(0xFFEEF2FF)
private val MUT = Color(0xFF93A0C8)
private val DARK = Color(0xFF0B0F1D)
private val OKC = Color(0xFF18B8A6)
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
            val r = withContext(Dispatchers.IO) { Applier.apply(ctx, l, true) { f, t -> prog = f; phase = t } }
            prog = 1f
            done = r
            report(r, l)
        }
    }

    fun choose(l: Level) {
        level = l
        store.level = l
        runApply(l)
    }

    fun applyWithAnimation() {
        runApply(level)
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Auxilio de Mira", color = INK, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Free Fire", color = MUT, fontSize = 12.sp)
        }

        // Escaneo
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CARD)
                .border(1.dp, LINE, RoundedCornerShape(12.dp))
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
        Level.values().forEach { l -> LevelCard(l, l == level, counts[l]) { choose(l) } }

        // Permisos
        SectionTitle("Permisos")
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CARD).padding(horizontal = 14.dp, vertical = 6.dp)) {
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
        val c = Color(level.color)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.copy(alpha = 0.4f))
                .drawBehind { drawRect(c, size = Size(size.width * fill.value, size.height)) }
                .clickable { applyWithAnimation() }
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) { Text("Aplicar sensibilidad", color = DARK, fontWeight = FontWeight.Bold, fontSize = 14.sp) }

        GhostButton("Restaurar valores originales") {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { Applier.restoreAll(ctx) }
                EXTRAS.forEach { extras[it.first] = false }
                msg = if (ok) "Valores originales restaurados." else "Algunos valores no se pudieron restaurar."
            }
        }
        GhostButton("Escanear de nuevo") { scanKey++ }

        SectionTitle("Registro (ADB: adb logcat -s AuxilioDeMira)")
        val logLines = remember(msg, scanRes, local) { Trace.lines().takeLast(40) }
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

        if (msg.isNotEmpty()) Text(msg, color = MUT, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
    }
    if (loading) LoadingScreen(loadLevel, prog, phase, done) { loading = false }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, color = MUT, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
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
private fun LevelCard(l: Level, selected: Boolean, count: Int?, onClick: () -> Unit) {
    val c = Color(l.color)
    val border by animateColorAsState(if (selected) c else Color.Transparent, tween(450), label = "border")
    val scale by animateFloatAsState(
        if (selected) 1.02f else 1f,
        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow),
        label = "scale"
    )
    val dotH by animateDpAsState(if (selected) 46.dp else 36.dp, tween(500), label = "dot")
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(shape)
            .background(if (selected) c.copy(alpha = 0.14f).compositeOver(CARD) else CARD)
            .border(2.dp, border, shape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(12.dp).height(dotH).clip(RoundedCornerShape(6.dp)).background(c))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(l.label, color = INK, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(l.desc, color = MUT, fontSize = 12.sp)
            if (count != null) Text("$count comandos disponibles", color = c, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✓" else "✗", color = if (ok) OKC else BAD, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
        Text(label, color = INK, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (!ok) TextButton(onClick = onClick) { Text(action, color = ORANGE, fontSize = 12.sp) }
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
private fun LoadingScreen(l: Level, prog: Float, phase: String, done: Applier.ApplyResult?, onClose: () -> Unit) {
    val c = Color(l.color)
    val p by animateFloatAsState(prog, tween(300), label = "prog")
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE60B0F1D))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .padding(32.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(CARD)
                .border(1.dp, LINE, RoundedCornerShape(20.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (done == null) "Aplicando sensibilidad ${l.label.lowercase()}" else "Sensibilidad ${l.label.lowercase()} aplicada",
                color = INK, fontWeight = FontWeight.Bold, fontSize = 16.sp
            )
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { p },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = c,
                trackColor = LINE
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (done == null) "$phase ${(p * 100).toInt()}%" else "${done.ok} de ${done.ok + done.failed} comandos conservados",
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
