package com.zerointerrupt.clicker.model

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import com.zerointerrupt.clicker.BuildConfig
import com.zerointerrupt.clicker.ClickTargetParceled
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import kotlin.math.max

enum class ActionType(val code: Int, val label: String) {
    TAP(0, "Otomatik dokunma"),
    SWIPE(1, "Kaydırma"),
    HOLD(2, "Uzun basma"),
    SUB_PRESET(3, "Eşzamanlı plan");

    companion object {
        fun fromCode(code: Int): ActionType = entries.firstOrNull { it.code == code } ?: TAP
    }
}

enum class ExecutionMode(val code: Int, val label: String) {
    CONCURRENT(0, "Eşzamanlı"),
    SEQUENTIAL(1, "Sıralı hareketler");

    companion object {
        fun fromCode(code: Int): ExecutionMode = entries.firstOrNull { it.code == code } ?: CONCURRENT
    }
}

/**
 * Click/Swipe/Hold/SubPreset target step configuration with normalized coordinates and Parcelable support.
 */
data class ClickTarget(
    val id: Int,
    var index: Int,
    var x: Float,
    var y: Float,
    var normX: Float = -1f,
    var normY: Float = -1f,
    var intervalMs: Long = DEFAULT_INTERVAL_MS,
    var pressDurationMs: Long = DEFAULT_PRESS_DURATION_MS,
    var microJitter: Boolean = true,
    var enabled: Boolean = true,
    var actionType: ActionType = ActionType.TAP,
    var endX: Float = x,
    var endY: Float = y,
    var endNormX: Float = -1f,
    var endNormY: Float = -1f,
    var swipeDurationMs: Long = DEFAULT_SWIPE_DURATION_MS,
    var repeatCount: Int = DEFAULT_REPEAT_COUNT,
    var refPresetId: String = "",
    var subDurationMs: Long = DEFAULT_SUB_DURATION_MS
) : Parcelable {

    constructor(parcel: Parcel) : this(
        id = parcel.readInt(),
        index = parcel.readInt(),
        x = parcel.readFloat(),
        y = parcel.readFloat(),
        normX = parcel.readFloat(),
        normY = parcel.readFloat(),
        intervalMs = parcel.readLong(),
        pressDurationMs = parcel.readLong(),
        microJitter = parcel.readByte().toInt() != 0,
        enabled = parcel.readByte().toInt() != 0,
        actionType = ActionType.fromCode(parcel.readInt()),
        endX = parcel.readFloat(),
        endY = parcel.readFloat(),
        endNormX = parcel.readFloat(),
        endNormY = parcel.readFloat(),
        swipeDurationMs = parcel.readLong(),
        repeatCount = parcel.readInt().coerceIn(MIN_REPEAT_COUNT, MAX_REPEAT_COUNT),
        refPresetId = parcel.readString().orEmpty(),
        subDurationMs = parcel.readLong().coerceIn(MIN_SUB_DURATION_MS, MAX_SUB_DURATION_MS)
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(id)
        parcel.writeInt(index)
        parcel.writeFloat(x)
        parcel.writeFloat(y)
        parcel.writeFloat(normX)
        parcel.writeFloat(normY)
        parcel.writeLong(intervalMs)
        parcel.writeLong(pressDurationMs)
        parcel.writeByte(if (microJitter) 1 else 0)
        parcel.writeByte(if (enabled) 1 else 0)
        parcel.writeInt(actionType.code)
        parcel.writeFloat(endX)
        parcel.writeFloat(endY)
        parcel.writeFloat(endNormX)
        parcel.writeFloat(endNormY)
        parcel.writeLong(swipeDurationMs)
        parcel.writeInt(repeatCount.coerceIn(MIN_REPEAT_COUNT, MAX_REPEAT_COUNT))
        parcel.writeString(refPresetId)
        parcel.writeLong(subDurationMs.coerceIn(MIN_SUB_DURATION_MS, MAX_SUB_DURATION_MS))
    }

    override fun describeContents(): Int = 0

    fun setStartPixel(px: Float, py: Float, screenW: Int, screenH: Int) {
        val maxX = max(1, screenW - 1).toFloat()
        val maxY = max(1, screenH - 1).toFloat()
        x = px.coerceIn(0f, maxX)
        y = py.coerceIn(0f, maxY)
        normX = (x / maxX).coerceIn(0f, 1f)
        normY = (y / maxY).coerceIn(0f, 1f)
    }

    fun setEndPixel(px: Float, py: Float, screenW: Int, screenH: Int) {
        val maxX = max(1, screenW - 1).toFloat()
        val maxY = max(1, screenH - 1).toFloat()
        endX = px.coerceIn(0f, maxX)
        endY = py.coerceIn(0f, maxY)
        endNormX = (endX / maxX).coerceIn(0f, 1f)
        endNormY = (endY / maxY).coerceIn(0f, 1f)
    }

    fun ensureDefaultSwipeEndpoint(screenW: Int, screenH: Int) {
        val dx = endX - x
        val dy = endY - y
        if ((dx * dx + dy * dy) < 16f) {
            val offsetX = (screenW * 0.14f).coerceIn(100f, 240f)
            val candidateX = if (x + offsetX <= screenW - 60f) x + offsetX else x - offsetX
            setEndPixel(candidateX, y, screenW, screenH)
        } else if (endNormX !in 0f..1f || endNormY !in 0f..1f) {
            setEndPixel(endX, endY, screenW, screenH)
        }
    }

    /**
     * Remaps pixel coordinates cleanly from saved normalized coordinates for the given viewport
     * WITHOUT overwriting (normX, normY) when clamping across rotations.
     */
    fun remapToScreen(screenW: Int, screenH: Int) {
        val maxX = max(1, screenW - 1).toFloat()
        val maxY = max(1, screenH - 1).toFloat()
        if (normX in 0f..1f && normY in 0f..1f) {
            x = (normX * maxX).coerceIn(0f, maxX)
            y = (normY * maxY).coerceIn(0f, maxY)
        } else {
            x = x.coerceIn(0f, maxX)
            y = y.coerceIn(0f, maxY)
            normX = (x / maxX).coerceIn(0f, 1f)
            normY = (y / maxY).coerceIn(0f, 1f)
        }
        if (endNormX in 0f..1f && endNormY in 0f..1f) {
            endX = (endNormX * maxX).coerceIn(0f, maxX)
            endY = (endNormY * maxY).coerceIn(0f, maxY)
        } else if (actionType == ActionType.SWIPE) {
            endX = endX.coerceIn(0f, maxX)
            endY = endY.coerceIn(0f, maxY)
            endNormX = (endX / maxX).coerceIn(0f, 1f)
            endNormY = (endY / maxY).coerceIn(0f, 1f)
        } else {
            endX = x
            endY = y
        }
    }

    fun toParceled(
        stageIndex: Int = 0,
        stageDurationMs: Long = 0L,
        stageGapMs: Long = this.intervalMs,
        stageRepeatCount: Int = this.repeatCount,
        holdInId: Int = 0,
        holdOutId: Int = 0
    ): ClickTargetParceled = ClickTargetParceled().apply {
        id = this@ClickTarget.id
        x = this@ClickTarget.x
        y = this@ClickTarget.y
        intervalMs = this@ClickTarget.intervalMs.coerceIn(MIN_STAGE_GAP_MS, MAX_INTERVAL_MS)
        pressDurationMs = this@ClickTarget.pressDurationMs.coerceIn(MIN_PRESS_MS, MAX_PRESS_MS)
        microJitter = this@ClickTarget.microJitter
        enabled = this@ClickTarget.enabled
        actionType = this@ClickTarget.actionType.code
        endX = this@ClickTarget.endX
        endY = this@ClickTarget.endY
        swipeDurationMs = this@ClickTarget.swipeDurationMs.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS)
        repeatCount = this@ClickTarget.repeatCount.coerceIn(MIN_REPEAT_COUNT, MAX_REPEAT_COUNT)
        this.stageIndex = stageIndex
        this.stageDurationMs = stageDurationMs.coerceAtLeast(0L)
        this.stageGapMs = stageGapMs.coerceIn(MIN_STAGE_GAP_MS, MAX_INTERVAL_MS)
        this.stageRepeatCount = stageRepeatCount.coerceIn(MIN_REPEAT_COUNT, MAX_REPEAT_COUNT)
        this.holdInId = holdInId.coerceAtLeast(0)
        this.holdOutId = holdOutId.coerceAtLeast(0)
    }

    companion object {
        const val MIN_STAGE_GAP_MS = 0L
        const val MIN_INTERVAL_MS = 5L
        const val MAX_INTERVAL_MS = 60_000L
        const val DEFAULT_INTERVAL_MS = 80L

        const val MIN_PRESS_MS = 8L
        const val MAX_PRESS_MS = 30_000L
        const val DEFAULT_PRESS_DURATION_MS = 25L

        const val MIN_SWIPE_MS = 8L
        const val MAX_SWIPE_MS = 15_000L
        const val DEFAULT_SWIPE_DURATION_MS = 320L

        const val MIN_REPEAT_COUNT = 0
        const val MAX_REPEAT_COUNT = 9999
        const val DEFAULT_REPEAT_COUNT = 0

        const val MIN_SUB_DURATION_MS = 8L
        const val MAX_SUB_DURATION_MS = 600_000L
        const val DEFAULT_SUB_DURATION_MS = 3_000L

        const val SEAMLESS_HOLD_GAP_MS = 80L
        const val SEAMLESS_HOLD_DIST_PX = 20f

        fun defaultRepeatFor(executionMode: ExecutionMode): Int =
            if (executionMode == ExecutionMode.CONCURRENT) 0 else 1

        @JvmField
        val CREATOR = object : Parcelable.Creator<ClickTarget> {
            override fun createFromParcel(parcel: Parcel): ClickTarget = ClickTarget(parcel)
            override fun newArray(size: Int): Array<ClickTarget?> = arrayOfNulls(size)
        }
    }
}

data class ClickPreset(
    val id: String,
    var name: String,
    var executionMode: ExecutionMode = ExecutionMode.CONCURRENT,
    var presetLoopCount: Int = 0,
    var isFrozen: Boolean = false,
    var parentPresetId: String = "",
    var isRecordedMacro: Boolean = false,
    val targets: MutableList<ClickTarget> = mutableListOf()
)

data class ClickGlobalSettings(
    val defaultIntervalMs: Long = ClickTarget.DEFAULT_INTERVAL_MS,
    val defaultPressDurationMs: Long = ClickTarget.DEFAULT_PRESS_DURATION_MS,
    val tuningTier: Int = TIER_CUSTOM,
    val customIntervalMs: Long = ClickTarget.DEFAULT_INTERVAL_MS,
    val customPressDurationMs: Long = ClickTarget.DEFAULT_PRESS_DURATION_MS,
    val defaultMicroJitter: Boolean = true,
    val jitterRadiusPx: Float = 4.0f,
    val selfCheckPassed: Boolean = false,
    val autoLockOnStart: Boolean = true,
    val autoCollapseBar: Boolean = false,
    val showMacroTrajectories: Boolean = true,
    val lockedAnchorAlpha: Float = DEFAULT_LOCKED_ALPHA,
    val themeMode: Int = THEME_SYSTEM,
    val dynamicColor: Boolean = true,
    val launcherIconMode: Int = LAUNCHER_SYSTEM,
    val launcherDynamicIcon: Boolean = false,
    val volumeKeyToggleEnabled: Boolean = true,
    val proModeEnabled: Boolean = false,
    val preferredBackend: Int = BACKEND_AUTO,
    val rpcBridgeEnabled: Boolean = false,
    val rpcToken: String = ""
) {
    companion object {
        const val DEFAULT_LOCKED_ALPHA = 0.65f
        const val MIN_LOCKED_ALPHA = 0.35f
        const val MAX_LOCKED_ALPHA = 0.75f

        const val TIER_CASUAL = 0
        const val TIER_STANDARD = 1
        const val TIER_FAST = 2
        const val TIER_CUSTOM = 3

        const val CASUAL_INTERVAL_MS = 150L
        const val CASUAL_PRESS_MS = 45L
        const val STANDARD_INTERVAL_MS = 60L
        const val STANDARD_PRESS_MS = 24L
        const val FAST_INTERVAL_MS = 20L
        const val FAST_PRESS_MS = 10L

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        const val LAUNCHER_SYSTEM = 0
        const val LAUNCHER_DARK = 1
        const val LAUNCHER_LIGHT = 2

        const val BACKEND_AUTO = 0
        const val BACKEND_UINPUT = 1
        const val BACKEND_UHID = 2
        const val BACKEND_HYBRID = 3
        const val BACKEND_FALLBACK = 4
    }
}

object ClickConfigStore {
    private const val PREF_NAME = "zero_interrupt_clicker_prefs"
    private const val KEY_TARGETS = "saved_targets_json"
    private const val KEY_PRESETS_JSON = "saved_presets_json"
    private const val KEY_ACTIVE_PRESET_ID = "active_preset_id"
    private const val DEFAULT_PRESET_ID = "preset_1"

    private const val KEY_DEFAULT_INTERVAL = "default_interval_ms"
    private const val KEY_DEFAULT_PRESS = "default_press_ms"
    private const val KEY_TUNING_TIER = "tuning_tier"
    private const val KEY_CUSTOM_INTERVAL = "custom_interval_ms"
    private const val KEY_CUSTOM_PRESS = "custom_press_ms"
    private const val KEY_DEFAULT_JITTER = "default_jitter"
    private const val KEY_JITTER_RADIUS = "jitter_radius_px"
    private const val KEY_SELF_CHECK_PASSED = "self_check_passed"
    private const val KEY_AUTO_LOCK_ON_START = "auto_lock_on_start"
    private const val KEY_AUTO_COLLAPSE_BAR = "auto_collapse_bar"
    private const val KEY_SHOW_MACRO_TRAJECTORIES = "show_macro_trajectories"
    private const val KEY_LOCKED_ANCHOR_ALPHA = "locked_anchor_alpha"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    private const val KEY_LAUNCHER_ICON_MODE = "launcher_icon_mode"
    private const val KEY_LAUNCHER_DYNAMIC_ICON = "launcher_dynamic_icon"
    private const val KEY_VOL_KEY_TOGGLE = "volume_key_toggle_enabled"
    private const val KEY_PRO_MODE = "pro_mode_enabled"
    private const val KEY_PREFERRED_BACKEND = "preferred_backend"
    private const val KEY_RPC_BRIDGE_ENABLED = "rpc_bridge_enabled"
    private const val KEY_RPC_TOKEN = "rpc_token"
    private const val KEY_MIGRATED_CONCURRENT_ZERO = "migrated_concurrent_zero_v3"

    private const val KEY_CONTROL_BAR_X = "control_bar_x"
    private const val KEY_CONTROL_BAR_Y = "control_bar_y"
    private const val KEY_CONTROL_BAR_DOCK_RIGHT = "control_bar_dock_right"
    private const val KEY_CONTROL_BAR_COLLAPSED = "control_bar_collapsed"

    private val secureRandom = SecureRandom()

    fun generateRpcToken(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
        val sb = StringBuilder("zi_")
        repeat(20) {
            sb.append(alphabet[secureRandom.nextInt(alphabet.length)])
        }
        return sb.toString()
    }

    fun loadSettings(context: Context): ClickGlobalSettings {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        var token = prefs.getString(KEY_RPC_TOKEN, "") ?: ""
        if (token.isBlank()) {
            token = generateRpcToken()
            prefs.edit().putString(KEY_RPC_TOKEN, token).apply()
        }
        val defInterval = prefs.getLong(KEY_DEFAULT_INTERVAL, ClickTarget.DEFAULT_INTERVAL_MS)
            .coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS)
        val defPress = prefs.getLong(KEY_DEFAULT_PRESS, ClickTarget.DEFAULT_PRESS_DURATION_MS)
            .coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS)
        val inferredTier = when {
            defInterval == ClickGlobalSettings.CASUAL_INTERVAL_MS && defPress == ClickGlobalSettings.CASUAL_PRESS_MS ->
                ClickGlobalSettings.TIER_CASUAL
            defInterval == ClickGlobalSettings.STANDARD_INTERVAL_MS && defPress == ClickGlobalSettings.STANDARD_PRESS_MS ->
                ClickGlobalSettings.TIER_STANDARD
            defInterval == ClickGlobalSettings.FAST_INTERVAL_MS && defPress == ClickGlobalSettings.FAST_PRESS_MS ->
                ClickGlobalSettings.TIER_FAST
            else -> ClickGlobalSettings.TIER_CUSTOM
        }
        val tuningTier = if (prefs.contains(KEY_TUNING_TIER)) {
            prefs.getInt(KEY_TUNING_TIER, inferredTier)
                .coerceIn(ClickGlobalSettings.TIER_CASUAL, ClickGlobalSettings.TIER_CUSTOM)
        } else {
            inferredTier
        }
        val customInterval = prefs.getLong(KEY_CUSTOM_INTERVAL, defInterval)
            .coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS)
        val customPress = prefs.getLong(KEY_CUSTOM_PRESS, defPress)
            .coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS)
        return ClickGlobalSettings(
            defaultIntervalMs = defInterval,
            defaultPressDurationMs = defPress,
            tuningTier = tuningTier,
            customIntervalMs = customInterval,
            customPressDurationMs = customPress,
            defaultMicroJitter = prefs.getBoolean(KEY_DEFAULT_JITTER, true),
            jitterRadiusPx = prefs.getFloat(KEY_JITTER_RADIUS, 4.0f).coerceIn(0f, 16f),
            selfCheckPassed = prefs.getBoolean(KEY_SELF_CHECK_PASSED, false),
            autoLockOnStart = prefs.getBoolean(KEY_AUTO_LOCK_ON_START, true),
            autoCollapseBar = prefs.getBoolean(KEY_AUTO_COLLAPSE_BAR, false),
            showMacroTrajectories = prefs.getBoolean(KEY_SHOW_MACRO_TRAJECTORIES, true),
            lockedAnchorAlpha = prefs.getFloat(KEY_LOCKED_ANCHOR_ALPHA, ClickGlobalSettings.DEFAULT_LOCKED_ALPHA)
                .coerceIn(ClickGlobalSettings.MIN_LOCKED_ALPHA, ClickGlobalSettings.MAX_LOCKED_ALPHA),
            themeMode = prefs.getInt(KEY_THEME_MODE, ClickGlobalSettings.THEME_SYSTEM)
                .coerceIn(ClickGlobalSettings.THEME_SYSTEM, ClickGlobalSettings.THEME_DARK),
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, true),
            launcherIconMode = prefs.getInt(KEY_LAUNCHER_ICON_MODE, ClickGlobalSettings.LAUNCHER_SYSTEM)
                .coerceIn(ClickGlobalSettings.LAUNCHER_SYSTEM, ClickGlobalSettings.LAUNCHER_LIGHT),
            launcherDynamicIcon = prefs.getBoolean(KEY_LAUNCHER_DYNAMIC_ICON, false),
            volumeKeyToggleEnabled = prefs.getBoolean(KEY_VOL_KEY_TOGGLE, true),
            proModeEnabled = prefs.getBoolean(KEY_PRO_MODE, false),
            preferredBackend = prefs.getInt(KEY_PREFERRED_BACKEND, ClickGlobalSettings.BACKEND_AUTO)
                .coerceIn(ClickGlobalSettings.BACKEND_AUTO, ClickGlobalSettings.BACKEND_FALLBACK),
            rpcBridgeEnabled = prefs.getBoolean(KEY_RPC_BRIDGE_ENABLED, false),
            rpcToken = token
        )
    }

    fun saveSettings(context: Context, settings: ClickGlobalSettings) {
        val safeToken = settings.rpcToken.ifBlank { generateRpcToken() }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_DEFAULT_INTERVAL, settings.defaultIntervalMs)
            .putLong(KEY_DEFAULT_PRESS, settings.defaultPressDurationMs)
            .putInt(KEY_TUNING_TIER, settings.tuningTier)
            .putLong(KEY_CUSTOM_INTERVAL, settings.customIntervalMs)
            .putLong(KEY_CUSTOM_PRESS, settings.customPressDurationMs)
            .putBoolean(KEY_DEFAULT_JITTER, settings.defaultMicroJitter)
            .putFloat(KEY_JITTER_RADIUS, settings.jitterRadiusPx.coerceIn(0f, 16f))
            .putBoolean(KEY_SELF_CHECK_PASSED, settings.selfCheckPassed)
            .putBoolean(KEY_AUTO_LOCK_ON_START, settings.autoLockOnStart)
            .putBoolean(KEY_AUTO_COLLAPSE_BAR, settings.autoCollapseBar)
            .putBoolean(KEY_SHOW_MACRO_TRAJECTORIES, settings.showMacroTrajectories)
            .putFloat(
                KEY_LOCKED_ANCHOR_ALPHA,
                settings.lockedAnchorAlpha.coerceIn(
                    ClickGlobalSettings.MIN_LOCKED_ALPHA,
                    ClickGlobalSettings.MAX_LOCKED_ALPHA
                )
            )
            .putInt(KEY_THEME_MODE, settings.themeMode)
            .putBoolean(KEY_DYNAMIC_COLOR, settings.dynamicColor)
            .putInt(
                KEY_LAUNCHER_ICON_MODE,
                settings.launcherIconMode.coerceIn(
                    ClickGlobalSettings.LAUNCHER_SYSTEM,
                    ClickGlobalSettings.LAUNCHER_LIGHT
                )
            )
            .putBoolean(KEY_LAUNCHER_DYNAMIC_ICON, settings.launcherDynamicIcon)
            .putBoolean(KEY_VOL_KEY_TOGGLE, settings.volumeKeyToggleEnabled)
            .putBoolean(KEY_PRO_MODE, settings.proModeEnabled)
            .putInt(KEY_PREFERRED_BACKEND, settings.preferredBackend)
            .putBoolean(KEY_RPC_BRIDGE_ENABLED, settings.rpcBridgeEnabled)
            .putString(KEY_RPC_TOKEN, safeToken)
            .apply()
    }

    fun hasLauncherIconModePref(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).contains(KEY_LAUNCHER_ICON_MODE)

    fun markSelfCheckPassed(context: Context, passed: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SELF_CHECK_PASSED, passed)
            .apply()
    }

    private fun jsonArrayToTargets(array: JSONArray?, defaultRepeat: Int = ClickTarget.DEFAULT_REPEAT_COUNT): MutableList<ClickTarget> {
        if (array == null) return mutableListOf()
        val result = mutableListOf<ClickTarget>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val px = obj.optDouble("x", 0.0).toFloat()
            val py = obj.optDouble("y", 0.0).toFloat()
            val act = ActionType.fromCode(obj.optInt("actionType", 0))
            result += ClickTarget(
                id = obj.optInt("id", i + 1),
                index = i + 1,
                x = px,
                y = py,
                normX = obj.optDouble("normX", -1.0).toFloat(),
                normY = obj.optDouble("normY", -1.0).toFloat(),
                intervalMs = obj.optLong("intervalMs", ClickTarget.DEFAULT_INTERVAL_MS)
                    .coerceIn(ClickTarget.MIN_STAGE_GAP_MS, ClickTarget.MAX_INTERVAL_MS),
                pressDurationMs = obj.optLong("pressDurationMs", ClickTarget.DEFAULT_PRESS_DURATION_MS)
                    .coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS),
                microJitter = obj.optBoolean("microJitter", true),
                enabled = obj.optBoolean("enabled", true),
                actionType = act,
                endX = obj.optDouble("endX", px.toDouble()).toFloat(),
                endY = obj.optDouble("endY", py.toDouble()).toFloat(),
                endNormX = obj.optDouble("endNormX", -1.0).toFloat(),
                endNormY = obj.optDouble("endNormY", -1.0).toFloat(),
                swipeDurationMs = obj.optLong("swipeDurationMs", ClickTarget.DEFAULT_SWIPE_DURATION_MS)
                    .coerceIn(ClickTarget.MIN_SWIPE_MS, ClickTarget.MAX_SWIPE_MS),
                repeatCount = obj.optInt("repeatCount", defaultRepeat)
                    .coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT),
                refPresetId = obj.optString("refPresetId", ""),
                subDurationMs = obj.optLong("subDurationMs", ClickTarget.DEFAULT_SUB_DURATION_MS)
                    .coerceIn(ClickTarget.MIN_SUB_DURATION_MS, ClickTarget.MAX_SUB_DURATION_MS)
            )
        }
        return result
    }

    private fun targetsToJsonArray(targets: List<ClickTarget>): JSONArray {
        val array = JSONArray()
        targets.forEachIndexed { idx, t ->
            t.index = idx + 1
            val obj = JSONObject().apply {
                put("id", t.id)
                put("x", t.x.toDouble())
                put("y", t.y.toDouble())
                put("normX", t.normX.toDouble())
                put("normY", t.normY.toDouble())
                put("intervalMs", t.intervalMs)
                put("pressDurationMs", t.pressDurationMs)
                put("microJitter", t.microJitter)
                put("enabled", t.enabled)
                put("actionType", t.actionType.code)
                put("endX", t.endX.toDouble())
                put("endY", t.endY.toDouble())
                put("endNormX", t.endNormX.toDouble())
                put("endNormY", t.endNormY.toDouble())
                put("swipeDurationMs", t.swipeDurationMs)
                put("repeatCount", t.repeatCount.coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT))
                if (t.refPresetId.isNotBlank()) put("refPresetId", t.refPresetId)
                put("subDurationMs", t.subDurationMs.coerceIn(ClickTarget.MIN_SUB_DURATION_MS, ClickTarget.MAX_SUB_DURATION_MS))
            }
            array.put(obj)
        }
        return array
    }

    private fun loadLegacyTargetsRaw(context: Context): MutableList<ClickTarget> {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TARGETS, null) ?: return mutableListOf()
        return try {
            jsonArrayToTargets(JSONArray(raw), defaultRepeat = 0)
        } catch (_: Throwable) {
            mutableListOf()
        }
    }

    fun loadPresets(context: Context): MutableList<ClickPreset> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PRESETS_JSON, null)
        var migratedLegacyFrozen = false
        val parsed = if (!raw.isNullOrBlank()) {
            try {
                val arr = JSONArray(raw)
                val list = mutableListOf<ClickPreset>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "").ifBlank { "preset_${i + 1}" }
                    var name = obj.optString("name", "").ifBlank { "Plan ${i + 1}" }
                    val execMode = ExecutionMode.fromCode(obj.optInt("executionMode", 0))
                    val loopCount = obj.optInt("presetLoopCount", 0)
                        .coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT)
                    var frozen = if (obj.has("isFrozen")) obj.optBoolean("isFrozen", false) else false
                    if (!obj.has("isFrozen") && name.trimStart().startsWith("#")) {
                        name = name.trimStart().dropWhile { it == '#' }.trimStart().ifEmpty { "Plan ${i + 1}" }
                        frozen = true
                        migratedLegacyFrozen = true
                    }
                    val parentId = obj.optString("parentPresetId", "")
                    val isRecorded = obj.optBoolean("isRecordedMacro", false)
                    val targets = jsonArrayToTargets(
                        obj.optJSONArray("targets"),
                        defaultRepeat = ClickTarget.defaultRepeatFor(execMode)
                    )
                    list += ClickPreset(
                        id = id,
                        name = name,
                        executionMode = execMode,
                        presetLoopCount = loopCount,
                        isFrozen = frozen,
                        parentPresetId = parentId,
                        isRecordedMacro = isRecorded,
                        targets = targets
                    )
                }
                list
            } catch (_: Throwable) {
                mutableListOf()
            }
        } else {
            mutableListOf()
        }

        if (parsed.isNotEmpty()) {
            var changed = migratedLegacyFrozen
            if (!prefs.getBoolean(KEY_MIGRATED_CONCURRENT_ZERO, false)) {
                for (p in parsed) {
                    if (p.executionMode == ExecutionMode.CONCURRENT) {
                        for (t in p.targets) {
                            if (t.repeatCount == 1) {
                                t.repeatCount = 0
                                changed = true
                            }
                        }
                    }
                }
                prefs.edit().putBoolean(KEY_MIGRATED_CONCURRENT_ZERO, true).apply()
            }
            if (changed) {
                val savedId = prefs.getString(KEY_ACTIVE_PRESET_ID, parsed.first().id) ?: parsed.first().id
                persistPresetsInternal(context, parsed, savedId)
            }
            return parsed
        }

        val migrated = ClickPreset(
            id = DEFAULT_PRESET_ID,
            name = "Plan 1",
            executionMode = ExecutionMode.CONCURRENT,
            targets = loadLegacyTargetsRaw(context)
        )
        val initialList = mutableListOf(migrated)
        prefs.edit().putBoolean(KEY_MIGRATED_CONCURRENT_ZERO, true).apply()
        persistPresetsInternal(context, initialList, migrated.id)
        return initialList
    }

    private fun persistPresetsInternal(
        context: Context,
        presets: List<ClickPreset>,
        activePresetId: String
    ) {
        val safeList = if (presets.isEmpty()) {
            listOf(ClickPreset(id = DEFAULT_PRESET_ID, name = "Plan 1"))
        } else {
            presets
        }
        val active = safeList.firstOrNull { it.id == activePresetId } ?: safeList.first()
        val array = JSONArray()
        for (preset in safeList) {
            array.put(JSONObject().apply {
                put("id", preset.id)
                put("name", preset.name)
                put("executionMode", preset.executionMode.code)
                put("presetLoopCount", preset.presetLoopCount.coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT))
                put("isFrozen", preset.isFrozen)
                if (preset.parentPresetId.isNotBlank()) {
                    put("parentPresetId", preset.parentPresetId)
                }
                if (preset.isRecordedMacro) {
                    put("isRecordedMacro", true)
                }
                put("targets", targetsToJsonArray(preset.targets))
            })
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PRESETS_JSON, array.toString())
            .putString(KEY_ACTIVE_PRESET_ID, active.id)
            .putString(KEY_TARGETS, targetsToJsonArray(active.targets).toString())
            .apply()
    }

    fun getActivePresetId(context: Context): String {
        val presets = loadPresets(context)
        val savedId = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_PRESET_ID, DEFAULT_PRESET_ID)
        val resolved = presets.firstOrNull { it.id == savedId && !it.isFrozen }
            ?: presets.firstOrNull { !it.isFrozen }
            ?: presets.firstOrNull { it.id == savedId }
            ?: presets.first()
        if (resolved.id != savedId) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_ACTIVE_PRESET_ID, resolved.id)
                .apply()
        }
        return resolved.id
    }

    fun getActivePreset(context: Context): ClickPreset {
        val presets = loadPresets(context)
        val activeId = getActivePresetId(context)
        return presets.firstOrNull { it.id == activeId } ?: presets.first()
    }

    fun getActivePresetIndexAndTotal(context: Context): Pair<Int, Int> {
        val presets = loadPresets(context)
        val switchable = presets.filter { !it.isFrozen }
        if (switchable.isEmpty()) return Pair(0, 1)
        val activeId = getActivePresetId(context)
        val idx = switchable.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
        return Pair(idx, switchable.size)
    }

    fun nextMainPresetNumber(presets: List<ClickPreset>): Int {
        val regex = Regex("^Plan\\s*(\\d+)$")
        var maxNum = 0
        for (p in presets) {
            val m = regex.find(p.name.trim())
            if (m != null) {
                val n = m.groupValues[1].toIntOrNull() ?: 0
                if (n > maxNum) maxNum = n
            }
        }
        val topLevelCount = presets.count { it.parentPresetId.isBlank() && !it.isFrozen }
        return max(maxNum + 1, topLevelCount + 1)
    }

    fun setActivePresetId(context: Context, presetId: String): ClickPreset {
        val presets = loadPresets(context)
        val target = presets.firstOrNull { it.id == presetId } ?: presets.first()
        persistPresetsInternal(context, presets, target.id)
        return target
    }

    fun createPreset(
        context: Context,
        name: String,
        initialTargets: List<ClickTarget> = emptyList(),
        executionMode: ExecutionMode = ExecutionMode.CONCURRENT,
        presetLoopCount: Int = 0,
        isFrozen: Boolean = false,
        parentPresetId: String = "",
        isRecordedMacro: Boolean = false,
        activate: Boolean = true
    ): ClickPreset {
        val presets = loadPresets(context)
        val existingIds = presets.map { it.id }.toSet()
        var seq = presets.size + 1
        var newId = "preset_$seq"
        while (newId in existingIds) {
            seq++
            newId = "preset_$seq"
        }
        val cleanName = name.trim().ifEmpty { "Plan ${nextMainPresetNumber(presets)}" }
        val copiedTargets = initialTargets.mapIndexed { idx, t ->
            t.copy(id = idx + 1, index = idx + 1)
        }.toMutableList()
        val created = ClickPreset(
            id = newId,
            name = cleanName,
            executionMode = executionMode,
            presetLoopCount = presetLoopCount.coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT),
            isFrozen = isFrozen,
            parentPresetId = parentPresetId,
            isRecordedMacro = isRecordedMacro,
            targets = copiedTargets
        )
        presets += created
        val nextActiveId = if (activate && !isFrozen) created.id else getActivePresetId(context)
        persistPresetsInternal(context, presets, nextActiveId)
        return created
    }

    fun createPresetBatchAndActivate(
        context: Context,
        subPresets: List<ClickPreset>,
        mainPreset: ClickPreset
    ): ClickPreset {
        val presets = loadPresets(context)
        val existingIds = presets.map { it.id }.toMutableSet()
        var seq = presets.size + 1
        fun allocId(): String {
            while ("preset_$seq" in existingIds) seq++
            val id = "preset_$seq"
            existingIds += id
            return id
        }

        val mainId = allocId()
        val idRemap = HashMap<String, String>()
        val finalSubs = subPresets.map { sub ->
            val newId = allocId()
            idRemap[sub.id] = newId
            val copiedTargets = sub.targets.mapIndexed { idx, t ->
                t.copy(id = idx + 1, index = idx + 1)
            }.toMutableList()
            ClickPreset(
                id = newId,
                name = sub.name.trim().ifEmpty { newId },
                executionMode = sub.executionMode,
                presetLoopCount = sub.presetLoopCount.coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT),
                isFrozen = true,
                parentPresetId = mainId,
                isRecordedMacro = sub.isRecordedMacro,
                targets = copiedTargets
            )
        }

        val finalMainTargets = mainPreset.targets.mapIndexed { idx, t ->
            val remappedRef = if (t.actionType == ActionType.SUB_PRESET) {
                idRemap[t.refPresetId] ?: t.refPresetId
            } else ""
            t.copy(id = idx + 1, index = idx + 1, refPresetId = remappedRef)
        }.toMutableList()
        val finalMain = ClickPreset(
            id = mainId,
            name = mainPreset.name.trim().ifEmpty { "Plan ${nextMainPresetNumber(presets)}" },
            executionMode = mainPreset.executionMode,
            presetLoopCount = mainPreset.presetLoopCount.coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT),
            isFrozen = false,
            parentPresetId = "",
            isRecordedMacro = mainPreset.isRecordedMacro,
            targets = finalMainTargets
        )

        presets += finalSubs
        presets += finalMain
        persistPresetsInternal(context, presets, finalMain.id)
        return finalMain
    }

    fun renamePreset(context: Context, presetId: String, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isEmpty()) return false
        val presets = loadPresets(context)
        val target = presets.firstOrNull { it.id == presetId } ?: return false
        val oldName = target.name
        target.name = clean
        if (oldName != clean) {
            for (child in presets) {
                if (child.parentPresetId == target.id && child.name.startsWith("$oldName.")) {
                    val suffix = child.name.removePrefix("$oldName.")
                    child.name = "$clean.$suffix"
                }
            }
        }
        val currentActive = getActivePresetId(context)
        persistPresetsInternal(context, presets, currentActive)
        return true
    }

    fun setPresetLoopCount(context: Context, presetId: String, loopCount: Int): Boolean {
        val presets = loadPresets(context)
        val target = presets.firstOrNull { it.id == presetId } ?: return false
        val safe = loopCount.coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT)
        if (target.presetLoopCount != safe) {
            target.presetLoopCount = safe
            persistPresetsInternal(context, presets, getActivePresetId(context))
        }
        return true
    }

    fun setPresetExecutionMode(context: Context, presetId: String, mode: ExecutionMode): Boolean {
        val presets = loadPresets(context)
        val target = presets.firstOrNull { it.id == presetId } ?: return false
        if (target.executionMode != mode) {
            if (mode == ExecutionMode.SEQUENTIAL) {
                for (t in target.targets) {
                    if (t.repeatCount == 0) t.repeatCount = 1
                }
            } else {
                for (t in target.targets) {
                    if (t.actionType != ActionType.SUB_PRESET && t.repeatCount == 1) {
                        t.repeatCount = 0
                    }
                }
            }
            target.executionMode = mode
            persistPresetsInternal(context, presets, getActivePresetId(context))
        }
        return true
    }

    fun isMacroPreset(presets: List<ClickPreset>, preset: ClickPreset): Boolean {
        if (preset.isRecordedMacro) return true
        if (preset.targets.any { it.actionType == ActionType.SUB_PRESET }) return true
        if (presets.any { it.parentPresetId == preset.id }) return true
        return preset.executionMode == ExecutionMode.SEQUENTIAL && preset.targets.size >= 8
    }

    data class DisplayTargetEntry(
        val target: ClickTarget,
        val isExpandedChild: Boolean
    )

    fun collectExpandedDisplayTargets(
        presets: List<ClickPreset>,
        preset: ClickPreset,
        screenW: Int,
        screenH: Int,
        visitedIds: MutableSet<String> = mutableSetOf(preset.id),
        fromChild: Boolean = false
    ): List<DisplayTargetEntry> {
        val out = mutableListOf<DisplayTargetEntry>()
        for (t in preset.targets) {
            if (t.actionType == ActionType.SUB_PRESET) {
                if (!t.enabled) continue
                if (t.refPresetId.isNotBlank() && t.refPresetId !in visitedIds) {
                    val child = presets.firstOrNull { it.id == t.refPresetId }
                    if (child != null) {
                        visitedIds += child.id
                        out += collectExpandedDisplayTargets(
                            presets = presets,
                            preset = child,
                            screenW = screenW,
                            screenH = screenH,
                            visitedIds = visitedIds,
                            fromChild = true
                        )
                        visitedIds -= child.id
                    }
                }
            } else {
                val copy = t.copy().apply { remapToScreen(screenW, screenH) }
                out += DisplayTargetEntry(copy, isExpandedChild = fromChild)
            }
        }
        return out
    }

    fun collectPreviewTargets(
        presets: List<ClickPreset>,
        preset: ClickPreset,
        visitedIds: MutableSet<String> = mutableSetOf(preset.id)
    ): List<ClickTarget> {
        val out = mutableListOf<ClickTarget>()
        for (t in preset.targets) {
            if (t.actionType == ActionType.SUB_PRESET) {
                if (t.refPresetId.isNotBlank() && t.refPresetId !in visitedIds) {
                    val child = presets.firstOrNull { it.id == t.refPresetId }
                    if (child != null) {
                        visitedIds += child.id
                        out += collectPreviewTargets(presets, child, visitedIds)
                        visitedIds -= child.id
                    }
                }
            } else {
                out += t
            }
        }
        return out
    }

    private data class CompiledStageDraft(
        val sourceStepId: Int,
        val isSubPreset: Boolean,
        val gapMs: Long,
        val items: MutableList<ClickTargetParceled>
    )

    private data class StageCompileOutput(
        val targets: List<ClickTargetParceled>,
        val seamlessOutgoingStepIds: Set<Int>
    )

    private fun buildCompiledStages(
        presets: List<ClickPreset>,
        activePreset: ClickPreset,
        liveOverrideTargets: List<ClickTarget>?,
        executionMode: ExecutionMode,
        settings: ClickGlobalSettings,
        screenW: Int,
        screenH: Int
    ): StageCompileOutput {
        val rawTargets = liveOverrideTargets ?: activePreset.targets
        val globalIntervalMs = settings.defaultIntervalMs.coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS)
        val globalPressMs = settings.defaultPressDurationMs.coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS)

        fun resolveSingle(src: ClickTarget): ClickTarget {
            val copy = src.copy()
            copy.remapToScreen(screenW, screenH)
            return if (copy.actionType == ActionType.TAP) {
                copy.copy(intervalMs = globalIntervalMs, pressDurationMs = globalPressMs)
            } else {
                copy
            }
        }

        if (executionMode == ExecutionMode.CONCURRENT) {
            val list = rawTargets
                .filter { it.enabled && it.actionType != ActionType.SUB_PRESET }
                .map { t ->
                    val r = resolveSingle(t)
                    r.toParceled(
                        stageIndex = 0,
                        stageDurationMs = 0L,
                        stageGapMs = r.intervalMs.coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS),
                        stageRepeatCount = r.repeatCount
                    )
                }
            return StageCompileOutput(list, emptySet())
        }

        val drafts = mutableListOf<CompiledStageDraft>()
        var stageIdx = 0
        for (step in rawTargets) {
            if (!step.enabled) continue
            if (step.actionType != ActionType.SUB_PRESET) {
                val r = resolveSingle(step)
                val gapMs = r.intervalMs.coerceIn(ClickTarget.MIN_STAGE_GAP_MS, ClickTarget.MAX_INTERVAL_MS)
                drafts += CompiledStageDraft(
                    sourceStepId = step.id,
                    isSubPreset = false,
                    gapMs = gapMs,
                    items = mutableListOf(
                        r.toParceled(
                            stageIndex = stageIdx++,
                            stageDurationMs = 0L,
                            stageGapMs = gapMs,
                            stageRepeatCount = r.repeatCount
                        )
                    )
                )
            } else {
                val child = presets.firstOrNull {
                    it.id == step.refPresetId &&
                        it.id != activePreset.id &&
                        it.executionMode == ExecutionMode.CONCURRENT
                } ?: continue
                val childTargets = child.targets.filter { it.enabled && it.actionType != ActionType.SUB_PRESET }
                if (childTargets.isEmpty()) continue
                val durMs = step.subDurationMs.coerceIn(ClickTarget.MIN_SUB_DURATION_MS, ClickTarget.MAX_SUB_DURATION_MS)
                val gapMs = step.intervalMs.coerceIn(ClickTarget.MIN_STAGE_GAP_MS, ClickTarget.MAX_INTERVAL_MS)
                val stRep = step.repeatCount.coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT)
                val stageItems = mutableListOf<ClickTargetParceled>()
                childTargets.forEachIndexed { childOffset, childTarget ->
                    val r = resolveSingle(childTarget)
                    val virtualId = 100_000 + stageIdx * 1000 + childOffset
                    stageItems += r.copy(id = virtualId).toParceled(
                        stageIndex = stageIdx,
                        stageDurationMs = durMs,
                        stageGapMs = gapMs,
                        stageRepeatCount = stRep
                    )
                }
                drafts += CompiledStageDraft(
                    sourceStepId = step.id,
                    isSubPreset = true,
                    gapMs = gapMs,
                    items = stageItems
                )
                stageIdx++
            }
        }

        // Automatically pair adjacent stages where continuous HOLD/SWIPE fingers can bridge seamlessly without lifting
        val seamlessStepIds = LinkedHashSet<Int>()
        fun outBridgePoint(item: ClickTargetParceled, isSub: Boolean): Pair<Float, Float>? {
            return when (item.actionType) {
                ActionType.HOLD.code -> {
                    val continuous = if (isSub) item.repeatCount == 0 else item.repeatCount >= 1
                    if (continuous) Pair(item.x, item.y) else null
                }
                ActionType.SWIPE.code -> {
                    val continuous = if (isSub) item.repeatCount == 1 else item.repeatCount >= 1
                    if (continuous) Pair(item.endX, item.endY) else null
                }
                else -> null
            }
        }

        fun inBridgePoint(item: ClickTargetParceled, isSub: Boolean): Pair<Float, Float>? {
            return when (item.actionType) {
                ActionType.HOLD.code -> {
                    val continuous = if (isSub) item.repeatCount == 0 else item.repeatCount >= 1
                    if (continuous) Pair(item.x, item.y) else null
                }
                ActionType.SWIPE.code -> {
                    val continuous = if (isSub) item.repeatCount == 1 else item.repeatCount >= 1
                    if (continuous) Pair(item.x, item.y) else null
                }
                else -> null
            }
        }

        var nextHoldLinkId = 1
        if (drafts.isNotEmpty()) {
            for (s in drafts.indices) {
                val cur = drafts[s]
                if (cur.gapMs > ClickTarget.SEAMLESS_HOLD_GAP_MS) continue
                val nextIdx = (s + 1) % drafts.size
                if (drafts.size == 1 && !cur.isSubPreset) continue
                val next = drafts[nextIdx]

                val curCandidates = cur.items.mapNotNull { item ->
                    outBridgePoint(item, cur.isSubPreset)?.let { pt -> Pair(item, pt) }
                }
                val nextCandidates = next.items.mapNotNull { item ->
                    if (item.holdInId != 0) null
                    else inBridgePoint(item, next.isSubPreset)?.let { pt -> Pair(item, pt) }
                }
                if (curCandidates.isEmpty() || nextCandidates.isEmpty()) continue

                val usedNext = HashSet<Int>()
                var stageMerged = false
                for ((u, outPt) in curCandidates) {
                    var bestIdx = -1
                    var bestDist = ClickTarget.SEAMLESS_HOLD_DIST_PX
                    for (j in nextCandidates.indices) {
                        if (j in usedNext) continue
                        val (_, inPt) = nextCandidates[j]
                        val dist = kotlin.math.hypot((outPt.first - inPt.first).toDouble(), (outPt.second - inPt.second).toDouble()).toFloat()
                        if (dist <= bestDist) {
                            bestDist = dist
                            bestIdx = j
                        }
                    }
                    if (bestIdx >= 0) {
                        usedNext += bestIdx
                        val linkId = nextHoldLinkId++
                        u.holdOutId = linkId
                        nextCandidates[bestIdx].first.holdInId = linkId
                        stageMerged = true
                    }
                }
                if (stageMerged) {
                    seamlessStepIds += cur.sourceStepId
                }
            }
        }

        val compiled = drafts.flatMap { it.items }
        return StageCompileOutput(compiled, seamlessStepIds)
    }

    fun findSeamlessMergeStepIds(
        presets: List<ClickPreset>,
        activePreset: ClickPreset,
        screenW: Int,
        screenH: Int
    ): Set<Int> {
        if (activePreset.executionMode != ExecutionMode.SEQUENTIAL || activePreset.targets.isEmpty()) {
            return emptySet()
        }
        return buildCompiledStages(
            presets = presets,
            activePreset = activePreset,
            liveOverrideTargets = null,
            executionMode = ExecutionMode.SEQUENTIAL,
            settings = ClickGlobalSettings(),
            screenW = screenW,
            screenH = screenH
        ).seamlessOutgoingStepIds
    }

    fun compileTargetsForExecution(
        presets: List<ClickPreset>,
        activePreset: ClickPreset,
        liveOverrideTargets: List<ClickTarget>?,
        executionMode: ExecutionMode,
        settings: ClickGlobalSettings,
        screenW: Int,
        screenH: Int
    ): List<ClickTargetParceled> {
        return buildCompiledStages(
            presets = presets,
            activePreset = activePreset,
            liveOverrideTargets = liveOverrideTargets,
            executionMode = executionMode,
            settings = settings,
            screenW = screenW,
            screenH = screenH
        ).targets
    }

    fun applyGlobalTapSettingsToAllPresets(
        context: Context,
        settings: ClickGlobalSettings
    ): Int {
        val presets = loadPresets(context)
        val intervalMs = settings.defaultIntervalMs.coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS)
        val pressMs = settings.defaultPressDurationMs.coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS)
        var updatedCount = 0
        for (preset in presets) {
            for (target in preset.targets) {
                if (target.actionType == ActionType.TAP) {
                    target.intervalMs = intervalMs
                    target.pressDurationMs = pressMs
                    updatedCount++
                }
            }
        }
        persistPresetsInternal(context, presets, getActivePresetId(context))
        return updatedCount
    }

    fun resolveTargetsForExecution(
        targets: List<ClickTarget>,
        settings: ClickGlobalSettings
    ): List<ClickTarget> {
        val intervalMs = settings.defaultIntervalMs.coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS)
        val pressMs = settings.defaultPressDurationMs.coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS)
        return targets.map { target ->
            if (target.actionType == ActionType.TAP) {
                target.copy(intervalMs = intervalMs, pressDurationMs = pressMs)
            } else {
                target.copy()
            }
        }
    }

    fun collectCascadeDeleteIds(
        presets: List<ClickPreset>,
        initialIds: Set<String>
    ): Set<String> {
        val toDelete = initialIds.toMutableSet()
        val byId = presets.associateBy { it.id }
        var expanded = true
        while (expanded) {
            expanded = false
            for (p in presets) {
                if (p.parentPresetId.isNotBlank() && p.parentPresetId in toDelete && p.id !in toDelete) {
                    toDelete += p.id
                    expanded = true
                }
            }
            for (pid in toDelete.toList()) {
                val p = byId[pid] ?: continue
                for (t in p.targets) {
                    if (t.actionType == ActionType.SUB_PRESET && t.refPresetId.isNotBlank()) {
                        val childId = t.refPresetId
                        val child = byId[childId] ?: continue
                        if (childId in toDelete) continue
                        val referencedBySurviving = presets.any { other ->
                            other.id !in toDelete &&
                                other.targets.any { it.actionType == ActionType.SUB_PRESET && it.refPresetId == childId }
                        }
                        if ((child.parentPresetId == p.id || child.isFrozen) && !referencedBySurviving) {
                            toDelete += childId
                            expanded = true
                        }
                    }
                }
            }
        }
        return toDelete
    }

    fun deletePreset(context: Context, presetId: String): Boolean {
        val presets = loadPresets(context)
        if (presets.none { it.id == presetId }) return false
        val cascadeIds = collectCascadeDeleteIds(presets, setOf(presetId))
        val remaining = presets.filter { it.id !in cascadeIds }
        if (remaining.isEmpty()) return false
        val currentActive = getActivePresetId(context)
        val nextActive = if (remaining.any { it.id == currentActive && !it.isFrozen }) {
            currentActive
        } else {
            remaining.firstOrNull { !it.isFrozen }?.id ?: remaining.first().id
        }
        persistPresetsInternal(context, remaining, nextActive)
        return true
    }

    fun deletePresetsBatch(context: Context, presetIds: Set<String>): Int {
        if (presetIds.isEmpty()) return 0
        val presets = loadPresets(context)
        val cascadeIds = collectCascadeDeleteIds(presets, presetIds)
        val remaining = presets.filter { it.id !in cascadeIds }
        if (remaining.isEmpty()) return 0
        val deletedCount = presets.size - remaining.size
        if (deletedCount <= 0) return 0
        val currentActive = getActivePresetId(context)
        val nextActive = if (remaining.any { it.id == currentActive }) {
            currentActive
        } else {
            remaining.firstOrNull { !it.isFrozen }?.id ?: remaining.first().id
        }
        persistPresetsInternal(context, remaining, nextActive)
        return deletedCount
    }

    fun createEmptyPresetsBatch(
        context: Context,
        count: Int,
        executionMode: ExecutionMode = ExecutionMode.CONCURRENT
    ): List<ClickPreset> {
        val safeCount = count.coerceIn(1, 50)
        val presets = loadPresets(context)
        val existingIds = presets.map { it.id }.toMutableSet()
        val existingNames = presets.map { it.name }.toMutableSet()
        var seq = presets.size + 1
        var nameNum = nextMainPresetNumber(presets)
        val created = mutableListOf<ClickPreset>()
        repeat(safeCount) {
            while ("preset_$seq" in existingIds) seq++
            val newId = "preset_$seq"
            existingIds += newId
            while ("Plan $nameNum" in existingNames) nameNum++
            val name = "Plan $nameNum"
            existingNames += name
            val p = ClickPreset(
                id = newId,
                name = name,
                executionMode = executionMode,
                targets = mutableListOf()
            )
            presets += p
            created += p
        }
        persistPresetsInternal(context, presets, created.first().id)
        return created
    }

    fun applyPresetFreezeStates(context: Context, frozenIds: Set<String>): Int {
        val presets = loadPresets(context)
        var changed = 0
        for (preset in presets) {
            val wantFrozen = preset.id in frozenIds
            if (preset.isFrozen != wantFrozen) {
                preset.isFrozen = wantFrozen
                changed++
            }
        }
        if (changed > 0) {
            val currentActive = getActivePresetId(context)
            val activeObj = presets.firstOrNull { it.id == currentActive }
            val nextActive = if (activeObj == null || activeObj.isFrozen) {
                presets.firstOrNull { !it.isFrozen }?.id ?: currentActive
            } else {
                currentActive
            }
            persistPresetsInternal(context, presets, nextActive)
        }
        return changed
    }

    fun freezePresetsBatch(context: Context, presetIds: Set<String>): Int {
        if (presetIds.isEmpty()) return 0
        val presets = loadPresets(context)
        var changed = 0
        for (preset in presets) {
            if (preset.id in presetIds && !preset.isFrozen) {
                preset.isFrozen = true
                changed++
            }
        }
        if (changed > 0) {
            val currentActive = getActivePresetId(context)
            val activeObj = presets.firstOrNull { it.id == currentActive }
            val nextActive = if (activeObj == null || activeObj.isFrozen) {
                presets.firstOrNull { !it.isFrozen }?.id ?: currentActive
            } else {
                currentActive
            }
            persistPresetsInternal(context, presets, nextActive)
        }
        return changed
    }

    fun unfreezePresetsBatch(context: Context, presetIds: Set<String>): Int {
        if (presetIds.isEmpty()) return 0
        val presets = loadPresets(context)
        var changed = 0
        for (preset in presets) {
            if (preset.id in presetIds && preset.isFrozen) {
                preset.isFrozen = false
                changed++
            }
        }
        if (changed > 0) {
            persistPresetsInternal(context, presets, getActivePresetId(context))
        }
        return changed
    }

    fun switchToNextPreset(context: Context): Triple<Int, Int, ClickPreset> {
        val presets = loadPresets(context)
        val activeId = getActivePresetId(context)
        val switchable = presets.filter { !it.isFrozen }.ifEmpty { presets }
        val currentSwitchIdx = switchable.indexOfFirst { it.id == activeId }
        val nextPreset = if (currentSwitchIdx >= 0) {
            switchable[(currentSwitchIdx + 1) % switchable.size]
        } else {
            switchable.first()
        }
        persistPresetsInternal(context, presets, nextPreset.id)
        val switchIdx = switchable.indexOfFirst { it.id == nextPreset.id }.coerceAtLeast(0)
        return Triple(switchIdx, switchable.size, nextPreset)
    }

    fun exportPresetToJson(preset: ClickPreset): String {
        val obj = JSONObject().apply {
            put("schema", "zerointerrupt.preset.v3")
            put("name", preset.name)
            put("executionMode", preset.executionMode.code)
            put("presetLoopCount", preset.presetLoopCount)
            if (preset.isRecordedMacro) {
                put("isRecordedMacro", true)
            }
            put("targets", targetsToJsonArray(preset.targets))
        }
        return obj.toString(2)
    }

    fun exportPresetsBundleToJson(presets: List<ClickPreset>, activePresetId: String): String {
        val arr = JSONArray()
        for (preset in presets) {
            arr.put(JSONObject().apply {
                put("id", preset.id)
                put("name", preset.name)
                put("executionMode", preset.executionMode.code)
                put("presetLoopCount", preset.presetLoopCount)
                put("isFrozen", preset.isFrozen)
                if (preset.parentPresetId.isNotBlank()) {
                    put("parentPresetId", preset.parentPresetId)
                }
                if (preset.isRecordedMacro) {
                    put("isRecordedMacro", true)
                }
                put("targets", targetsToJsonArray(preset.targets))
            })
        }
        val obj = JSONObject().apply {
            put("schema", "zerointerrupt.presets.bundle.v1")
            put("appVersion", BuildConfig.VERSION_NAME)
            put("activePresetId", activePresetId)
            put("presets", arr)
        }
        return obj.toString(2)
    }

    fun importPresetFromJson(
        context: Context,
        rawJson: String,
        screenW: Int,
        screenH: Int
    ): ClickPreset? {
        return try {
            val trimmed = rawJson.trim()
            if (trimmed.isEmpty()) return null
            val name: String
            val execMode: ExecutionMode
            val loopCount: Int
            val isRecorded: Boolean
            val targets: MutableList<ClickTarget>
            if (trimmed.startsWith("[")) {
                name = "Planları içe aktar"
                execMode = ExecutionMode.CONCURRENT
                loopCount = 0
                isRecorded = false
                targets = jsonArrayToTargets(JSONArray(trimmed), defaultRepeat = 0)
            } else {
                val obj = JSONObject(trimmed)
                name = obj.optString("name", "").ifBlank { "Planları içe aktar" }
                execMode = ExecutionMode.fromCode(obj.optInt("executionMode", 0))
                loopCount = obj.optInt("presetLoopCount", 0)
                    .coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT)
                isRecorded = obj.optBoolean("isRecordedMacro", false)
                targets = jsonArrayToTargets(
                    obj.optJSONArray("targets"),
                    defaultRepeat = ClickTarget.defaultRepeatFor(execMode)
                )
            }
            targets.forEach { it.remapToScreen(screenW, screenH) }
            createPreset(
                context = context,
                name = name,
                initialTargets = targets,
                executionMode = execMode,
                presetLoopCount = loopCount,
                isRecordedMacro = isRecorded,
                activate = true
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun importPresetsBatchFromJson(
        context: Context,
        rawJson: String,
        screenW: Int,
        screenH: Int
    ): List<ClickPreset> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return emptyList()
        return try {
            val rawArray: JSONArray
            var desiredActiveOldId = ""
            if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                val presetsArr = obj.optJSONArray("presets")
                if (presetsArr != null) {
                    rawArray = presetsArr
                    desiredActiveOldId = obj.optString("activePresetId", "")
                } else {
                    val single = importPresetFromJson(context, trimmed, screenW, screenH)
                    return if (single != null) listOf(single) else emptyList()
                }
            } else if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                if (arr.length() == 0) return emptyList()
                val firstObj = arr.optJSONObject(0)
                val looksLikePresetList = firstObj != null && (firstObj.has("targets") || firstObj.has("executionMode"))
                if (looksLikePresetList) {
                    rawArray = arr
                } else {
                    val single = importPresetFromJson(context, trimmed, screenW, screenH)
                    return if (single != null) listOf(single) else emptyList()
                }
            } else {
                return emptyList()
            }

            if (rawArray.length() == 0) return emptyList()
            val existingPresets = loadPresets(context)
            val existingIds = existingPresets.map { it.id }.toMutableSet()
            var seq = existingPresets.size + 1
            fun allocId(): String {
                while ("preset_$seq" in existingIds) seq++
                val id = "preset_$seq"
                existingIds += id
                return id
            }

            data class StagedPreset(
                val oldId: String,
                val newId: String,
                val name: String,
                val mode: ExecutionMode,
                val loopCount: Int,
                val isFrozen: Boolean,
                val oldParentId: String,
                val isRecordedMacro: Boolean,
                val targets: MutableList<ClickTarget>
            )

            val staged = mutableListOf<StagedPreset>()
            val oldToNewId = HashMap<String, String>()
            for (i in 0 until rawArray.length()) {
                val item = rawArray.optJSONObject(i) ?: continue
                val oldId = item.optString("id", "").trim()
                val newId = allocId()
                if (oldId.isNotEmpty()) {
                    oldToNewId[oldId] = newId
                }
                val execMode = ExecutionMode.fromCode(item.optInt("executionMode", 0))
                val loopCount = item.optInt("presetLoopCount", 0)
                    .coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT)
                var rawName = item.optString("name", "").trim()
                var isFrozen = item.optBoolean("isFrozen", false)
                if (!item.has("isFrozen") && rawName.startsWith("#")) {
                    rawName = rawName.dropWhile { it == '#' }.trimStart()
                    isFrozen = true
                }
                val oldParentId = item.optString("parentPresetId", "").trim()
                val isRecorded = item.optBoolean("isRecordedMacro", false)
                val targets = jsonArrayToTargets(
                    item.optJSONArray("targets"),
                    defaultRepeat = ClickTarget.defaultRepeatFor(execMode)
                )
                targets.forEach { it.remapToScreen(screenW, screenH) }
                val cleanName = rawName.ifEmpty { "Plan ${existingPresets.size + staged.size + 1} içe aktar" }
                staged += StagedPreset(oldId, newId, cleanName, execMode, loopCount, isFrozen, oldParentId, isRecorded, targets)
            }
            if (staged.isEmpty()) return emptyList()

            val imported = staged.map { s ->
                val remappedTargets = s.targets.mapIndexed { idx, t ->
                    val resolvedRef = if (t.actionType == ActionType.SUB_PRESET && t.refPresetId.isNotBlank()) {
                        oldToNewId[t.refPresetId] ?: t.refPresetId
                    } else {
                        t.refPresetId
                    }
                    t.copy(id = idx + 1, index = idx + 1, refPresetId = resolvedRef).apply {
                        remapToScreen(screenW, screenH)
                    }
                }.toMutableList()
                val resolvedParentId = if (s.oldParentId.isNotBlank()) {
                    oldToNewId[s.oldParentId] ?: ""
                } else ""
                ClickPreset(
                    id = s.newId,
                    name = s.name,
                    executionMode = s.mode,
                    presetLoopCount = s.loopCount,
                    isFrozen = s.isFrozen,
                    parentPresetId = resolvedParentId,
                    isRecordedMacro = s.isRecordedMacro,
                    targets = remappedTargets
                )
            }

            existingPresets += imported
            val activeNewId = oldToNewId[desiredActiveOldId]
                ?: imported.lastOrNull { !it.isFrozen }?.id
                ?: imported.last().id
            persistPresetsInternal(context, existingPresets, activeNewId)
            imported
        } catch (_: Throwable) {
            emptyList()
        }
    }

    const val SCRIPT_TEMPLATE_EXAMPLE = """# name: Komut dosyası planı
# mode: seq
# loop: 1
tap 540 1200 24 80 1
hold 540 1500 600 40 1
swipe 300 1600 800 1600 280 120 1"""

    fun importPresetFromScript(
        context: Context,
        rawInput: String,
        screenW: Int,
        screenH: Int
    ): ClickPreset? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            if (trimmed.contains("\"presets\"")) {
                return importPresetsBatchFromJson(context, trimmed, screenW, screenH).lastOrNull()
            }
            return importPresetFromJson(context, trimmed, screenW, screenH)
        }

        val presets = loadPresets(context)
        var name = "Komut dosyası planı ${nextMainPresetNumber(presets)}"
        var execMode = ExecutionMode.SEQUENTIAL
        var loopCount = 0
        val parsedTargets = mutableListOf<ClickTarget>()
        var nextId = 1

        for (rawLine in trimmed.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#") || line.startsWith("//")) {
                val meta = line.removePrefix("#").removePrefix("//").trim()
                when {
                    meta.startsWith("name:", ignoreCase = true) -> {
                        val candidate = meta.substringAfter(":").trim()
                        if (candidate.isNotEmpty()) name = candidate.take(24)
                    }
                    meta.startsWith("mode:", ignoreCase = true) -> {
                        val m = meta.substringAfter(":").trim().lowercase()
                        execMode = if (m.startsWith("conc") || m == "Eşzamanlı") {
                            ExecutionMode.CONCURRENT
                        } else {
                            ExecutionMode.SEQUENTIAL
                        }
                    }
                    meta.startsWith("loop:", ignoreCase = true) -> {
                        val parsed = meta.substringAfter(":").trim().toIntOrNull()
                        if (parsed != null) {
                            loopCount = parsed.coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT)
                        }
                    }
                }
                continue
            }

            val parts = line.split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            val cmd = parts[0].lowercase()
            when (cmd) {
                "wait", "pause", "sleep" -> {
                    val waitMs = parts.getOrNull(1)?.toLongOrNull() ?: return null
                    val prev = parsedTargets.lastOrNull() ?: continue
                    prev.intervalMs = waitMs.coerceIn(ClickTarget.MIN_STAGE_GAP_MS, ClickTarget.MAX_INTERVAL_MS)
                }
                "click" -> {
                    val x = parts.getOrNull(1)?.toFloatOrNull() ?: return null
                    val y = parts.getOrNull(2)?.toFloatOrNull() ?: return null
                    val rep = (parts.getOrNull(3)?.toIntOrNull() ?: ClickTarget.defaultRepeatFor(execMode))
                        .coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT)
                    val t = ClickTarget(
                        id = nextId,
                        index = nextId,
                        x = x,
                        y = y,
                        intervalMs = ClickTarget.DEFAULT_INTERVAL_MS,
                        pressDurationMs = ClickTarget.DEFAULT_PRESS_DURATION_MS,
                        actionType = ActionType.TAP,
                        repeatCount = rep
                    ).apply { setStartPixel(x, y, screenW, screenH) }
                    parsedTargets += t
                    nextId++
                }
                "tap", "hold" -> {
                    val x = parts.getOrNull(1)?.toFloatOrNull() ?: return null
                    val y = parts.getOrNull(2)?.toFloatOrNull() ?: return null
                    val defaultDur = if (cmd == "hold") 500L else ClickTarget.DEFAULT_PRESS_DURATION_MS
                    val pressMs = (parts.getOrNull(3)?.toLongOrNull() ?: defaultDur)
                        .coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS)
                    val gapMs = (parts.getOrNull(4)?.toLongOrNull() ?: ClickTarget.DEFAULT_INTERVAL_MS)
                        .coerceIn(ClickTarget.MIN_STAGE_GAP_MS, ClickTarget.MAX_INTERVAL_MS)
                    val rep = (parts.getOrNull(5)?.toIntOrNull() ?: ClickTarget.defaultRepeatFor(execMode))
                        .coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT)
                    val t = ClickTarget(
                        id = nextId,
                        index = nextId,
                        x = x,
                        y = y,
                        intervalMs = gapMs,
                        pressDurationMs = pressMs,
                        actionType = ActionType.HOLD,
                        repeatCount = rep
                    ).apply { setStartPixel(x, y, screenW, screenH) }
                    parsedTargets += t
                    nextId++
                }
                "swipe" -> {
                    val x1 = parts.getOrNull(1)?.toFloatOrNull() ?: return null
                    val y1 = parts.getOrNull(2)?.toFloatOrNull() ?: return null
                    val x2 = parts.getOrNull(3)?.toFloatOrNull() ?: return null
                    val y2 = parts.getOrNull(4)?.toFloatOrNull() ?: return null
                    val durMs = (parts.getOrNull(5)?.toLongOrNull() ?: ClickTarget.DEFAULT_SWIPE_DURATION_MS)
                        .coerceIn(ClickTarget.MIN_SWIPE_MS, ClickTarget.MAX_SWIPE_MS)
                    val gapMs = (parts.getOrNull(6)?.toLongOrNull() ?: ClickTarget.DEFAULT_INTERVAL_MS)
                        .coerceIn(ClickTarget.MIN_STAGE_GAP_MS, ClickTarget.MAX_INTERVAL_MS)
                    val rep = (parts.getOrNull(7)?.toIntOrNull() ?: ClickTarget.defaultRepeatFor(execMode))
                        .coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT)
                    val t = ClickTarget(
                        id = nextId,
                        index = nextId,
                        x = x1,
                        y = y1,
                        intervalMs = gapMs,
                        actionType = ActionType.SWIPE,
                        endX = x2,
                        endY = y2,
                        swipeDurationMs = durMs,
                        repeatCount = rep
                    ).apply {
                        setStartPixel(x1, y1, screenW, screenH)
                        setEndPixel(x2, y2, screenW, screenH)
                    }
                    parsedTargets += t
                    nextId++
                }
                else -> return null
            }
        }
        if (parsedTargets.isEmpty()) return null
        return createPreset(
            context = context,
            name = name,
            initialTargets = parsedTargets,
            executionMode = execMode,
            presetLoopCount = loopCount,
            activate = true
        )
    }

    fun loadTargets(context: Context): MutableList<ClickTarget> =
        getActivePreset(context).targets.map { it.copy() }.toMutableList()

    fun loadTargetsForScreen(context: Context, screenW: Int, screenH: Int): MutableList<ClickTarget> =
        getActivePreset(context).targets.map { t ->
            t.copy().also { it.remapToScreen(screenW, screenH) }
        }.toMutableList()

    fun saveTargets(context: Context, targets: List<ClickTarget>) {
        val presets = loadPresets(context)
        val activeId = getActivePresetId(context)
        val active = presets.firstOrNull { it.id == activeId } ?: presets.first()
        active.targets.clear()
        targets.forEachIndexed { idx, t ->
            t.index = idx + 1
            active.targets += t.copy(index = idx + 1)
        }
        persistPresetsInternal(context, presets, active.id)
    }

    fun saveControlBarPosition(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CONTROL_BAR_X, x)
            .putInt(KEY_CONTROL_BAR_Y, y)
            .apply()
    }

    fun loadControlBarPosition(context: Context): Pair<Int, Int>? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_CONTROL_BAR_X) || !prefs.contains(KEY_CONTROL_BAR_Y)) {
            return null
        }
        return Pair(prefs.getInt(KEY_CONTROL_BAR_X, 40), prefs.getInt(KEY_CONTROL_BAR_Y, 220))
    }

    fun saveControlBarCollapseState(context: Context, collapsed: Boolean, dockRight: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CONTROL_BAR_COLLAPSED, collapsed)
            .putBoolean(KEY_CONTROL_BAR_DOCK_RIGHT, dockRight)
            .apply()
    }

    fun loadControlBarDockRight(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONTROL_BAR_DOCK_RIGHT, false)

    fun loadControlBarCollapsed(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONTROL_BAR_COLLAPSED, false)
}
