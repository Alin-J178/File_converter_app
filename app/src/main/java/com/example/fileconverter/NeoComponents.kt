package com.example.fileconverter

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- Neo-brutalist palette (kept for backward compat; prefer LocalAppColors) ----
val BrutBlack = Color(0xFF111111)
val BrutCream = Color(0xFFFFF3C4)
val BrutYellow = Color(0xFFFFD400)
val BrutPink = Color(0xFFFF5FA2)
val BrutGreen = Color(0xFF4ADE80)
val BrutPurple = Color(0xFF9B6CFF)
val BrutGrey = Color(0xFFBDBDBD)
val BrutBlue = Color(0xFF4D96FF)
val BrutOrange = Color(0xFFFF9F1C)
val BrutMuted = Color(0xFF7A7A7A)
val BrutBrown = Color(0xFF8B6914)

/** Shorthand for accessing the current theme colors. */
val C: AppColors
    @Composable get() = LocalAppColors.current

private val NeoBorder = 3.dp

/**
 * Neo-brutalist card: thick black border, solid black offset shadow, flat fill.
 */
@Composable
fun NeoCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = LocalAppColors.current.card,
    borderColor: Color = LocalAppColors.current.border,
    cornerRadius: Dp = 14.dp,
    shadowOffset: Dp = 6.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = shadowOffset, y = shadowOffset)
                .clip(shape)
                .background(C.border)
        )
        Box(
            modifier = Modifier
                .clip(shape)
                .background(backgroundColor)
                .border(NeoBorder, borderColor, shape)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    }
                ),
            content = content,
        )
    }
}

/**
 * Neo-brutalist button with solid background and hard shadow.
 */
@Composable
fun NeoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = C.accent,
    contentColor: Color = C.accentText,
    enabled: Boolean = true,
    height: Dp = 52.dp,
) {
    val shape = RoundedCornerShape(14.dp)
    val active = enabled
    Box(modifier = modifier.height(height)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = 5.dp, y = 6.dp)
                .clip(shape)
                .background(if (active) C.border else Color(0xFF9E9E9E))
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(if (active) backgroundColor else Color(0xFFE3E3E3))
                .border(NeoBorder, if (active) C.border else Color(0xFF9E9E9E), shape)
                .clickable(enabled = active, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = if (active) contentColor else Color(0xFF9E9E9E),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Neo-brutalist square icon button.
 */
@Composable
fun NeoIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = C.surface,
    iconTint: Color = C.onSurface,
    size: Dp = 48.dp,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = 4.dp, y = 5.dp)
                .clip(shape)
                .background(C.border)
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(backgroundColor)
                .border(NeoBorder, C.border, shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconTint,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/**
 * Neo-brutalist input-style row (white field with black border + shadow).
 */
@Composable
fun NeoField(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(modifier = modifier.height(height)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = 5.dp, y = 6.dp)
                .clip(shape)
                .background(C.border)
        )
        Row(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(C.inputBg)
                .border(NeoBorder, C.inputBorder, shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * Neo-brutalist slider: bordered track, colored fill, chunky round thumb.
 * [value] is 0..100.
 */
@Composable
fun NeoSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = C.accent,
) {
    val density = LocalDensity.current
    val trackHeight = 20.dp
    val thumbSize = 34.dp
    BoxWithConstraints(modifier = modifier.height(thumbSize + 14.dp)) {
        val trackWidthPx = with(density) { maxWidth.toPx() }
        val fraction = (value / 100f).coerceIn(0f, 1f)
        val trackShape = RoundedCornerShape(10.dp)

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(trackHeight)
                .clip(trackShape)
                .background(C.sliderTrack)
                .border(NeoBorder, C.border, trackShape)
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(fraction)
                .height(trackHeight)
                .clip(trackShape)
                .background(accent)
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(
                    x = (maxWidth - thumbSize) * fraction,
                    y = (trackHeight - thumbSize) / 2 + 5.dp,
                )
                .size(thumbSize)
                .clip(CircleShape)
                .background(C.border)
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(
                    x = (maxWidth - thumbSize) * fraction,
                    y = (trackHeight - thumbSize) / 2,
                )
                .size(thumbSize)
                .clip(CircleShape)
                .background(C.surface)
                .border(NeoBorder, C.border, CircleShape)
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun update(x: Float) {
                            onValueChange(((x / trackWidthPx).coerceIn(0f, 1f)) * 100f)
                        }
                        update(down.position.x)
                        drag(down.id) { change -> update(change.position.x) }
                    }
                }
        )
    }
}

/** Neo-brutalist section heading. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = C.onBackground,
        fontSize = 22.sp,
        fontWeight = FontWeight.Black,
    )
}

/** A single slice entry for [NeoPieChart]. */
data class PieSlice(
    val label: String,
    val value: Float,
    val color: Color,
    val sizeBytes: Long = 0L,
)

/**
 * Neo-brutalist donut pie chart with a collapsible legend dropdown.
 * The donut is centered; tapping the dropdown bar reveals a scrollable legend.
 */
@Composable
fun NeoPieChart(
    modifier: Modifier = Modifier,
    slices: List<PieSlice> = emptyList(),
    onSliceClick: ((String) -> Unit)? = null,
) {
    val chartBg = C.pieChart
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        NeoCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = chartBg,
            cornerRadius = 18.dp,
            shadowOffset = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (slices.isEmpty()) {
                    // Empty state placeholder
                    DonutChart(
                        slices = listOf(PieSlice("", 1f, C.muted.copy(alpha = 0.3f))),
                        modifier = Modifier.size(130.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No saved files yet",
                        color = C.muted,
                        fontSize = 14.sp,
                    )
                } else {
                    // Centered donut chart
                    DonutChart(
                        slices = slices,
                        modifier = Modifier.size(150.dp),
                    )

                    // Collapsible legend
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically(expandFrom = androidx.compose.ui.Alignment.Top),
                        exit = shrinkVertically(shrinkTowards = androidx.compose.ui.Alignment.Top),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp)
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Render legend items in a wrap layout
                            val rows = slices.chunked(2)
                            rows.forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    row.forEach { slice ->
                                        Row(
                                            modifier = Modifier
                                                .weight(1f)
                                                .then(
                                                    if (onSliceClick != null) {
                                                        Modifier.clickable { onSliceClick(slice.label) }
                                                    } else Modifier
                                                ),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(14.dp)
                                                    .clip(RoundedCornerShape(3.dp))
                                                    .background(slice.color)
                                                    .border(2.dp, C.border, RoundedCornerShape(3.dp)),
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text = "${slice.label} (${slice.value.toInt()})",
                                                    color = C.onCard,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                                if (slice.sizeBytes > 0) {
                                                    Text(
                                                        text = formatBytes(slice.sizeBytes),
                                                        color = C.muted,
                                                        fontSize = 11.sp,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    // Fill remaining space if odd number
                                    if (row.size < 2) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Dropdown toggle bar
        if (slices.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            val barShape = RoundedCornerShape(14.dp)
            Box(modifier = Modifier.fillMaxWidth().height(44.dp)) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .offset(x = 4.dp, y = 5.dp)
                        .clip(barShape)
                        .background(C.border),
                )
                Row(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(barShape)
                        .background(C.surface)
                        .border(3.dp, C.border, barShape)
                        .clickable { expanded = !expanded },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = C.onSurface,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

/** Canvas-based donut chart with grow-in animation. */
@Composable
private fun DonutChart(
    slices: List<PieSlice>,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 55.dp,
) {
    val total = slices.sumOf { it.value.toDouble() }.toFloat()
    if (total <= 0f) return

    val animProgress = remember { Animatable(0f) }
    val borderColor = C.border
    LaunchedEffect(slices) {
        animProgress.snapTo(0f)
        animProgress.animateTo(1f, animationSpec = tween(durationMillis = 700))
    }

    Canvas(modifier = modifier) {
        val outlineWidth = strokeWidth.toPx()
        val fillWidth = outlineWidth - 6f // inner fill is 3px thinner on each side
        val diameter = minOf(size.width, size.height) - outlineWidth
        val topLeft = Offset(
            (size.width - diameter) / 2f,
            (size.height - diameter) / 2f,
        )
        val arcSize = Size(diameter, diameter)
        val progress = animProgress.value

        var startAngle = -90f
        slices.forEach { slice ->
            val sweep = (slice.value / total) * 360f * progress
            val gap = 2f
            // Outline (drawn first, slightly wider)
            drawArc(
                color = borderColor,
                startAngle = startAngle,
                sweepAngle = sweep - gap,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = outlineWidth, cap = StrokeCap.Butt),
            )
            // Colored fill on top
            drawArc(
                color = slice.color,
                startAngle = startAngle,
                sweepAngle = sweep - gap,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = fillWidth, cap = StrokeCap.Butt),
            )
            startAngle += sweep + gap
        }
    }
}
