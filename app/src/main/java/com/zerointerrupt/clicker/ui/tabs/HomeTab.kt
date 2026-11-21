package com.zerointerrupt.clicker.ui.tabs

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zerointerrupt.clicker.shizuku.ShizukuConnectionState
import com.zerointerrupt.clicker.ui.MainScreenCallbacks
import com.zerointerrupt.clicker.ui.MainUiState
import com.zerointerrupt.clicker.ui.components.OverflowMenuAction
import com.zerointerrupt.clicker.ui.components.PageTopHeader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val HeroRingEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

@Composable
fun HomeTab(
    state: MainUiState,
    callbacks: MainScreenCallbacks,
    onOpenSetupWizard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    val menuActions = buildList {
        add(
            OverflowMenuAction(
                label = "Kurulum sihirbazı",
                badge = if (state.needsSetupWizard) "Yapılandırılmadı" else "Hazır",
                badgeHighlight = state.needsSetupWizard,
                onClick = onOpenSetupWizard
            )
        )
        if (state.connectionState == ShizukuConnectionState.CONNECTED) {
            add(
                OverflowMenuAction(
                    label = "Donanımın özel kullanımını bırak",
                    isDestructive = true,
                    onClick = callbacks.onReleaseNative
                )
            )
        }
        add(
            OverflowMenuAction(
                label = "Durumu yenile",
                onClick = callbacks.onRefreshCapabilities
            )
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val ringSize = when {
            maxHeight < 380.dp -> 148.dp
            maxHeight < 540.dp -> 176.dp
            else -> 212.dp
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PageTopHeader(
                title = "Başlat",
                menuActions = menuActions,
                showMenuAlertDot = state.needsSetupWizard
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                HeroWavyRingButton(
                    needsSetup = state.needsSetupWizard,
                    overlayVisible = state.overlayVisible,
                    clickingRunning = state.clickingRunning,
                    ringSize = ringSize,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (state.needsSetupWizard) {
                            onOpenSetupWizard()
                        } else {
                            callbacks.onToggleOverlay()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun HeroWavyRingButton(
    needsSetup: Boolean,
    overlayVisible: Boolean,
    clickingRunning: Boolean,
    ringSize: Dp,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 480f),
        label = "heroPressScale"
    )

    // One-shot transition: closed circle (0f, ▶) <-> half-split wavy ring at 1/2 rest (1f, ||)
    val openFraction by animateFloatAsState(
        targetValue = if (overlayVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 620, easing = HeroRingEasing),
        label = "heroRingOpenFraction"
    )

    // Continuous M3 wavy phase scroll while active/opened (matches 5s self-check cadence)
    val wavePhase = if (overlayVisible || openFraction > 0.002f) {
        val waveTransition = rememberInfiniteTransition(label = "heroWavyRingTransition")
        val animatedPhase by waveTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1350, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "heroWavyRingPhase"
        )
        animatedPhase
    } else {
        0f
    }

    val activeRingColor by animateColorAsState(
        targetValue = when {
            needsSetup && !overlayVisible -> cs.tertiary
            else -> cs.primary
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "heroRingColor"
    )

    val trackColor = cs.secondaryContainer
    val wavyPath = remember { Path() }
    val triPath = remember { Path() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(ringSize)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clip(CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokePx = (ringSize * 0.068f).toPx()
                val maxAmpPx = (ringSize * 0.025f).toPx()
                val pad = strokePx * 0.5f + maxAmpPx + 2.dp.toPx()
                val cx = size.width * 0.5f
                val cy = size.height * 0.5f
                val radius = (minOf(size.width, size.height) * 0.5f - pad).coerceAtLeast(16f)

                val p = openFraction.coerceIn(0f, 1f)
                if (p <= 0.002f) {
                    // Closed state (▶): crisp 360° solid circle (per mp4 frame 1)
                    drawCircle(
                        color = activeRingColor,
                        radius = radius,
                        center = Offset(cx, cy),
                        style = Stroke(width = strokePx)
                    )
                } else {
                    val sepDeg = ((strokePx * 1.85f) / radius * 180.0 / PI)
                        .toFloat()
                        .coerceIn(18f, 30f)
                    val halfSep = sepDeg * 0.5f
                    val gapOpen = (p / 0.14f).coerceIn(0f, 1f)
                    val curHalfSep = halfSep * gapOpen
                    val splitSweep = 180f * p

                    // Inactive smooth track slides counter-clockwise from 12 o'clock down to left 1/2
                    val trackStartDeg = -90f - curHalfSep
                    val trackSweep = (splitSweep - sepDeg * gapOpen).coerceAtLeast(0f)
                    if (trackSweep > 1f) {
                        val arcLeft = cx - radius
                        val arcTop = cy - radius
                        val arcDiam = radius * 2f
                        drawArc(
                            color = trackColor,
                            startAngle = trackStartDeg,
                            sweepAngle = -trackSweep,
                            useCenter = false,
                            topLeft = Offset(arcLeft, arcTop),
                            size = Size(arcDiam, arcDiam),
                            style = Stroke(width = strokePx, cap = StrokeCap.Round)
                        )
                    }

                    // Active right arc morphs smoothly into M3 waves and rests at 1/2
                    val activeStartDeg = -90f + curHalfSep
                    val activeEndDeg = 270f - splitSweep - curHalfSep
                    val activeSweepDeg = (activeEndDeg - activeStartDeg).coerceAtLeast(2f)

                    val waveFactor = p * p * (3f - 2f * p)
                    val ampPx = maxAmpPx * waveFactor
                    val steps = 128
                    val freq = 10f

                    wavyPath.reset()
                    for (i in 0..steps) {
                        val t = i.toFloat() / steps.toFloat()
                        val deg = activeStartDeg + activeSweepDeg * t
                        val rad = (deg * PI / 180.0).toFloat()

                        val edgeRamp = if (p > 0.99f) {
                            val rIn = (t / 0.10f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                            val rOut = ((1f - t) / 0.10f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                            rIn * rOut
                        } else {
                            // Near closed circle let endpoints close smoothly, then taper into split caps
                            val rIn = (t / 0.10f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                            val rOut = ((1f - t) / 0.10f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                            val splitWeight = p
                            (1f - splitWeight) + splitWeight * (rIn * rOut)
                        }

                        val r = radius + ampPx * edgeRamp * cos(freq * rad - wavePhase)
                        val px = cx + r * cos(rad)
                        val py = cy + r * sin(rad)
                        if (i == 0) wavyPath.moveTo(px, py) else wavyPath.lineTo(px, py)
                    }

                    drawPath(
                        path = wavyPath,
                        color = activeRingColor,
                        style = Stroke(
                            width = strokePx,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }

            // Center icon directly inside ring (zero inner circular background plate)
            AnimatedContent(
                targetState = overlayVisible,
                transitionSpec = {
                    (fadeIn(tween(200)) + scaleIn(initialScale = 0.8f, animationSpec = tween(200))) togetherWith
                        (fadeOut(tween(160)) + scaleOut(targetScale = 0.8f, animationSpec = tween(160)))
                },
                label = "heroCenterIcon"
            ) { isOpen ->
                Canvas(modifier = Modifier.size(ringSize * 0.30f)) {
                    if (isOpen) {
                        // Bold M3 Expressive double pause bars matching Image 3 proportions
                        val barW = size.width * 0.21f
                        val barH = size.height * 0.64f
                        val gap = size.width * 0.16f
                        val corner = CornerRadius(barW * 0.22f, barW * 0.22f)
                        val totalW = barW * 2f + gap
                        val leftX = (size.width - totalW) * 0.5f
                        val topY = (size.height - barH) * 0.5f

                        drawRoundRect(
                            color = activeRingColor,
                            topLeft = Offset(leftX, topY),
                            size = Size(barW, barH),
                            cornerRadius = corner
                        )
                        drawRoundRect(
                            color = activeRingColor,
                            topLeft = Offset(leftX + barW + gap, topY),
                            size = Size(barW, barH),
                            cornerRadius = corner
                        )
                    } else {
                        // Bold rounded right-pointing Play triangle balanced optically in ring center
                        val h = size.height * 0.64f
                        val w = h * 0.86f
                        val cx = size.width * 0.5f + w * 0.08f
                        val cy = size.height * 0.5f
                        val p1 = Offset(cx - w * 0.5f, cy - h * 0.5f)
                        val p2 = Offset(cx + w * 0.5f, cy)
                        val p3 = Offset(cx - w * 0.5f, cy + h * 0.5f)

                        drawRoundedTriangle(
                            path = triPath,
                            a = p1,
                            b = p2,
                            c = p3,
                            cornerRadius = h * 0.14f,
                            color = activeRingColor
                        )
                    }
                }
            }
        }

        Text(
            text = when {
                overlayVisible -> if (clickingRunning) "Çalışıyor" else "Yüzen pencereyi kapat"
                needsSetup -> "Başlatma bekleniyor"
                else -> "Yüzen pencereyi etkinleştir"
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSurface
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundedTriangle(
    path: Path,
    a: Offset,
    b: Offset,
    c: Offset,
    cornerRadius: Float,
    color: Color
) {
    fun moveToward(from: Offset, to: Offset, dist: Float): Offset {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1e-3f)
        val frac = (dist / len).coerceIn(0f, 0.45f)
        return Offset(from.x + dx * frac, from.y + dy * frac)
    }

    val aIn = moveToward(a, c, cornerRadius)
    val aOut = moveToward(a, b, cornerRadius)
    val bIn = moveToward(b, a, cornerRadius)
    val bOut = moveToward(b, c, cornerRadius)
    val cIn = moveToward(c, b, cornerRadius)
    val cOut = moveToward(c, a, cornerRadius)

    path.reset()
    path.moveTo(aOut.x, aOut.y)
    path.lineTo(bIn.x, bIn.y)
    path.quadraticTo(b.x, b.y, bOut.x, bOut.y)
    path.lineTo(cIn.x, cIn.y)
    path.quadraticTo(c.x, c.y, cOut.x, cOut.y)
    path.lineTo(aIn.x, aIn.y)
    path.quadraticTo(a.x, a.y, aOut.x, aOut.y)
    path.close()
    drawPath(path = path, color = color)
}
