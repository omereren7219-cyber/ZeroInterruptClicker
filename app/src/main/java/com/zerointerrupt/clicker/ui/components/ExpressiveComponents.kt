package com.zerointerrupt.clicker.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Immutable
data class ExpressiveOption<T>(
    val value: T,
    val label: String,
    @DrawableRes val iconRes: Int? = null
)

/**
 * Official Material 3 Expressive Connected Button Group (per m3.material.io/components/button-groups):
 * - No outer wrapper slab container.
 * - Adjacent unselected items share tight 8.dp inner corners with 50% (24.dp) outer end caps on soft secondaryContainer fill.
 * - Selected item morphs all 4 corners smoothly into a full CircleShape pill with primary fill and onPrimary content,
 *   while adjacent items stay aligned without jittering or clipping labels.
 */
@Composable
fun <T> ExpressiveConnectedGroup(
    options: List<ExpressiveOption<T>>,
    selectedValue: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option.value == selectedValue
            val isFirst = index == 0
            val isLast = index == options.lastIndex

            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()

            val pressScale by animateFloatAsState(
                targetValue = if (isPressed) 0.96f else 1f,
                animationSpec = spring(dampingRatio = 0.72f, stiffness = 560f),
                label = "connPress_$index"
            )

            val innerTarget = when {
                isSelected -> 24.dp
                isPressed -> 14.dp
                else -> 8.dp
            }
            val innerCorner by animateDpAsState(
                targetValue = innerTarget,
                animationSpec = spring(
                    dampingRatio = 0.72f,
                    stiffness = 440f
                ),
                label = "connCorner_$index"
            )

            val shape = RoundedCornerShape(
                topStart = if (isFirst || isSelected) 24.dp else innerCorner,
                bottomStart = if (isFirst || isSelected) 24.dp else innerCorner,
                topEnd = if (isLast || isSelected) 24.dp else innerCorner,
                bottomEnd = if (isLast || isSelected) 24.dp else innerCorner
            )

            val bgColor by animateColorAsState(
                targetValue = if (isSelected) cs.primary else cs.secondaryContainer,
                animationSpec = tween(durationMillis = 180),
                label = "connBg_$index"
            )

            val fgColor by animateColorAsState(
                targetValue = if (isSelected) cs.onPrimary else cs.onSecondaryContainer,
                animationSpec = tween(durationMillis = 180),
                label = "connFg_$index"
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(height)
                    .graphicsLayer {
                        scaleX = pressScale
                        scaleY = pressScale
                    }
                    .clip(shape)
                    .background(bgColor)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(option.value)
                    }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (option.iconRes != null) {
                        Icon(
                            painter = painterResource(option.iconRes),
                            contentDescription = null,
                            tint = fgColor,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = option.label,
                        color = fgColor,
                        fontSize = 12.5.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Material 3 Expressive Determinate Wavy Linear Progress Indicator:
 * - Animated sinusoidal active stroke (zero-allocation cached Path)
 * - Clean gap between active wave and flat inactive track
 * - Trailing M3 stop indicator dot inside the right track cap
 */
@Composable
fun M3WavyLinearProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    strokeWidth: Dp = 4.5.dp,
    waveAmplitude: Dp = 3.dp,
    wavelength: Dp = 34.dp,
    gapSize: Dp = 6.dp
) {
    val transition = rememberInfiniteTransition(label = "wavyLinearPhase")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1350, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhaseRad"
    )

    val wavePath = remember { Path() }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp)
    ) {
        val w = size.width
        val h = size.height
        val strokePx = strokeWidth.toPx()
        val halfStroke = strokePx * 0.5f
        val cy = h * 0.5f
        val maxAmpPx = waveAmplitude.toPx().coerceAtMost((h - strokePx) * 0.48f)
        val lambdaPx = wavelength.toPx().coerceAtLeast(12f)
        val gapPx = gapSize.toPx()
        val k = (2.0 * PI / lambdaPx).toFloat()

        val p = progress().coerceIn(0f, 1f)
        val usableW = (w - strokePx).coerceAtLeast(1f)
        val splitX = halfStroke + usableW * p

        val activeEnd = (splitX - gapPx * 0.5f).coerceIn(halfStroke, w - halfStroke)
        val inactiveStart = (splitX + gapPx * 0.5f).coerceIn(halfStroke, w - halfStroke)
        val endX = w - halfStroke

        // Right flat inactive track + trailing stop dot
        if (endX - inactiveStart > 1.5f) {
            drawLine(
                color = trackColor,
                start = Offset(inactiveStart, cy),
                end = Offset(endX, cy),
                strokeWidth = strokePx,
                cap = StrokeCap.Round
            )
        }
        if (endX - inactiveStart > strokePx * 0.9f) {
            drawCircle(
                color = color,
                radius = (strokePx * 0.46f).coerceAtLeast(2f),
                center = Offset(endX, cy)
            )
        }

        // Left sinusoidal wavy active track
        val activeLen = activeEnd - halfStroke
        if (activeLen > 0.5f) {
            // Smooth amplitude ramp near 0% and 100% per M3 wavy spec
            val edgeStart = (p / 0.06f).coerceIn(0f, 1f)
            val edgeEnd = ((1f - p) / 0.04f).coerceIn(0f, 1f)
            val amp = maxAmpPx * edgeStart * edgeEnd

            wavePath.reset()
            val startY = cy + amp * sin(halfStroke * k - phase)
            wavePath.moveTo(halfStroke, startY)

            val stepPx = 2.dp.toPx().coerceAtLeast(2f)
            var x = halfStroke + stepPx
            while (x < activeEnd) {
                val y = cy + amp * sin(x * k - phase)
                wavePath.lineTo(x, y)
                x += stepPx
            }
            wavePath.lineTo(activeEnd, cy + amp * sin(activeEnd * k - phase))

            drawPath(
                path = wavePath,
                color = color,
                style = Stroke(
                    width = strokePx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}

/**
 * Material 3 Expressive Capsule Slider with high-contrast split track, trailing stop dot,
 * tall capsule thumb, and floating tooltip Popup while dragging.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressiveSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    floatingLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val cs = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isDragged by interactionSource.collectIsDraggedAsState()
    val isInteracting = (isPressed || isDragged) && enabled

    val thumbWidth by animateDpAsState(
        targetValue = if (isInteracting) 6.dp else 4.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 480f),
        label = "sliderThumbW"
    )
    val thumbHeight by animateDpAsState(
        targetValue = if (isInteracting) 32.dp else 26.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 480f),
        label = "sliderThumbH"
    )

    val activeColor = if (enabled) cs.primary else cs.onSurface.copy(alpha = 0.28f)
    val inactiveColor = if (enabled) cs.secondaryContainer else cs.onSurface.copy(alpha = 0.12f)
    val stopDotColor = if (enabled) cs.primary.copy(alpha = 0.75f) else cs.onSurface.copy(alpha = 0.22f)

    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth(),
        thumb = {
            Box(contentAlignment = Alignment.Center) {
                if (isInteracting) {
                    Popup(
                        alignment = Alignment.TopCenter,
                        offset = IntOffset(0, -105)
                    ) {
                        AnimatedVisibility(
                            visible = isInteracting,
                            enter = fadeIn(tween(90)) + scaleIn(initialScale = 0.8f),
                            exit = fadeOut(tween(90)) + scaleOut(targetScale = 0.8f)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = cs.inverseSurface,
                                contentColor = cs.inverseOnSurface,
                                shadowElevation = 4.dp
                            ) {
                                Text(
                                    text = floatingLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .width(thumbWidth)
                        .height(thumbHeight)
                        .clip(CircleShape)
                        .background(activeColor)
                )
            }
        },
        track = { sliderState ->
            val rangeSpan = (sliderState.valueRange.endInclusive - sliderState.valueRange.start)
                .takeIf { it > 0f } ?: 1f
            val fraction = ((sliderState.value - sliderState.valueRange.start) / rangeSpan).coerceIn(0f, 1f)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
            ) {
                val h = size.height
                val w = size.width
                val gapPx = 8.dp.toPx()
                val splitX = (w * fraction).coerceIn(0f, w)
                val radius = CornerRadius(h * 0.5f, h * 0.5f)

                val activeEnd = (splitX - gapPx).coerceAtLeast(0f)
                if (activeEnd >= h * 0.9f) {
                    drawRoundRect(
                        color = activeColor,
                        topLeft = Offset(0f, 0f),
                        size = Size(activeEnd, h),
                        cornerRadius = radius
                    )
                }

                val inactiveStart = (splitX + gapPx).coerceAtMost(w)
                val inactiveWidth = (w - inactiveStart).coerceAtLeast(0f)
                if (inactiveWidth >= h * 0.9f) {
                    drawRoundRect(
                        color = inactiveColor,
                        topLeft = Offset(inactiveStart, 0f),
                        size = Size(inactiveWidth, h),
                        cornerRadius = radius
                    )
                    if (inactiveWidth > h * 0.6f) {
                        drawCircle(
                            color = stopDotColor,
                            radius = 2.2.dp.toPx(),
                            center = Offset(w - h * 0.5f, h * 0.5f)
                        )
                    }
                }
            }
        }
    )
}

/**
 * Clean borderless Material 3 tonal surface card following official M3 tonal hierarchy.
 */
@Composable
fun ExpressiveCard(
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    cornerRadius: Dp = 24.dp,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(cornerRadius),
        color = containerColor ?: MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        border = border,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

@Immutable
data class OverflowMenuAction(
    val label: String,
    val badge: String? = null,
    val badgeHighlight: Boolean = false,
    val enabled: Boolean = true,
    val isDestructive: Boolean = false,
    val onClick: () -> Unit
)

/**
 * Clean Material 3 single-line top bar matching Google Clock style (Image 4):
 * large page title on the left, optional trailing action icon, and optional 3-dot overflow menu.
 */
@Composable
fun PageTopHeader(
    title: String,
    modifier: Modifier = Modifier,
    menuActions: List<OverflowMenuAction> = emptyList(),
    showMenuAlertDot: Boolean = false,
    trailingAction: (@Composable () -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (trailingAction != null) {
                trailingAction()
            }
            if (menuActions.isNotEmpty()) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "Daha fazla",
                                tint = cs.onSurfaceVariant
                            )
                            if (showMenuAlertDot) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(cs.error)
                                )
                            }
                        }
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = cs.surfaceContainerHigh
                    ) {
                        menuActions.forEach { item ->
                            DropdownMenuItem(
                                enabled = item.enabled,
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = item.label,
                                            color = if (item.isDestructive && item.enabled) {
                                                cs.error
                                            } else {
                                                Color.Unspecified
                                            }
                                        )
                                        if (!item.badge.isNullOrBlank()) {
                                            Surface(
                                                shape = CircleShape,
                                                color = if (item.badgeHighlight) {
                                                    cs.errorContainer
                                                } else {
                                                    cs.secondaryContainer
                                                }
                                            ) {
                                                Text(
                                                    text = item.badge,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (item.badgeHighlight) {
                                                        cs.onErrorContainer
                                                    } else {
                                                        cs.onSecondaryContainer
                                                    },
                                                    modifier = Modifier.padding(
                                                        horizontal = 7.dp,
                                                        vertical = 2.dp
                                                    )
                                                )
                                            }
                                        }
                                    }
                                },
                                onClick = {
                                    menuExpanded = false
                                    item.onClick()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
