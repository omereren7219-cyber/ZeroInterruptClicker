package com.zerointerrupt.clicker.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.TileService
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.switchmaterial.SwitchMaterial
import com.zerointerrupt.clicker.QuickTileService
import com.zerointerrupt.clicker.R
import com.zerointerrupt.clicker.model.ActionType
import com.zerointerrupt.clicker.model.ClickConfigStore
import com.zerointerrupt.clicker.model.ClickGlobalSettings
import com.zerointerrupt.clicker.model.ClickTarget
import com.zerointerrupt.clicker.model.ExecutionMode
import com.zerointerrupt.clicker.shizuku.ShizukuController
import com.zerointerrupt.clicker.shizuku.TouchMuxUserService
import com.zerointerrupt.clicker.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

object OverlayStateHub {
    private val _overlayVisible = MutableStateFlow(false)
    val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    private val _clickingRunning = MutableStateFlow(false)
    val clickingRunning: StateFlow<Boolean> = _clickingRunning.asStateFlow()

    private val _configVersion = MutableStateFlow(0L)
    val configVersion: StateFlow<Long> = _configVersion.asStateFlow()

    internal fun setOverlayVisible(context: Context, visible: Boolean) {
        _overlayVisible.value = visible
        requestQuickTileUpdate(context)
    }

    internal fun setClickingRunning(context: Context, running: Boolean) {
        _clickingRunning.value = running
        requestQuickTileUpdate(context)
    }

    internal fun bumpConfigVersion() {
        _configVersion.value = _configVersion.value + 1L
    }

    fun requestQuickTileUpdate(context: Context) {
        try {
            TileService.requestListeningState(
                context.applicationContext,
                ComponentName(context.applicationContext, QuickTileService::class.java)
            )
        } catch (_: Throwable) {
        }
    }
}

/**
 * Foreground overlay service implementing zero-relayout window orchestration,
 * multi-gesture anchors (TAP / SWIPE / HOLD), normalized aspect-safe coordinates,
 * and SpringAnimation edge snapping/collapsing.
 */
class OverlayService : Service(), ControlBarView.Listener {

    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private lateinit var themeColors: OverlayThemeColors
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoCollapseRunnable = Runnable { collapseControlBar() }

    private var controlBarView: ControlBarView? = null
    private var controlBarLp: WindowManager.LayoutParams? = null
    private var isBarCollapsed: Boolean = false
    private var dockRight: Boolean = false

    private var barPosAnimator: ValueAnimator? = null
    private var barMorphAnimator: ValueAnimator? = null
    private var barToolsScaleSpringY: SpringAnimation? = null
    private var pendingPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var currentMorphProgress: Float = 1f
    private var morphTargetProgress: Float = 1f
    private var morphEdgeMarginPx: Int = 0
    private var morphExpandedTopY: Int = 0
    private var morphPillTopOffsetPx: Int = 0
    private var morphScreenWidthPx: Int = 0
    private var morphScreenHeightPx: Int = 0
    private var dragScreenWidthPx: Int = 0
    private var dragScreenHeightPx: Int = 0
    private var handoffStaleBar: ControlBarView? = null
    private var pendingCommitBar: ControlBarView? = null
    private var pendingCommitRunnable: Runnable? = null
    private var handoffGeneration: Long = 0L
    // M3 Emphasized Decelerate: crisp launch with zero hesitation and silky coasting deceleration
    private val morphInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    private val glidePosInterpolator = PathInterpolator(0.05f, 0.84f, 0.14f, 1f)
    private val collapseFoldInterpolator = PathInterpolator(0.16f, 0.62f, 0.14f, 1f)
    private val linearInterpolator = LinearInterpolator()

    private var swipeConnectorView: SwipeConnectorOverlayView? = null
    private var swipeConnectorLp: WindowManager.LayoutParams? = null

    private var tunePanelView: View? = null
    private var tunePanelLp: WindowManager.LayoutParams? = null
    private var macroRecordOverlay: MacroRecordOverlay? = null
    private var normalOverlayAttached: Boolean = false
    private var resumeOverlayAfterRecord: Boolean = false

    private val targets = mutableListOf<ClickTarget>()
    private val anchorViews = LinkedHashMap<Int, AnchorPointView>()
    private val anchorLps = LinkedHashMap<Int, WindowManager.LayoutParams>()
    private val endAnchorViews = LinkedHashMap<Int, AnchorPointView>()
    private val endAnchorLps = LinkedHashMap<Int, WindowManager.LayoutParams>()

    private var selectedTargetId: Int = -1
    private var nextTargetId: Int = 1
    private var activePresetId: String = ""
    private var activeExecutionMode: ExecutionMode = ExecutionMode.CONCURRENT
    private var activePresetLoopCount: Int = 0
    private var lockedAlpha: Float = ClickGlobalSettings.DEFAULT_LOCKED_ALPHA
    private var isLocked: Boolean = false
    private var lastAutoLockOnStart: Boolean = true
    private var showMacroTrajectoriesPref: Boolean = true
    private var isClicking: Boolean = false
    private var runSessionSeq: Long = 0L
    private var pendingAutoLockStartRunnable: Runnable? = null
    private var engineMode: Int = TouchMuxUserService.MODE_IDLE

    private var engineCollectJob: Job? = null
    private var volumeToggleJob: Job? = null
    private var emergencyUngrabJob: Job? = null
    private var clickingCompletedJob: Job? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                onScreenGeometryChanged()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        themeColors = OverlayThemeColors.resolve(this)
        dockRight = ClickConfigStore.loadControlBarDockRight(this)
        val initialSettings = ClickConfigStore.loadSettings(this)
        lockedAlpha = initialSettings.lockedAnchorAlpha
            .coerceIn(ClickGlobalSettings.MIN_LOCKED_ALPHA, ClickGlobalSettings.MAX_LOCKED_ALPHA)
        lastAutoLockOnStart = initialSettings.autoLockOnStart
        showMacroTrajectoriesPref = initialSettings.showMacroTrajectories
        ShizukuController.initialize(this)
        startForegroundWithNotification()
        displayManager.registerDisplayListener(displayListener, null)

        engineCollectJob = serviceScope.launch {
            ShizukuController.engineSnapshot.collect { snap ->
                engineMode = snap.engineMode
                refreshControlBarState()
            }
        }

        volumeToggleJob = serviceScope.launch {
            ShizukuController.volumeKeyToggleEvents.collect {
                if (ClickConfigStore.loadSettings(this@OverlayService).volumeKeyToggleEnabled) {
                    onTogglePlayStop()
                }
            }
        }

        emergencyUngrabJob = serviceScope.launch {
            ShizukuController.emergencyUngrabEvents.collect {
                isClicking = false
                OverlayStateHub.setClickingRunning(this@OverlayService, false)
                applyLockMode(false)
                refreshAnchorVisuals()
                refreshControlBarState()
                Toast.makeText(
                    this@OverlayService,
                    "Ses kısma düğmesinin 3 saniye basılı tutulduğu algılandı; dokunma denetimi acil olarak serbest bırakıldı",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        clickingCompletedJob = serviceScope.launch {
            ShizukuController.clickingCompletedEvents.collect {
                if (isClicking) {
                    isClicking = false
                    OverlayStateHub.setClickingRunning(this@OverlayService, false)
                    refreshAnchorVisuals()
                    refreshControlBarState()
                    scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
                    Toast.makeText(
                        this@OverlayService,
                        "Planın çalıştırılması tamamlandı",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_OVERLAY -> {
                macroRecordOverlay?.dismissAll()
                macroRecordOverlay = null
                stopClickingInternal()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_RECORDING -> {
                enterRecordingMode()
            }
            ACTION_STOP_CLICKING, ACTION_RPC_STOP -> {
                ensureNormalOverlayAttached()
                stopClickingInternal()
            }
            ACTION_RPC_START -> {
                ensureNormalOverlayAttached()
                if (!isClicking) {
                    onTogglePlayStop()
                }
            }
            ACTION_RPC_SWITCH_PRESET -> {
                ensureNormalOverlayAttached()
                val targetId = intent.getStringExtra(EXTRA_PRESET_ID).orEmpty()
                val targetName = intent.getStringExtra(EXTRA_PRESET_NAME).orEmpty()
                val presets = ClickConfigStore.loadPresets(this)
                val match = when {
                    targetId.isNotBlank() -> presets.firstOrNull { it.id == targetId }
                    targetName.isNotBlank() -> presets.firstOrNull { it.name == targetName }
                    else -> null
                }
                if (match != null) {
                    val wasClicking = isClicking
                    ClickConfigStore.setActivePresetId(this, match.id)
                    replaceTargetsWithActivePreset()
                    OverlayStateHub.bumpConfigVersion()
                    if (wasClicking) {
                        continueClickingAfterPresetSwitch(showToast = false)
                    }
                }
            }
            ACTION_REFRESH_SETTINGS -> {
                val settings = ClickConfigStore.loadSettings(this)
                themeColors = OverlayThemeColors.resolve(this)
                controlBarView?.applyThemeColors(themeColors)
                macroRecordOverlay?.updateTheme(themeColors)
                lockedAlpha = settings.lockedAnchorAlpha.coerceIn(
                    ClickGlobalSettings.MIN_LOCKED_ALPHA,
                    ClickGlobalSettings.MAX_LOCKED_ALPHA
                )
                showMacroTrajectoriesPref = settings.showMacroTrajectories
                if (normalOverlayAttached) {
                    if (settings.autoLockOnStart && !lastAutoLockOnStart && !isLocked) {
                        applyLockMode(true)
                    }
                    lastAutoLockOnStart = settings.autoLockOnStart
                    reloadTargetSettingsFromStore()
                    if (shouldHideCurrentMacroOverlay()) {
                        removeAllAnchorWindows()
                    } else if (!isLocked) {
                        attachAllAnchors()
                    }
                    syncSwipeConnectorLayer()
                    if (tunePanelView != null) {
                        showOrUpdateTunePanel(forceShow = true)
                    }
                    refreshLockedAlphasIfNeeded()
                    refreshControlBarState()
                    if (settings.autoCollapseBar) {
                        scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
                    } else {
                        cancelAutoCollapse()
                    }
                } else {
                    lastAutoLockOnStart = settings.autoLockOnStart
                }
            }
            else -> {
                if (macroRecordOverlay?.isActive() == true) {
                    macroRecordOverlay?.dismissAll()
                    macroRecordOverlay = null
                }
                ensureNormalOverlayAttached()
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        themeColors = OverlayThemeColors.resolve(this)
        controlBarView?.applyThemeColors(themeColors)
        macroRecordOverlay?.updateTheme(themeColors)
        onScreenGeometryChanged()
        if (tunePanelView != null) {
            showOrUpdateTunePanel(forceShow = true)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        macroRecordOverlay?.dismissAll()
        macroRecordOverlay = null
        cancelBarSprings()
        displayManager.unregisterDisplayListener(displayListener)
        engineCollectJob?.cancel()
        volumeToggleJob?.cancel()
        emergencyUngrabJob?.cancel()
        clickingCompletedJob?.cancel()
        stopClickingInternal()
        dismissTunePanel()
        removeAllAnchorWindows()
        removeSwipeConnectorLayer()
        removeControlBar()
        normalOverlayAttached = false
        OverlayStateHub.setClickingRunning(this, false)
        OverlayStateHub.setOverlayVisible(this, false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopClickIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP_CLICKING },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val exitOverlayIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP_OVERLAY },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_touch_app)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_stop, getString(R.string.action_stop_clicking), stopClickIntent)
            .addAction(R.drawable.ic_close, getString(R.string.action_exit_overlay), exitOverlayIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createBaseOverlayParams(
        width: Int,
        height: Int,
        touchable: Boolean
    ): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!touchable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun loadInitialTargets() {
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        val preset = ClickConfigStore.getActivePreset(this)
        activePresetId = preset.id
        activeExecutionMode = preset.executionMode
        activePresetLoopCount = preset.presetLoopCount
        targets.clear()
        preset.targets.forEach { t ->
            val copy = t.copy()
            copy.remapToScreen(metrics.widthPx, metrics.heightPx)
            targets.add(copy)
        }
        if (targets.isEmpty()) {
            val settings = ClickConfigStore.loadSettings(this)
            val created = ClickTarget(
                id = 1,
                index = 1,
                x = metrics.widthPx * 0.5f,
                y = metrics.heightPx * 0.5f,
                intervalMs = settings.defaultIntervalMs,
                pressDurationMs = settings.defaultPressDurationMs,
                microJitter = settings.defaultMicroJitter,
                enabled = true,
                repeatCount = ClickTarget.defaultRepeatFor(activeExecutionMode)
            )
            created.setStartPixel(metrics.widthPx * 0.5f, metrics.heightPx * 0.5f, metrics.widthPx, metrics.heightPx)
            targets += created
            persistCurrentTargets()
        }
        nextTargetId = (targets.maxOfOrNull { it.id } ?: 0) + 1
        selectedTargetId = targets.firstOrNull()?.id ?: -1
    }

    private fun attachControlBar() {
        val bar = ControlBarView(this, themeColors, this)
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        val savedPos = ClickConfigStore.loadControlBarPosition(this)
        bar.setToolsExpanded(false)
        val initialW = bar.getExpandedWidthPx()
        val initialH = bar.getExpandedBarHeightPx()
        val marginPx = bar.getEdgeMarginPx()
        val defaultX = if (dockRight) {
            (metrics.widthPx - initialW - marginPx).coerceAtLeast(0)
        } else {
            marginPx.coerceAtMost((metrics.widthPx - initialW).coerceAtLeast(0))
        }
        val lp = createBaseOverlayParams(
            initialW,
            initialH,
            touchable = true
        ).apply {
            x = (savedPos?.first ?: defaultX).coerceIn(0, (metrics.widthPx - initialW).coerceAtLeast(0))
            y = (savedPos?.second ?: (metrics.heightPx * 0.25f).roundToInt())
                .coerceIn(0, (metrics.heightPx - initialH).coerceAtLeast(0))
        }
        dockRight = (lp.x + initialW / 2) >= (metrics.widthPx / 2)
        bar.setCollapsed(false, dockRight)
        bar.syncWindowPosition(lp.x, lp.y)
        windowManager.addView(bar, lp)
        controlBarView = bar
        controlBarLp = lp
        isBarCollapsed = false
        refreshControlBarState()
    }

    private fun reloadTargetSettingsFromStore() {
        val preset = ClickConfigStore.getActivePreset(this)
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        val stored = preset.targets.map { t -> t.copy().also { it.remapToScreen(metrics.widthPx, metrics.heightPx) } }
        val currentSig = targets.map {
            listOf(it.id, it.x, it.y, it.intervalMs, it.pressDurationMs, it.microJitter, it.enabled, it.actionType, it.endX, it.endY, it.swipeDurationMs, it.repeatCount, it.refPresetId, it.subDurationMs)
        }
        val storedSig = stored.map {
            listOf(it.id, it.x, it.y, it.intervalMs, it.pressDurationMs, it.microJitter, it.enabled, it.actionType, it.endX, it.endY, it.swipeDurationMs, it.repeatCount, it.refPresetId, it.subDurationMs)
        }
        val presetSwitched = activePresetId != preset.id
        if (presetSwitched ||
            activeExecutionMode != preset.executionMode ||
            activePresetLoopCount != preset.presetLoopCount ||
            currentSig != storedSig
        ) {
            val wasClicking = isClicking
            replaceTargetsWithActivePreset()
            if (wasClicking) {
                if (presetSwitched) {
                    continueClickingAfterPresetSwitch(showToast = false)
                } else if (targets.any { it.enabled }) {
                    pushTargetsToServiceAsync()
                } else {
                    stopClickingInternal()
                }
            }
        }
    }

    private fun replaceTargetsWithActivePreset() {
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        val preset = ClickConfigStore.getActivePreset(this)
        activePresetId = preset.id
        activeExecutionMode = preset.executionMode
        activePresetLoopCount = preset.presetLoopCount
        dismissTunePanel()
        removeAllAnchorWindows()
        targets.clear()
        preset.targets.forEach { t ->
            val copy = t.copy()
            copy.remapToScreen(metrics.widthPx, metrics.heightPx)
            targets.add(copy)
        }
        nextTargetId = (targets.maxOfOrNull { it.id } ?: 0) + 1
        selectedTargetId = targets.firstOrNull { it.actionType != ActionType.SUB_PRESET }?.id ?: -1
        syncSwipeConnectorLayer()
        attachAllAnchors()
        refreshControlBarState()
    }

    private fun persistCurrentTargets() {
        ClickConfigStore.saveTargets(this, targets)
        OverlayStateHub.bumpConfigVersion()
    }

    private fun removeViewSafely(view: View?) {
        if (view == null) return
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: Throwable) {
        }
    }

    private fun createSyncedControlBar(toolsExpanded: Boolean): ControlBarView {
        val (presetIdx, presetCount) = ClickConfigStore.getActivePresetIndexAndTotal(this)
        return ControlBarView(this, themeColors, this).apply {
            setToolsExpanded(toolsExpanded)
            updateState(
                isRunning = isClicking,
                isLocked = isLocked,
                engineMode = engineMode,
                presetIndex = presetIdx,
                presetCount = presetCount
            )
        }
    }

    private fun clearPendingHandoffCallbacks() {
        val listener = pendingPreDrawListener
        val bar = pendingCommitBar ?: controlBarView
        if (listener != null && bar != null) {
            val vto = bar.viewTreeObserver
            if (vto.isAlive) {
                vto.removeOnPreDrawListener(listener)
            }
        }
        pendingPreDrawListener = null
        val commit = pendingCommitRunnable
        if (commit != null) {
            pendingCommitBar?.removeCallbacks(commit)
            controlBarView?.removeCallbacks(commit)
        }
        pendingCommitRunnable = null
        pendingCommitBar = null
    }

    private fun swapToNewControlBarWindow(
        newBar: ControlBarView,
        newLp: WindowManager.LayoutParams,
        onCommitted: () -> Unit
    ) {
        clearPendingHandoffCallbacks()
        handoffStaleBar?.let { stale ->
            removeViewSafely(stale)
            handoffStaleBar = null
        }
        handoffGeneration += 1L
        val expectedGen = handoffGeneration
        val oldBar = controlBarView
        handoffStaleBar = oldBar
        controlBarView = newBar
        controlBarLp = newLp
        newBar.syncWindowPosition(newLp.x, newLp.y)
        windowManager.addView(newBar, newLp)

        val cleanupStale = Runnable {
            if (handoffStaleBar === oldBar) {
                handoffStaleBar = null
                removeViewSafely(oldBar)
            }
        }

        var committed = false
        val finalizeCommit = Runnable {
            if (committed) return@Runnable
            committed = true
            if (pendingCommitBar === newBar) {
                pendingCommitRunnable?.let { newBar.removeCallbacks(it) }
                pendingCommitRunnable = null
                pendingCommitBar = null
            }
            if (handoffGeneration != expectedGen || controlBarView !== newBar || !newBar.isAttachedToWindow) {
                cleanupStale.run()
                return@Runnable
            }
            if (handoffStaleBar === oldBar && oldBar != null && oldBar.hasActiveTouchGesture()) {
                // Keep oldBar alive invisibly until its in-flight ACTION_DOWN -> ACTION_UP completes
                // so a rapid tap landing during window handoff never gets dropped mid-gesture.
                oldBar.alpha = 0f
                oldBar.runWhenTouchIdle {
                    oldBar.removeCallbacks(cleanupStale)
                    cleanupStale.run()
                }
                oldBar.postDelayed(cleanupStale, 450L)
            } else {
                cleanupStale.run()
            }
            onCommitted()
        }
        pendingCommitBar = newBar
        pendingCommitRunnable = finalizeCommit

        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val vto = newBar.viewTreeObserver
                if (vto.isAlive) {
                    vto.removeOnPreDrawListener(this)
                }
                if (pendingPreDrawListener === this) {
                    pendingPreDrawListener = null
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vto.isAlive) {
                    vto.registerFrameCommitCallback {
                        finalizeCommit.run()
                    }
                } else {
                    newBar.postOnAnimation(finalizeCommit)
                }
                return true
            }
        }
        pendingPreDrawListener = listener
        newBar.viewTreeObserver.addOnPreDrawListener(listener)
        // Safety net only if frame commit callback is dropped during teardown.
        newBar.postDelayed(finalizeCommit, 900L)
    }

    private fun cancelBarSprings() {
        handoffGeneration += 1L
        clearPendingHandoffCallbacks()
        handoffStaleBar?.let { stale ->
            removeViewSafely(stale)
            handoffStaleBar = null
        }
        barPosAnimator?.cancel()
        barMorphAnimator?.cancel()
        barToolsScaleSpringY?.cancel()
        barPosAnimator = null
        barMorphAnimator = null
        barToolsScaleSpringY = null
    }

    private fun animateControlBarPosX(
        targetX: Int,
        onEnd: (() -> Unit)? = null
    ) {
        val bar = controlBarView ?: run { onEnd?.invoke(); return }
        val lp = controlBarLp ?: run { onEnd?.invoke(); return }
        barPosAnimator?.cancel()
        barPosAnimator = null
        if (lp.x == targetX) {
            bar.syncWindowPosition(lp.x, lp.y)
            onEnd?.invoke()
            return
        }
        val snapDist = kotlin.math.abs(targetX - lp.x)
        val anim = ValueAnimator.ofInt(lp.x, targetX).apply {
            duration = (140f + snapDist * 0.16f).toLong().coerceIn(150L, 240L)
            interpolator = morphInterpolator
            addUpdateListener { va ->
                if (controlBarView === bar && bar.isAttachedToWindow) {
                    lp.x = va.animatedValue as Int
                    bar.syncWindowPosition(lp.x, lp.y)
                    try {
                        windowManager.updateViewLayout(bar, lp)
                    } catch (_: Throwable) {
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }
                override fun onAnimationEnd(animation: Animator) {
                    if (barPosAnimator === animation) {
                        barPosAnimator = null
                    }
                    if (!canceled && controlBarView === bar && bar.isAttachedToWindow) {
                        lp.x = targetX
                        bar.syncWindowPosition(lp.x, lp.y)
                        try {
                            windowManager.updateViewLayout(bar, lp)
                        } catch (_: Throwable) {
                        }
                        onEnd?.invoke()
                    }
                }
            })
        }
        barPosAnimator = anim
        anim.start()
    }

    private fun startBarMorphAnimation(
        targetProgress: Float,
        screenWidthPx: Int,
        screenHeightPx: Int,
        startHostX: Int? = null,
        endHostX: Int? = null
    ) {
        val bar = controlBarView ?: return
        val lp = controlBarLp ?: return
        barMorphAnimator?.cancel()
        barMorphAnimator = null
        val clampedTarget = targetProgress.coerceIn(0f, 1f)
        val startProgress = currentMorphProgress.coerceIn(0f, 1f)
        morphTargetProgress = clampedTarget
        morphScreenWidthPx = screenWidthPx
        morphScreenHeightPx = screenHeightPx

        val fromHostX = startHostX ?: lp.x
        val toHostX = endHostX ?: lp.x
        val glideDist = kotlin.math.abs(toHostX - fromHostX)
        val hasGlide = glideDist > 2

        if (kotlin.math.abs(clampedTarget - startProgress) < 0.001f && !hasGlide) {
            finalizeBarMorphState(bar, lp, clampedTarget, screenWidthPx, screenHeightPx)
            return
        }

        val anim = if (hasGlide) {
            val isCollapsing = clampedTarget < startProgress
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (isCollapsing) {
                    (240f + glideDist * 0.14f).toLong().coerceIn(250L, 340L)
                } else {
                    240L
                }
                interpolator = if (isCollapsing) linearInterpolator else morphInterpolator
                addUpdateListener { va ->
                    if (controlBarView !== bar || !bar.isAttachedToWindow) return@addUpdateListener
                    val rawT = (va.animatedValue as Float).coerceIn(0f, 1f)
                    val glideT = if (isCollapsing) glidePosInterpolator.getInterpolation(rawT) else rawT
                    val foldT = if (isCollapsing) collapseFoldInterpolator.getInterpolation(rawT) else rawT
                    val nextX = Math.round(fromHostX + (toHostX - fromHostX) * glideT)
                    if (nextX != lp.x) {
                        lp.x = nextX
                        bar.syncWindowPosition(lp.x, lp.y)
                        try {
                            windowManager.updateViewLayout(bar, lp)
                        } catch (_: Throwable) {
                        }
                    }
                    val progress = (startProgress + (clampedTarget - startProgress) * foldT).coerceIn(0f, 1f)
                    currentMorphProgress = progress
                    bar.applyMorphProgress(progress, dockRight, morphPillTopOffsetPx)
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var canceled = false
                    override fun onAnimationCancel(animation: Animator) {
                        canceled = true
                    }
                    override fun onAnimationEnd(animation: Animator) {
                        if (barMorphAnimator === animation) {
                            barMorphAnimator = null
                        }
                        if (!canceled && controlBarView === bar && bar.isAttachedToWindow) {
                            finalizeBarMorphState(bar, lp, clampedTarget, screenWidthPx, screenHeightPx)
                        }
                    }
                })
            }
        } else {
            ValueAnimator.ofFloat(startProgress, clampedTarget).apply {
                duration = 240L
                interpolator = morphInterpolator
                addUpdateListener { va ->
                    if (controlBarView !== bar || !bar.isAttachedToWindow) return@addUpdateListener
                    val progress = (va.animatedValue as Float).coerceIn(0f, 1f)
                    currentMorphProgress = progress
                    // Zero WindowManager IPC during morph: all motion stays local inside locked morphHostWidthPx.
                    bar.applyMorphProgress(progress, dockRight, morphPillTopOffsetPx)
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var canceled = false
                    override fun onAnimationCancel(animation: Animator) {
                        canceled = true
                    }
                    override fun onAnimationEnd(animation: Animator) {
                        if (barMorphAnimator === animation) {
                            barMorphAnimator = null
                        }
                        if (!canceled && controlBarView === bar && bar.isAttachedToWindow) {
                            finalizeBarMorphState(bar, lp, clampedTarget, screenWidthPx, screenHeightPx)
                        }
                    }
                })
            }
        }
        barMorphAnimator = anim
        anim.start()
    }

    private fun finalizeBarMorphState(
        morphBar: ControlBarView,
        @Suppress("UNUSED_PARAMETER") morphLp: WindowManager.LayoutParams,
        targetProgress: Float,
        screenWidthPx: Int,
        screenHeightPx: Int
    ) {
        val toolsOpen = morphBar.isToolsExpanded()
        if (targetProgress <= 0f) {
            currentMorphProgress = 0f
            isBarCollapsed = true
            val restingBar = createSyncedControlBar(toolsOpen)
            restingBar.setCollapsed(true, dockRight)
            val collapsedW = restingBar.getCollapsedWidthPx()
            val collapsedH = restingBar.getCollapsedHeightPx()
            val restingLp = createBaseOverlayParams(collapsedW, collapsedH, touchable = true).apply {
                x = if (dockRight) (screenWidthPx - collapsedW).coerceAtLeast(0) else 0
                y = (morphExpandedTopY + morphPillTopOffsetPx)
                    .coerceIn(0, (screenHeightPx - collapsedH).coerceAtLeast(0))
            }
            swapToNewControlBarWindow(restingBar, restingLp) {
                ClickConfigStore.saveControlBarCollapseState(this@OverlayService, true, dockRight)
            }
        } else {
            currentMorphProgress = 1f
            isBarCollapsed = false
            val restingBar = createSyncedControlBar(toolsOpen)
            restingBar.setCollapsed(false, dockRight)
            val expandedW = restingBar.getExpandedWidthPx()
            val expandedH = restingBar.getExpandedBarHeightPx()
            val restingLp = createBaseOverlayParams(expandedW, expandedH, touchable = true).apply {
                x = if (dockRight) {
                    (screenWidthPx - expandedW - morphEdgeMarginPx).coerceAtLeast(0)
                } else {
                    morphEdgeMarginPx.coerceAtMost((screenWidthPx - expandedW).coerceAtLeast(0))
                }
                y = morphExpandedTopY.coerceIn(0, (screenHeightPx - expandedH).coerceAtLeast(0))
            }
            swapToNewControlBarWindow(restingBar, restingLp) {
                ClickConfigStore.saveControlBarCollapseState(this@OverlayService, false, dockRight)
                ClickConfigStore.saveControlBarPosition(this@OverlayService, restingLp.x, restingLp.y)
                scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
            }
        }
    }

    private fun animateBarToolsUnfold(bar: ControlBarView) {
        barToolsScaleSpringY?.cancel()
        bar.pivotY = 0f
        bar.scaleY = 0.94f
        barToolsScaleSpringY = SpringAnimation(bar, DynamicAnimation.SCALE_Y, 1f).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            spring.stiffness = 380f
            addEndListener { _, _, _, _ -> barToolsScaleSpringY = null }
            start()
        }
    }

    private fun removeControlBar() {
        cancelBarSprings()
        removeViewSafely(handoffStaleBar)
        handoffStaleBar = null
        removeViewSafely(controlBarView)
        controlBarView = null
        controlBarLp = null
    }

    private fun buildExpandedDisplayEntries(): List<ClickConfigStore.DisplayTargetEntry> {
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        val presets = ClickConfigStore.loadPresets(this)
        val out = mutableListOf<ClickConfigStore.DisplayTargetEntry>()
        for (t in targets) {
            if (t.actionType == ActionType.SUB_PRESET) {
                if (!t.enabled || t.refPresetId.isBlank()) continue
                val child = presets.firstOrNull { it.id == t.refPresetId } ?: continue
                out += ClickConfigStore.collectExpandedDisplayTargets(
                    presets = presets,
                    preset = child,
                    screenW = metrics.widthPx,
                    screenH = metrics.heightPx,
                    fromChild = true
                )
            } else {
                out += ClickConfigStore.DisplayTargetEntry(t, isExpandedChild = false)
            }
        }
        return out
    }

    private fun shouldHideCurrentMacroOverlay(): Boolean {
        if (showMacroTrajectoriesPref) return false
        val presets = ClickConfigStore.loadPresets(this)
        val activePreset = presets.firstOrNull { it.id == activePresetId }
            ?: ClickConfigStore.getActivePreset(this)
        return ClickConfigStore.isMacroPreset(presets, activePreset)
    }

    private fun syncSwipeConnectorLayer() {
        if (shouldHideCurrentMacroOverlay()) {
            removeSwipeConnectorLayer()
            return
        }
        val displayEntries = buildExpandedDisplayEntries()
        val hasSwipes = displayEntries.any { it.target.actionType == ActionType.SWIPE }
        val hasExpandedChildren = displayEntries.any { it.isExpandedChild }
        val hasVisibleTargets = displayEntries.isNotEmpty()
        if (!hasSwipes && !hasExpandedChildren && !(isLocked && hasVisibleTargets)) {
            removeSwipeConnectorLayer()
            return
        }
        val safeAlpha = lockedAlpha.coerceIn(
            ClickGlobalSettings.MIN_LOCKED_ALPHA,
            ClickGlobalSettings.MAX_LOCKED_ALPHA
        )
        var view = swipeConnectorView
        if (view == null) {
            view = SwipeConnectorOverlayView(this)
            val lp = createBaseOverlayParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                touchable = false
            ).apply {
                alpha = safeAlpha
            }
            windowManager.addView(view, lp)
            swipeConnectorView = view
            swipeConnectorLp = lp
        } else {
            swipeConnectorLp?.let { lp ->
                if (lp.alpha != safeAlpha) {
                    lp.alpha = safeAlpha
                    windowManager.updateViewLayout(view, lp)
                }
            }
        }
        view.updateExpandedSwipes(displayEntries, isClicking, isLocked)
    }

    private fun removeSwipeConnectorLayer() {
        val v = swipeConnectorView ?: return
        try {
            windowManager.removeViewImmediate(v)
        } catch (_: Throwable) {
        }
        swipeConnectorView = null
        swipeConnectorLp = null
    }

    private fun bringControlBarToFrontIfNeeded() {
        val bar = controlBarView ?: return
        val lp = controlBarLp ?: return
        try {
            windowManager.removeViewImmediate(bar)
            windowManager.addView(bar, lp)
        } catch (_: Throwable) {
        }
    }

    private fun attachAllAnchors() {
        if (isLocked || shouldHideCurrentMacroOverlay()) return
        for (t in targets) {
            attachSingleAnchor(t)
        }
        refreshAnchorVisuals()
    }

    private fun attachSingleAnchor(target: ClickTarget) {
        if (isLocked || target.actionType == ActionType.SUB_PRESET) {
            anchorViews.remove(target.id)?.let { removeViewSafely(it) }
            anchorLps.remove(target.id)
            endAnchorViews.remove(target.id)?.let { removeViewSafely(it) }
            endAnchorLps.remove(target.id)
            return
        }
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        target.remapToScreen(metrics.widthPx, metrics.heightPx)

        if (!anchorViews.containsKey(target.id)) {
            val view = AnchorPointView(
                context = this,
                targetId = target.id,
                isEndHandle = false,
                onDragMove = { id, isEnd, nx, ny -> handleAnchorDragMove(id, isEnd, nx, ny) },
                onDragFinish = { id, _ -> handleAnchorDragFinish(id) },
                onTap = { id, _ -> handleAnchorTap(id) }
            )
            val lp = createBaseOverlayParams(
                view.viewSizePx,
                view.viewSizePx,
                touchable = !isLocked
            ).apply {
                x = (target.x - view.halfSizePx).roundToInt()
                y = (target.y - view.halfSizePx).roundToInt()
                alpha = if (isLocked) lockedAlpha else 1.0f
            }
            view.syncWindowPosition(lp.x, lp.y)
            windowManager.addView(view, lp)
            anchorViews[target.id] = view
            anchorLps[target.id] = lp
        }

        if (target.actionType == ActionType.SWIPE) {
            target.ensureDefaultSwipeEndpoint(metrics.widthPx, metrics.heightPx)
            if (!endAnchorViews.containsKey(target.id)) {
                val endView = AnchorPointView(
                    context = this,
                    targetId = target.id,
                    isEndHandle = true,
                    onDragMove = { id, isEnd, nx, ny -> handleAnchorDragMove(id, isEnd, nx, ny) },
                    onDragFinish = { id, _ -> handleAnchorDragFinish(id) },
                    onTap = { id, _ -> handleAnchorTap(id) }
                )
                val endLp = createBaseOverlayParams(
                    endView.viewSizePx,
                    endView.viewSizePx,
                    touchable = !isLocked
                ).apply {
                    x = (target.endX - endView.halfSizePx).roundToInt()
                    y = (target.endY - endView.halfSizePx).roundToInt()
                    alpha = if (isLocked) lockedAlpha else 1.0f
                }
                endView.syncWindowPosition(endLp.x, endLp.y)
                windowManager.addView(endView, endLp)
                endAnchorViews[target.id] = endView
                endAnchorLps[target.id] = endLp
            }
        } else {
            endAnchorViews.remove(target.id)?.let { v ->
                try {
                    windowManager.removeViewImmediate(v)
                } catch (_: Throwable) {
                }
            }
            endAnchorLps.remove(target.id)
        }
    }

    private fun removeAllAnchorWindows() {
        for (v in anchorViews.values) {
            try {
                windowManager.removeViewImmediate(v)
            } catch (_: Throwable) {
            }
        }
        anchorViews.clear()
        anchorLps.clear()

        for (v in endAnchorViews.values) {
            try {
                windowManager.removeViewImmediate(v)
            } catch (_: Throwable) {
            }
        }
        endAnchorViews.clear()
        endAnchorLps.clear()
    }

    private fun handleAnchorDragMove(targetId: Int, isEnd: Boolean, newLpX: Int, newLpY: Int) {
        val view = (if (isEnd) endAnchorViews[targetId] else anchorViews[targetId]) ?: return
        val lp = (if (isEnd) endAnchorLps[targetId] else anchorLps[targetId]) ?: return
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        val minX = -view.halfSizePx
        val maxX = metrics.widthPx - 1 - view.halfSizePx
        val minY = -view.halfSizePx
        val maxY = metrics.heightPx - 1 - view.halfSizePx

        lp.x = newLpX.coerceIn(minX, maxX.coerceAtLeast(minX))
        lp.y = newLpY.coerceIn(minY, maxY.coerceAtLeast(minY))
        windowManager.updateViewLayout(view, lp)

        val cx = (lp.x + view.halfSizePx).toFloat()
        val cy = (lp.y + view.halfSizePx).toFloat()
        targets.firstOrNull { it.id == targetId }?.let { t ->
            if (isEnd) {
                t.setEndPixel(cx, cy, metrics.widthPx, metrics.heightPx)
            } else {
                t.setStartPixel(cx, cy, metrics.widthPx, metrics.heightPx)
            }
        }
        swipeConnectorView?.updateExpandedSwipes(buildExpandedDisplayEntries(), isClicking, isLocked)
    }

    private fun handleAnchorDragFinish(targetId: Int) {
        selectedTargetId = targetId
        persistCurrentTargets()
        refreshAnchorVisuals()
        if (isClicking) {
            pushTargetsToServiceAsync()
        }
    }

    private fun handleAnchorTap(targetId: Int) {
        selectedTargetId = targetId
        refreshAnchorVisuals()
        showOrUpdateTunePanel(forceShow = true)
    }

    private fun refreshAnchorVisuals() {
        targets.forEachIndexed { idx, t ->
            t.index = idx + 1
            anchorViews[t.id]?.setVisualState(
                index = t.index,
                enabled = t.enabled,
                jitter = t.microJitter,
                action = t.actionType,
                isLocked = isLocked,
                isRunning = isClicking,
                isSelected = (t.id == selectedTargetId)
            )
            endAnchorViews[t.id]?.setVisualState(
                index = t.index,
                enabled = t.enabled,
                jitter = t.microJitter,
                action = t.actionType,
                isLocked = isLocked,
                isRunning = isClicking,
                isSelected = (t.id == selectedTargetId)
            )
        }
        syncSwipeConnectorLayer()
    }

    private fun refreshControlBarState() {
        val (presetIdx, presetCount) = ClickConfigStore.getActivePresetIndexAndTotal(this)
        controlBarView?.updateState(
            isRunning = isClicking,
            isLocked = isLocked,
            engineMode = engineMode,
            presetIndex = presetIdx,
            presetCount = presetCount
        )
    }

    private fun refreshLockedAlphasIfNeeded() {
        val targetAlpha = if (isLocked) lockedAlpha else 1.0f
        for ((id, view) in anchorViews) {
            val lp = anchorLps[id] ?: continue
            if (lp.alpha != targetAlpha) {
                lp.alpha = targetAlpha
                windowManager.updateViewLayout(view, lp)
            }
        }
        for ((id, view) in endAnchorViews) {
            val lp = endAnchorLps[id] ?: continue
            if (lp.alpha != targetAlpha) {
                lp.alpha = targetAlpha
                windowManager.updateViewLayout(view, lp)
            }
        }
        val safeCanvasAlpha = lockedAlpha.coerceIn(
            ClickGlobalSettings.MIN_LOCKED_ALPHA,
            ClickGlobalSettings.MAX_LOCKED_ALPHA
        )
        swipeConnectorView?.let { v ->
            swipeConnectorLp?.let { lp ->
                if (lp.alpha != safeCanvasAlpha) {
                    lp.alpha = safeCanvasAlpha
                    windowManager.updateViewLayout(v, lp)
                }
            }
        }
    }

    /**
     * Switches between draggable per-anchor windows (unlocked) and a single unified
     * FLAG_NOT_TOUCHABLE overlay layer (locked) so Android 12+ BLOCK_UNTRUSTED touch-occlusion
     * opacity never compounds across stacked overlay windows or drops cross-app / desktop touches.
     */
    private fun applyLockMode(locked: Boolean) {
        if (isLocked == locked) return
        isLocked = locked
        if (locked) {
            dismissTunePanel()
            removeAllAnchorWindows()
        } else {
            syncSwipeConnectorLayer()
            attachAllAnchors()
        }
        refreshLockedAlphasIfNeeded()
        refreshAnchorVisuals()
        refreshControlBarState()
    }

    private fun scheduleAutoCollapse(delayMs: Long) {
        cancelAutoCollapse()
        if (isBarCollapsed) return
        if (tunePanelView != null) return
        if (!ClickConfigStore.loadSettings(this).autoCollapseBar) return
        mainHandler.postDelayed(autoCollapseRunnable, delayMs)
    }

    private fun cancelAutoCollapse() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
    }

    private fun collapseControlBar() {
        var bar = controlBarView ?: return
        var lp = controlBarLp ?: return
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        cancelAutoCollapse()
        dismissTunePanel()

        if (barMorphAnimator != null) {
            if (morphTargetProgress <= 0f) return
            morphTargetProgress = 0f
            barMorphAnimator?.cancel()
            barMorphAnimator = null
            startBarMorphAnimation(0f, metrics.widthPx, metrics.heightPx)
            return
        }
        if (pendingPreDrawListener != null) {
            if (morphTargetProgress <= 0f) return
            cancelBarSprings()
            bar = controlBarView ?: return
            lp = controlBarLp ?: return
        }
        if (isBarCollapsed) return
        cancelBarSprings()

        val expandedW = bar.getExpandedWidthPx()
        val expandedH = bar.getExpandedBarHeightPx()
        val collapsedH = bar.getCollapsedHeightPx()
        val hostW = bar.getMorphHostWidthPx().coerceAtMost(metrics.widthPx)
        val alreadyMorphHost = lp.width == hostW
        val edgeMargin = bar.getEdgeMarginPx().coerceAtMost(((metrics.widthPx - expandedW) / 2).coerceAtLeast(0))
        if (!alreadyMorphHost) {
            dockRight = (lp.x + expandedW / 2) >= (metrics.widthPx / 2)
        }
        bar.setDockRight(dockRight)
        val dockEdgeX = if (dockRight) {
            (metrics.widthPx - expandedW - edgeMargin).coerceAtLeast(0)
        } else {
            edgeMargin
        }
        val dockHostX = if (dockRight) (metrics.widthPx - hostW).coerceAtLeast(0) else 0
        // Translate host window proportionally so the expanded card inside morphBar starts pixel-exact at lp.x.
        val startHostX = if (alreadyMorphHost) lp.x else dockHostX + (lp.x - dockEdgeX)

        morphEdgeMarginPx = edgeMargin
        morphExpandedTopY = lp.y.coerceIn(0, (metrics.heightPx - expandedH).coerceAtLeast(0))
        val targetCollapsedY = (morphExpandedTopY + (expandedH - collapsedH) / 2)
            .coerceIn(0, (metrics.heightPx - collapsedH).coerceAtLeast(0))
        morphPillTopOffsetPx = (targetCollapsedY - morphExpandedTopY)
            .coerceIn(0, (expandedH - collapsedH).coerceAtLeast(0))
        morphScreenWidthPx = metrics.widthPx
        morphScreenHeightPx = metrics.heightPx

        val startProgress = if (alreadyMorphHost) currentMorphProgress.coerceIn(0f, 1f) else 1f
        currentMorphProgress = startProgress
        morphTargetProgress = 0f

        val morphBar = createSyncedControlBar(bar.isToolsExpanded())
        morphBar.prepareMorphLayout(dockRight, morphPillTopOffsetPx, initiallyCollapsed = false)
        if (startProgress < 1f) {
            morphBar.applyMorphProgress(startProgress, dockRight, morphPillTopOffsetPx)
        }
        val morphLp = createBaseOverlayParams(hostW, expandedH, touchable = true).apply {
            x = startHostX
            y = morphExpandedTopY
        }
        swapToNewControlBarWindow(morphBar, morphLp) {
            if (controlBarView === morphBar && morphTargetProgress <= 0f && barMorphAnimator == null) {
                startBarMorphAnimation(
                    targetProgress = 0f,
                    screenWidthPx = metrics.widthPx,
                    screenHeightPx = metrics.heightPx,
                    startHostX = startHostX,
                    endHostX = dockHostX
                )
            }
        }
    }

    private fun expandControlBar() {
        var bar = controlBarView ?: return
        var lp = controlBarLp ?: return
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        cancelAutoCollapse()

        if (barMorphAnimator != null) {
            if (morphTargetProgress >= 1f) return
            val hostW = bar.getMorphHostWidthPx().coerceAtMost(metrics.widthPx)
            val dockHostX = if (dockRight) (metrics.widthPx - hostW).coerceAtLeast(0) else 0
            morphEdgeMarginPx = bar.getEdgeMarginPx()
                .coerceAtMost(((metrics.widthPx - bar.getExpandedWidthPx()) / 2).coerceAtLeast(0))
            isBarCollapsed = false
            morphTargetProgress = 1f
            barMorphAnimator?.cancel()
            barMorphAnimator = null
            startBarMorphAnimation(
                targetProgress = 1f,
                screenWidthPx = metrics.widthPx,
                screenHeightPx = metrics.heightPx,
                startHostX = lp.x,
                endHostX = dockHostX
            )
            return
        }
        if (pendingPreDrawListener != null) {
            if (morphTargetProgress >= 1f) return
            cancelBarSprings()
            bar = controlBarView ?: return
            lp = controlBarLp ?: return
        }
        if (!isBarCollapsed) return
        cancelBarSprings()

        val expandedW = bar.getExpandedWidthPx()
        val expandedH = bar.getExpandedBarHeightPx()
        val collapsedH = bar.getCollapsedHeightPx()
        val hostW = bar.getMorphHostWidthPx().coerceAtMost(metrics.widthPx)
        val currentCollapsedY = lp.y.coerceIn(0, (metrics.heightPx - collapsedH).coerceAtLeast(0))

        morphExpandedTopY = (currentCollapsedY - (expandedH - collapsedH) / 2)
            .coerceIn(0, (metrics.heightPx - expandedH).coerceAtLeast(0))
        morphPillTopOffsetPx = (currentCollapsedY - morphExpandedTopY)
            .coerceIn(0, (expandedH - collapsedH).coerceAtLeast(0))
        morphEdgeMarginPx = bar.getEdgeMarginPx()
            .coerceAtMost(((metrics.widthPx - expandedW) / 2).coerceAtLeast(0))
        morphScreenWidthPx = metrics.widthPx
        morphScreenHeightPx = metrics.heightPx

        isBarCollapsed = false
        currentMorphProgress = 0f
        morphTargetProgress = 1f

        val morphBar = createSyncedControlBar(bar.isToolsExpanded())
        morphBar.prepareMorphLayout(dockRight, morphPillTopOffsetPx, initiallyCollapsed = true)
        val morphLp = createBaseOverlayParams(hostW, expandedH, touchable = true).apply {
            x = if (dockRight) (metrics.widthPx - hostW).coerceAtLeast(0) else 0
            y = morphExpandedTopY
        }
        swapToNewControlBarWindow(morphBar, morphLp) {
            if (controlBarView === morphBar && morphTargetProgress >= 1f && barMorphAnimator == null) {
                startBarMorphAnimation(1f, metrics.widthPx, metrics.heightPx)
            }
        }
    }

    override fun onSwitchPreset() {
        val presets = ClickConfigStore.loadPresets(this)
        val switchable = presets.filter { !it.isFrozen }
        val active = ClickConfigStore.getActivePreset(this)
        if (switchable.isEmpty()) {
            Toast.makeText(
                this,
                "Tüm planlar dondurulmuş. Dondurmayı kaldırmak için plan sayfasının sağ üst köşesindeki ⋮ menüsünü kullanın",
                Toast.LENGTH_SHORT
            ).show()
            scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
            return
        }
        if (switchable.size == 1 && switchable[0].id == active.id) {
            val frozenCount = (presets.size - 1).coerceAtLeast(0)
            val msg = if (frozenCount > 0) {
                "Şu anda yalnızca 1 plan dondurulmamış:「${active.name}」(dondurulmuş diğer $frozenCount plan atlandı)"
            } else {
                "Şu anda yalnızca 1 plan var:「${active.name}」. Ana ekrandan yeni plan ekleyebilirsiniz"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
            return
        }
        val wasClicking = isClicking
        val (newIdx, total, nextPreset) = ClickConfigStore.switchToNextPreset(this)
        replaceTargetsWithActivePreset()
        OverlayStateHub.bumpConfigVersion()
        if (wasClicking) {
            continueClickingAfterPresetSwitch(
                showToast = true,
                switchIdx = newIdx,
                switchTotal = total,
                presetName = nextPreset.name
            )
        } else {
            Toast.makeText(
                this,
                "Plan değiştiriliyor ${newIdx + 1}/$total: ${nextPreset.name} (${targets.size} nokta)",
                Toast.LENGTH_SHORT
            ).show()
            scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
        }
    }

    private fun continueClickingAfterPresetSwitch(
        showToast: Boolean,
        switchIdx: Int = 0,
        switchTotal: Int = 1,
        presetName: String = ""
    ) {
        if (targets.none { it.enabled }) {
            stopClickingInternal()
            if (showToast) {
                Toast.makeText(
                    this,
                    "Plan değiştiriliyor ${switchIdx + 1}/$switchTotal: $presetName (etkin düğüm yok, durduruldu)",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }
        if (!ShizukuController.isServiceConnected()) {
            stopClickingInternal()
            Toast.makeText(this, "Shizuku dokunma hizmetine henüz bağlanılmadı; çalışma durduruldu", Toast.LENGTH_SHORT).show()
            return
        }
        if (showToast) {
            Toast.makeText(
                this,
                "Plan değiştiriliyor ${switchIdx + 1}/$switchTotal: $presetName · Kesintisiz çalışıyor",
                Toast.LENGTH_SHORT
            ).show()
        }
        startClickingInternal()
    }

    override fun onToggleToolsExpanded() {
        cancelAutoCollapse()
        val bar = controlBarView ?: return
        val lp = controlBarLp ?: return
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        val nextExpanded = !bar.isToolsExpanded()
        bar.setToolsExpanded(nextExpanded)
        if (!nextExpanded) {
            dismissTunePanel()
        }
        lp.width = bar.getCurrentWidthPx()
        lp.height = bar.getCurrentHeightPx()
        lp.y = lp.y.coerceIn(0, (metrics.heightPx - lp.height).coerceAtLeast(0))
        bar.syncWindowPosition(lp.x, lp.y)
        windowManager.updateViewLayout(bar, lp)
        animateBarToolsUnfold(bar)
        scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
    }

    override fun onCollapseRequested() {
        collapseControlBar()
    }

    override fun onExpandRequested() {
        expandControlBar()
    }

    override fun onDragStart() {
        cancelAutoCollapse()
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        dragScreenWidthPx = metrics.widthPx
        dragScreenHeightPx = metrics.heightPx
        val wasMorphHost = barMorphAnimator != null ||
            (controlBarLp?.width == controlBarView?.getMorphHostWidthPx())
        cancelBarSprings()
        if (wasMorphHost) {
            isBarCollapsed = false
            currentMorphProgress = 1f
            val bar = controlBarView
            val lp = controlBarLp
            if (bar != null && lp != null) {
                bar.setCollapsed(false, dockRight)
                val expandedW = bar.getExpandedWidthPx()
                val expandedH = bar.getExpandedBarHeightPx()
                val hostW = lp.width
                val edgeMargin = bar.getEdgeMarginPx()
                    .coerceAtMost(((metrics.widthPx - expandedW) / 2).coerceAtLeast(0))
                val dockHostX = if (dockRight) (metrics.widthPx - hostW).coerceAtLeast(0) else 0
                val dockEdgeX = if (dockRight) {
                    (metrics.widthPx - expandedW - edgeMargin).coerceAtLeast(0)
                } else {
                    edgeMargin
                }
                lp.width = expandedW
                lp.height = expandedH
                lp.x = (lp.x - dockHostX + dockEdgeX)
                    .coerceIn(0, (metrics.widthPx - expandedW).coerceAtLeast(0))
                bar.syncWindowPosition(lp.x, lp.y)
                try {
                    windowManager.updateViewLayout(bar, lp)
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun onTogglePlayStop() {
        if (pendingAutoLockStartRunnable != null) {
            pendingAutoLockStartRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingAutoLockStartRunnable = null
            if (!isClicking) {
                refreshControlBarState()
                scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
                return
            }
        }
        if (isClicking) {
            stopClickingInternal()
            return
        }
        if (targets.none { it.enabled }) {
            Toast.makeText(this, "En az bir hareket bağlantı noktası etkinleştirin", Toast.LENGTH_SHORT).show()
            scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
            return
        }
        if (!ShizukuController.isServiceConnected()) {
            ShizukuController.evaluateShizukuState()
            Toast.makeText(this, "Shizuku dokunma hizmetine henüz bağlanılmadı. Ana ekrandan izin verip bağlanın", Toast.LENGTH_SHORT).show()
            scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
            return
        }

        val settings = ClickConfigStore.loadSettings(this)
        if (!isLocked && settings.autoLockOnStart) {
            applyLockMode(true)
            // Let WindowManager / InputDispatcher commit removal of per-anchor windows before injecting DOWN
            val startTask = Runnable {
                pendingAutoLockStartRunnable = null
                if (!isClicking) {
                    startClickingInternal()
                }
            }
            pendingAutoLockStartRunnable = startTask
            mainHandler.postDelayed(startTask, 40L)
        } else {
            startClickingInternal()
        }
    }

    private fun startClickingInternal() {
        cancelAutoCollapse()
        val expectedSeq = ++runSessionSeq
        val snapshot = targets.map { it.copy() }
        val modeSnapshot = activeExecutionMode
        val loopSnapshot = activePresetLoopCount
        isClicking = true
        OverlayStateHub.setClickingRunning(this, true)
        refreshAnchorVisuals()
        refreshControlBarState()

        serviceScope.launch(Dispatchers.IO) {
            val mode = ShizukuController.startClicking(
                targets = snapshot,
                executionMode = modeSnapshot,
                presetLoopCount = loopSnapshot
            )
            withContext(Dispatchers.Main) {
                if (expectedSeq != runSessionSeq || !isClicking) return@withContext
                if (mode < 0) {
                    isClicking = false
                    OverlayStateHub.setClickingRunning(this@OverlayService, false)
                    refreshAnchorVisuals()
                    refreshControlBarState()
                    Toast.makeText(
                        this@OverlayService,
                        if (mode == -4) "Geçerli planda çalıştırılabilir hareket düğümü yok" else "Ardışık dokunuş başlatılamadı (code=$mode). Shizuku durumunu kontrol edin",
                        Toast.LENGTH_SHORT
                    ).show()
                    scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
                } else {
                    engineMode = mode
                    refreshControlBarState()
                    scheduleAutoCollapse(START_COLLAPSE_DELAY_MS)
                }
            }
        }
    }

    private fun stopClickingInternal() {
        pendingAutoLockStartRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingAutoLockStartRunnable = null
        runSessionSeq++
        if (!isClicking) return
        cancelAutoCollapse()
        isClicking = false
        OverlayStateHub.setClickingRunning(this, false)
        refreshAnchorVisuals()
        refreshControlBarState()
        serviceScope.launch(Dispatchers.IO) {
            ShizukuController.stopClicking()
        }
        if (!isBarCollapsed) {
            scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
        }
    }

    override fun onAddTarget() {
        if (isClicking) {
            stopClickingInternal()
        }
        if (isLocked) {
            applyLockMode(false)
        }
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        val settings = ClickConfigStore.loadSettings(this)
        val offsetIndex = targets.size % 6
        val offsetPx = (offsetIndex - 2) * 34f * resources.displayMetrics.density
        val sx = (metrics.widthPx * 0.5f + offsetPx).coerceIn(40f, (metrics.widthPx - 40).coerceAtLeast(40).toFloat())
        val sy = (metrics.heightPx * 0.5f + offsetPx * 0.6f).coerceIn(40f, (metrics.heightPx - 40).coerceAtLeast(40).toFloat())
        val newTarget = ClickTarget(
            id = nextTargetId++,
            index = targets.size + 1,
            x = sx,
            y = sy,
            intervalMs = settings.defaultIntervalMs,
            pressDurationMs = settings.defaultPressDurationMs,
            microJitter = settings.defaultMicroJitter,
            enabled = true,
            actionType = ActionType.TAP,
            repeatCount = ClickTarget.defaultRepeatFor(activeExecutionMode)
        )
        newTarget.setStartPixel(sx, sy, metrics.widthPx, metrics.heightPx)
        targets += newTarget
        selectedTargetId = newTarget.id
        attachSingleAnchor(newTarget)
        persistCurrentTargets()
        refreshAnchorVisuals()
        if (tunePanelView != null) {
            showOrUpdateTunePanel(forceShow = true)
        } else {
            scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
        }
    }

    override fun onRemoveTarget() {
        if (targets.isEmpty()) return
        if (isClicking) {
            stopClickingInternal()
        }
        val removeId = if (targets.any { it.id == selectedTargetId }) {
            selectedTargetId
        } else {
            targets.last().id
        }
        targets.removeAll { it.id == removeId }
        anchorViews.remove(removeId)?.let { view ->
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: Throwable) {
            }
        }
        anchorLps.remove(removeId)
        endAnchorViews.remove(removeId)?.let { view ->
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: Throwable) {
            }
        }
        endAnchorLps.remove(removeId)
        selectedTargetId = targets.lastOrNull()?.id ?: -1
        persistCurrentTargets()
        refreshAnchorVisuals()
        if (targets.isEmpty()) {
            dismissTunePanel()
            scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
        } else if (tunePanelView != null) {
            showOrUpdateTunePanel(forceShow = true)
        } else {
            scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
        }
    }

    override fun onToggleLockEdit() {
        if (isClicking && isLocked) {
            stopClickingInternal()
        }
        applyLockMode(!isLocked)
        scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
    }

    private fun ensureNormalOverlayAttached() {
        if (normalOverlayAttached) return
        normalOverlayAttached = true
        val settings = ClickConfigStore.loadSettings(this)
        isLocked = settings.autoLockOnStart
        lastAutoLockOnStart = settings.autoLockOnStart
        showMacroTrajectoriesPref = settings.showMacroTrajectories
        loadInitialTargets()
        syncSwipeConnectorLayer()
        attachAllAnchors()
        attachControlBar()
        OverlayStateHub.setOverlayVisible(this, true)
        scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
    }

    private fun enterRecordingMode() {
        if (macroRecordOverlay?.isActive() == true) return
        if (!ShizukuController.isServiceConnected()) {
            ShizukuController.evaluateShizukuState()
            Toast.makeText(this, "Önce Shizuku hizmetinin bağlı olduğundan emin olun", Toast.LENGTH_SHORT).show()
            if (!normalOverlayAttached) {
                stopSelf()
            }
            return
        }
        if (isClicking) {
            stopClickingInternal()
        }
        cancelAutoCollapse()
        resumeOverlayAfterRecord = normalOverlayAttached
        if (normalOverlayAttached) {
            dismissTunePanel()
            removeAllAnchorWindows()
            removeSwipeConnectorLayer()
            removeControlBar()
            normalOverlayAttached = false
        }

        val controller = MacroRecordOverlay(
            context = this,
            windowManager = windowManager,
            scope = serviceScope,
            colors = themeColors,
            onFinished = { savedPlan, openPresetsTab ->
                macroRecordOverlay = null
                if (savedPlan != null) {
                    OverlayStateHub.bumpConfigVersion()
                }
                if (resumeOverlayAfterRecord) {
                    resumeOverlayAfterRecord = false
                    ensureNormalOverlayAttached()
                    replaceTargetsWithActivePreset()
                }
                if (openPresetsTab) {
                    startActivity(
                        Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra(MainActivity.EXTRA_OPEN_TAB, "PRESETS")
                        }
                    )
                }
                if (!normalOverlayAttached) {
                    stopSelf()
                }
            }
        )
        macroRecordOverlay = controller
        controller.start()
    }

    override fun onStartMacroRecord() {
        enterRecordingMode()
    }

    override fun onCloseOverlay() {
        cancelAutoCollapse()
        macroRecordOverlay?.dismissAll()
        macroRecordOverlay = null
        stopClickingInternal()
        stopSelf()
    }

    override fun onDragMove(newLpX: Int, newLpY: Int) {
        val bar = controlBarView ?: return
        val lp = controlBarLp ?: return
        val screenW = if (dragScreenWidthPx > 0) dragScreenWidthPx else {
            ShizukuController.getDisplayScreenMetrics(this).also {
                dragScreenWidthPx = it.widthPx
                dragScreenHeightPx = it.heightPx
            }.widthPx
        }
        val screenH = dragScreenHeightPx.coerceAtLeast(1)
        val w = bar.getCurrentWidthPx()
        val h = bar.getCurrentHeightPx()
        lp.x = newLpX.coerceIn(0, (screenW - w).coerceAtLeast(0))
        lp.y = newLpY.coerceIn(0, (screenH - h).coerceAtLeast(0))
        bar.syncWindowPosition(lp.x, lp.y)
        dockRight = (lp.x + w / 2) >= (screenW / 2)
        bar.setDockRight(dockRight)
        windowManager.updateViewLayout(bar, lp)
    }

    override fun onDragEnd() {
        val bar = controlBarView ?: return
        val lp = controlBarLp ?: return
        val screenW = if (dragScreenWidthPx > 0) dragScreenWidthPx else {
            ShizukuController.getDisplayScreenMetrics(this).widthPx
        }
        dragScreenWidthPx = 0
        dragScreenHeightPx = 0
        val w = bar.getCurrentWidthPx()
        dockRight = (lp.x + w / 2) >= (screenW / 2)
        bar.setDockRight(dockRight)

        if (isBarCollapsed) {
            val targetX = if (dockRight) (screenW - w).coerceAtLeast(0) else 0
            animateControlBarPosX(targetX) {
                ClickConfigStore.saveControlBarCollapseState(this, true, dockRight)
            }
        } else {
            bar.syncWindowPosition(lp.x, lp.y)
            ClickConfigStore.saveControlBarPosition(this, lp.x, lp.y)
            ClickConfigStore.saveControlBarCollapseState(this, false, dockRight)
            scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
        }
    }

    /**
     * Bug 3 Fix: Remaps normalized coords cleanly on rotation without truncating/saving clamped portrait pixels.
     */
    private fun onScreenGeometryChanged() {
        dragScreenWidthPx = 0
        dragScreenHeightPx = 0
        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        val bar = controlBarView
        val barLp = controlBarLp
        if (bar != null && barLp != null) {
            cancelBarSprings()
            currentMorphProgress = if (isBarCollapsed) 0f else 1f
            bar.setCollapsed(isBarCollapsed, dockRight)
            val w = bar.getCurrentWidthPx()
            val h = bar.getCurrentHeightPx()
            barLp.width = w
            barLp.height = h
            barLp.x = if (isBarCollapsed) {
                if (dockRight) (metrics.widthPx - w).coerceAtLeast(0) else 0
            } else {
                barLp.x.coerceIn(0, (metrics.widthPx - w).coerceAtLeast(0))
            }
            if (!isBarCollapsed) {
                dockRight = (barLp.x + w / 2) >= (metrics.widthPx / 2)
                bar.setDockRight(dockRight)
            }
            barLp.y = barLp.y.coerceIn(0, (metrics.heightPx - h).coerceAtLeast(0))
            bar.syncWindowPosition(barLp.x, barLp.y)
            windowManager.updateViewLayout(bar, barLp)
        }

        for (t in targets) {
            t.remapToScreen(metrics.widthPx, metrics.heightPx)
            val view = anchorViews[t.id]
            val lp = anchorLps[t.id]
            if (view != null && lp != null) {
                lp.x = (t.x - view.halfSizePx).roundToInt()
                lp.y = (t.y - view.halfSizePx).roundToInt()
                view.syncWindowPosition(lp.x, lp.y)
                windowManager.updateViewLayout(view, lp)
            }
            val endView = endAnchorViews[t.id]
            val endLp = endAnchorLps[t.id]
            if (endView != null && endLp != null) {
                endLp.x = (t.endX - endView.halfSizePx).roundToInt()
                endLp.y = (t.endY - endView.halfSizePx).roundToInt()
                endView.syncWindowPosition(endLp.x, endLp.y)
                windowManager.updateViewLayout(endView, endLp)
            }
        }
        swipeConnectorView?.updateExpandedSwipes(buildExpandedDisplayEntries(), isClicking, isLocked)

        serviceScope.launch(Dispatchers.IO) {
            ShizukuController.updateDisplayMetrics()
            if (isClicking) {
                ShizukuController.updateTargets(
                    targets = targets.map { it.copy() },
                    executionMode = activeExecutionMode,
                    presetLoopCount = activePresetLoopCount
                )
            }
        }
    }

    private fun pushTargetsToServiceAsync() {
        val snapshot = targets.map { it.copy() }
        val modeSnapshot = activeExecutionMode
        val loopSnapshot = activePresetLoopCount
        serviceScope.launch(Dispatchers.IO) {
            ShizukuController.updateTargets(
                targets = snapshot,
                executionMode = modeSnapshot,
                presetLoopCount = loopSnapshot
            )
        }
    }

    private fun dismissTunePanel() {
        val panel = tunePanelView ?: return
        try {
            windowManager.removeViewImmediate(panel)
        } catch (_: Throwable) {
        }
        tunePanelView = null
        tunePanelLp = null
        refreshControlBarState()
    }

    private fun showOrUpdateTunePanel(forceShow: Boolean) {
        if (!forceShow && tunePanelView == null) return
        val target = targets.firstOrNull { it.id == selectedTargetId && it.actionType != ActionType.SUB_PRESET }
            ?: targets.firstOrNull { it.actionType != ActionType.SUB_PRESET }?.also { selectedTargetId = it.id }
            ?: return

        cancelAutoCollapse()
        dismissTunePanel()

        val colors = themeColors
        val settings = ClickConfigStore.loadSettings(this)
        val d = resources.displayMetrics.density
        val pad = (14f * d).roundToInt()
        val panelWidth = (268f * d).roundToInt()
        val themedCtx = colors.themedContext

        val root = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22f * d
                setColor(colors.surfaceContainerHigh)
                setStroke((1f * d).roundToInt().coerceAtLeast(1), colors.outlineVariant)
            }
        }

        val titleRow = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(themedCtx).apply {
            text = "Hareket #${target.index}  (${target.x.roundToInt()}, ${target.y.roundToInt()})"
            setTextColor(colors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeTv = TextView(themedCtx).apply {
            text = "Tamamlandı"
            isClickable = true
            setTextColor(colors.onPrimaryContainer)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            paint.isFakeBoldText = true
            setPadding((12f * d).roundToInt(), (5f * d).roundToInt(), (12f * d).roundToInt(), (5f * d).roundToInt())
            val pillBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * d
                setColor(colors.primaryContainer)
            }
            background = colors.createRippleDrawable(pillBg)
            setOnClickListener {
                dismissTunePanel()
                scheduleAutoCollapse(IDLE_COLLAPSE_DELAY_MS)
            }
        }
        titleRow.addView(titleTv)
        titleRow.addView(closeTv)
        root.addView(titleRow)

        // Gesture Action Type selector (sürekli dokunma / kaydırma / uzun basma)
        val gestureLabel = TextView(themedCtx).apply {
            text = "Hareket türü"
            setTextColor(colors.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, (8f * d).roundToInt(), 0, (4f * d).roundToInt())
        }
        root.addView(gestureLabel)

        val gestureRow = LinearLayout(themedCtx).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(ActionType.TAP, ActionType.SWIPE, ActionType.HOLD).forEach { act ->
            gestureRow.addView(createChipButton(themedCtx, act.label, target.actionType == act, colors) {
                if (target.actionType != act) {
                    target.actionType = act
                    if (act == ActionType.TAP) {
                        target.intervalMs = settings.defaultIntervalMs
                        target.pressDurationMs = settings.defaultPressDurationMs
                    }
                    attachSingleAnchor(target)
                    persistCurrentTargets()
                    refreshAnchorVisuals()
                    bringControlBarToFrontIfNeeded()
                    if (isClicking) pushTargetsToServiceAsync()
                    showOrUpdateTunePanel(forceShow = true)
                }
            })
        }
        root.addView(gestureRow)

        // Preset Execution Mode row (paralel / sıralı makro)
        val execRow = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (6f * d).roundToInt(), 0, 0)
        }
        ExecutionMode.entries.forEach { mode ->
            execRow.addView(createChipButton(themedCtx, mode.label, activeExecutionMode == mode, colors) {
                if (activeExecutionMode != mode) {
                    activeExecutionMode = mode
                    ClickConfigStore.setPresetExecutionMode(this, activePresetId, mode)
                    if (mode == ExecutionMode.SEQUENTIAL) {
                        for (t in targets) if (t.repeatCount == 0) t.repeatCount = 1
                    } else {
                        for (t in targets) if (t.actionType != ActionType.SUB_PRESET && t.repeatCount == 1) t.repeatCount = 0
                    }
                    OverlayStateHub.bumpConfigVersion()
                    if (isClicking) pushTargetsToServiceAsync()
                    showOrUpdateTunePanel(forceShow = true)
                }
            })
        }
        root.addView(execRow)

        if (target.actionType == ActionType.TAP) {
            val globalTapInfoTv = TextView(themedCtx).apply {
                text = "Dokunma ritmi: aralık ${settings.defaultIntervalMs}ms · basılı tutma ${settings.defaultPressDurationMs}ms\n（Ana ekranda «Ayarlama → Tekrarlama ayarları» bölümünden değiştirebilirsiniz）"
                setTextColor(colors.onSurfaceVariant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, (10f * d).roundToInt(), 0, (2f * d).roundToInt())
            }
            root.addView(globalTapInfoTv)
        } else {
            // Interval section (SWIPE / HOLD only; TAP follows global sürekli dokunma ayarları)
            val intervalValueTv = TextView(themedCtx).apply {
                text = "Döngü aralığı: ${target.intervalMs} ms"
                setTextColor(colors.onSurfaceVariant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, (9f * d).roundToInt(), 0, (4f * d).roundToInt())
            }
            root.addView(intervalValueTv)

            val intervalPresetChips = mutableListOf<Pair<Long, TextView>>()
            fun refreshIntervalPresetHighlight() {
                intervalPresetChips.forEach { (preset, chip) ->
                    styleChipButton(chip, selected = (target.intervalMs == preset), colors = colors)
                }
            }

            val minIntervalForMode = if (activeExecutionMode == ExecutionMode.SEQUENTIAL) {
                ClickTarget.MIN_STAGE_GAP_MS
            } else {
                ClickTarget.MIN_INTERVAL_MS
            }
            val intervalBtnRow = LinearLayout(themedCtx).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(-50L to "-50", -10L to "-10", 10L to "+10", 50L to "+50").forEach { (delta, label) ->
                intervalBtnRow.addView(createChipButton(themedCtx, label, false, colors) {
                    target.intervalMs = (target.intervalMs + delta)
                        .coerceIn(minIntervalForMode, ClickTarget.MAX_INTERVAL_MS)
                    intervalValueTv.text = "Döngü aralığı: ${target.intervalMs} ms"
                    refreshIntervalPresetHighlight()
                    persistCurrentTargets()
                    if (isClicking) pushTargetsToServiceAsync()
                })
            }
            root.addView(intervalBtnRow)

            val intervalPresetRow = LinearLayout(themedCtx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (5f * d).roundToInt(), 0, 0)
            }
            val presetOptions = if (activeExecutionMode == ExecutionMode.SEQUENTIAL) {
                listOf(0L, 30L, 80L, 200L)
            } else {
                listOf(10L, 30L, 80L, 200L)
            }
            presetOptions.forEach { preset ->
                val label = if (preset == 0L) "Kesintisiz (0)" else "${preset}ms"
                val chip = createChipButton(themedCtx, label, target.intervalMs == preset, colors) {
                    target.intervalMs = preset
                    intervalValueTv.text = "Döngü aralığı: ${target.intervalMs} ms"
                    refreshIntervalPresetHighlight()
                    persistCurrentTargets()
                    if (isClicking) pushTargetsToServiceAsync()
                }
                intervalPresetChips += preset to chip
                intervalPresetRow.addView(chip)
            }
            root.addView(intervalPresetRow)

            // Press or Swipe duration section
            if (target.actionType == ActionType.SWIPE) {
                val swipeValueTv = TextView(themedCtx).apply {
                    text = "Kaydırma süresi: ${target.swipeDurationMs} ms"
                    setTextColor(colors.onSurfaceVariant)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setPadding(0, (9f * d).roundToInt(), 0, (4f * d).roundToInt())
                }
                root.addView(swipeValueTv)
                val swipeChips = mutableListOf<Pair<Long, TextView>>()
                val swipeRow = LinearLayout(themedCtx).apply { orientation = LinearLayout.HORIZONTAL }
                listOf(150L, 320L, 600L, 1000L).forEach { preset ->
                    val chip = createChipButton(themedCtx, "${preset}ms", target.swipeDurationMs == preset, colors) {
                        target.swipeDurationMs = preset
                        swipeValueTv.text = "Kaydırma süresi: ${target.swipeDurationMs} ms"
                        swipeChips.forEach { (p, c) -> styleChipButton(c, target.swipeDurationMs == p, colors) }
                        persistCurrentTargets()
                        if (isClicking) pushTargetsToServiceAsync()
                    }
                    swipeChips += preset to chip
                    swipeRow.addView(chip)
                }
                root.addView(swipeRow)
            } else {
                val pressValueTv = TextView(themedCtx).apply {
                    text = "Basılı tutma süresi (kombinasyon modu): ${target.pressDurationMs} ms"
                    setTextColor(colors.onSurfaceVariant)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setPadding(0, (9f * d).roundToInt(), 0, (4f * d).roundToInt())
                }
                root.addView(pressValueTv)

                val pressPresetChips = mutableListOf<Pair<Long, TextView>>()
                val pressPresetRow = LinearLayout(themedCtx).apply { orientation = LinearLayout.HORIZONTAL }
                listOf(300L, 800L, 1500L, 3000L).forEach { preset ->
                    val chip = createChipButton(themedCtx, "${preset}ms", target.pressDurationMs == preset, colors) {
                        target.pressDurationMs = preset
                        pressValueTv.text = "Basılı tutma süresi (kombinasyon modu): ${target.pressDurationMs} ms"
                        pressPresetChips.forEach { (p, c) -> styleChipButton(c, target.pressDurationMs == p, colors) }
                        persistCurrentTargets()
                        if (isClicking) pushTargetsToServiceAsync()
                    }
                    pressPresetChips += preset to chip
                    pressPresetRow.addView(chip)
                }
                root.addView(pressPresetRow)
            }
        }

        fun formatRepeatLabel(count: Int): String =
            if (count <= 0) "Döngü sayısı: sınırsız (0)" else "Döngü sayısı: $count"

        // Per-node repeatCount section
        val repeatValueTv = TextView(themedCtx).apply {
            text = formatRepeatLabel(target.repeatCount)
            setTextColor(colors.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, (9f * d).roundToInt(), 0, (4f * d).roundToInt())
        }
        root.addView(repeatValueTv)

        val repeatPresetChips = mutableListOf<Pair<Int, TextView>>()
        fun refreshRepeatPresetHighlight() {
            repeatPresetChips.forEach { (presetVal, chip) ->
                styleChipButton(chip, selected = (target.repeatCount == presetVal), colors = colors)
            }
        }

        val repeatStepRow = LinearLayout(themedCtx).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(-5 to "-5", -1 to "-1", 1 to "+1", 5 to "+5").forEach { (delta, label) ->
            repeatStepRow.addView(createChipButton(themedCtx, label, false, colors) {
                target.repeatCount = (target.repeatCount + delta)
                    .coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT)
                repeatValueTv.text = formatRepeatLabel(target.repeatCount)
                refreshRepeatPresetHighlight()
                persistCurrentTargets()
                if (isClicking) pushTargetsToServiceAsync()
            })
        }
        root.addView(repeatStepRow)

        val repeatPresetRow = LinearLayout(themedCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (5f * d).roundToInt(), 0, 0)
        }
        listOf(0 to "Sınırsız", 1 to "1 kez", 3 to "3 kez", 5 to "5 kez").forEach { (presetVal, chipLabel) ->
            val chip = createChipButton(themedCtx, chipLabel, target.repeatCount == presetVal, colors) {
                target.repeatCount = presetVal
                repeatValueTv.text = formatRepeatLabel(target.repeatCount)
                refreshRepeatPresetHighlight()
                persistCurrentTargets()
                if (isClicking) pushTargetsToServiceAsync()
            }
            repeatPresetChips += presetVal to chip
            repeatPresetRow.addView(chip)
        }
        root.addView(repeatPresetRow)

        val thumbTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(colors.onPrimary, colors.onSurfaceVariant)
        )
        val trackTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(colors.primary, colors.surfaceContainerHighest)
        )

        if (target.actionType == ActionType.TAP) {
            val jitterSwitch = SwitchMaterial(themedCtx).apply {
                text = "İnsan benzeri Gauss titreşimi (±${settings.jitterRadiusPx.roundToInt()}px)"
                setTextColor(colors.onSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, (6f * d).roundToInt(), 0, 0)
                thumbTintList = thumbTint
                trackTintList = trackTint
                isChecked = target.microJitter
                setOnCheckedChangeListener { _, checked ->
                    target.microJitter = checked
                    persistCurrentTargets()
                    refreshAnchorVisuals()
                    if (isClicking) pushTargetsToServiceAsync()
                }
            }
            root.addView(jitterSwitch)
        }

        val enabledSwitch = SwitchMaterial(themedCtx).apply {
            text = "Bu bağlantı noktasını etkinleştir"
            setTextColor(colors.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            thumbTintList = thumbTint
            trackTintList = trackTint
            isChecked = target.enabled
            setOnCheckedChangeListener { _, checked ->
                target.enabled = checked
                persistCurrentTargets()
                refreshAnchorVisuals()
                if (isClicking) pushTargetsToServiceAsync()
            }
        }
        root.addView(enabledSwitch)

        val metrics = ShizukuController.getDisplayScreenMetrics(this)
        val barLp = controlBarLp
        val barWidth = controlBarView?.getCurrentWidthPx() ?: (52f * d).roundToInt()
        val gap = (10f * d).roundToInt()
        val preferredX = if (barLp != null) {
            if (barLp.x + barWidth + gap + panelWidth <= metrics.widthPx) {
                barLp.x + barWidth + gap
            } else {
                (barLp.x - gap - panelWidth).coerceAtLeast(8)
            }
        } else {
            ((metrics.widthPx - panelWidth) / 2).coerceAtLeast(8)
        }.coerceIn(0, (metrics.widthPx - panelWidth - 8).coerceAtLeast(0))
        val preferredY = (barLp?.y ?: (metrics.heightPx * 0.2f).roundToInt())
            .coerceIn(16, (metrics.heightPx - (320f * d).roundToInt()).coerceAtLeast(16))

        val lp = createBaseOverlayParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = true
        ).apply {
            x = preferredX
            y = preferredY
        }

        windowManager.addView(root, lp)
        tunePanelView = root
        tunePanelLp = lp
        refreshControlBarState()
    }

    private fun createChipButton(
        ctx: Context,
        label: String,
        selected: Boolean,
        colors: OverlayThemeColors,
        onClick: () -> Unit
    ): TextView {
        val d = resources.displayMetrics.density
        return TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            isClickable = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, (6f * d).roundToInt(), 0, (6f * d).roundToInt())
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = (5f * d).roundToInt()
            }
            styleChipButton(this, selected, colors)
            setOnClickListener { onClick() }
        }
    }

    private fun styleChipButton(chip: TextView, selected: Boolean, colors: OverlayThemeColors) {
        val d = resources.displayMetrics.density
        chip.setTextColor(if (selected) colors.onPrimaryContainer else colors.onSecondaryContainer)
        chip.paint.isFakeBoldText = selected
        val chipBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * d
            setColor(if (selected) colors.primaryContainer else colors.secondaryContainer)
            if (!selected) {
                setStroke(1, colors.outlineVariant)
            }
        }
        chip.background = colors.createRippleDrawable(chipBg)
    }

    companion object {
        const val ACTION_START_OVERLAY = "com.zerointerrupt.clicker.ACTION_START_OVERLAY"
        const val ACTION_START_RECORDING = "com.zerointerrupt.clicker.ACTION_START_RECORDING"
        const val ACTION_STOP_OVERLAY = "com.zerointerrupt.clicker.ACTION_STOP_OVERLAY"
        const val ACTION_STOP_CLICKING = "com.zerointerrupt.clicker.ACTION_STOP_CLICKING"
        const val ACTION_REFRESH_SETTINGS = "com.zerointerrupt.clicker.ACTION_REFRESH_SETTINGS"
        const val ACTION_RPC_START = "com.zerointerrupt.clicker.ACTION_RPC_START"
        const val ACTION_RPC_STOP = "com.zerointerrupt.clicker.ACTION_RPC_STOP"
        const val ACTION_RPC_SWITCH_PRESET = "com.zerointerrupt.clicker.ACTION_RPC_SWITCH_PRESET"

        const val EXTRA_PRESET_ID = "preset_id"
        const val EXTRA_PRESET_NAME = "preset_name"

        private const val CHANNEL_ID = "zero_interrupt_overlay_channel"
        private const val NOTIFICATION_ID = 2001
        private const val IDLE_COLLAPSE_DELAY_MS = 4500L
        private const val START_COLLAPSE_DELAY_MS = 1500L
    }
}
