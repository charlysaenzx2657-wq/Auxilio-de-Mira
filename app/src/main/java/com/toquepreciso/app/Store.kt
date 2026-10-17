package com.toquepreciso.app

import android.content.Context
import org.json.JSONObject

enum class Level(val label: String, val desc: String, val color: Int) {
    BAJA("Baja", "Más control, giro lento", 0xFF5B8DEF.toInt()),
    MEDIA("Media", "Equilibrio entre calibrado y preciso", 0xFF18B8A6.toInt()),
    MAX("Máxima", "Deslizamiento muy rápido", 0xFFFF7A1A.toInt())
}

class Knob(
    val id: String,
    val label: String,
    val min: Float,
    val max: Float,
    val step: Float,
    val fmt: (Float) -> String
)

object Knobs {
    val all = listOf(
        Knob("ptr", "Velocidad del puntero", -7f, 7f, 1f) { v -> (if (v > 0) "+" else "") + v.toInt() },
        Knob("lp", "Pulsación larga", 100f, 800f, 50f) { v -> "${v.toInt()} ms" },
        Knob("dt", "Doble toque", 100f, 500f, 50f) { v -> "${v.toInt()} ms" },
        Knob("an", "Escala de animaciones", 0f, 1f, 0.25f) { v -> if (v == 0f) "Apagadas" else "${v}x" },
        Knob("hz", "Tasa de refresco", 60f, 120f, 30f) { v -> "${v.toInt()} Hz" },
        Knob("hp", "Vibración al tocar", 0f, 2f, 1f) { v -> listOf("Apagada", "Suave", "Fuerte")[v.toInt()] },
        Knob("fr", "Fricción de deslizamiento", 0.5f, 2f, 0.25f) { v -> "${v}x" },
        Knob("sv", "Velocidad de desplazamiento", 0.5f, 2f, 0.25f) { v -> "${v}x" }
    )

    val defaults: Map<Level, Map<String, Float>> = mapOf(
        Level.BAJA to mapOf("ptr" to -3f, "lp" to 500f, "dt" to 300f, "an" to 1f, "hz" to 60f, "hp" to 2f, "fr" to 1.5f, "sv" to 0.75f),
        Level.MEDIA to mapOf("ptr" to -2f, "lp" to 300f, "dt" to 250f, "an" to 0.5f, "hz" to 90f, "hp" to 1f, "fr" to 1f, "sv" to 1f),
        Level.MAX to mapOf("ptr" to -1f, "lp" to 200f, "dt" to 200f, "an" to 0f, "hz" to 120f, "hp" to 0f, "fr" to 0.5f, "sv" to 1.5f)
    )
}

val EXTRAS = listOf(
    "edge" to "Gestos de borde menos sensibles",
    "hz" to "Fijar tasa de refresco alta",
    "rot" to "Bloquear rotación",
    "dnd" to "No molestar al jugar"
)

class Store(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("toque", Context.MODE_PRIVATE)

    var level: Level
        get() = runCatching { Level.valueOf(p.getString("level", "MEDIA")!!) }.getOrDefault(Level.MEDIA)
        set(v) { p.edit().putString("level", v.name).apply() }

    fun value(l: Level, k: Knob): Float =
        p.getFloat("v_${l.name}_${k.id}", Knobs.defaults[l]!![k.id]!!)

    fun setValue(l: Level, k: Knob, v: Float) {
        p.edit().putFloat("v_${l.name}_${k.id}", v.coerceIn(k.min, k.max)).apply()
    }

    fun extra(id: String): Boolean = p.getBoolean("x_$id", false)
    fun setExtra(id: String, on: Boolean) { p.edit().putBoolean("x_$id", on).apply() }

    var opacity: Int
        get() = p.getInt("opacity", 100)
        set(v) { p.edit().putInt("opacity", v).apply() }

    var snap: Boolean
        get() = p.getBoolean("snap", true)
        set(v) { p.edit().putBoolean("snap", v).apply() }

    var dndPrev: Int
        get() = p.getInt("dnd_prev", -1)
        set(v) { p.edit().putInt("dnd_prev", v).apply() }

    var posX: Int
        get() = p.getInt("pos_x", -1)
        set(v) { p.edit().putInt("pos_x", v).apply() }

    var posY: Int
        get() = p.getInt("pos_y", -1)
        set(v) { p.edit().putInt("pos_y", v).apply() }

    fun backup(): JSONObject = JSONObject(p.getString("backup", "{}") ?: "{}")
    fun saveBackup(o: JSONObject) { p.edit().putString("backup", o.toString()).apply() }
}
