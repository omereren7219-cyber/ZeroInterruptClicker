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
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

data class ShizukuDiagnostics(
    val binderAlive: Boolean = false,
    val permissionGranted: Boolean = false,
    val userServiceBinderAlive: Boolean = false,
    val connectionState: ShizukuConnectionState = ShizukuConnectionState.SHIZUKU_NOT_RUNNING,
    val shizukuVersion: Int = -1,
    val lastEvent: String = "Henüz tanı çalıştırılmadı",
    val lastError: String = "Henüz hata ayrıntısı yok",
    val lastCallback: String = "Henüz callback çağrılmadı",
    val exceptionClass: String = "",
    val exceptionMessage: String = "",
    val bindStatus: String = "Başlatılmadı",
    val bindDurationMs: Long = -1L,
    val onServiceConnectedResult: String = "Henüz çağrılmadı",
    val onServiceDisconnectedResult: String = "Henüz çağrılmadı",
    val onBindingDiedResult: String = "Henüz çağrılmadı",
    val onNullBindingResult: String = "Henüz çağrılmadı",
    val bindStartedAtMs: Long = -1L,
    val bindFinishedAtMs: Long = -1L,
    val eventTimeline: List<ShizukuDiagnosticEvent> = emptyList()
)

data class ShizukuDiagnosticEvent(
    val timestampMs: Long,
    val event: String,
    val detail: String,
    val severity: String
)

/**
 * Process-wide Shizuku UserService connection, permission, auto-rebind, and IPC controller.
 */
object ShizukuController {
    private const val TAG = "ShizukuBaglanti"
    private const val DIAGNOSTICS_PREFS = "shizuku_diagnostics"
    private const val MAX_TIMELINE_EVENTS = 100
    const val REQUEST_CODE_SHIZUKU = 10042

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appContext: Context? = null
    private var listenersRegistered = false
    private var boundArgs: Shizuku.UserServiceArgs? = null
    private var bindTimeoutJob: Job? = null
    private var bindStartedAtMs = 0L
    private const val BIND_TIMEOUT_MS = 5_000L
    private const val REBIND_DELAY_MS = 250L
    private val bindLock = Any()
    private var bindInFlight = false
    @Volatile
    private var service: ITouchMuxService? = null

    private val _connectionState = MutableStateFlow(ShizukuConnectionState.SHIZUKU_NOT_RUNNING)
    val connectionState: StateFlow<ShizukuConnectionState> = _connectionState.asStateFlow()

    private val _diagnostics = MutableStateFlow(ShizukuDiagnostics())
    val diagnostics: StateFlow<ShizukuDiagnostics> = _diagnostics.asStateFlow()

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
        Log.d(TAG, "Shizuku binder alındı")
        Log.i(TAG, "Shizuku binder received.")
        evaluateShizukuState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder öldü; bağlantı temizleniyor")
        Log.w(TAG, "Shizuku binder died; resetting service reference.")
        service = null
        bindTimeoutJob?.cancel()
        bindTimeoutJob = null
        synchronized(bindLock) { bindInFlight = false }
        _slotStates.value = List(16) { 0 }
        _connectionState.value = ShizukuConnectionState.ERROR
        _engineSnapshot.value = _engineSnapshot.value.copy(
            engineMode = TouchMuxUserService.MODE_IDLE,
            statusDetail = "Bağlantı Koptu: Shizuku binder bağlantısı kesildi"
        )
        publishDiagnostics("Shizuku binder öldü", "Shizuku binder bağlantısı kesildi")
        scheduleRebind()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE_SHIZUKU) {
            Log.d(TAG, "Shizuku izin sonucu alındı: $grantResult")
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Shizuku izni verildi; UserService bind başlatılıyor")
                bindUserService()
            } else {
                _connectionState.value = ShizukuConnectionState.NO_PERMISSION
                _engineSnapshot.value = _engineSnapshot.value.copy(
                    statusDetail = "Kullanıcı Shizuku iznini reddetti"
                )
                publishDiagnostics(
                    "Shizuku izni reddedildi",
                    "checkSelfPermission sonucu: DENIED ($grantResult)"
                )
            }
        }
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "UserService onServiceConnected çağrıldı: $name")
            recordCallback("onServiceConnected", "ÇAĞRILDI: component=$name")
            bindTimeoutJob?.cancel()
            bindTimeoutJob = null
            if (binder == null || !binder.pingBinder()) {
                recordCallback("onServiceConnected", "HATA: binder geçersiz veya ölü")
                failBinding("onServiceConnected binder geçersiz veya ölü: component=$name")
                return
            }
            val svc = ITouchMuxService.Stub.asInterface(binder)
            service = svc
            try {
                svc.registerCallback(serviceCallback)
                appContext?.let { ctx ->
                    val settings = ClickConfigStore.loadSettings(ctx)
                    svc.setVolumeKeyControlEnabled(settings.volumeKeyToggleEnabled)
                    svc.setPreferredBackend(settings.preferredBackend)
                }
            } catch (t: Throwable) {
                service = null
                recordCallback("onServiceConnected", "HATA: ${t.javaClass.simpleName}: ${t.message ?: "mesaj yok"}")
                failBinding(
                    "UserService el sıkışması başarısız: ${t.javaClass.simpleName}: ${t.message ?: "mesaj yok"}",
                    t
                )
                return
            }
            synchronized(bindLock) { bindInFlight = false }
            _connectionState.value = ShizukuConnectionState.CONNECTED
            recordCallback("onServiceConnected", "BAŞARILI: AIDL el sıkışması tamamlandı")
            publishDiagnostics("UserService bağlandı", "", bindStatus = "Bağlandı")
            try {
                binder.linkToDeath({
                    Log.d(TAG, "TouchMuxUserService binder öldü; bağlantı temizleniyor")
                    Log.w(TAG, "TouchMuxUserService remote process died.")
                    service = null
                    synchronized(bindLock) { bindInFlight = false }
                    _slotStates.value = List(16) { 0 }
                    _connectionState.value = ShizukuConnectionState.ERROR
                    _engineSnapshot.value = _engineSnapshot.value.copy(
                        engineMode = TouchMuxUserService.MODE_IDLE,
                        statusDetail = "Bağlantı Koptu: Shizuku UserService binder bağlantısı kesildi"
                    )
                    publishDiagnostics("UserService binder öldü", "UserService binder bağlantısı kesildi")
                    scheduleRebind()
                }, 0)
            } catch (t: Throwable) {
                Log.w(TAG, "UserService binder ölüm bildirimi kaydedilemedi", t)
            }
            refreshCapabilitiesAsync()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "UserService onServiceDisconnected çağrıldı: $name")
            recordCallback("onServiceDisconnected", "ÇAĞRILDI: component=$name")
            service = null
            synchronized(bindLock) { bindInFlight = false }
            _slotStates.value = List(16) { 0 }
            _connectionState.value = ShizukuConnectionState.ERROR
            _engineSnapshot.value = _engineSnapshot.value.copy(
                engineMode = TouchMuxUserService.MODE_IDLE,
                statusDetail = "Bağlantı Koptu: Shizuku UserService bağlantısı kesildi"
            )
            publishDiagnostics(
                "UserService bağlantısı koptu",
                "onServiceDisconnected çağrıldı: component=$name",
                bindStatus = "Koptu"
            )
            scheduleRebind()
        }

        override fun onBindingDied(name: ComponentName) {
            Log.e(TAG, "UserService onBindingDied çağrıldı: component=$name")
            recordCallback("onBindingDied", "ÇAĞRILDI: component=$name")
            service = null
            synchronized(bindLock) { bindInFlight = false }
            _slotStates.value = List(16) { 0 }
            _connectionState.value = ShizukuConnectionState.ERROR
            _engineSnapshot.value = _engineSnapshot.value.copy(
                engineMode = TouchMuxUserService.MODE_IDLE,
                statusDetail = "UserService binding öldü"
            )
            publishDiagnostics(
                "UserService binding öldü",
                "onBindingDied çağrıldı: component=$name",
                bindStatus = "Koptu"
            )
            scheduleRebind()
        }

        override fun onNullBinding(name: ComponentName) {
            Log.e(TAG, "UserService onNullBinding çağrıldı: component=$name")
            recordCallback("onNullBinding", "ÇAĞRILDI: component=$name")
            service = null
            synchronized(bindLock) { bindInFlight = false }
            _slotStates.value = List(16) { 0 }
            _connectionState.value = ShizukuConnectionState.ERROR
            _engineSnapshot.value = _engineSnapshot.value.copy(
                engineMode = TouchMuxUserService.MODE_IDLE,
                statusDetail = "UserService null binder döndürdü"
            )
            publishDiagnostics(
                "UserService null binder",
                "onNullBinding çağrıldı: component=$name",
                bindStatus = "Hata"
            )
            scheduleRebind()
        }
    }

    fun initialize(context: Context) {
        Log.d(TAG, "Shizuku initialize başlatıldı")
        if (appContext == null) {
            appContext = context.applicationContext
            loadPersistedDiagnostics()
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
            Log.d(TAG, "Shizuku binder bulunamadı")
            service = null
            _slotStates.value = List(16) { 0 }
            _connectionState.value = ShizukuConnectionState.SHIZUKU_NOT_RUNNING
            _engineSnapshot.value = EngineSnapshot(statusDetail = "Önce Shizuku hizmetini başlatın")
            publishDiagnostics("Shizuku binder bulunamadı", "Shizuku.pingBinder() = false")
            return
        }
        if (!hasPermission()) {
            Log.d(TAG, "Shizuku izni yok; izin isteniyor")
            _connectionState.value = ShizukuConnectionState.NO_PERMISSION
            _engineSnapshot.value = EngineSnapshot(statusDetail = "Shizuku İzni Gerekli")
            publishDiagnostics("Shizuku izni yok", "Shizuku.checkSelfPermission() = DENIED")
            requestPermission()
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
        Log.d(TAG, "Shizuku izin akışı başlatıldı")
        try {
            if (!Shizuku.pingBinder()) {
                Log.d(TAG, "İzin isteği başarısız: Shizuku binder yok")
                _connectionState.value = ShizukuConnectionState.SHIZUKU_NOT_RUNNING
                _engineSnapshot.value = _engineSnapshot.value.copy(
                    statusDetail = "Shizuku hizmeti çalışmıyor"
                )
                return
            }
            if (hasPermission()) {
                Log.d(TAG, "Shizuku izni zaten mevcut; bind başlatılıyor")
                bindUserService()
                return
            }
            Log.d(TAG, "Shizuku.requestPermission çağrılıyor")
            Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
        } catch (t: Throwable) {
            Log.e(TAG, "Shizuku izin akışı başarısız", t)
            _connectionState.value = ShizukuConnectionState.ERROR
            _engineSnapshot.value = _engineSnapshot.value.copy(
                statusDetail = "Shizuku izni isteği başarısız: ${t.message}"
            )
        }
    }

    fun bindUserService() {
        Log.d(TAG, "Shizuku.bindUserService hazırlığı başlatıldı")
        val ctx = appContext ?: return
        if (!Shizuku.pingBinder() || !hasPermission()) {
            Log.d(TAG, "UserService bind iptal edildi: binder veya izin yok")
            evaluateShizukuState()
            return
        }
        if (service?.asBinder()?.pingBinder() == true) {
            Log.d(TAG, "UserService zaten bağlı")
            _connectionState.value = ShizukuConnectionState.CONNECTED
            return
        }
        synchronized(bindLock) {
            if (bindInFlight) {
                Log.d(TAG, "UserService bind zaten devam ediyor")
                return
            }
            bindInFlight = true
        }
        _connectionState.value = ShizukuConnectionState.BINDING
        bindStartedAtMs = System.currentTimeMillis()
        publishDiagnostics("Shizuku.bindUserService çağrıldı", "", bindStatus = "Devam ediyor")
        val args = boundArgs ?: Shizuku.UserServiceArgs(
            ComponentName(ctx.packageName, TouchMuxUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("touchmux")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
            .also { boundArgs = it }

        try {
            Log.d(TAG, "Shizuku.bindUserService çağrılıyor")
            Shizuku.bindUserService(args, userServiceConnection)
            bindTimeoutJob?.cancel()
            bindTimeoutJob = scope.launch {
                delay(BIND_TIMEOUT_MS)
                val timedOut = synchronized(bindLock) {
                    if (bindInFlight && service == null) {
                        bindInFlight = false
                        true
                    } else {
                        false
                    }
                }
                if (timedOut) {
                    Log.d(TAG, "UserService bağlantı timeout: 5 saniye")
                    Log.e(TAG, "Timed out waiting for Shizuku UserService connection")
                    unbindUserService()
                    _connectionState.value = ShizukuConnectionState.ERROR
                    _engineSnapshot.value = _engineSnapshot.value.copy(
                        statusDetail = "Bağlantı Zaman Aşımı: Shizuku UserService 5 saniye içinde bağlanamadı"
                    )
                    publishDiagnostics(
                        "UserService bind zaman aşımı",
                        "onServiceConnected 5 saniye içinde çağrılmadı",
                        bindStatus = "Timeout (5000 ms)"
                    )
                }
                bindTimeoutJob = null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Shizuku.bindUserService başarısız", t)
            bindTimeoutJob?.cancel()
            bindTimeoutJob = null
            synchronized(bindLock) { bindInFlight = false }
            failBinding(
                "UserService bind çağrısı başarısız: ${t.javaClass.simpleName}: ${t.message ?: "mesaj yok"}",
                t
            )
        }
    }

    private fun failBinding(detail: String, throwable: Throwable? = null) {
        synchronized(bindLock) { bindInFlight = false }
        _connectionState.value = ShizukuConnectionState.ERROR
        _engineSnapshot.value = _engineSnapshot.value.copy(statusDetail = detail)
        Log.e(TAG, detail)
        publishDiagnostics(
            event = "UserService bind başarısız",
            error = detail,
            throwable = throwable,
            bindStatus = "Hata"
        )
    }

    fun refreshShizukuDiagnostics() {
        publishDiagnostics("Manuel tanı yenilendi", "")
    }

    private fun publishDiagnostics(
        event: String,
        error: String,
        throwable: Throwable? = null,
        callbackName: String? = null,
        bindStatus: String? = null,
        timelineDetail: String? = null
    ) {
        var binderError = ""
        val binderAlive = try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            binderError = "${t.javaClass.simpleName}: ${t.message ?: "mesaj yok"}"
            false
        }
        var permissionError = ""
        val permissionGranted = try {
            binderAlive && !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            permissionError = "${t.javaClass.simpleName}: ${t.message ?: "mesaj yok"}"
            false
        }
        var serviceError = ""
        val serviceAlive = try {
            service?.asBinder()?.pingBinder() == true
        } catch (t: Throwable) {
            serviceError = "${t.javaClass.simpleName}: ${t.message ?: "mesaj yok"}"
            false
        }
        val version = try {
            Shizuku.getVersion()
        } catch (_: Throwable) {
            -1
        }
        val probeError = listOf(binderError, permissionError, serviceError)
            .filter { it.isNotBlank() }
            .joinToString("; ")
        val manualRefresh = event == "Manuel tanı yenilendi"
        val effectiveError = error.ifBlank { probeError }
        val previous = _diagnostics.value
        val exceptionClass = throwable?.javaClass?.name.orEmpty()
        val exceptionMessage = throwable?.message.orEmpty()
        val nowMs = System.currentTimeMillis()
        val severity = when {
            effectiveError.isNotBlank() || bindStatus?.contains("Timeout") == true -> "ERROR"
            bindStatus == "Bağlandı" -> "SUCCESS"
            else -> "INFO"
        }
        val timeline = (previous.eventTimeline + ShizukuDiagnosticEvent(
            timestampMs = nowMs,
            event = event,
            detail = timelineDetail ?: effectiveError.ifBlank { error }.ifBlank { "Durum kaydı" },
            severity = severity
        )).takeLast(MAX_TIMELINE_EVENTS)
        val durationMs = if (bindStatus != null && bindStatus != "Devam ediyor" && bindStartedAtMs > 0L) {
            nowMs - bindStartedAtMs
        } else {
            previous.bindDurationMs
        }
        val bindFinished = bindStatus != null && bindStatus != "Devam ediyor"
        val current = ShizukuDiagnostics(
            binderAlive = binderAlive,
            permissionGranted = permissionGranted,
            userServiceBinderAlive = serviceAlive,
            connectionState = _connectionState.value,
            shizukuVersion = version,
            lastEvent = if (manualRefresh) previous.lastEvent else event,
            lastError = if (manualRefresh) previous.lastError else effectiveError.ifBlank { "Henüz hata ayrıntısı yok" },
            lastCallback = previous.lastCallback,
            exceptionClass = if (manualRefresh) previous.exceptionClass else exceptionClass,
            exceptionMessage = if (manualRefresh) previous.exceptionMessage else exceptionMessage,
            bindStatus = bindStatus ?: previous.bindStatus,
            bindDurationMs = durationMs,
            onServiceConnectedResult = previous.onServiceConnectedResult,
            onServiceDisconnectedResult = previous.onServiceDisconnectedResult,
            onBindingDiedResult = previous.onBindingDiedResult,
            onNullBindingResult = previous.onNullBindingResult,
            bindStartedAtMs = if (bindStartedAtMs > 0L) bindStartedAtMs else previous.bindStartedAtMs,
            bindFinishedAtMs = if (bindFinished) nowMs else previous.bindFinishedAtMs,
            eventTimeline = timeline
        )
        val withCallback = callbackName?.let { callback ->
            current.copy(
                lastCallback = callback,
                onServiceConnectedResult = if (callback.startsWith("onServiceConnected")) callback else current.onServiceConnectedResult,
                onServiceDisconnectedResult = if (callback.startsWith("onServiceDisconnected")) callback else current.onServiceDisconnectedResult,
                onBindingDiedResult = if (callback.startsWith("onBindingDied")) callback else current.onBindingDiedResult,
                onNullBindingResult = if (callback.startsWith("onNullBinding")) callback else current.onNullBindingResult
            )
        } ?: current
        _diagnostics.value = withCallback
        persistDiagnostics(withCallback)
        Log.i(
            TAG,
            "diag event=$event binderAlive=$binderAlive permissionGranted=$permissionGranted " +
                "userServiceBinderAlive=$serviceAlive state=${current.connectionState} " +
                "version=$version error=${withCallback.lastError} " +
                "exception=${withCallback.exceptionClass}: ${withCallback.exceptionMessage}"
        )
    }

    private fun recordCallback(name: String, result: String) {
        val current = _diagnostics.value
        publishDiagnostics(
            event = name,
            error = if (result.startsWith("HATA")) result else "",
            callbackName = "$name: $result",
            bindStatus = current.bindStatus,
            timelineDetail = result
        )
    }

    private fun loadPersistedDiagnostics() {
        val prefs = appContext?.getSharedPreferences(DIAGNOSTICS_PREFS, Context.MODE_PRIVATE) ?: return
        val timeline = runCatching {
            val json = JSONArray(prefs.getString("eventTimeline", "[]"))
            buildList {
                for (i in 0 until json.length()) {
                    val item = json.getJSONObject(i)
                    add(
                        ShizukuDiagnosticEvent(
                            timestampMs = item.optLong("timestampMs", -1L),
                            event = item.optString("event", "Bilinmeyen olay"),
                            detail = item.optString("detail", "Durum kaydı"),
                            severity = item.optString("severity", "INFO")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
        _diagnostics.value = ShizukuDiagnostics(
            binderAlive = prefs.getBoolean("binderAlive", false),
            permissionGranted = prefs.getBoolean("permissionGranted", false),
            userServiceBinderAlive = prefs.getBoolean("userServiceBinderAlive", false),
            connectionState = runCatching {
                ShizukuConnectionState.valueOf(prefs.getString("connectionState", ShizukuConnectionState.ERROR.name)!!)
            }.getOrDefault(ShizukuConnectionState.ERROR),
            shizukuVersion = prefs.getInt("shizukuVersion", -1),
            lastEvent = prefs.getString("lastEvent", "Henüz tanı çalıştırılmadı") ?: "Henüz tanı çalıştırılmadı",
            lastError = prefs.getString("lastError", "Henüz hata ayrıntısı yok") ?: "Henüz hata ayrıntısı yok",
            lastCallback = prefs.getString("lastCallback", "Henüz callback çağrılmadı") ?: "Henüz callback çağrılmadı",
            exceptionClass = prefs.getString("exceptionClass", "") ?: "",
            exceptionMessage = prefs.getString("exceptionMessage", "") ?: "",
            bindStatus = prefs.getString("bindStatus", "Başlatılmadı") ?: "Başlatılmadı",
            bindDurationMs = prefs.getLong("bindDurationMs", -1L),
            onServiceConnectedResult = prefs.getString("onServiceConnectedResult", "Henüz çağrılmadı") ?: "Henüz çağrılmadı",
            onServiceDisconnectedResult = prefs.getString("onServiceDisconnectedResult", "Henüz çağrılmadı") ?: "Henüz çağrılmadı",
            onBindingDiedResult = prefs.getString("onBindingDiedResult", "Henüz çağrılmadı") ?: "Henüz çağrılmadı",
            onNullBindingResult = prefs.getString("onNullBindingResult", "Henüz çağrılmadı") ?: "Henüz çağrılmadı",
            bindStartedAtMs = prefs.getLong("bindStartedAtMs", -1L),
            bindFinishedAtMs = prefs.getLong("bindFinishedAtMs", -1L),
            eventTimeline = timeline
        )
    }

    private fun persistDiagnostics(value: ShizukuDiagnostics) {
        appContext?.getSharedPreferences(DIAGNOSTICS_PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean("binderAlive", value.binderAlive)
            ?.putBoolean("permissionGranted", value.permissionGranted)
            ?.putBoolean("userServiceBinderAlive", value.userServiceBinderAlive)
            ?.putString("connectionState", value.connectionState.name)
            ?.putInt("shizukuVersion", value.shizukuVersion)
            ?.putString("lastEvent", value.lastEvent)
            ?.putString("lastError", value.lastError)
            ?.putString("lastCallback", value.lastCallback)
            ?.putString("exceptionClass", value.exceptionClass)
            ?.putString("exceptionMessage", value.exceptionMessage)
            ?.putString("bindStatus", value.bindStatus)
            ?.putLong("bindDurationMs", value.bindDurationMs)
            ?.putString("onServiceConnectedResult", value.onServiceConnectedResult)
            ?.putString("onServiceDisconnectedResult", value.onServiceDisconnectedResult)
            ?.putString("onBindingDiedResult", value.onBindingDiedResult)
            ?.putString("onNullBindingResult", value.onNullBindingResult)
            ?.putLong("bindStartedAtMs", value.bindStartedAtMs)
            ?.putLong("bindFinishedAtMs", value.bindFinishedAtMs)
            ?.putString("eventTimeline", JSONArray().apply {
                value.eventTimeline.forEach { item ->
                    put(JSONObject().apply {
                        put("timestampMs", item.timestampMs)
                        put("event", item.event)
                        put("detail", item.detail)
                        put("severity", item.severity)
                    })
                }
            }.toString())
            ?.apply()
    }

    fun exportDiagnosticsText(): String {
        val value = _diagnostics.value
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        fun time(ms: Long): String = if (ms > 0L) format.format(Date(ms)) else "Henüz yok"
        fun line(label: String, content: String): String = "$label: $content"
        return buildString {
            appendLine("ZeroInterruptClicker Shizuku teşhis kaydı")
            appendLine(line("Dışa aktarma zamanı", time(System.currentTimeMillis())))
            appendLine(line("Shizuku binder", value.binderAlive.toString()))
            appendLine(line("Shizuku izni", value.permissionGranted.toString()))
            appendLine(line("UserService binder", value.userServiceBinderAlive.toString()))
            appendLine(line("Bağlantı durumu", value.connectionState.name))
            appendLine(line("Shizuku API sürümü", value.shizukuVersion.toString()))
            appendLine(line("Bind durumu", value.bindStatus))
            appendLine(line("Bind başlangıcı", time(value.bindStartedAtMs)))
            appendLine(line("Bind bitişi", time(value.bindFinishedAtMs)))
            appendLine(line("Bind süresi", if (value.bindDurationMs >= 0L) "${value.bindDurationMs} ms" else "Henüz tamamlanmadı"))
            appendLine(line("Son olay", value.lastEvent))
            appendLine(line("Son hata", value.lastError))
            appendLine(line("Son callback", value.lastCallback))
            appendLine(line("Exception sınıfı", value.exceptionClass.ifBlank { "Yok" }))
            appendLine(line("Exception mesajı", value.exceptionMessage.ifBlank { "Yok" }))
            appendLine()
            appendLine("Olay zaman çizelgesi:")
            if (value.eventTimeline.isEmpty()) {
                appendLine("Henüz olay kaydı yok")
            } else {
                value.eventTimeline.forEach { item ->
                    appendLine("${time(item.timestampMs)} [${item.severity}] ${item.event}: ${item.detail}")
                }
            }
        }
    }

    private fun scheduleRebind() {
        scope.launch {
            delay(REBIND_DELAY_MS)
            if (Shizuku.pingBinder() && hasPermission() && service?.asBinder()?.pingBinder() != true) {
                Log.i(TAG, "Shizuku UserService yeniden bağlanıyor")
                bindUserService()
            }
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
        bindTimeoutJob?.cancel()
        bindTimeoutJob = null
        synchronized(bindLock) { bindInFlight = false }
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
