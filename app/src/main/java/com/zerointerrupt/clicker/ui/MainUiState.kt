package com.zerointerrupt.clicker.ui

import com.zerointerrupt.clicker.BuildConfig
import com.zerointerrupt.clicker.model.ActionType
import com.zerointerrupt.clicker.model.ClickGlobalSettings
import com.zerointerrupt.clicker.model.ClickPreset
import com.zerointerrupt.clicker.model.ClickTarget
import com.zerointerrupt.clicker.model.ExecutionMode
import com.zerointerrupt.clicker.shizuku.EngineSnapshot
import com.zerointerrupt.clicker.shizuku.ShizukuConnectionState

enum class MainTab(val title: String) {
    HOME("Başlat"),
    PRESETS("Profil"),
    TUNING("Ayarla"),
    LAB("Laboratuvar")
}

data class MainUiState(
    val connectionState: ShizukuConnectionState,
    val engineSnapshot: EngineSnapshot,
    val slotStates: List<Int> = List(16) { 0 },
    val overlayVisible: Boolean,
    val clickingRunning: Boolean,
    val overlayPermGranted: Boolean,
    val notifPermGranted: Boolean,
    val showNotifRow: Boolean,
    val batteryOptIgnored: Boolean = true,
    val selfCheckPassed: Boolean,
    val selfCheckRemainingMs: Long,
    val selfCheckTimeoutMs: Long,
    val tuningTier: Int = ClickGlobalSettings.TIER_CUSTOM,
    val intervalMs: Long,
    val pressDurationMs: Long,
    val microJitter: Boolean,
    val jitterRadiusPx: Float,
    val lockedAnchorAlpha: Float,
    val autoLockOnStart: Boolean,
    val autoCollapseBar: Boolean,
    val showMacroTrajectories: Boolean = true,
    val presets: List<ClickPreset>,
    val activePresetId: String,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val themeMode: Int = ClickGlobalSettings.THEME_SYSTEM,
    val dynamicColor: Boolean = true,
    val launcherIconMode: Int = ClickGlobalSettings.LAUNCHER_SYSTEM,
    val launcherDynamicIcon: Boolean = false,
    val volumeKeyToggleEnabled: Boolean = true,
    val proModeEnabled: Boolean = false,
    val preferredBackend: Int = ClickGlobalSettings.BACKEND_AUTO,
    val rpcBridgeEnabled: Boolean = false,
    val rpcToken: String = "",
    val rpcServerRunning: Boolean = false,
    val appVersionName: String = BuildConfig.VERSION_NAME
) {
    val activePreset: ClickPreset?
        get() = presets.firstOrNull { it.id == activePresetId } ?: presets.firstOrNull()

    val needsSetupWizard: Boolean
        get() {
            if (!overlayPermGranted) return true
            if (connectionState != ShizukuConnectionState.CONNECTED) return true
            val canUseNative = engineSnapshot.touchDeviceReadable &&
                preferredBackend != ClickGlobalSettings.BACKEND_FALLBACK
            return canUseNative && !selfCheckPassed
        }
}

data class MainScreenCallbacks(
    val onToggleOverlay: () -> Unit,
    val onGrantOverlay: () -> Unit,
    val onGrantShizuku: () -> Unit,
    val onGrantNotif: () -> Unit,
    val onGrantBatteryOpt: () -> Unit,
    val onStartSelfCheck: () -> Unit,
    val onConfirmSelfCheck: () -> Unit,
    val onRollbackSelfCheck: () -> Unit,
    val onReleaseNative: () -> Unit,
    val onCleanupOrphans: () -> Unit,
    val onRefreshCapabilities: () -> Unit,
    val onSelectTuningTier: (Int) -> Unit,
    val onResetTuningDefaults: () -> Unit,
    val onIntervalChange: (Long, Boolean) -> Unit,
    val onPressChange: (Long, Boolean) -> Unit,
    val onMicroJitterChange: (Boolean) -> Unit,
    val onJitterRadiusChange: (Float, Boolean) -> Unit,
    val onLockedAlphaChange: (Float, Boolean) -> Unit,
    val onAutoLockChange: (Boolean) -> Unit,
    val onAutoCollapseChange: (Boolean) -> Unit,
    val onShowMacroTrajectoriesChange: (Boolean) -> Unit,
    val onApplyDefaultsToGlobal: () -> Unit,
    val onSelectPreset: (String) -> Unit,
    val onCyclePreset: (Int) -> Unit,
    val onCreatePreset: (String, Boolean) -> Unit,
    val onStartMacroRecording: () -> Unit,
    val onRenamePreset: (String, String) -> Unit,
    val onDeletePreset: (String) -> Unit,
    val onSetPresetExecutionMode: (String, ExecutionMode) -> Unit,
    val onSetPresetLoopCount: (String, Int) -> Unit,
    val onExportPresetJson: (ClickPreset) -> Unit,
    val onImportPresetJson: (String) -> Unit,
    val onImportPresetScript: (String) -> Unit,
    val onExportAllPresetsJson: () -> Unit,
    val onBatchImportPresetsJson: (String) -> Unit,
    val onApplyPresetFreezeStates: (Set<String>) -> Unit,
    val onBatchDeletePresets: (Set<String>) -> Unit,
    val onBatchCreateEmptyPresets: (Int, ExecutionMode) -> Unit,
    val onSavePresetTarget: (
        existingId: Int?,
        x: Float,
        y: Float,
        intervalMs: Long,
        pressMs: Long,
        jitter: Boolean,
        enabled: Boolean,
        actionType: ActionType,
        endX: Float,
        endY: Float,
        swipeDurationMs: Long,
        repeatCount: Int,
        refPresetId: String,
        subDurationMs: Long
    ) -> Unit,
    val onTogglePresetTargetEnabled: (Int, Boolean) -> Unit,
    val onDeletePresetTarget: (Int) -> Unit,
    val onThemeModeChange: (Int) -> Unit,
    val onDynamicColorChange: (Boolean) -> Unit,
    val onLauncherIconModeChange: (Int) -> Unit,
    val onLauncherDynamicIconChange: (Boolean) -> Unit,
    val onVolumeKeyToggleChange: (Boolean) -> Unit,
    val onRequestAddQuickTile: () -> Unit,
    val onProModeChange: (Boolean) -> Unit,
    val onPreferredBackendChange: (Int) -> Unit,
    val onRpcBridgeEnabledChange: (Boolean) -> Unit,
    val onResetRpcToken: () -> Unit,
    val onCopyText: (label: String, text: String) -> Unit
)
