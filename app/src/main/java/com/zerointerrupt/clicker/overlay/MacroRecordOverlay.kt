package com.zerointerrupt.clicker.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.zerointerrupt.clicker.R
import com.zerointerrupt.clicker.model.ActionType
import com.zerointerrupt.clicker.model.ClickConfigStore
import com.zerointerrupt.clicker.model.MacroCompiledPlan
import com.zerointerrupt.clicker.model.MacroCompiler
import com.zerointerrupt.clicker.shizuku.ShizukuController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Self-contained overlay controller managing floating macro recording controls
 * (Ready -> Armed waiting for first touch -> Live Recording -> Result Preview Modal).
 */
class MacroRecordOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val scope: CoroutineScope,
    private var colors: OverlayThemeColors,
    private val onFinished: (savedPlan: MacroCompiledPlan?, openPresetsTab: Boolean) -> Unit
) {

    enum class Stage {
        READY,
        ARMED,
        RECORDING,
        PREVIEW
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var stage: Stage = Stage.READY

    private var barView: RecordControlBarView? = null
    private var barLp: WindowManager.LayoutParams? = null
    private var previewView: RecordPreviewOverlayView? = null

    private var activePlan: MacroCompiledPlan? = null
    private var lastElapsedMs: Long = 0L
    private var lastStrokes: Int = 0
    private var lastFingers: Int = 0
    private var pollingActive = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!pollingActive) return
            val self = this
            scope.launch(Dispatchers.IO) {
                val stats = ShizukuController.getTouchRecordingStats()
                val recState = stats.getOrNull(0)?.toInt() ?: 0
                val elapsedMs = stats.getOrNull(1) ?: 0L
                val strokes = (stats.getOrNull(2) ?: 0L).toInt()
                val fingers = (stats.getOrNull(3) ?: 0L).toInt()
                withContext(Dispatchers.Main) {
                    if (!pollingActive) return@withContext
                    if (stage == Stage.ARMED && recState == 2) {
                        stage = Stage.RECORDING
                    }
                    lastElapsedMs = elapsedMs
                    lastStrokes = strokes
                    lastFingers = fingers
                    barView?.renderState(stage, lastElapsedMs, lastStrokes, lastFingers)
                    mainHandler.postDelayed(self, 100L)
                }
            }
        }
    }

    fun start() {
        dismissAll()
        stage = Stage.READY
        lastElapsedMs = 0L
        lastStrokes = 0
        lastFingers = 0
        attachControlBar()
    }

    fun updateTheme(newColors: OverlayThemeColors) {
        colors = newColors
        barView?.applyColors(newColors)
    }

    fun isActive(): Boolean = barView != null || previewView != null

    fun dismissAll() {
        stopPolling()
        scope.launch(Dispatchers.IO) {
            ShizukuController.cancelTouchRecording()
        }
        removeBar()
        removePreview()
    }

    private fun attachControlBar() {
        removeBar()
        val metrics = ShizukuController.getDisplayScreenMetrics(context)
        val view = RecordControlBarView(
            context = context,
            initialColors = colors,
            onPrimaryClick = { handlePrimaryAction() },
            onCancelClick = { handleCancel() },
            onDragMove = { nx, ny -> updateBarPosition(nx, ny) },
            onBoundsChanged = { syncExcludeRect() }
        )
        view.renderState(stage, lastElapsedMs, lastStrokes, lastFingers)

        val d = context.resources.displayMetrics.density
        view.elevation = 16f * d
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (20f * d).roundToInt()
            y = (metrics.heightPx * 0.14f).roundToInt().coerceAtLeast((56f * d).roundToInt())
        }
        windowManager.addView(view, lp)
        barView = view
        barLp = lp
    }

    private fun updateBarPosition(nx: Int, ny: Int) {
        val v = barView ?: return
        val lp = barLp ?: return
        val metrics = ShizukuController.getDisplayScreenMetrics(context)
        val vw = v.width.coerceAtLeast((180f * context.resources.displayMetrics.density).roundToInt())
        val vh = v.height.coerceAtLeast((48f * context.resources.displayMetrics.density).roundToInt())
        lp.x = nx.coerceIn(0, (metrics.widthPx - vw).coerceAtLeast(0))
        lp.y = ny.coerceIn(0, (metrics.heightPx - vh).coerceAtLeast(0))
        try {
            windowManager.updateViewLayout(v, lp)
        } catch (_: Throwable) {
        }
        syncExcludeRect()
    }

    private fun currentExcludeBounds(): IntArray {
        val v = barView
        val lp = barLp
        if (v == null || lp == null) return intArrayOf(0, 0, 0, 0)
        val w = if (v.width > 0) v.width else (260f * context.resources.displayMetrics.density).roundToInt()
        val h = if (v.height > 0) v.height else (64f * context.resources.displayMetrics.density).roundToInt()
        return intArrayOf(lp.x, lp.y, lp.x + w, lp.y + h)
    }

    private fun syncExcludeRect() {
        if (stage != Stage.ARMED && stage != Stage.RECORDING) return
        val b = currentExcludeBounds()
        scope.launch(Dispatchers.IO) {
            ShizukuController.updateRecordingExcludeRect(b[0], b[1], b[2], b[3])
        }
    }

    private fun handlePrimaryAction() {
        when (stage) {
            Stage.READY -> {
                if (!ShizukuController.isServiceConnected()) {
                    ShizukuController.evaluateShizukuState()
                    Toast.makeText(context, "Önce ana ekrandan Shizuku hizmetine izin verip bağlanın", Toast.LENGTH_SHORT).show()
                    return
                }
                val b = currentExcludeBounds()
                scope.launch(Dispatchers.IO) {
                    val ok = ShizukuController.startTouchRecording(b[0], b[1], b[2], b[3])
                    withContext(Dispatchers.Main) {
                        if (barView == null) return@withContext
                        if (!ok) {
                            Toast.makeText(
                                context,
                                "Kayıt için fiziksel dokunmatik ekran düğümü açılamadı. Lütfen laboratuvarda donanım durumunu yenileyin",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            stage = Stage.ARMED
                            lastElapsedMs = 0L
                            lastStrokes = 0
                            lastFingers = 0
                            barView?.renderState(stage, 0L, 0, 0)
                            startPolling()
                            syncExcludeRect()
                        }
                    }
                }
            }

            Stage.RECORDING -> {
                stopPolling()
                scope.launch(Dispatchers.IO) {
                    val json = ShizukuController.stopTouchRecording()
                    val presets = ClickConfigStore.loadPresets(context)
                    val settings = ClickConfigStore.loadSettings(context)
                    val metrics = ShizukuController.getDisplayScreenMetrics(context)
                    val compiled = MacroCompiler.compileFromJson(
                        rawJson = json,
                        existingPresets = presets,
                        fallbackScreenW = metrics.widthPx,
                        fallbackScreenH = metrics.heightPx,
                        microJitter = settings.defaultMicroJitter
                    )
                    withContext(Dispatchers.Main) {
                        if (compiled == null) {
                            stage = Stage.READY
                            lastElapsedMs = 0L
                            lastStrokes = 0
                            lastFingers = 0
                            barView?.renderState(stage, 0L, 0, 0)
                            Toast.makeText(context, "Geçerli dokunma hareketi algılanmadı. Lütfen yeniden kaydedin", Toast.LENGTH_SHORT).show()
                        } else {
                            activePlan = compiled
                            stage = Stage.PREVIEW
                            removeBar()
                            showPreviewModal(compiled)
                        }
                    }
                }
            }

            else -> Unit
        }
    }

    private fun handleCancel() {
        stopPolling()
        scope.launch(Dispatchers.IO) {
            ShizukuController.cancelTouchRecording()
        }
        removeBar()
        removePreview()
        onFinished(null, false)
    }

    private fun showPreviewModal(plan: MacroCompiledPlan) {
        removePreview()
        val metrics = ShizukuController.getDisplayScreenMetrics(context)
        val d = context.resources.displayMetrics.density
        val dialogWidth = minOf(metrics.widthPx - (32f * d).roundToInt(), (368f * d).roundToInt())
            .coerceAtLeast((260f * d).roundToInt())
        val maxModalHeightPx = (metrics.heightPx * 0.80f).roundToInt()
            .coerceAtLeast((280f * d).roundToInt())

        val modal = RecordPreviewOverlayView(
            context = context,
            themeColors = colors,
            plan = plan,
            maxModalHeightPx = maxModalHeightPx,
            onRetake = {
                activePlan = null
                removePreview()
                stage = Stage.READY
                attachControlBar()
            },
            onCancel = {
                activePlan = null
                removePreview()
                onFinished(null, false)
            },
            onSave = { customName, loopCount, openPresets ->
                val finalPlan = plan.withCustomMainName(customName).withLoopCount(loopCount)
                val savedMain = ClickConfigStore.createPresetBatchAndActivate(
                    context = context,
                    subPresets = finalPlan.subPresets,
                    mainPreset = finalPlan.mainPreset
                )
                activePlan = null
                removePreview()
                Toast.makeText(
                    context,
                    "「${savedMain.name}」 kaydedildi ve geçerli plan olarak ayarlandı",
                    Toast.LENGTH_SHORT
                ).show()
                onFinished(finalPlan.copy(mainPreset = savedMain), openPresets)
            }
        )

        val lp = WindowManager.LayoutParams(
            dialogWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.52f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        windowManager.addView(modal, lp)
        previewView = modal
    }

    private fun startPolling() {
        if (pollingActive) return
        pollingActive = true
        mainHandler.post(pollRunnable)
    }

    private fun stopPolling() {
        pollingActive = false
        mainHandler.removeCallbacks(pollRunnable)
    }

    private fun removeBar() {
        val v = barView ?: return
        try {
            windowManager.removeViewImmediate(v)
        } catch (_: Throwable) {
        }
        barView = null
        barLp = null
    }

    private fun removePreview() {
        val v = previewView ?: return
        try {
            windowManager.removeViewImmediate(v)
        } catch (_: Throwable) {
        }
        previewView = null
    }
}

@SuppressLint("ViewConstructor")
private class RecordControlBarView(
    context: Context,
    initialColors: OverlayThemeColors,
    private val onPrimaryClick: () -> Unit,
    private val onCancelClick: () -> Unit,
    private val onDragMove: (Int, Int) -> Unit,
    private val onBoundsChanged: () -> Unit
) : LinearLayout(context) {

    private val d = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var colors = initialColors
    private val statusDot: View
    private val titleView: TextView
    private val subtitleView: TextView
    private val primaryBtn: TextView
    private val cancelBtn: ImageView

    private var downRawX = 0f
    private var downRawY = 0f
    private var startLpX = 0
    private var startLpY = 0
    private var dragging = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((12f * d).roundToInt(), (8f * d).roundToInt(), (8f * d).roundToInt(), (8f * d).roundToInt())

        statusDot = View(context)
        addView(
            statusDot,
            LayoutParams((10f * d).roundToInt(), (10f * d).roundToInt()).apply {
                marginEnd = (10f * d).roundToInt()
            }
        )

        val textCol = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        titleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        subtitleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(titleView)
        textCol.addView(subtitleView)
        addView(
            textCol,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (12f * d).roundToInt()
            }
        )

        primaryBtn = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding((14f * d).roundToInt(), (7f * d).roundToInt(), (14f * d).roundToInt(), (7f * d).roundToInt())
            isClickable = true
            setOnClickListener { onPrimaryClick() }
        }
        addView(
            primaryBtn,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (6f * d).roundToInt()
            }
        )

        cancelBtn = ImageView(context).apply {
            val sz = (32f * d).roundToInt()
            val pad = (7f * d).roundToInt()
            setPadding(pad, pad, pad, pad)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_close))
            isClickable = true
            setOnClickListener { onCancelClick() }
            layoutParams = LayoutParams(sz, sz)
        }
        addView(cancelBtn)

        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> onBoundsChanged() }
        applyColors(initialColors)
    }

    fun applyColors(newColors: OverlayThemeColors) {
        colors = newColors
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 26f * d
            setColor(newColors.surfaceContainerHigh)
            setStroke((1f * d).roundToInt().coerceAtLeast(1), newColors.outlineVariant)
        }
        titleView.setTextColor(newColors.onSurface)
        subtitleView.setTextColor(newColors.onSurfaceVariant)
        cancelBtn.setColorFilter(newColors.onSurfaceVariant)
        cancelBtn.background = newColors.createRippleDrawable(null, isOvalMask = true)
    }

    fun renderState(stage: MacroRecordOverlay.Stage, elapsedMs: Long, strokes: Int, fingers: Int) {
        val dotColor: Int
        when (stage) {
            MacroRecordOverlay.Stage.READY -> {
                dotColor = colors.primary
                titleView.text = "Hareket kaydı hazır"
                subtitleView.text = "Bir köşeye sürükleyip「Hazır」a dokunun"
                primaryBtn.visibility = View.VISIBLE
                primaryBtn.text = "Hazır"
                stylePrimaryPill(colors.primary, colors.onPrimary)
            }
            MacroRecordOverlay.Stage.ARMED -> {
                dotColor = Color.parseColor("#F59E0B")
                titleView.text = "İlk dokunma bekleniyor…"
                subtitleView.text = "Başlamak için hedef uygulamada herhangi bir işlem yapın"
                primaryBtn.visibility = View.GONE
            }
            MacroRecordOverlay.Stage.RECORDING -> {
                dotColor = colors.error
                val secStr = String.format(Locale.US, "%.1fs", elapsedMs / 1000.0)
                titleView.text = "Kayıt sürüyor $secStr"
                subtitleView.text = if (fingers > 0) "$strokes hareket kaydedildi · Şu anda $fingers parmak basılı" else "$strokes dokunma kaydedildi"
                primaryBtn.visibility = View.VISIBLE
                primaryBtn.text = "Bitir"
                stylePrimaryPill(colors.error, Color.WHITE)
            }
            else -> dotColor = colors.primary
        }
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(dotColor)
        }
    }

    private fun stylePrimaryPill(bgColor: Int, textColor: Int) {
        primaryBtn.setTextColor(textColor)
        val pill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f * d
            setColor(bgColor)
        }
        primaryBtn.background = colors.createRippleDrawable(pill)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val lp = layoutParams as? WindowManager.LayoutParams
                downRawX = ev.rawX
                downRawY = ev.rawY
                startLpX = lp?.x ?: 0
                startLpY = lp?.y ?: 0
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(ev.rawX - downRawX, ev.rawY - downRawY) > touchSlop) {
                    dragging = true
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val lp = layoutParams as? WindowManager.LayoutParams
                downRawX = event.rawX
                downRawY = event.rawY
                startLpX = lp?.x ?: 0
                startLpY = lp?.y ?: 0
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlop) {
                    dragging = true
                }
                if (dragging) {
                    onDragMove((startLpX + dx).roundToInt(), (startLpY + dy).roundToInt())
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

private class MaxHeightScrollView(
    context: Context,
    var maxHeightPx: Int
) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val cappedSpec = when (mode) {
            MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            else -> MeasureSpec.makeMeasureSpec(minOf(size, maxHeightPx), MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, cappedSpec)
    }
}

@SuppressLint("ViewConstructor")
private class RecordPreviewOverlayView(
    context: Context,
    private val themeColors: OverlayThemeColors,
    private val plan: MacroCompiledPlan,
    private val maxModalHeightPx: Int,
    private val onRetake: () -> Unit,
    private val onCancel: () -> Unit,
    private val onSave: (customName: String, loopCount: Int, openPresetsTab: Boolean) -> Unit
) : LinearLayout(context) {

    private val d = resources.displayMetrics.density
    private val headerHeightReservePx = (132f * d).roundToInt()
    private val bodyScroll: MaxHeightScrollView

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availH = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            maxModalHeightPx
        } else {
            minOf(MeasureSpec.getSize(heightMeasureSpec), maxModalHeightPx)
        }
        bodyScroll.maxHeightPx = (availH - headerHeightReservePx).coerceAtLeast((110f * d).roundToInt())
        val cappedHeightSpec = MeasureSpec.makeMeasureSpec(availH, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, cappedHeightSpec)
    }

    init {
        orientation = VERTICAL
        setPadding((18f * d).roundToInt(), (18f * d).roundToInt(), (18f * d).roundToInt(), (16f * d).roundToInt())
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f * d
            setColor(themeColors.surfaceContainerHigh)
            setStroke((1f * d).roundToInt().coerceAtLeast(1), themeColors.outlineVariant)
        }

        // Top header row (pinned outside scroll body)
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(context).apply {
            text = "Hareket kaydı tamamlandı"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(themeColors.onSurface)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeIv = ImageView(context).apply {
            val sz = (30f * d).roundToInt()
            val pad = (6f * d).roundToInt()
            setPadding(pad, pad, pad, pad)
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_close))
            setColorFilter(themeColors.onSurfaceVariant)
            background = themeColors.createRippleDrawable(null, isOvalMask = true)
            setOnClickListener { onCancel() }
            layoutParams = LayoutParams(sz, sz)
        }
        header.addView(titleTv)
        header.addView(closeIv)
        addView(header)

        bodyScroll = MaxHeightScrollView(
            context,
            (maxModalHeightPx - headerHeightReservePx).coerceAtLeast((140f * d).roundToInt())
        ).apply {
            isVerticalScrollBarEnabled = true
        }
        val bodyContent = LinearLayout(context).apply {
            orientation = VERTICAL
        }

        // Summary line
        val durStr = formatDurationShort(plan.totalDurationMs)
        val subCount = plan.subPresets.size
        val summaryParts = mutableListOf("Süre $durStr", "${plan.rawStrokeCount} özgün işlem", "Toplam ${plan.mainPreset.targets.size} adım")
        if (subCount > 0) summaryParts += "$subCount paralel alt plan oluştur"
        val summaryTv = TextView(context).apply {
            text = summaryParts.joinToString(" · ")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(themeColors.onSurfaceVariant)
            setPadding(0, (4f * d).roundToInt(), 0, (4f * d).roundToInt())
        }
        bodyContent.addView(summaryTv)

        if (plan.mergedContinuousHolds > 0) {
            val mergeTv = TextView(context).apply {
                text = "✓ Aşamalar arasında basılı tutulmaya devam eden ${plan.mergedContinuousHolds} dokunuş otomatik olarak birleştirildi"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors.activeRunningGreen)
                setPadding(0, 0, 0, (8f * d).roundToInt())
            }
            bodyContent.addView(mergeTv)
        }

        // Scheme name EditText + child scheme hint
        val nameInput = EditText(themeColors.themedContext).apply {
            setText(plan.mainPreset.name)
            hint = "Ana planın adını girin"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(themeColors.onSurface)
            setHintTextColor(themeColors.onSurfaceVariant)
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(20))
            setPadding((12f * d).roundToInt(), (10f * d).roundToInt(), (12f * d).roundToInt(), (10f * d).roundToInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * d
                setColor(themeColors.surfaceContainer)
                setStroke(1, themeColors.outlineVariant)
            }
        }
        bodyContent.addView(
            nameInput,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4f * d).roundToInt()
            }
        )

        val structureHint = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(themeColors.onSurfaceVariant)
            setPadding((2f * d).roundToInt(), (6f * d).roundToInt(), 0, (8f * d).roundToInt())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        fun refreshHint(baseName: String) {
            val clean = baseName.trim().ifEmpty { plan.mainPreset.name }
            structureHint.text = if (subCount > 0) {
                val children = if (subCount <= 3) {
                    (1..subCount).joinToString("、") { "$clean.$it" }
                } else {
                    "$clean.1 ~ $clean.$subCount (toplam $subCount adet)"
                }
                "Kaydedilecek: ana plan「$clean」(sıralı kombinasyon) + alt planlar「$children」(paralel · varsayılan olarak dondurulmuş)"
            } else {
                "Kaydedilecek: ana plan「$clean」(sıralı kombinasyon)"
            }
        }
        refreshHint(plan.mainPreset.name)
        nameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshHint(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        bodyContent.addView(structureHint)

        var selectedLoopCount = plan.mainPreset.presetLoopCount
        val loopRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (8f * d).roundToInt())
        }
        val loopLabel = TextView(context).apply {
            text = "Çalıştırma sayısı"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(themeColors.onSurfaceVariant)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        loopRow.addView(loopLabel)
        val loopChips = mutableListOf<Pair<Int, TextView>>()
        fun styleLoopChip(tv: TextView, selected: Boolean) {
            tv.setTextColor(if (selected) themeColors.onPrimaryContainer else themeColors.onSecondaryContainer)
            tv.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tv.background = themeColors.createRippleDrawable(
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 14f * d
                    setColor(if (selected) themeColors.primaryContainer else themeColors.surfaceContainer)
                    if (!selected) setStroke(1, themeColors.outlineVariant)
                }
            )
        }
        listOf(0 to "Sonsuz döngü", 1 to "1 kez çalıştır").forEach { (cnt, label) ->
            val chip = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding((10f * d).roundToInt(), (5f * d).roundToInt(), (10f * d).roundToInt(), (5f * d).roundToInt())
                setOnClickListener {
                    selectedLoopCount = cnt
                    loopChips.forEach { (v, c) -> styleLoopChip(c, v == selectedLoopCount) }
                }
            }
            styleLoopChip(chip, cnt == selectedLoopCount)
            loopChips += cnt to chip
            loopRow.addView(
                chip,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = (6f * d).roundToInt()
                }
            )
        }
        bodyContent.addView(loopRow)

        // Scrollable step breakdown list (capped to keep preview snappy on long macros)
        val allTemp = plan.subPresets + plan.mainPreset
        val seamlessIds = ClickConfigStore.findSeamlessMergeStepIds(
            presets = allTemp,
            activePreset = plan.mainPreset,
            screenW = plan.screenW,
            screenH = plan.screenH
        )

        val stepsBox = ScrollView(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * d
                setColor(themeColors.surfaceContainer)
            }
        }
        val stepsList = LinearLayout(context).apply {
            orientation = VERTICAL
            val p = (10f * d).roundToInt()
            setPadding(p, p, p, p)
        }
        val maxPreviewSteps = 80
        val totalSteps = plan.mainPreset.targets.size
        plan.mainPreset.targets.take(maxPreviewSteps).forEach { step ->
            val row = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding((8f * d).roundToInt(), (6f * d).roundToInt(), (8f * d).roundToInt(), (6f * d).roundToInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 10f * d
                    setColor(ColorUtils.setAlphaComponent(themeColors.surfaceContainerHighest, 175))
                }
            }
            val title = when (step.actionType) {
                ActionType.SUB_PRESET -> {
                    val sub = plan.subPresets.firstOrNull { it.id == step.refPresetId }
                    "#${step.index} paralel alt plan: ${sub?.name ?: "Paralel bölüm"} (${sub?.targets?.size ?: 0} dokunma noktası)"
                }
                ActionType.SWIPE ->
                    "#${step.index} Kaydırma (${step.x.roundToInt()},${step.y.roundToInt()} → ${step.endX.roundToInt()},${step.endY.roundToInt()})"
                else -> {
                    val clickTag = if (step.pressDurationMs <= 120L && step.repeatCount > 1) "Ardışık dokunuş (basılı tutma ile)" else "Uzun basma"
                    "#${step.index} $clickTag (${step.x.roundToInt()},${step.y.roundToInt()})"
                }
            }
            val detail = when (step.actionType) {
                ActionType.SUB_PRESET -> {
                    val gapStr = if (step.intervalMs <= 0L) "0 ms kesintisiz" else "${step.intervalMs} ms bekle"
                    val mergeStr = if (step.id in seamlessIds) " · Dokunuş kesintisiz sürer" else ""
                    "${formatDurationShort(step.subDurationMs)} sürer · $gapStr$mergeStr"
                }
                ActionType.SWIPE -> {
                    val gapStr = if (step.intervalMs <= 0L) "0ms" else "${step.intervalMs} ms bekle"
                    "${step.swipeDurationMs} ms kaydırma · $gapStr"
                }
                else -> {
                    val gapStr = if (step.intervalMs <= 0L) "0 ms kesintisiz" else "${step.intervalMs} ms bekle"
                    val mergeStr = if (step.id in seamlessIds) " · Dokunuş kesintisiz sürer" else ""
                    "${step.pressDurationMs} ms basılı tut · $gapStr · ${step.repeatCount} kez$mergeStr"
                }
            }

            row.addView(TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors.onSurface)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(TextView(context).apply {
                text = detail
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(if (step.id in seamlessIds) themeColors.activeRunningGreen else themeColors.onSurfaceVariant)
                maxLines = 2
            })
            stepsList.addView(
                row,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = (6f * d).roundToInt()
                }
            )
        }
        if (totalSteps > maxPreviewSteps) {
            stepsList.addView(
                TextView(context).apply {
                    text = "… Kalan ${totalSteps - maxPreviewSteps} adım önizlemede daraltıldı; kaydedildiğinde $totalSteps adımın tamamı korunacak"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTextColor(themeColors.onSurfaceVariant)
                    gravity = Gravity.CENTER
                    setPadding((8f * d).roundToInt(), (6f * d).roundToInt(), (8f * d).roundToInt(), (4f * d).roundToInt())
                }
            )
        }
        stepsBox.addView(stepsList)
        val maxListHeight = minOf((200f * d).roundToInt(), (maxModalHeightPx * 0.34f).roundToInt())
            .coerceAtLeast((110f * d).roundToInt())
        bodyContent.addView(
            stepsBox,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (totalSteps > 3) {
                    height = maxListHeight
                }
                bottomMargin = (10f * d).roundToInt()
            }
        )
        bodyScroll.addView(bodyContent)
        addView(
            bodyScroll,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (6f * d).roundToInt()
            }
        )

        // Footer actions (pinned outside scroll body, always reachable)
        val btnRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        fun makeActionBtn(label: String, bg: Int, fg: Int, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(fg)
                setPadding((14f * d).roundToInt(), (9f * d).roundToInt(), (14f * d).roundToInt(), (9f * d).roundToInt())
                background = themeColors.createRippleDrawable(
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 18f * d
                        setColor(bg)
                    }
                )
                setOnClickListener { onClick() }
            }
        }

        btnRow.addView(
            makeActionBtn("Yeniden kaydet", themeColors.secondaryContainer, themeColors.onSecondaryContainer, onRetake),
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (6f * d).roundToInt()
            }
        )
        btnRow.addView(
            makeActionBtn("Plan sayfasına git", themeColors.primaryContainer, themeColors.onPrimaryContainer) {
                onSave(nameInput.text.toString(), selectedLoopCount, true)
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (6f * d).roundToInt()
            }
        )
        btnRow.addView(
            makeActionBtn("Kaydet ve kullan", themeColors.primary, themeColors.onPrimary) {
                onSave(nameInput.text.toString(), selectedLoopCount, false)
            }
        )
        addView(btnRow)
    }

    private fun formatDurationShort(ms: Long): String {
        return if (ms >= 1000L) {
            val sec = ms / 1000.0
            if (ms % 1000L == 0L) "${ms / 1000L} saniye" else String.format(Locale.US, "%.2f saniye", sec)
        } else {
            "${ms}ms"
        }
    }
}
