package com.zerointerrupt.clicker.shizuku

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import androidx.annotation.Keep
import com.zerointerrupt.clicker.ClickTargetParceled
import com.zerointerrupt.clicker.ITouchMuxCallback
import com.zerointerrupt.clicker.ITouchMuxService
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Method
import java.util.Random as JavaRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.math.roundToLong

/**
 * Privileged Shizuku UserService (UID 2000 shell / UID 0 root).
 *
 * Tier 1: /dev/uinput + EVIOCGRAB (MODE_NATIVE_UINPUT = 1)
 * Tier 2: /dev/uhid 10-finger HID Digitizer + EVIOCGRAB (MODE_NATIVE_UHID = 2)
 * Tier 3: EVIOCGRAB evdev + C++ rawToLogical + zero-GC IInputManager multi-touch (MODE_HYBRID_EVDEV_INJECT = 3)
 * Tier 4: Pure in-process IInputManager inject fallback (MODE_SHIZUKU_FALLBACK = 4)
 */
@Keep
class TouchMuxUserService() : ITouchMuxService.Stub() {

    @Suppress("unused")
    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    private val nativeLoaded = AtomicBoolean(false)
    private val callbacksRegistered = AtomicBoolean(false)
    private var nativeLoadError: String? = null

    private val currentMode = AtomicInteger(MODE_IDLE)
    private val preferredBackend = AtomicInteger(BACKEND_AUTO)
    private val clickingActive = AtomicBoolean(false)
    private val selfCheckConfirmed = AtomicBoolean(false)
    private val volumeKeyEnabled = AtomicBoolean(true)
    private var statusDetail: String = "Idle"

    private val watchdogExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val rpcFallbackExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var selfCheckWatchdogTask: ScheduledFuture<*>? = null
    private val nativeLifecycleLock = Any()

    private val remoteCallbacks = RemoteCallbackList<ITouchMuxCallback>()
    private val callbackBroadcastLock = Any()

    // Zero-GC pre-allocated arrays shared by Tier 3 Hybrid & Tier 4 Fallback
    private val injectFrameLock = Any()
    private val hybridIdsBuf = IntArray(MAX_SLOTS)
    private val hybridXsBuf = FloatArray(MAX_SLOTS)
    private val hybridYsBuf = FloatArray(MAX_SLOTS)
    private val hybridPressuresBuf = FloatArray(MAX_SLOTS)
    private val hybridSizesBuf = FloatArray(MAX_SLOTS)
    private val slotStatesBuf = IntArray(MAX_SLOTS)

    private val preallocProps = Array(MAX_SLOTS) {
        MotionEvent.PointerProperties().apply { toolType = MotionEvent.TOOL_TYPE_FINGER }
    }
    private val preallocCoords = Array(MAX_SLOTS) {
        MotionEvent.PointerCoords()
    }

    // Tier 4 Fallback injector state
    private val fallbackLock = Any()
    private var fallbackTargets: List<ClickTargetParceled> = emptyList()
    private var fallbackDisplayW: Int = 1080
    private var fallbackDisplayH: Int = 2400
    private var fallbackExecutionMode: Int = 0
    private var fallbackPresetLoopCount: Int = 0
    private var fallbackJitterRadiusPx: Float = 4.0f
    private var fallbackConfigVersion: Long = 0L
    private var fallbackThread: Thread? = null
    private val fallbackRunning = AtomicBoolean(false)
    private val javaRng = JavaRandom()

    private var cachedInjectMethod: Method? = null
    private var cachedInputManagerObj: Any? = null

    // JNI declarations matching jni_bridge.cpp
    private external fun nativeProbeCapabilities(): String
    private external fun nativeCleanupOrphans(processName: String): Int
    private external fun nativeRegisterCallbacks(
        hybIds: IntArray,
        hybXs: FloatArray,
        hybYs: FloatArray,
        hybPressures: FloatArray,
        hybSizes: FloatArray,
        slotBuf: IntArray
    )
    private external fun nativeSetVolumeKeyEnabled(enabled: Boolean)
    private external fun nativeInitAndGrab(
        displayW: Int,
        displayH: Int,
        rotation: Int,
        selfCheckTimeoutMs: Int,
        preferredBackend: Int
    ): Int
    private external fun nativeConfirmSelfCheck(): Boolean
    private external fun nativeSetTargetsAndRunning(
        ids: IntArray,
        xs: FloatArray,
        ys: FloatArray,
        intervalsMs: LongArray,
        pressDurationsMs: LongArray,
        jitters: BooleanArray,
        enabledFlags: BooleanArray,
        actionTypes: IntArray,
        endXs: FloatArray,
        endYs: FloatArray,
        swipeDurationsMs: LongArray,
        repeatCounts: IntArray,
        stageIndices: IntArray,
        stageDurationsMs: LongArray,
        stageGapsMs: LongArray,
        stageRepeatCounts: IntArray,
        holdInIds: IntArray,
        holdOutIds: IntArray,
        running: Boolean,
        displayW: Int,
        displayH: Int,
        rotation: Int,
        jitterRadiusPx: Float,
        executionMode: Int,
        presetLoopCount: Int,
        forceReset: Boolean
    )
    private external fun nativeUpdateDisplayMetrics(displayW: Int, displayH: Int, rotation: Int)
    private external fun nativeStopClicking()
    private external fun nativeShutdown()
    private external fun nativeDestroyEngine()
    private external fun nativeGetState(): Int
    private external fun nativeGetBackendMode(): Int
    private external fun nativeDidRollback(): Boolean
    private external fun nativeGetLastError(): String
    private external fun nativeGetTouchDeviceInfo(): String
    private external fun nativeGetSlotStates(): IntArray
    private external fun nativeInjectRpcTap(x: Float, y: Float, durationMs: Long)
    private external fun nativeInjectRpcSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long)
    private external fun nativeSetRpcHold(holdId: Int, down: Boolean, x: Float, y: Float)
    private external fun nativeStartRecording(
        displayW: Int,
        displayH: Int,
        rotation: Int,
        excludeLeft: Int,
        excludeTop: Int,
        excludeRight: Int,
        excludeBottom: Int
    ): Boolean
    private external fun nativeUpdateRecordingExcludeRect(
        excludeLeft: Int,
        excludeTop: Int,
        excludeRight: Int,
        excludeBottom: Int
    )
    private external fun nativeGetRecordingStats(): LongArray
    private external fun nativeStopRecording(): String
    private external fun nativeCancelRecording()

    init {
        exemptHiddenApisIfNeeded()
    }

    private fun exemptHiddenApisIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime")
            val runtime = getRuntime.invoke(null)
            val setExemptions = vmRuntimeClass.getDeclaredMethod(
                "setHiddenApiExemptions",
                Array<String>::class.java
            )
            setExemptions.invoke(runtime, arrayOf("L"))
        } catch (t: Throwable) {
            Log.w(TAG, "Hidden API exemption notice: ${t.message}")
        }
    }

    private fun ensureNativeLoaded(nativeLibDir: String?): Boolean {
        if (!nativeLoaded.get()) {
            if (nativeLibDir.isNullOrBlank()) {
                nativeLoadError = "nativeLibDir is empty"
                return false
            }
            val soFile = File(nativeLibDir, "libtouch_mux.so")
            if (!soFile.exists()) {
                nativeLoadError = "libtouch_mux.so not found at ${soFile.absolutePath}"
                return false
            }
            try {
                System.load(soFile.absolutePath)
                nativeLoaded.set(true)
                nativeLoadError = null
            } catch (t: Throwable) {
                nativeLoadError = "System.load failed: ${t.message}"
                Log.e(TAG, nativeLoadError, t)
                return false
            }
        }

        if (callbacksRegistered.compareAndSet(false, true)) {
            ensureFallbackInjectorReady()
            try {
                nativeRegisterCallbacks(
                    hybridIdsBuf,
                    hybridXsBuf,
                    hybridYsBuf,
                    hybridPressuresBuf,
                    hybridSizesBuf,
                    slotStatesBuf
                )
                nativeSetVolumeKeyEnabled(volumeKeyEnabled.get())
            } catch (t: Throwable) {
                callbacksRegistered.set(false)
                Log.w(TAG, "nativeRegisterCallbacks failed: ${t.message}")
            }
        }
        return true
    }

    private fun ensureFallbackInjectorReady(): Boolean {
        if (cachedInjectMethod != null && cachedInputManagerObj != null) {
            return true
        }
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            val binder = getService.invoke(null, "input") as? IBinder
            if (binder != null) {
                val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
                val asInterface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
                val im = asInterface.invoke(null, binder)
                val inject = im.javaClass.getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType
                )
                cachedInputManagerObj = im
                cachedInjectMethod = inject
                return true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "IInputManager reflection fallback: ${t.message}")
        }

        return try {
            val imClass = Class.forName("android.hardware.input.InputManager")
            val getInstance = imClass.getDeclaredMethod("getInstance")
            val im = getInstance.invoke(null)
            val inject = imClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            cachedInputManagerObj = im
            cachedInjectMethod = inject
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize InputManager fallback: ${t.message}", t)
            false
        }
    }

    @Keep
    @Suppress("unused")
    fun onNativeHybridMotionFrame(downTimeMs: Long, eventTimeMs: Long, action: Int, pointerCount: Int) {
        if (pointerCount <= 0 || pointerCount > MAX_SLOTS) return
        injectMotionFrameZeroGc(
            downTimeMs = downTimeMs,
            eventTimeMs = eventTimeMs,
            action = action,
            pointerCount = pointerCount,
            ids = hybridIdsBuf,
            xs = hybridXsBuf,
            ys = hybridYsBuf,
            pressures = hybridPressuresBuf,
            sizes = hybridSizesBuf
        )
    }

    @Keep
    @Suppress("unused")
    fun onNativeVolumeKeyDoubleTap() {
        notifyRemoteCallbacks { it.onVolumeKeyToggleRequested() }
    }

    @Keep
    @Suppress("unused")
    fun onNativeEmergencyUngrab() {
        clickingActive.set(false)
        selfCheckConfirmed.set(false)
        currentMode.set(MODE_IDLE)
        statusDetail = "Volume-Down 3s emergency rollback released EVIOCGRAB."
        notifyRemoteCallbacks { it.onEmergencyUngrabTriggered() }
    }

    @Keep
    @Suppress("unused")
    fun onNativeSlotStatesChanged() {
        val copy = slotStatesBuf.copyOf()
        notifyRemoteCallbacks { it.onSlotStatesChanged(copy) }
    }

    @Keep
    @Suppress("unused")
    fun onNativeClickingCompleted() {
        clickingActive.set(false)
        statusDetail = "Armed (${backendLabel(currentMode.get())})"
        notifyRemoteCallbacks { it.onClickingCompleted() }
    }

    private inline fun notifyRemoteCallbacks(action: (ITouchMuxCallback) -> Unit) {
        synchronized(callbackBroadcastLock) {
            val count = remoteCallbacks.beginBroadcast()
            try {
                for (i in 0 until count) {
                    try {
                        action(remoteCallbacks.getBroadcastItem(i))
                    } catch (_: Throwable) {
                    }
                }
            } finally {
                remoteCallbacks.finishBroadcast()
            }
        }
    }

    override fun registerCallback(callback: ITouchMuxCallback?) {
        if (callback == null) return
        synchronized(callbackBroadcastLock) {
            remoteCallbacks.register(callback)
        }
    }

    override fun probeCapabilities(nativeLibDir: String?): String {
        val obj = JSONObject()
        val loaded = ensureNativeLoaded(nativeLibDir)
        obj.put("nativeLibLoaded", loaded)
        obj.put("nativeLoadError", nativeLoadError ?: "")
        obj.put("uid", android.os.Process.myUid())
        obj.put("pid", android.os.Process.myPid())

        if (loaded) {
            try {
                // Pure read-only probing: NEVER blindly kill sibling processes here
                val nativeJson = JSONObject(nativeProbeCapabilities())
                val keys = nativeJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    obj.put(key, nativeJson.get(key))
                }
            } catch (t: Throwable) {
                obj.put("nativeProbeError", t.message ?: "unknown")
            }
        } else {
            obj.put("uinputWritable", false)
            obj.put("uinputErrno", -1)
            obj.put("uhidWritable", false)
            obj.put("uhidErrno", -1)
            obj.put("touchDeviceReadable", false)
            obj.put("touchDeviceErrno", -1)
        }

        val fallbackReady = ensureFallbackInjectorReady()
        obj.put("fallbackInjectorReady", fallbackReady)
        obj.put("currentMode", currentMode.get())
        obj.put("preferredBackend", preferredBackend.get())
        obj.put("statusDetail", statusDetail)
        return obj.toString()
    }

    override fun cleanupOrphanProcesses(): Int = synchronized(nativeLifecycleLock) {
        if (!nativeLoaded.get()) return@synchronized 0
        try {
            nativeCleanupOrphans("")
        } catch (_: Throwable) {
            0
        }
    }

    override fun setPreferredBackend(backend: Int) {
        preferredBackend.set(backend.coerceIn(BACKEND_AUTO, BACKEND_FALLBACK))
    }

    override fun setVolumeKeyControlEnabled(enabled: Boolean) {
        volumeKeyEnabled.set(enabled)
        if (nativeLoaded.get()) {
            try {
                nativeSetVolumeKeyEnabled(enabled)
            } catch (_: Throwable) {
            }
        }
    }

    override fun initNativeEngine(
        nativeLibDir: String?,
        displayWidth: Int,
        displayHeight: Int,
        rotation: Int,
        prefBackend: Int
    ): Int = synchronized(nativeLifecycleLock) {
        preferredBackend.set(prefBackend.coerceIn(BACKEND_AUTO, BACKEND_FALLBACK))
        if (prefBackend == BACKEND_FALLBACK) {
            stopNativeSilently()
            val ready = ensureFallbackInjectorReady()
            currentMode.set(if (ready) MODE_SHIZUKU_FALLBACK else MODE_IDLE)
            selfCheckConfirmed.set(ready)
            statusDetail = if (ready) "Fallback InputManager Ready" else "Fallback InputManager unavailable"
            return@synchronized if (ready) 0 else -1003
        }
        if (!ensureNativeLoaded(nativeLibDir)) {
            statusDetail = nativeLoadError ?: "Native load failed"
            return@synchronized -1001
        }
        val rc = nativeInitAndGrab(displayWidth, displayHeight, rotation, 0, prefBackend)
        if (rc == 0) {
            selfCheckConfirmed.set(true)
            val mode = resolveNativeMode()
            currentMode.set(mode)
            statusDetail = "${backendLabel(mode)}: ${nativeGetTouchDeviceInfo()}"
        } else {
            statusDetail = nativeGetLastError().ifBlank { "Yerel motor başlatılamadı ($rc)" }
        }
        rc
    }

    override fun startSelfCheckGrab(
        nativeLibDir: String?,
        displayWidth: Int,
        displayHeight: Int,
        rotation: Int,
        timeoutMs: Int,
        prefBackend: Int
    ): Int = synchronized(nativeLifecycleLock) {
        preferredBackend.set(prefBackend.coerceIn(BACKEND_AUTO, BACKEND_FALLBACK))
        if (prefBackend == BACKEND_FALLBACK) {
            stopNativeSilently()
            val ready = ensureFallbackInjectorReady()
            currentMode.set(if (ready) MODE_SHIZUKU_FALLBACK else MODE_IDLE)
            selfCheckConfirmed.set(ready)
            statusDetail = if (ready) "Forced Fallback Mode Ready" else "Fallback unavailable"
            return@synchronized if (ready) 0 else -1003
        }
        if (!ensureNativeLoaded(nativeLibDir)) {
            statusDetail = nativeLoadError ?: "Native load failed"
            return@synchronized -1001
        }
        stopFallbackThread()
        selfCheckConfirmed.set(false)
        cancelWatchdogTask()

        val safeTimeout = timeoutMs.coerceIn(2000, 15000)
        val rc = nativeInitAndGrab(displayWidth, displayHeight, rotation, safeTimeout, prefBackend)
        if (rc != 0) {
            currentMode.set(MODE_IDLE)
            statusDetail = nativeGetLastError().ifBlank { "Yerel motor denetimi alınamadı ($rc)" }
            return@synchronized rc
        }

        val mode = resolveNativeMode()
        currentMode.set(mode)
        statusDetail = "5s Touch Self-Check Pending [${backendLabel(mode)}]..."

        selfCheckWatchdogTask = watchdogExecutor.schedule({
            synchronized(nativeLifecycleLock) {
                if (!selfCheckConfirmed.get() && nativeLoaded.get()) {
                    Log.w(TAG, "JVM watchdog enforcing self-check rollback.")
                    try {
                        nativeShutdown()
                    } catch (_: Throwable) {
                    }
                    if (isNativeMode(currentMode.get())) {
                        currentMode.set(MODE_IDLE)
                        statusDetail = "Self-check timed out; safely rolled back."
                    }
                }
            }
        }, safeTimeout.toLong() + 150L, TimeUnit.MILLISECONDS)

        0
    }

    override fun confirmSelfCheck(): Boolean = synchronized(nativeLifecycleLock) {
        cancelWatchdogTask()
        if (preferredBackend.get() == BACKEND_FALLBACK && currentMode.get() == MODE_SHIZUKU_FALLBACK) {
            selfCheckConfirmed.set(true)
            return@synchronized true
        }
        if (!nativeLoaded.get()) return@synchronized false
        val ok = nativeConfirmSelfCheck()
        if (ok) {
            selfCheckConfirmed.set(true)
            val mode = resolveNativeMode()
            currentMode.set(mode)
            statusDetail = "${backendLabel(mode)} Verified: ${nativeGetTouchDeviceInfo()}"
        } else {
            selfCheckConfirmed.set(false)
            currentMode.set(MODE_IDLE)
            statusDetail = if (nativeDidRollback()) {
                "Self-check expired and rolled back automatically."
            } else {
                nativeGetLastError().ifBlank { "Yerel TouchMux etkin değil." }
            }
        }
        ok
    }

    override fun cancelSelfCheckAndUngrab() = synchronized(nativeLifecycleLock) {
        cancelWatchdogTask()
        selfCheckConfirmed.set(false)
        clickingActive.set(false)
        stopFallbackThread()
        stopNativeSilently()
        currentMode.set(MODE_IDLE)
        statusDetail = "Exclusive grab released; physical touch restored."
    }

    private fun stopNativeSilently() {
        if (nativeLoaded.get()) {
            try {
                nativeShutdown()
            } catch (_: Throwable) {
            }
        }
    }

    private fun cancelWatchdogTask() {
        selfCheckWatchdogTask?.cancel(false)
        selfCheckWatchdogTask = null
    }

    private fun resolveNativeMode(): Int {
        val m = if (nativeLoaded.get()) nativeGetBackendMode() else 0
        return when (m) {
            1 -> MODE_NATIVE_UINPUT
            2 -> MODE_NATIVE_UHID
            3 -> MODE_HYBRID_EVDEV_INJECT
            else -> MODE_IDLE
        }
    }

    override fun startClicking(
        targets: MutableList<ClickTargetParceled>?,
        displayWidth: Int,
        displayHeight: Int,
        rotation: Int,
        allowFallback: Boolean,
        executionMode: Int,
        presetLoopCount: Int,
        jitterRadiusPx: Float,
        prefBackend: Int
    ): Int {
        val list = targets ?: emptyList()
        preferredBackend.set(prefBackend.coerceIn(BACKEND_AUTO, BACKEND_FALLBACK))

        if (prefBackend == BACKEND_FALLBACK) {
            stopNativeSilently()
            return startFallbackInternal(
                list = list,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                executionMode = executionMode,
                presetLoopCount = presetLoopCount,
                jitterRadiusPx = jitterRadiusPx
            )
        }

        val nativeActive = nativeLoaded.get() && nativeGetState() == NATIVE_STATE_RUNNING_GRABBED
        if (nativeActive) {
            stopFallbackThread()
            val mode = resolveNativeMode()
            pushTargetsToNative(
                list = list,
                running = true,
                displayW = displayWidth,
                displayH = displayHeight,
                rotation = rotation,
                jitterRadiusPx = jitterRadiusPx,
                executionMode = executionMode,
                presetLoopCount = presetLoopCount,
                forceReset = true
            )
            currentMode.set(mode)
            clickingActive.set(true)
            statusDetail = "Running (${backendLabel(mode)})"
            return mode
        }

        if (!allowFallback) {
            statusDetail = "Native TouchMux not active and fallback disabled."
            return -1002
        }

        return startFallbackInternal(
            list = list,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            executionMode = executionMode,
            presetLoopCount = presetLoopCount,
            jitterRadiusPx = jitterRadiusPx
        )
    }

    private fun startFallbackInternal(
        list: List<ClickTargetParceled>,
        displayWidth: Int,
        displayHeight: Int,
        executionMode: Int,
        presetLoopCount: Int,
        jitterRadiusPx: Float
    ): Int {
        if (!ensureFallbackInjectorReady()) {
            statusDetail = "Fallback InputManager injector unavailable."
            return -1003
        }
        synchronized(fallbackLock) {
            fallbackTargets = list.toList()
            fallbackDisplayW = displayWidth.coerceAtLeast(1)
            fallbackDisplayH = displayHeight.coerceAtLeast(1)
            fallbackExecutionMode = executionMode
            fallbackPresetLoopCount = presetLoopCount.coerceIn(0, 9999)
            fallbackJitterRadiusPx = jitterRadiusPx.coerceIn(0f, 16f)
            fallbackConfigVersion++
        }
        clickingActive.set(true)
        currentMode.set(MODE_SHIZUKU_FALLBACK)
        startOrWakeFallbackThread()
        statusDetail = "Running (Shizuku UID ${android.os.Process.myUid()} Fallback)"
        return MODE_SHIZUKU_FALLBACK
    }

    override fun updateTargets(
        targets: MutableList<ClickTargetParceled>?,
        displayWidth: Int,
        displayHeight: Int,
        rotation: Int,
        executionMode: Int,
        presetLoopCount: Int,
        jitterRadiusPx: Float
    ) {
        val list = targets ?: emptyList()
        val running = clickingActive.get()
        if (isNativeMode(currentMode.get()) && nativeLoaded.get()) {
            pushTargetsToNative(
                list = list,
                running = running,
                displayW = displayWidth,
                displayH = displayHeight,
                rotation = rotation,
                jitterRadiusPx = jitterRadiusPx,
                executionMode = executionMode,
                presetLoopCount = presetLoopCount,
                forceReset = false
            )
        } else {
            synchronized(fallbackLock) {
                fallbackTargets = list.toList()
                fallbackDisplayW = displayWidth.coerceAtLeast(1)
                fallbackDisplayH = displayHeight.coerceAtLeast(1)
                fallbackExecutionMode = executionMode
                fallbackPresetLoopCount = presetLoopCount.coerceIn(0, 9999)
                fallbackJitterRadiusPx = jitterRadiusPx.coerceIn(0f, 16f)
                fallbackConfigVersion++
            }
        }
    }

    override fun updateDisplayMetrics(displayWidth: Int, displayHeight: Int, rotation: Int) {
        if (nativeLoaded.get()) {
            nativeUpdateDisplayMetrics(displayWidth, displayHeight, rotation)
        }
        synchronized(fallbackLock) {
            fallbackDisplayW = displayWidth.coerceAtLeast(1)
            fallbackDisplayH = displayHeight.coerceAtLeast(1)
        }
    }

    override fun stopClicking() {
        clickingActive.set(false)
        if (isNativeMode(currentMode.get()) && nativeLoaded.get()) {
            nativeStopClicking()
            statusDetail = "Armed (${backendLabel(currentMode.get())})"
        } else {
            stopFallbackThread()
            if (currentMode.get() == MODE_SHIZUKU_FALLBACK) {
                statusDetail = "Ready (Shizuku Fallback)"
            }
        }
    }

    override fun shutdownEngine() = synchronized(nativeLifecycleLock) {
        cancelWatchdogTask()
        clickingActive.set(false)
        selfCheckConfirmed.set(false)
        stopFallbackThread()
        stopNativeSilently()
        currentMode.set(MODE_IDLE)
        statusDetail = "Engine stopped."
    }

    override fun getEngineMode(): Int = currentMode.get()

    override fun getStatusDetail(): String = statusDetail

    override fun getSlotStates(): IntArray {
        return if (isNativeMode(currentMode.get()) && nativeLoaded.get()) {
            try {
                nativeGetSlotStates()
            } catch (_: Throwable) {
                slotStatesBuf.copyOf()
            }
        } else {
            slotStatesBuf.copyOf()
        }
    }

    override fun injectRpcTap(x: Float, y: Float, durationMs: Long) {
        val dur = durationMs.coerceIn(8L, 10_000L)
        if (isNativeMode(currentMode.get()) && nativeLoaded.get() && nativeGetState() == NATIVE_STATE_RUNNING_GRABBED) {
            nativeInjectRpcTap(x, y, dur)
            return
        }
        if (!ensureFallbackInjectorReady()) return
        rpcFallbackExecutor.execute {
            val downTime = SystemClock.uptimeMillis()
            val ids = IntArray(1) { 9 }
            val xs = FloatArray(1) { x }
            val ys = FloatArray(1) { y }
            val ps = FloatArray(1) { 0.16f }
            val ss = FloatArray(1) { 0.16f }
            injectMotionFrameZeroGc(downTime, downTime, MotionEvent.ACTION_DOWN, 1, ids, xs, ys, ps, ss)
            try {
                Thread.sleep(dur)
            } catch (_: InterruptedException) {
            }
            val upTime = SystemClock.uptimeMillis()
            injectMotionFrameZeroGc(downTime, upTime, MotionEvent.ACTION_UP, 1, ids, xs, ys, ps, ss)
        }
    }

    override fun injectRpcSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val dur = durationMs.coerceIn(8L, 15_000L)
        if (isNativeMode(currentMode.get()) && nativeLoaded.get() && nativeGetState() == NATIVE_STATE_RUNNING_GRABBED) {
            nativeInjectRpcSwipe(x1, y1, x2, y2, dur)
            return
        }
        if (!ensureFallbackInjectorReady()) return
        rpcFallbackExecutor.execute {
            val downTime = SystemClock.uptimeMillis()
            val ids = IntArray(1) { 9 }
            val xs = FloatArray(1) { x1 }
            val ys = FloatArray(1) { y1 }
            val ps = FloatArray(1) { 0.16f }
            val ss = FloatArray(1) { 0.16f }
            injectMotionFrameZeroGc(downTime, downTime, MotionEvent.ACTION_DOWN, 1, ids, xs, ys, ps, ss)
            val steps = (dur / 8L).toInt().coerceAtLeast(2)
            for (s in 1..steps) {
                try {
                    Thread.sleep(8L)
                } catch (_: InterruptedException) {
                    break
                }
                val t = s.toFloat() / steps.toFloat()
                xs[0] = x1 + (x2 - x1) * t
                ys[0] = y1 + (y2 - y1) * t
                injectMotionFrameZeroGc(
                    downTime,
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_MOVE,
                    1,
                    ids,
                    xs,
                    ys,
                    ps,
                    ss
                )
            }
            injectMotionFrameZeroGc(
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                1,
                ids,
                xs,
                ys,
                ps,
                ss
            )
        }
    }

    override fun setRpcHold(holdId: Int, down: Boolean, x: Float, y: Float) {
        if (isNativeMode(currentMode.get()) && nativeLoaded.get() && nativeGetState() == NATIVE_STATE_RUNNING_GRABBED) {
            nativeSetRpcHold(holdId, down, x, y)
            return
        }
        if (!ensureFallbackInjectorReady()) return
        rpcFallbackExecutor.execute {
            val now = SystemClock.uptimeMillis()
            val ids = IntArray(1) { (8 + (holdId and 0x07)) }
            val xs = FloatArray(1) { x }
            val ys = FloatArray(1) { y }
            val ps = FloatArray(1) { 0.16f }
            val ss = FloatArray(1) { 0.16f }
            val action = if (down) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP
            injectMotionFrameZeroGc(now, now, action, 1, ids, xs, ys, ps, ss)
        }
    }

    override fun startTouchRecording(
        displayWidth: Int,
        displayHeight: Int,
        rotation: Int,
        excludeLeft: Int,
        excludeTop: Int,
        excludeRight: Int,
        excludeBottom: Int
    ): Boolean = synchronized(nativeLifecycleLock) {
        if (!nativeLoaded.get()) return@synchronized false
        try {
            cancelWatchdogTask()
            stopFallbackThread()
            clickingActive.set(false)
            selfCheckConfirmed.set(false)
            currentMode.set(MODE_IDLE)
            val ok = nativeStartRecording(
                displayWidth,
                displayHeight,
                rotation,
                excludeLeft,
                excludeTop,
                excludeRight,
                excludeBottom
            )
            statusDetail = if (ok) {
                "Recording Touch..."
            } else {
                nativeGetLastError().ifBlank { "Dokunmatik kayıt başlatılamadı." }
            }
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "startTouchRecording failed", t)
            false
        }
    }

    override fun updateRecordingExcludeRect(excludeLeft: Int, excludeTop: Int, excludeRight: Int, excludeBottom: Int) {
        if (!nativeLoaded.get()) return
        try {
            nativeUpdateRecordingExcludeRect(excludeLeft, excludeTop, excludeRight, excludeBottom)
        } catch (_: Throwable) {
        }
    }

    override fun getTouchRecordingStats(): LongArray {
        if (!nativeLoaded.get()) return longArrayOf(0L, 0L, 0L, 0L)
        return try {
            nativeGetRecordingStats()
        } catch (_: Throwable) {
            longArrayOf(0L, 0L, 0L, 0L)
        }
    }

    override fun stopTouchRecording(): String = synchronized(nativeLifecycleLock) {
        if (!nativeLoaded.get()) return@synchronized ""
        return try {
            val res = nativeStopRecording()
            statusDetail = "Recording stopped."
            res
        } catch (t: Throwable) {
            Log.e(TAG, "stopTouchRecording failed", t)
            ""
        }
    }

    override fun cancelTouchRecording() = synchronized(nativeLifecycleLock) {
        if (!nativeLoaded.get()) return@synchronized
        try {
            nativeCancelRecording()
        } catch (_: Throwable) {
        }
    }

    override fun destroy() {
        if (nativeLoaded.get()) {
            try {
                nativeCancelRecording()
            } catch (_: Throwable) {
            }
        }
        shutdownEngine()
        if (nativeLoaded.get()) {
            try {
                nativeDestroyEngine()
            } catch (_: Throwable) {
            }
        }
        remoteCallbacks.kill()
        watchdogExecutor.shutdownNow()
        rpcFallbackExecutor.shutdownNow()
        System.exit(0)
    }

    private fun pushTargetsToNative(
        list: List<ClickTargetParceled>,
        running: Boolean,
        displayW: Int,
        displayH: Int,
        rotation: Int,
        jitterRadiusPx: Float,
        executionMode: Int,
        presetLoopCount: Int,
        forceReset: Boolean
    ) {
        val n = list.size
        val ids = IntArray(n)
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val intervals = LongArray(n)
        val presses = LongArray(n)
        val jitters = BooleanArray(n)
        val enabled = BooleanArray(n)
        val actionTypes = IntArray(n)
        val endXs = FloatArray(n)
        val endYs = FloatArray(n)
        val swipeDurs = LongArray(n)
        val repeatCounts = IntArray(n)
        val stageIndices = IntArray(n)
        val stageDurs = LongArray(n)
        val stageGaps = LongArray(n)
        val stageReps = IntArray(n)
        val holdInIds = IntArray(n)
        val holdOutIds = IntArray(n)
        for (i in 0 until n) {
            val t = list[i]
            ids[i] = t.id
            xs[i] = t.x
            ys[i] = t.y
            intervals[i] = t.intervalMs
            presses[i] = t.pressDurationMs
            jitters[i] = t.microJitter
            enabled[i] = t.enabled
            actionTypes[i] = t.actionType
            endXs[i] = t.endX
            endYs[i] = t.endY
            swipeDurs[i] = t.swipeDurationMs
            repeatCounts[i] = t.repeatCount.coerceIn(0, 9999)
            stageIndices[i] = t.stageIndex.coerceAtLeast(0)
            stageDurs[i] = t.stageDurationMs.coerceAtLeast(0L)
            stageGaps[i] = t.stageGapMs.coerceIn(0L, 60_000L)
            stageReps[i] = t.stageRepeatCount.coerceIn(0, 9999)
            holdInIds[i] = t.holdInId.coerceAtLeast(0)
            holdOutIds[i] = t.holdOutId.coerceAtLeast(0)
        }
        nativeSetTargetsAndRunning(
            ids,
            xs,
            ys,
            intervals,
            presses,
            jitters,
            enabled,
            actionTypes,
            endXs,
            endYs,
            swipeDurs,
            repeatCounts,
            stageIndices,
            stageDurs,
            stageGaps,
            stageReps,
            holdInIds,
            holdOutIds,
            running,
            displayW,
            displayH,
            rotation,
            jitterRadiusPx.coerceIn(0f, 16f),
            executionMode,
            presetLoopCount.coerceIn(0, 9999),
            forceReset
        )
    }

    private class FallbackTargetState(
        var config: ClickTargetParceled,
        var pressed: Boolean = false,
        var holdLockedDown: Boolean = false,
        var swipeFinalReached: Boolean = false,
        var pointerId: Int = -1,
        var nextDeadlineNs: Long = 0L,
        var swipeStartNs: Long = 0L,
        var swipeEndNs: Long = 0L,
        var startX: Float = 0f,
        var startY: Float = 0f,
        var endX: Float = 0f,
        var endY: Float = 0f,
        var completedCycles: Int = 0,
        var finishedRounds: Boolean = false
    )

    private class FallbackStageState(
        val stageIndex: Int,
        val durationMs: Long,
        val gapMs: Long,
        val repeatCount: Int,
        val targets: MutableList<FallbackTargetState> = mutableListOf(),
        var completedRepeats: Int = 0,
        var startDelayUntilNs: Long = 0L,
        var stageDeadlineNs: Long = 0L,
        var inGap: Boolean = false,
        var gapDeadlineNs: Long = 0L
    )

    private class ActivePointer(
        var targetId: Int,
        val pointerId: Int,
        var x: Float,
        var y: Float
    )

    private fun startOrWakeFallbackThread() {
        synchronized(fallbackLock) {
            if (fallbackRunning.get() && fallbackThread?.isAlive == true) {
                return
            }
            fallbackRunning.set(true)
            val thread = Thread({ runFallbackLoop() }, "ShizukuFallbackInjector")
            thread.priority = Thread.MAX_PRIORITY
            fallbackThread = thread
            thread.start()
        }
    }

    private fun stopFallbackThread() {
        fallbackRunning.set(false)
        val t: Thread?
        synchronized(fallbackLock) {
            t = fallbackThread
            fallbackThread = null
        }
        if (t != null && t !== Thread.currentThread()) {
            LockSupport.unpark(t)
            try {
                t.join(350)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun sampleGaussianOffset(radius: Float): Float {
        if (radius <= 0f) return 0f
        return (javaRng.nextGaussian().toFloat() * radius * 0.45f).coerceIn(-radius, radius)
    }

    private fun sampleGaussianMs(baseMs: Long, microJitter: Boolean, minMs: Long, maxMs: Long): Long {
        val base = baseMs.coerceIn(minMs, maxMs)
        if (!microJitter) return base
        val factor = (1.0 + javaRng.nextGaussian() * 0.04).coerceIn(0.90, 1.10)
        return (base.toDouble() * factor).roundToLong().coerceIn(minMs, maxMs)
    }

    private fun runFallbackLoop() {
        val runtimeMap = LinkedHashMap<Int, FallbackTargetState>()
        val stagesList = ArrayList<FallbackStageState>()
        val activePointers = ArrayList<ActivePointer>(MAX_SLOTS)
        val fbIds = IntArray(MAX_SLOTS)
        val fbXs = FloatArray(MAX_SLOTS)
        val fbYs = FloatArray(MAX_SLOTS)
        val fbPressures = FloatArray(MAX_SLOTS)
        val fbSizes = FloatArray(MAX_SLOTS)
        var downTimeMs = 0L
        var activeStageIdx = 0
        var seqCompletedLoops = 0
        var lastConfigVersion = -1L
        var lastExecMode = -1
        var completedNaturally = false

        fun emitActiveFrame(action: Int, eventTimeMs: Long) {
            val cnt = activePointers.size.coerceAtMost(MAX_SLOTS)
            if (cnt <= 0) return
            for (i in 0 until cnt) {
                val p = activePointers[i]
                fbIds[i] = p.pointerId
                fbXs[i] = p.x
                fbYs[i] = p.y
                fbPressures[i] = 0.16f
                fbSizes[i] = 0.16f
            }
            injectMotionFrameZeroGc(downTimeMs, eventTimeMs, action, cnt, fbIds, fbXs, fbYs, fbPressures, fbSizes)
        }

        fun allocPointerId(): Int {
            for (pid in 0 until 10) {
                if (activePointers.none { it.pointerId == pid }) {
                    return pid
                }
            }
            return -1
        }

        fun liftPointer(targetId: Int) {
            val idx = activePointers.indexOfFirst { it.targetId == targetId }
            if (idx < 0) return
            val nowMs = SystemClock.uptimeMillis()
            val action = if (activePointers.size == 1) {
                MotionEvent.ACTION_UP
            } else {
                MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            emitActiveFrame(action, nowMs)
            activePointers.removeAt(idx)
        }

        fun pressPointer(targetId: Int, x: Float, y: Float): Int {
            val pid = allocPointerId()
            if (pid < 0) return -1
            val nowMs = SystemClock.uptimeMillis()
            if (activePointers.isEmpty()) {
                downTimeMs = nowMs
            }
            activePointers.add(ActivePointer(targetId, pid, x, y))
            val idx = activePointers.size - 1
            val action = if (activePointers.size == 1) {
                MotionEvent.ACTION_DOWN
            } else {
                MotionEvent.ACTION_POINTER_DOWN or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            emitActiveFrame(action, nowMs)
            return pid
        }

        fun movePointer(targetId: Int, x: Float, y: Float) {
            val ptr = activePointers.firstOrNull { it.targetId == targetId } ?: return
            ptr.x = x
            ptr.y = y
            emitActiveFrame(MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis())
        }

        try {
            while (fallbackRunning.get() && clickingActive.get()) {
                val snapshot: List<ClickTargetParceled>
                val dispW: Int
                val dispH: Int
                val execMode: Int
                val loopCount: Int
                val jitterRad: Float
                val configVersion: Long
                synchronized(fallbackLock) {
                    snapshot = fallbackTargets
                    dispW = fallbackDisplayW
                    dispH = fallbackDisplayH
                    execMode = fallbackExecutionMode
                    loopCount = fallbackPresetLoopCount
                    jitterRad = fallbackJitterRadiusPx
                    configVersion = fallbackConfigVersion
                }

                val nowNs = System.nanoTime()
                val isSeq = execMode == 1
                val configChanged = (lastConfigVersion != configVersion) || (lastExecMode != execMode)

                if (isSeq) {
                    if (configChanged) {
                        lastConfigVersion = configVersion
                        lastExecMode = execMode
                        runtimeMap.clear()
                        while (activePointers.isNotEmpty()) {
                            liftPointer(activePointers.last().targetId)
                        }
                        stagesList.clear()
                        activeStageIdx = 0
                        seqCompletedLoops = 0
                        for (t in snapshot) {
                            if (!t.enabled) continue
                            if (stagesList.isEmpty() || stagesList.last().stageIndex != t.stageIndex) {
                                stagesList += FallbackStageState(
                                    stageIndex = t.stageIndex,
                                    durationMs = t.stageDurationMs.coerceAtLeast(0L),
                                    gapMs = t.stageGapMs.coerceIn(0L, 60_000L),
                                    repeatCount = t.stageRepeatCount.coerceIn(0, 9999),
                                    startDelayUntilNs = nowNs
                                )
                            }
                            stagesList.last().targets += FallbackTargetState(
                                config = t,
                                nextDeadlineNs = nowNs
                            )
                        }
                    }
                } else {
                    if (lastExecMode == 1) {
                        stagesList.clear()
                        activeStageIdx = 0
                        while (activePointers.isNotEmpty()) {
                            liftPointer(activePointers.last().targetId)
                        }
                    }
                    lastConfigVersion = configVersion
                    lastExecMode = execMode

                    val enabledIds = HashSet<Int>(snapshot.size)
                    for (t in snapshot) {
                        if (!t.enabled) continue
                        enabledIds.add(t.id)
                        val existing = runtimeMap[t.id]
                        if (existing == null) {
                            runtimeMap[t.id] = FallbackTargetState(
                                config = t,
                                nextDeadlineNs = nowNs + runtimeMap.size * 500_000L
                            )
                        } else {
                            existing.config = t
                            if (t.repeatCount == 0 && existing.finishedRounds) {
                                existing.finishedRounds = false
                                existing.completedCycles = 0
                                if (!existing.pressed) existing.nextDeadlineNs = nowNs
                            } else if (t.repeatCount > 0 && existing.completedCycles < t.repeatCount && existing.finishedRounds) {
                                existing.finishedRounds = false
                                if (!existing.pressed) existing.nextDeadlineNs = nowNs
                            }
                        }
                    }

                    val iter = runtimeMap.entries.iterator()
                    while (iter.hasNext()) {
                        val entry = iter.next()
                        if (!enabledIds.contains(entry.key)) {
                            if (entry.value.pressed) {
                                liftPointer(entry.key)
                            }
                            iter.remove()
                        }
                    }
                }

                var earliestNs = Long.MAX_VALUE
                fun stepState(st: FallbackTargetState): Boolean {
                    if (st.holdLockedDown || st.finishedRounds) return false
                    if (st.nextDeadlineNs > nowNs) return false

                    val maxX = (dispW - 1).coerceAtLeast(1).toFloat()
                    val maxY = (dispH - 1).coerceAtLeast(1).toFloat()
                    val act = st.config.actionType
                    val useTapJitter = (act == ACTION_TAP) && st.config.microJitter

                    if (!st.pressed) {
                        val carriedPtr = if (st.config.holdInId > 0) {
                            val keyId = -30_000 - st.config.holdInId
                            activePointers.firstOrNull { it.targetId == keyId }
                        } else null
                        val pid = if (carriedPtr != null) {
                            carriedPtr.targetId = st.config.id
                            st.startX = carriedPtr.x
                            st.startY = carriedPtr.y
                            if (act == ACTION_SWIPE) {
                                st.endX = st.config.endX.coerceIn(0f, maxX)
                                st.endY = st.config.endY.coerceIn(0f, maxY)
                            }
                            carriedPtr.pointerId
                        } else {
                            val sx = (st.config.x + if (useTapJitter) sampleGaussianOffset(jitterRad) else 0f).coerceIn(0f, maxX)
                            val sy = (st.config.y + if (useTapJitter) sampleGaussianOffset(jitterRad) else 0f).coerceIn(0f, maxY)
                            st.startX = sx
                            st.startY = sy
                            if (act == ACTION_SWIPE) {
                                st.endX = st.config.endX.coerceIn(0f, maxX)
                                st.endY = st.config.endY.coerceIn(0f, maxY)
                            }
                            pressPointer(st.config.id, sx, sy)
                        }
                        if (pid >= 0) {
                            st.pressed = true
                            st.pointerId = pid
                            st.swipeFinalReached = false
                            when {
                                act == ACTION_SWIPE -> {
                                    val durMs = sampleGaussianMs(st.config.swipeDurationMs, false, 8L, 15_000L)
                                    st.swipeStartNs = nowNs
                                    st.swipeEndNs = nowNs + durMs * 1_000_000L
                                    st.nextDeadlineNs = minOf(st.swipeEndNs, nowNs + 4_000_000L)
                                }
                                act == ACTION_HOLD && st.config.repeatCount == 0 -> {
                                    st.holdLockedDown = true
                                    st.nextDeadlineNs = Long.MAX_VALUE
                                }
                                else -> {
                                    val pressMs = sampleGaussianMs(
                                        st.config.pressDurationMs,
                                        useTapJitter,
                                        8L,
                                        if (act == ACTION_HOLD) 30_000L else 5_000L
                                    )
                                    st.nextDeadlineNs = nowNs + pressMs * 1_000_000L
                                }
                            }
                        } else {
                            st.nextDeadlineNs = nowNs + 5_000_000L
                        }
                        return false
                    }

                    if (act == ACTION_SWIPE && !st.swipeFinalReached) {
                        if (nowNs < st.swipeEndNs) {
                            val total = (st.swipeEndNs - st.swipeStartNs).coerceAtLeast(1L).toDouble()
                            val t = ((nowNs - st.swipeStartNs).toDouble() / total).coerceIn(0.0, 1.0).toFloat()
                            movePointer(
                                st.config.id,
                                st.startX + (st.endX - st.startX) * t,
                                st.startY + (st.endY - st.startY) * t
                            )
                            st.nextDeadlineNs = minOf(st.swipeEndNs, nowNs + 4_000_000L)
                            return false
                        } else {
                            movePointer(st.config.id, st.endX, st.endY)
                            st.swipeFinalReached = true
                            val curStage = if (isSeq && stagesList.isNotEmpty()) stagesList[activeStageIdx] else null
                            val advancingStage = curStage != null &&
                                st.config.holdOutId > 0 &&
                                curStage.repeatCount >= 1 &&
                                (curStage.completedRepeats + 1) >= curStage.repeatCount
                            if (advancingStage && curStage!!.durationMs > 0L) {
                                st.holdLockedDown = true
                                st.nextDeadlineNs = Long.MAX_VALUE
                                return false
                            }
                            if (advancingStage && curStage!!.durationMs == 0L && curStage.targets.size == 1) {
                                val ptr = activePointers.firstOrNull { it.targetId == st.config.id }
                                if (ptr != null) {
                                    ptr.targetId = -30_000 - st.config.holdOutId
                                } else {
                                    liftPointer(st.config.id)
                                }
                                st.pressed = false
                                st.swipeFinalReached = false
                                st.pointerId = -1
                                st.nextDeadlineNs = nowNs
                                return true
                            }
                            st.nextDeadlineNs = nowNs + 4_000_000L
                            return false
                        }
                    }

                    val curStage = if (isSeq && stagesList.isNotEmpty()) stagesList[activeStageIdx] else null
                    val handoffOut = curStage != null &&
                        st.config.holdOutId > 0 &&
                        curStage.durationMs == 0L &&
                        curStage.targets.size == 1 &&
                        curStage.repeatCount >= 1 &&
                        (curStage.completedRepeats + 1) >= curStage.repeatCount
                    if (handoffOut) {
                        val ptr = activePointers.firstOrNull { it.targetId == st.config.id }
                        if (ptr != null) {
                            ptr.targetId = -30_000 - st.config.holdOutId
                        } else {
                            liftPointer(st.config.id)
                        }
                    } else {
                        liftPointer(st.config.id)
                    }
                    st.pressed = false
                    st.swipeFinalReached = false
                    st.pointerId = -1
                    val intervalMs = if (handoffOut) 0L else sampleGaussianMs(st.config.intervalMs, useTapJitter, 5L, 60_000L)
                    st.nextDeadlineNs = nowNs + intervalMs * 1_000_000L
                    return true
                }

                fun resetStageTargets(stage: FallbackStageState, readyAtNs: Long, preserveHeld: Boolean = false) {
                    stage.stageDeadlineNs = 0L
                    stage.startDelayUntilNs = readyAtNs
                    stage.inGap = false
                    stage.targets.forEachIndexed { i, vt ->
                        if (preserveHeld && vt.pressed && vt.holdLockedDown && vt.pointerId >= 0) {
                            vt.completedCycles = 0
                            vt.finishedRounds = false
                            return@forEachIndexed
                        }
                        vt.pressed = false
                        vt.holdLockedDown = false
                        vt.swipeFinalReached = false
                        vt.pointerId = -1
                        vt.completedCycles = 0
                        vt.finishedRounds = false
                        vt.nextDeadlineNs = if (vt.config.holdInId > 0) readyAtNs else (readyAtNs + i * 500_000L)
                    }
                }

                if (isSeq && stagesList.isNotEmpty()) {
                    for (pass in 0 until 2) {
                        if (activeStageIdx >= stagesList.size) activeStageIdx = 0
                        val stage = stagesList[activeStageIdx]
                        if (stage.targets.isEmpty()) break

                        if (stage.inGap) {
                            if (nowNs < stage.gapDeadlineNs) {
                                earliestNs = minOf(earliestNs, stage.gapDeadlineNs)
                                break
                            }
                            stage.inGap = false
                            val nextStageIdx = (activeStageIdx + 1) % stagesList.size
                            if (nextStageIdx == 0) {
                                seqCompletedLoops++
                                if (loopCount >= 1 && seqCompletedLoops >= loopCount) {
                                    completedNaturally = true
                                    break
                                }
                            }
                            activeStageIdx = nextStageIdx
                            resetStageTargets(stagesList[activeStageIdx], nowNs, preserveHeld = false)
                            continue
                        }
                        if (stage.startDelayUntilNs > nowNs) {
                            earliestNs = minOf(earliestNs, stage.startDelayUntilNs)
                            break
                        }

                        if (stage.durationMs > 0L || stage.targets.size > 1) {
                            if (stage.stageDeadlineNs == 0L) {
                                resetStageTargets(stage, nowNs, preserveHeld = true)
                                stage.stageDeadlineNs = if (stage.repeatCount == 0) {
                                    Long.MAX_VALUE
                                } else {
                                    nowNs + stage.durationMs * 1_000_000L
                                }
                            }
                            if (nowNs >= stage.stageDeadlineNs) {
                                val advancing = (stage.completedRepeats + 1 >= stage.repeatCount)
                                val gapMs = sampleGaussianMs(stage.gapMs, false, 0L, 60_000L)
                                for (vt in stage.targets) {
                                    val isHoldInf = (vt.config.actionType == ACTION_HOLD && vt.config.repeatCount == 0)
                                    if (advancing && vt.pressed && vt.config.holdOutId > 0) {
                                        if (vt.config.actionType == ACTION_SWIPE && !vt.swipeFinalReached) {
                                            movePointer(vt.config.id, vt.endX, vt.endY)
                                        }
                                        val ptr = activePointers.firstOrNull { it.targetId == vt.config.id }
                                        if (ptr != null) {
                                            ptr.targetId = -30_000 - vt.config.holdOutId
                                        } else {
                                            liftPointer(vt.config.id)
                                        }
                                        vt.pressed = false
                                        vt.holdLockedDown = false
                                        vt.swipeFinalReached = false
                                        vt.pointerId = -1
                                    } else if (!advancing && gapMs <= 80L && vt.pressed && isHoldInf) {
                                        vt.completedCycles = 0
                                        vt.finishedRounds = false
                                    } else {
                                        if (vt.pressed) {
                                            liftPointer(vt.config.id)
                                        }
                                        vt.pressed = false
                                        vt.holdLockedDown = false
                                        vt.swipeFinalReached = false
                                        vt.pointerId = -1
                                    }
                                }
                                stage.stageDeadlineNs = 0L
                                stage.completedRepeats++
                                if (!advancing) {
                                    resetStageTargets(stage, nowNs + gapMs * 1_000_000L, preserveHeld = true)
                                    if (gapMs == 0L) {
                                        continue
                                    }
                                    earliestNs = minOf(earliestNs, stage.startDelayUntilNs)
                                } else {
                                    stage.completedRepeats = 0
                                    val nextStageIdx = (activeStageIdx + 1) % stagesList.size
                                    if (nextStageIdx == 0 && loopCount >= 1 && (seqCompletedLoops + 1) >= loopCount) {
                                        seqCompletedLoops++
                                        completedNaturally = true
                                        break
                                    }
                                    if (gapMs == 0L) {
                                        activeStageIdx = nextStageIdx
                                        resetStageTargets(stagesList[activeStageIdx], nowNs, preserveHeld = false)
                                        if (stagesList.size > 1) {
                                            continue
                                        }
                                    } else {
                                        stage.inGap = true
                                        stage.gapDeadlineNs = nowNs + gapMs * 1_000_000L
                                        earliestNs = minOf(earliestNs, stage.gapDeadlineNs)
                                    }
                                }
                                break
                            }

                            if (stage.stageDeadlineNs != Long.MAX_VALUE) {
                                earliestNs = minOf(earliestNs, stage.stageDeadlineNs)
                            }
                            for (vt in stage.targets) {
                                if (!vt.config.enabled || vt.finishedRounds) continue
                                val completed = stepState(vt)
                                if (completed && vt.config.repeatCount >= 1) {
                                    vt.completedCycles++
                                    if (vt.completedCycles >= vt.config.repeatCount) {
                                        vt.finishedRounds = true
                                        vt.nextDeadlineNs = Long.MAX_VALUE
                                    }
                                }
                                if (!vt.holdLockedDown && !vt.finishedRounds) {
                                    earliestNs = minOf(earliestNs, vt.nextDeadlineNs)
                                }
                            }
                            break
                        } else {
                            val vt = stage.targets[0]
                            val completed = stepState(vt)
                            if (completed && stage.repeatCount >= 1) {
                                stage.completedRepeats++
                                val isTapStage = vt.config.actionType == ACTION_TAP
                                if (stage.completedRepeats < stage.repeatCount) {
                                    val waitMs = sampleGaussianMs(vt.config.intervalMs, isTapStage && vt.config.microJitter, 5L, 60_000L)
                                    vt.nextDeadlineNs = nowNs + waitMs * 1_000_000L
                                } else {
                                    stage.completedRepeats = 0
                                    val nextStageIdx = (activeStageIdx + 1) % stagesList.size
                                    if (nextStageIdx == 0) {
                                        seqCompletedLoops++
                                        if (loopCount >= 1 && seqCompletedLoops >= loopCount) {
                                            completedNaturally = true
                                            break
                                        }
                                    }
                                    val waitMs = sampleGaussianMs(stage.gapMs, isTapStage && vt.config.microJitter && stage.gapMs >= 5L, 0L, 60_000L)
                                    activeStageIdx = nextStageIdx
                                    resetStageTargets(stagesList[activeStageIdx], nowNs + waitMs * 1_000_000L, preserveHeld = false)
                                    if (waitMs == 0L && stagesList.size > 1) {
                                        continue
                                    }
                                }
                            }
                            val nextStage = stagesList[activeStageIdx]
                            if (nextStage.startDelayUntilNs > nowNs) {
                                earliestNs = minOf(earliestNs, nextStage.startDelayUntilNs)
                            } else if (nextStage.targets.isNotEmpty() && !nextStage.targets[0].holdLockedDown) {
                                earliestNs = minOf(earliestNs, nextStage.targets[0].nextDeadlineNs)
                            }
                            break
                        }
                    }
                    if (completedNaturally) {
                        break
                    }
                } else if (!isSeq && runtimeMap.isNotEmpty()) {
                    var allFinite = true
                    var allDone = true
                    for (st in runtimeMap.values) {
                        if (st.config.repeatCount <= 0) allFinite = false
                        val completed = stepState(st)
                        if (completed && st.config.repeatCount >= 1) {
                            st.completedCycles++
                            if (st.completedCycles >= st.config.repeatCount) {
                                st.finishedRounds = true
                                st.nextDeadlineNs = Long.MAX_VALUE
                            }
                        }
                        if (st.config.repeatCount >= 1 && !st.finishedRounds) {
                            allDone = false
                        }
                        if (!st.holdLockedDown && !st.finishedRounds) {
                            earliestNs = minOf(earliestNs, st.nextDeadlineNs)
                        }
                    }
                    if (allFinite && allDone) {
                        completedNaturally = true
                        break
                    }
                }

                val sleepNs = if (earliestNs == Long.MAX_VALUE) {
                    20_000_000L
                } else {
                    (earliestNs - System.nanoTime()).coerceIn(500_000L, 50_000_000L)
                }
                LockSupport.parkNanos(sleepNs)
            }
        } finally {
            while (activePointers.isNotEmpty()) {
                liftPointer(activePointers.last().targetId)
            }
            fallbackRunning.set(false)
            synchronized(fallbackLock) {
                if (fallbackThread === Thread.currentThread()) {
                    fallbackThread = null
                }
            }
            if (completedNaturally && clickingActive.compareAndSet(true, false)) {
                if (currentMode.get() == MODE_SHIZUKU_FALLBACK) {
                    statusDetail = "Ready (Shizuku Fallback)"
                }
                notifyRemoteCallbacks { it.onClickingCompleted() }
            }
        }
    }

    private fun injectMotionFrameZeroGc(
        downTimeMs: Long,
        eventTimeMs: Long,
        action: Int,
        pointerCount: Int,
        ids: IntArray,
        xs: FloatArray,
        ys: FloatArray,
        pressures: FloatArray,
        sizes: FloatArray
    ) {
        val im = cachedInputManagerObj ?: return
        val method = cachedInjectMethod ?: return
        val count = pointerCount.coerceIn(1, MAX_SLOTS)

        synchronized(injectFrameLock) {
            for (i in 0 until count) {
                preallocProps[i].id = ids[i]
                preallocProps[i].toolType = MotionEvent.TOOL_TYPE_FINGER
                preallocCoords[i].x = xs[i]
                preallocCoords[i].y = ys[i]
                preallocCoords[i].pressure = pressures[i]
                preallocCoords[i].size = sizes[i]
            }

            val event = MotionEvent.obtain(
                downTimeMs,
                eventTimeMs,
                action,
                count,
                preallocProps,
                preallocCoords,
                0,
                0,
                1.0f,
                1.0f,
                VIRTUAL_INJECT_DEVICE_ID,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0
            )
            try {
                method.invoke(im, event, INJECT_INPUT_EVENT_MODE_ASYNC)
            } catch (t: Throwable) {
                Log.w(TAG, "injectInputEvent failed: ${t.message}")
            } finally {
                event.recycle()
            }
        }
    }

    companion object {
        private const val TAG = "TouchMuxUserService"
        private const val MAX_SLOTS = 16
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
        private const val VIRTUAL_INJECT_DEVICE_ID = -1
        private const val NATIVE_STATE_RUNNING_GRABBED = 2

        private const val ACTION_TAP = 0
        private const val ACTION_SWIPE = 1
        private const val ACTION_HOLD = 2

        const val BACKEND_AUTO = 0
        const val BACKEND_UINPUT = 1
        const val BACKEND_UHID = 2
        const val BACKEND_HYBRID = 3
        const val BACKEND_FALLBACK = 4

        const val MODE_IDLE = 0
        const val MODE_NATIVE_UINPUT = 1
        const val MODE_NATIVE_UHID = 2
        const val MODE_HYBRID_EVDEV_INJECT = 3
        const val MODE_SHIZUKU_FALLBACK = 4

        fun isNativeMode(mode: Int): Boolean =
            mode == MODE_NATIVE_UINPUT || mode == MODE_NATIVE_UHID || mode == MODE_HYBRID_EVDEV_INJECT

        fun backendLabel(mode: Int): String = when (mode) {
            MODE_NATIVE_UINPUT -> "Tier 1 UInput birleştirme"
            MODE_NATIVE_UHID -> "Tier 2 UHID sayısallaştırıcı"
            MODE_HYBRID_EVDEV_INJECT -> "Tier 3 Hybrid sıfır GC birleştirme"
            MODE_SHIZUKU_FALLBACK -> "Tier 4 Shizuku yedek enjeksiyonu"
            else -> "Boşta, etkin değil"
        }
    }
}
