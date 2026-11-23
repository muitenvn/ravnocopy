package com.example.ui.components

import android.content.Context
import com.example.data.GamepadButtonPressMode
import org.json.JSONArray
import org.json.JSONObject

internal enum class PcKeyAccent {
    NEUTRAL,
    GREEN,
    CYAN
}

/**
 * Định nghĩa một phần tử trên lớp Bàn phím & Chuột ảo PC (tách biệt hoàn toàn với Tay cầm).
 */
internal data class PcVirtualNodeDef(
    val id: String,
    val keys: List<String>,
    val customLabel: String = "",
    val isAnalogWasd: Boolean = false,
    val isDefaultNode: Boolean = false,
    val defaultRelX: Float,
    val defaultRelY: Float,
    val defaultWidthDp: Float,
    val defaultHeightDp: Float,
    val accent: PcKeyAccent = PcKeyAccent.NEUTRAL
) {
    fun displayLabel(): String {
        if (customLabel.isNotBlank()) return customLabel
        if (isAnalogWasd) return "WASD"
        return when {
            keys.isEmpty() -> id
            keys.size == 1 -> keys.first()
            keys.size <= 3 -> keys.joinToString("+")
            else -> "${keys.take(2).joinToString("+")}+${keys.size - 2}"
        }
    }
}

internal data class PcNodeCustomization(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1.0f,
    val widthScale: Float = 1.0f,
    val heightScale: Float = 1.0f,
    val opacity: Float = 0.78f,
    val pressMode: GamepadButtonPressMode = GamepadButtonPressMode.NORMAL,
    val turboHz: Int = 10,
    val hidden: Boolean = false
) {
    fun hasCustomDimensions(): Boolean =
        kotlin.math.abs(widthScale - 1.0f) >= 0.02f || kotlin.math.abs(heightScale - 1.0f) >= 0.02f
}

internal data class PcLayoutProfile(
    val usePerGame: Boolean = false,
    val customNodes: List<PcVirtualNodeDef> = emptyList(),
    val customizations: Map<String, PcNodeCustomization> = emptyMap()
)

/**
 * Kho lưu trữ bố cục Bàn phím & Chuột PC độc lập (không chạm vào cấu hình Tay cầm ảo).
 * Hỗ trợ lưu Chung (Global) và Riêng cho từng game (Per-game).
 */
internal object PcOverlayLayoutStore {
    private const val PREF_NAME = "ravno_pc_kbm_layouts"
    private const val KEY_USE_PER_GAME = "pc_use_per_game"
    private const val KEY_GLOBAL_JSON = "pc_layout_global"
    private const val KEY_GAME_PREFIX = "pc_layout_game_"

    /**
     * Bố cục mặc định gọn gàng (ít nút, màn hình thoáng):
     * - Trái: Cần analog WASD (+khóa chạy nhanh Shift), Esc, Tab
     * - Phải: LMB, RMB, Space, E, R
     * Người dùng có thể nhấn "+ Thêm phím" để mở bàn phím Full-Size thêm bất kỳ nút/cụm nào.
     */
    val DEFAULT_PC_NODES: List<PcVirtualNodeDef> = listOf(
        PcVirtualNodeDef(
            id = "pc_wasd_stick",
            keys = listOf("W", "A", "S", "D"),
            customLabel = "WASD",
            isAnalogWasd = true,
            isDefaultNode = true,
            defaultRelX = 0.14f,
            defaultRelY = 0.73f,
            defaultWidthDp = 128f,
            defaultHeightDp = 128f,
            accent = PcKeyAccent.GREEN
        ),
        PcVirtualNodeDef(
            id = "pc_key_esc",
            keys = listOf("ESC"),
            customLabel = "Esc",
            isDefaultNode = true,
            defaultRelX = 0.09f,
            defaultRelY = 0.18f,
            defaultWidthDp = 52f,
            defaultHeightDp = 38f,
            accent = PcKeyAccent.NEUTRAL
        ),
        PcVirtualNodeDef(
            id = "pc_key_tab",
            keys = listOf("TAB"),
            customLabel = "Tab",
            isDefaultNode = true,
            defaultRelX = 0.17f,
            defaultRelY = 0.18f,
            defaultWidthDp = 52f,
            defaultHeightDp = 38f,
            accent = PcKeyAccent.NEUTRAL
        ),
        PcVirtualNodeDef(
            id = "pc_key_rmb",
            keys = listOf("RMB"),
            customLabel = "RMB",
            isDefaultNode = true,
            defaultRelX = 0.78f,
            defaultRelY = 0.62f,
            defaultWidthDp = 64f,
            defaultHeightDp = 50f,
            accent = PcKeyAccent.CYAN
        ),
        PcVirtualNodeDef(
            id = "pc_key_lmb",
            keys = listOf("LMB"),
            customLabel = "LMB",
            isDefaultNode = true,
            defaultRelX = 0.90f,
            defaultRelY = 0.62f,
            defaultWidthDp = 68f,
            defaultHeightDp = 50f,
            accent = PcKeyAccent.GREEN
        ),
        PcVirtualNodeDef(
            id = "pc_key_e",
            keys = listOf("E"),
            customLabel = "E",
            isDefaultNode = true,
            defaultRelX = 0.80f,
            defaultRelY = 0.44f,
            defaultWidthDp = 46f,
            defaultHeightDp = 44f,
            accent = PcKeyAccent.NEUTRAL
        ),
        PcVirtualNodeDef(
            id = "pc_key_r",
            keys = listOf("R"),
            customLabel = "R",
            isDefaultNode = true,
            defaultRelX = 0.90f,
            defaultRelY = 0.44f,
            defaultWidthDp = 46f,
            defaultHeightDp = 44f,
            accent = PcKeyAccent.NEUTRAL
        ),
        PcVirtualNodeDef(
            id = "pc_key_space",
            keys = listOf("SPACE"),
            customLabel = "Space",
            isDefaultNode = true,
            defaultRelX = 0.86f,
            defaultRelY = 0.82f,
            defaultWidthDp = 92f,
            defaultHeightDp = 46f,
            accent = PcKeyAccent.GREEN
        )
    )

    private fun normalizeKey(gameKey: String?): String =
        (gameKey ?: "").trim().lowercase().replace(Regex("[^a-z0-9_]+"), "_")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isUsePerGame(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_PER_GAME, false)

    fun setUsePerGame(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_PER_GAME, enabled).apply()
    }

    fun hasPerGameProfile(context: Context, gameKey: String?): Boolean {
        val norm = normalizeKey(gameKey)
        if (norm.isEmpty()) return false
        return prefs(context).contains("$KEY_GAME_PREFIX$norm")
    }

    fun loadProfile(context: Context, gameKey: String?): PcLayoutProfile {
        val sp = prefs(context)
        val usePerGame = sp.getBoolean(KEY_USE_PER_GAME, false)
        val norm = normalizeKey(gameKey)
        val raw = if (usePerGame && norm.isNotEmpty()) {
            sp.getString("$KEY_GAME_PREFIX$norm", null) ?: sp.getString(KEY_GLOBAL_JSON, null)
        } else {
            sp.getString(KEY_GLOBAL_JSON, null)
        }
        return parseJson(raw, usePerGame)
    }

    fun saveProfile(context: Context, gameKey: String?, profile: PcLayoutProfile) {
        val sp = prefs(context)
        val norm = normalizeKey(gameKey)
        val targetKey = if (profile.usePerGame && norm.isNotEmpty()) {
            "$KEY_GAME_PREFIX$norm"
        } else {
            KEY_GLOBAL_JSON
        }
        sp.edit()
            .putBoolean(KEY_USE_PER_GAME, profile.usePerGame)
            .putString(targetKey, serializeJson(profile))
            .apply()
    }

    fun clearPerGameProfile(context: Context, gameKey: String?) {
        val norm = normalizeKey(gameKey)
        if (norm.isEmpty()) return
        prefs(context).edit().remove("$KEY_GAME_PREFIX$norm").apply()
    }

    fun resetProfile(context: Context, gameKey: String?, usePerGame: Boolean) {
        val sp = prefs(context)
        val norm = normalizeKey(gameKey)
        val targetKey = if (usePerGame && norm.isNotEmpty()) {
            "$KEY_GAME_PREFIX$norm"
        } else {
            KEY_GLOBAL_JSON
        }
        sp.edit().remove(targetKey).apply()
    }

    private fun serializeJson(profile: PcLayoutProfile): String {
        val root = JSONObject()
        val nodesArr = JSONArray()
        for (n in profile.customNodes) {
            val o = JSONObject()
            o.put("id", n.id)
            val kArr = JSONArray()
            n.keys.forEach { kArr.put(it) }
            o.put("keys", kArr)
            o.put("label", n.customLabel)
            o.put("rx", n.defaultRelX.toDouble())
            o.put("ry", n.defaultRelY.toDouble())
            o.put("w", n.defaultWidthDp.toDouble())
            o.put("h", n.defaultHeightDp.toDouble())
            o.put("accent", n.accent.name)
            nodesArr.put(o)
        }
        root.put("customNodes", nodesArr)

        val custObj = JSONObject()
        for ((id, c) in profile.customizations) {
            val o = JSONObject()
            o.put("ox", c.offsetX.toDouble())
            o.put("oy", c.offsetY.toDouble())
            o.put("s", c.scale.toDouble())
            o.put("ws", c.widthScale.toDouble())
            o.put("hs", c.heightScale.toDouble())
            o.put("op", c.opacity.toDouble())
            o.put("pm", c.pressMode.name)
            o.put("thz", c.turboHz)
            o.put("hid", c.hidden)
            custObj.put(id, o)
        }
        root.put("cust", custObj)
        return root.toString()
    }

    private fun parseJson(raw: String?, usePerGame: Boolean): PcLayoutProfile {
        if (raw.isNullOrBlank()) return PcLayoutProfile(usePerGame = usePerGame)
        return try {
            val root = JSONObject(raw)
            val customNodes = mutableListOf<PcVirtualNodeDef>()
            val nodesArr = root.optJSONArray("customNodes")
            if (nodesArr != null) {
                for (i in 0 until nodesArr.length()) {
                    val o = nodesArr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isBlank()) continue
                    val kArr = o.optJSONArray("keys")
                    val keys = mutableListOf<String>()
                    if (kArr != null) {
                        for (j in 0 until kArr.length()) {
                            val k = kArr.optString(j)
                            if (k.isNotBlank()) keys.add(k)
                        }
                    }
                    if (keys.isEmpty()) continue
                    val accent = runCatching { PcKeyAccent.valueOf(o.optString("accent", "NEUTRAL")) }
                        .getOrDefault(PcKeyAccent.NEUTRAL)
                    customNodes.add(
                        PcVirtualNodeDef(
                            id = id,
                            keys = keys,
                            customLabel = o.optString("label", ""),
                            isAnalogWasd = false,
                            isDefaultNode = false,
                            defaultRelX = o.optDouble("rx", 0.5).toFloat().coerceIn(0.05f, 0.95f),
                            defaultRelY = o.optDouble("ry", 0.5).toFloat().coerceIn(0.10f, 0.90f),
                            defaultWidthDp = o.optDouble("w", 50.0).toFloat().coerceIn(36f, 140f),
                            defaultHeightDp = o.optDouble("h", 44.0).toFloat().coerceIn(34f, 110f),
                            accent = accent
                        )
                    )
                }
            }

            val custMap = mutableMapOf<String, PcNodeCustomization>()
            val custObj = root.optJSONObject("cust")
            if (custObj != null) {
                val it = custObj.keys()
                while (it.hasNext()) {
                    val id = it.next()
                    val o = custObj.optJSONObject(id) ?: continue
                    val pm = runCatching { GamepadButtonPressMode.valueOf(o.optString("pm", "NORMAL")) }
                        .getOrDefault(GamepadButtonPressMode.NORMAL)
                    custMap[id] = PcNodeCustomization(
                        offsetX = o.optDouble("ox", 0.0).toFloat(),
                        offsetY = o.optDouble("oy", 0.0).toFloat(),
                        scale = o.optDouble("s", 1.0).toFloat().coerceIn(0.55f, 1.90f),
                        widthScale = o.optDouble("ws", 1.0).toFloat().coerceIn(0.50f, 2.50f),
                        heightScale = o.optDouble("hs", 1.0).toFloat().coerceIn(0.50f, 2.50f),
                        opacity = o.optDouble("op", 0.78).toFloat().coerceIn(0.20f, 1.00f),
                        pressMode = pm,
                        turboHz = o.optInt("thz", 10).coerceIn(4, 20),
                        hidden = o.optBoolean("hid", false)
                    )
                }
            }

            PcLayoutProfile(
                usePerGame = usePerGame,
                customNodes = customNodes,
                customizations = custMap
            )
        } catch (_: Exception) {
            PcLayoutProfile(usePerGame = usePerGame)
        }
    }
}
