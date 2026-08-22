package com.example.fileconverter

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- Neo-brutalist palette ----
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

private val NeoBorder = 3.dp

/**
 * Neo-brutalist card: thick black border, solid black offset shadow, flat fill.
 */
@Composable
fun NeoCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.White,
    borderColor: Color = BrutBlack,
    cornerRadius: Dp = 14.dp,
    shadowOffset: Dp = 6.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    // The content box is a regular child so it sizes the card; the shadow box
    // matches that size and is offset to poke out as the hard drop shadow.
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = shadowOffset, y = shadowOffset)
                .clip(shape)
                .background(borderColor)
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
    backgroundColor: Color = BrutYellow,
    contentColor: Color = BrutBlack,
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
                .background(if (active) BrutBlack else Color(0xFF9E9E9E))
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(if (active) backgroundColor else Color(0xFFE3E3E3))
                .border(NeoBorder, if (active) BrutBlack else Color(0xFF9E9E9E), shape)
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
    backgroundColor: Color = Color.White,
    iconTint: Color = BrutBlack,
    size: Dp = 48.dp,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = 4.dp, y = 5.dp)
                .clip(shape)
                .background(BrutBlack)
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(backgroundColor)
                .border(NeoBorder, BrutBlack, shape)
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
                .background(BrutBlack)
        )
        Row(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(Color.White)
                .border(NeoBorder, BrutBlack, shape)
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
    accent: Color = BrutYellow,
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
                .background(Color.White)
                .border(NeoBorder, BrutBlack, trackShape)
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
                .background(BrutBlack)
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
                .background(Color.White)
                .border(NeoBorder, BrutBlack, CircleShape)
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
        color = BrutBlack,
        fontSize = 22.sp,
        fontWeight = FontWeight.Black,
    )
}
