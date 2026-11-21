package com.zerointerrupt.clicker.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.zerointerrupt.clicker.BuildConfig
import com.zerointerrupt.clicker.ITouchMuxCallback
import com.zerointerrupt.clicker.ITouchMuxService
import com.zerointerrupt.clicker.model.ClickConfigStore
import com.zerointerrupt.clicker.model.ClickGlobalSettings
import com.zerointerrupt.clicker.model.ClickPreset
import com.zerointerrupt.clicker.model.ClickTarget
import com.zerointerrupt.clicker.model.ExecutionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import rikka.shizuku.Shizuku

enum class ShizukuConnectionState {
    SHIZUKU_NOT_RUNNING,
    NO_PERMISSION,
    BINDING,
    CONNECTED,
    ERROR
}

data class EngineSnapshot(
    val engineMode: Int = TouchMuxUserService.MODE_IDLE,
    val preferredBackend: Int = ClickGlobalSettings.BACKEND_AUTO,
    val uinputWritable: Boolean = false,
    val uinputErrno: Int = -1,
    val uhidWritable: Boolean = false,
    val uhidErrno: Int = -1,
    val touchDeviceReadable: Boolean = false,
    val touchErrno: Int = -1,
    val fallbackReady: Boolean = false,
    val touchDeviceInfo: String = "",
    val absRangeText: String = "",
    val statusDetail: String = "Shizuku hizmetine bağlı değil",
    val uid: Int = -1,
    val pid: Int = -1,
    val nativeProbeError: String = ""
)

data class DisplayScreenMetrics(
    val widthPx: Int,
    val heightPx: Int,
    val rotation: Int
)

/**
 * Process-wide Shizuku UserService connection, permission, auto-rebind, and IPC controller.
 */
object ShizukuController {
    private const val TAG = "ShizukuController"
    const val REQUEST_CODE_SHIZUKU = 10042

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appContext: Context? = null
    private var listenersRegistered = false
    private var boundArgs: Shizuku.UserServiceArgs? = null
    private var bindTimeoutJob: Job? = null
    private const val BIND_TIMEOUT_MS = 3_000L
    @Volatile
    private var service: ITouchMuxService? = null

    private val _connectionState = MutableStateFlow(ShizukuConnectionState.SHIZUKU_NOT_RUNNING)
    val connectionState: StateFlow<ShizukuConnectionState> = _connectionState.asStateFlow()

    private val _engineSnapshot = MutableStateFlow(EngineSnapshot())
    val engineSnapshot: StateFlow<EngineSnapshot> = _engineSnapshot.asStateFlow()

    private val _slotStates = MutableStateFlow(List(16) { 0 })
    val slotStates: StateFlow<List<Int>> = _slotStates.asStateFlow()

    private val _volumeKeyToggleEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val volumeKeyToggleEvents: SharedFlow<Unit> = _volumeKeyToggleEvents.asSharedFlow()

    private val _emergencyUngrabEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val emergencyUngrabEvents: SharedFlow<Unit> = _emergencyUngrabEvents.asSharedFlow()

    private val _clickingCompletedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val clickingCompletedEvents: SharedFlow<Unit> = _clickingCompletedEvents.asSharedFlow()

    private val serviceCallback = object : ITouchMuxCallback.Stub() {
        override fun onVolumeKeyToggleRequested() {
            _volumeKeyToggleEvents.tryEmit(Unit)
        }

        override fun onEmergencyUngrabTriggered() {
            _slotStates.value = List(16) { 0 }
            _emergencyUngrabEvents.tryEmit(Unit)
            refreshCapabilitiesAsync()
        }

        override fun onSlotStatesChanged(slots: IntArray?) {
            if (slots != null && slots.size >= 16) {
                _slotStates.value = slots.take(16)
            }
        }

        override fun onClickingCompleted() {
            _clickingCompletedEvents.tryEmit(Unit)
            refreshCapabilitiesAsync()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received.")
        evaluateShizukuState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died; resetting service reference.")
        service = null
        _slotStates.value = List(16) { 0 }
        _connectionState.value = ShizukuConnectionState.SHIZUKU_NOT_RUNNING
        _engineSnapshot.value = _engineSnapshot.value.copy(
            engineMode = TouchMuxUserService.MODE_IDLE,
            statusDetail = "Shizuku hizmeti bağlantısı kesildi, yeniden bağlanması bekleniyor"
        )
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE_SHIZUKU) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                bindUserService()
            } else {
                _connectionState.value = ShizukuConnectionState.NO_PERMISSION
                _engineSnapshot.value = _engineSnapshot.value.copy(
                    statusDetail = "Kullanıcı Shizuku iznini reddetti"
                )
            }
        }
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bindTimeoutJob?.cancel()
            bindTimeoutJob = null
            if (binder == null || !binder.pingBinder()) {
                _connectionState.value = ShizukuConnectionState.ERROR
                _engineSnapshot.value = _engineSnapshot.value.copy(
                    statusDetail = "UserService Binder geçersiz"
                )
                return
            }
            val svc = ITouchMuxService.Stub.asInterface(binder)
            service = svc
            _connectionState.value = ShizukuConnectionState.CONNECTED
            try {
                svc.registerCallback(serviceCallback)
                appContext?.let { ctx ->
                    val settings = ClickConfigStore.loadSettings(ctx)
                    svc.setVolumeKeyControlEnabled(settings.volumeKeyToggleEnabled)
                    svc.setPreferredBackend(settings.preferredBackend)
                }
            } catch (_: Throwable) {
            }
            try {
                binder.linkToDeath({
                    Log.w(TAG, "TouchMuxUserService remote process died.")
                    service = null
                    _slotStates.value = List(16) { 0 }
                    if (Shizuku.pingBinder() && hasPermission()) {
                        bindUserService()
                    } else {
                        _connectionState.value = ShizukuConnectionState.SHIZUKU_NOT_RUNNING
                    }
                }, 0)
            } catch (_: Throwable) {
            }
            refreshCapabilitiesAsync()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _slotStates.value = List(16) { 0 }
            if (Shizuku.pingBinder() && hasPermission()) {
                bindUserService()
            } else {
                _connectionState.value = ShizukuConnectionState.SHIZUKU_NOT_RUNNING
            }
        }
    }

    fun initialize(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        if (!listenersRegistered) {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            listenersRegistered = true
        }
        evaluateShizukuState()
    }

    fun evaluateShizukuState() {
        val binderAlive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        if (!binderAlive) {
            service = null
            _slotStates.value = List(16) { 0 }
            _connectionState.value = ShizukuConnectionState.SHIZUKU_NOT_RUNNING
            _engineSnapshot.value = EngineSnapshot(statusDetail = "Önce Shizuku hizmetini başlatın")
            return
        }
        if (!hasPermission()) {
            _connectionState.value = ShizukuConnectionState.NO_PERMISSION
            _engineSnapshot.value = EngineSnapshot(statusDetail = "Shizuku İzni Gerekli")
            return
        }
        if (service?.asBinder()?.pingBinder() == true) {
            _connectionState.value = ShizukuConnectionState.CONNECTED
            refreshCapabilitiesAsync()
        } else {
            bindUserService()
        }
    }

    fun hasPermission(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.isPreV11()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                _connectionState.value = ShizukuConnectionState.SHIZUKU_NOT_RUNNING
                _engineSnapshot.value = _engineSnapshot.value.copy(
                    statusDetail = "Shizuku hizmeti çalışmıyor"
                )
                return
            }
            if (hasPermission()) {
                bindUserService()
                return
            }
            Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
        } catch (t: Throwable) {
            _connectionState.value = ShizukuConnectionState.ERROR
            _engineSnapshot.value = _engineSnapshot.value.copy(
                statusDetail = "Shizuku izni isteği başarısız: ${t.message}"
            )
        }
    }

    fun bindUserService() {
        val ctx = appContext ?: return
        if (!Shizuku.pingBinder() || !hasPermission()) {
            evaluateShizukuState()
            return
        }
        if (service?.asBinder()?.pingBinder() == true) {
            _connectionState.value = ShizukuConnectionState.CONNECTED
            return
        }
        if (_connectionState.value == ShizukuConnectionState.BINDING) return
        _connectionState.value = ShizukuConnectionState.BINDING
        val args = boundArgs ?: Shizuku.UserServiceArgs(
            ComponentName(ctx.packageName, TouchMuxUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("touchmux")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
            .also { boundArgs = it }

        try {
            Shizuku.bindUserService(args, userServiceConnection)
            bindTimeoutJob?.cancel()
            bindTimeoutJob = scope.launch {
                delay(BIND_TIMEOUT_MS)
                if (_connectionState.value == ShizukuConnectionState.BINDING && service == null) {
                    Log.e(TAG, "Timed out waiting for Shizuku UserService connection")
                    _connectionState.value = ShizukuConnectionState.ERROR
                    _engineSnapshot.value = _engineSnapshot.value.copy(
                        statusDetail = "Bağlantı Başarısız: Shizuku UserService 3 saniye içinde bağlanamadı."
                    )
                }
                bindTimeoutJob = null
            }
        } catch (t: Throwable) {
            bindTimeoutJob?.cancel()
            bindTimeoutJob = null
            Log.e(TAG, "bindUserService error", t)
            _connectionState.value = ShizukuConnectionState.ERROR
            _engineSnapshot.value = _engineSnapshot.value.copy(
                statusDetail = "UserService bağlanamadı: ${t.message}"
            )
        }
    }

    fun refreshCapabilitiesAsync() {
        val ctx = appContext ?: return
        val svc = service ?: return
        scope.launch {
            try {
                val jsonStr = svc.probeCapabilities(ctx.applicationInfo.nativeLibraryDir)
                val obj = JSONObject(jsonStr)
                val touchPath = obj.optString("touchDevicePath", "")
                val touchName = obj.optString("touchDeviceName", "")
                val touchInfo = if (touchPath.isNotEmpty()) "$touchPath ($touchName)" else "Okunabilir dokunmatik giriş düğümü algılanmadı"
                val detail = obj.optString("statusDetail", "Ready")
                val uinputOk = obj.optBoolean("uinputWritable", false)
                val uinputErr = obj.optInt("uinputErrno", -1)
                val uhidOk = obj.optBoolean("uhidWritable", false)
                val uhidErr = obj.optInt("uhidErrno", -1)
                val touchOk = obj.optBoolean("touchDeviceReadable", false)
                val touchErr = obj.optInt("touchDeviceErrno", -1)
                val minX = obj.optInt("absMinX", 0)
                val maxX = obj.optInt("absMaxX", 0)
                val minY = obj.optInt("absMinY", 0)
                val maxY = obj.optInt("absMaxY", 0)
                val absText = if (touchOk) "X[$minX..$maxX] Y[$minY..$maxY]" else "-"

                val probeErr = buildString {
                    val loadErr = obj.optString("nativeLoadError", "")
                    if (loadErr.isNotEmpty()) append(loadErr)
                    if (!uinputOk) {
                        if (isNotEmpty()) append("; ")
                        append("/dev/uinput errno=$uinputErr")
                    }
                    if (!uhidOk) {
                        if (isNotEmpty()) append("; ")
                        append("/dev/uhid errno=$uhidErr")
                    }
                    if (!touchOk) {
                        if (isNotEmpty()) append("; ")
                        append("/dev/input errno=$touchErr")
                    }
                }

                _engineSnapshot.value = EngineSnapshot(
                    engineMode = obj.optInt("currentMode", TouchMuxUserService.MODE_IDLE),
                    preferredBackend = obj.optInt("preferredBackend", ClickGlobalSettings.BACKEND_AUTO),
                    uinputWritable = uinputOk,
                    uinputErrno = uinputErr,
                    uhidWritable = uhidOk,
                    uhidErrno = uhidErr,
                    touchDeviceReadable = touchOk,
                    touchErrno = touchErr,
                    fallbackReady = obj.optBoolean("fallbackInjectorReady", false),
                    touchDeviceInfo = touchInfo,
                    absRangeText = absText,
                    statusDetail = detail,
                    uid = obj.optInt("uid", -1),
                    pid = obj.optInt("pid", -1),
                    nativeProbeError = probeErr
                )
            } catch (t: Throwable) {
                Log.w(TAG, "probeCapabilities failed", t)
            }
        }
    }

    fun startSelfCheckGrab(timeoutMs: Int = 5000): Int {
        val ctx = appContext ?: return -1
        val svc = service ?: return -2
        val settings = ClickConfigStore.loadSettings(ctx)
        val metrics = getDisplayScreenMetrics(ctx)
        return try {
            val rc = svc.startSelfCheckGrab(
                ctx.applicationInfo.nativeLibraryDir,
                metrics.widthPx,
                metrics.heightPx,
                metrics.rotation,
                timeoutMs,
                settings.preferredBackend
            )
            refreshCapabilitiesAsync()
            rc
        } catch (t: Throwable) {
            Log.e(TAG, "startSelfCheckGrab failed", t)
            -3
        }
    }

    fun confirmSelfCheck(): Boolean {
        val ctx = appContext ?: return false
        val svc = service ?: return false
        return try {
            val ok = svc.confirmSelfCheck()
            ClickConfigStore.markSelfCheckPassed(ctx, ok)
            refreshCapabilitiesAsync()
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "confirmSelfCheck failed", t)
            false
        }
    }

    fun cancelSelfCheckAndUngrab() {
        val svc = service ?: return
        try {
            svc.cancelSelfCheckAndUngrab()
            _slotStates.value = List(16) { 0 }
            refreshCapabilitiesAsync()
        } catch (_: Throwable) {
        }
    }

    fun cleanupOrphanProcesses(): Int {
        val svc = service ?: return 0
        return try {
            val count = svc.cleanupOrphanProcesses()
            refreshCapabilitiesAsync()
            count
        } catch (_: Throwable) {
            0
        }
    }

    fun refreshSlotStates() {
        val svc = service ?: return
        scope.launch {
            try {
                val arr = svc.slotStates
                if (arr != null && arr.size >= 16) {
                    _slotStates.value = arr.take(16)
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun setVolumeKeyControlEnabled(enabled: Boolean) {
        val svc = service ?: return
        try {
            svc.setVolumeKeyControlEnabled(enabled)
        } catch (_: Throwable) {
        }
    }

    fun setPreferredBackend(backend: Int, reinitIfActive: Boolean = false) {
        val ctx = appContext
        val svc = service ?: return
        try {
            svc.setPreferredBackend(backend)
            if (reinitIfActive && ctx != null) {
                val settings = ClickConfigStore.loadSettings(ctx)
                val metrics = getDisplayScreenMetrics(ctx)
                if (settings.selfCheckPassed) {
                    svc.initNativeEngine(
                        ctx.applicationInfo.nativeLibraryDir,
                        metrics.widthPx,
                        metrics.heightPx,
                        metrics.rotation,
                        backend
                    )
                }
            }
            refreshCapabilitiesAsync()
        } catch (_: Throwable) {
        }
    }

    fun ensureNativeIfVerified(): Boolean {
        val ctx = appContext ?: return false
        val svc = service ?: return false
        val settings = ClickConfigStore.loadSettings(ctx)
        if (!settings.selfCheckPassed) return false
        if (settings.preferredBackend == ClickGlobalSettings.BACKEND_FALLBACK) return false
        return try {
            val curMode = svc.engineMode
            val matchesPref = settings.preferredBackend == ClickGlobalSettings.BACKEND_AUTO ||
                curMode == settings.preferredBackend
            if (TouchMuxUserService.isNativeMode(curMode) && matchesPref) return true
            val metrics = getDisplayScreenMetrics(ctx)
            val rc = svc.initNativeEngine(
                ctx.applicationInfo.nativeLibraryDir,
                metrics.widthPx,
                metrics.heightPx,
                metrics.rotation,
                settings.preferredBackend
            )
            refreshCapabilitiesAsync()
            rc == 0
        } catch (_: Throwable) {
            false
        }
    }

    fun startClicking(
        targets: List<ClickTarget>? = null,
        executionMode: ExecutionMode? = null,
        presetLoopCount: Int? = null
    ): Int {
        val ctx = appContext ?: return -1
        val svc = service ?: return -2
        return try {
            val snapshot = _engineSnapshot.value
            val settings = ClickConfigStore.loadSettings(ctx)
            if (settings.selfCheckPassed &&
                settings.preferredBackend != ClickGlobalSettings.BACKEND_FALLBACK &&
                snapshot.touchDeviceReadable &&
                (snapshot.uinputWritable || snapshot.uhidWritable || snapshot.fallbackReady)
            ) {
                ensureNativeIfVerified()
            }
            val metrics = getDisplayScreenMetrics(ctx)
            val presets = ClickConfigStore.loadPresets(ctx)
            val activePreset = ClickConfigStore.getActivePreset(ctx)
            val effectiveMode = executionMode ?: activePreset.executionMode
            val effectiveLoopCount = (presetLoopCount ?: activePreset.presetLoopCount).coerceIn(0, 9999)
            val parceled = ClickConfigStore.compileTargetsForExecution(
                presets = presets,
                activePreset = activePreset,
                liveOverrideTargets = targets,
                executionMode = effectiveMode,
                settings = settings,
                screenW = metrics.widthPx,
                screenH = metrics.heightPx
            )
            if (parceled.isEmpty()) return -4
            val mode = svc.startClicking(
                parceled,
                metrics.widthPx,
                metrics.heightPx,
                metrics.rotation,
                true,
                effectiveMode.code,
                effectiveLoopCount,
                settings.jitterRadiusPx,
                settings.preferredBackend
            )
            _engineSnapshot.value = _engineSnapshot.value.copy(
                engineMode = if (mode > 0) mode else TouchMuxUserService.MODE_IDLE,
                preferredBackend = settings.preferredBackend,
                statusDetail = svc.statusDetail
            )
            mode
        } catch (t: Throwable) {
            Log.e(TAG, "startClicking failed", t)
            -3
        }
    }

    fun updateTargets(
        targets: List<ClickTarget>? = null,
        executionMode: ExecutionMode? = null,
        presetLoopCount: Int? = null
    ) {
        val ctx = appContext ?: return
        val svc = service ?: return
        try {
            val settings = ClickConfigStore.loadSettings(ctx)
            val metrics = getDisplayScreenMetrics(ctx)
            val presets = ClickConfigStore.loadPresets(ctx)
            val activePreset = ClickConfigStore.getActivePreset(ctx)
            val effectiveMode = executionMode ?: activePreset.executionMode
            val effectiveLoopCount = (presetLoopCount ?: activePreset.presetLoopCount).coerceIn(0, 9999)
            val parceled = ClickConfigStore.compileTargetsForExecution(
                presets = presets,
                activePreset = activePreset,
                liveOverrideTargets = targets,
                executionMode = effectiveMode,
                settings = settings,
                screenW = metrics.widthPx,
                screenH = metrics.heightPx
            )
            svc.updateTargets(
                parceled,
                metrics.widthPx,
                metrics.heightPx,
                metrics.rotation,
                effectiveMode.code,
                effectiveLoopCount,
                settings.jitterRadiusPx
            )
        } catch (t: Throwable) {
            Log.w(TAG, "updateTargets failed", t)
        }
    }

    fun updateDisplayMetrics() {
        val ctx = appContext ?: return
        val svc = service ?: return
        try {
            val metrics = getDisplayScreenMetrics(ctx)
            svc.updateDisplayMetrics(metrics.widthPx, metrics.heightPx, metrics.rotation)
        } catch (_: Throwable) {
        }
    }

    fun stopClicking() {
        val svc = service ?: return
        try {
            svc.stopClicking()
            _engineSnapshot.value = _engineSnapshot.value.copy(statusDetail = svc.statusDetail)
        } catch (t: Throwable) {
            Log.w(TAG, "stopClicking failed", t)
        }
    }

    fun shutdownEngine() {
        val svc = service ?: return
        try {
            svc.shutdownEngine()
            _slotStates.value = List(16) { 0 }
            _engineSnapshot.value = _engineSnapshot.value.copy(
                engineMode = TouchMuxUserService.MODE_IDLE,
                statusDetail = svc.statusDetail
            )
        } catch (_: Throwable) {
        }
    }

    fun rpcTap(x: Float, y: Float, durationMs: Long = 25L): Boolean {
        val svc = service ?: return false
        return try {
            svc.injectRpcTap(x, y, durationMs)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun rpcSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L): Boolean {
        val svc = service ?: return false
        return try {
            svc.injectRpcSwipe(x1, y1, x2, y2, durationMs)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun rpcHold(holdId: Int, down: Boolean, x: Float, y: Float): Boolean {
        val svc = service ?: return false
        return try {
            svc.setRpcHold(holdId, down, x, y)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun startTouchRecording(excludeLeft: Int, excludeTop: Int, excludeRight: Int, excludeBottom: Int): Boolean {
        val ctx = appContext ?: return false
        val svc = service ?: return false
        return try {
            // Ensure libtouch_mux.so is loaded inside UserService before recording
            if (_engineSnapshot.value.touchDeviceInfo.isEmpty()) {
                svc.probeCapabilities(ctx.applicationInfo.nativeLibraryDir)
            }
            val metrics = getDisplayScreenMetrics(ctx)
            svc.startTouchRecording(
                metrics.widthPx,
                metrics.heightPx,
                metrics.rotation,
                excludeLeft,
                excludeTop,
                excludeRight,
                excludeBottom
            )
        } catch (t: Throwable) {
            Log.e(TAG, "startTouchRecording failed", t)
            false
        }
    }

    fun updateRecordingExcludeRect(excludeLeft: Int, excludeTop: Int, excludeRight: Int, excludeBottom: Int) {
        val svc = service ?: return
        try {
            svc.updateRecordingExcludeRect(excludeLeft, excludeTop, excludeRight, excludeBottom)
        } catch (_: Throwable) {
        }
    }

    fun getTouchRecordingStats(): LongArray {
        val svc = service ?: return longArrayOf(0L, 0L, 0L, 0L)
        return try {
            svc.touchRecordingStats ?: longArrayOf(0L, 0L, 0L, 0L)
        } catch (_: Throwable) {
            longArrayOf(0L, 0L, 0L, 0L)
        }
    }

    fun stopTouchRecording(): String {
        val svc = service ?: return ""
        return try {
            svc.stopTouchRecording().orEmpty()
        } catch (t: Throwable) {
            Log.e(TAG, "stopTouchRecording failed", t)
            ""
        }
    }

    fun cancelTouchRecording() {
        val svc = service ?: return
        try {
            svc.cancelTouchRecording()
        } catch (_: Throwable) {
        }
    }

    fun unbindUserService() {
        val args = boundArgs ?: return
        try {
            service?.shutdownEngine()
        } catch (_: Throwable) {
        }
        try {
            Shizuku.unbindUserService(args, userServiceConnection, true)
        } catch (_: Throwable) {
        }
        service = null
        _slotStates.value = List(16) { 0 }
    }

    fun isServiceConnected(): Boolean = service?.asBinder()?.pingBinder() == true

    @Suppress("DEPRECATION")
    fun getDisplayScreenMetrics(context: Context): DisplayScreenMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = display?.rotation ?: Surface.ROTATION_0

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            DisplayScreenMetrics(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1), rotation)
        } else {
            val metrics = DisplayMetrics()
            display?.getRealMetrics(metrics)
            DisplayScreenMetrics(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1), rotation)
        }
    }
}
