@file:OptIn(ExperimentalHazeMaterialsApi::class)

package net.mhanak.yama.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

val LocalHazeState = compositionLocalOf<HazeState?> { null }
val LocalUiOpacity = compositionLocalOf { 0.75f }

private const val GLASS_BORDER_ALPHA = 0.35f

fun Modifier.glassEffect(
    containerColor: Color = Color.Unspecified,
    shape: Shape = RectangleShape,
): Modifier = composed {
    val hazeState = LocalHazeState.current
    val uiOpacity = LocalUiOpacity.current
    val color = if (containerColor != Color.Unspecified) containerColor else MaterialTheme.colorScheme.surface
    if (hazeState == null) {
        // Blur disabled: solid opaque background so components look normal rather than transparent.
        // Always clip so rounded shapes are respected even without blur.
        return@composed Modifier.clip(shape).background(color)
    }
    val hazeModifier = Modifier.hazeEffect(state = hazeState, style = HazeStyle(
        backgroundColor = color,
        tint = HazeTint(color = color.copy(alpha = uiOpacity)),
        blurRadius = 20.dp,
        noiseFactor = 0.05f,
    ))
    // Always clip before hazeEffect so blur rendering is constrained to the composable's bounds
    // and doesn't bleed into adjacent components (e.g. segmented button row bleeding into app bar).
    Modifier.clip(shape).then(hazeModifier)
}

fun Modifier.glassSource(zIndex: Float = 0f): Modifier = composed {
    val hazeState = LocalHazeState.current ?: return@composed Modifier
    Modifier.hazeSource(hazeState, zIndex = zIndex)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    windowInsets: WindowInsets? = null,
) {
    val containerColor = MaterialTheme.colorScheme.surface
    TopAppBar(
        title = title,
        modifier = modifier.glassEffect(containerColor),
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        scrollBehavior = scrollBehavior,
        windowInsets = windowInsets ?: TopAppBarDefaults.windowInsets,
    )
}

@Composable
fun GlassModalDrawerSheet(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val shape = DrawerDefaults.shape
    ModalDrawerSheet(
        // Also a hazeSource at zIndex=1 so nested glass cards only blur the background (zIndex<1),
        // not each other — same pattern as AlbumsView in MainScreen.
        modifier = modifier.glassEffect(containerColor, shape).glassSource(zIndex = 1f),
        drawerContainerColor = Color.Transparent,
        drawerShape = shape,
        content = content,
    )
}

@Composable
fun GlassElevatedCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val shape = CardDefaults.shape
    Card(
        onClick = onClick,
        modifier = modifier.glassEffect(containerColor, shape),
        enabled = enabled,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = GLASS_BORDER_ALPHA)),
        content = content,
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val shape = CardDefaults.shape
    Card(
        modifier = modifier.glassEffect(containerColor, shape),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp,
            hoveredElevation = 0.dp,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = GLASS_BORDER_ALPHA)),
        content = content,
    )
}

@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val containerColor = MaterialTheme.colorScheme.primary
    val shape = ButtonDefaults.shape
    // Unspecified prevents M3's minimumInteractiveComponentSize from inflating the layout node to
    // 48dp, which would make the glass taller than the visible button border.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Button(
            onClick = onClick,
            modifier = modifier.glassEffect(containerColor, shape),
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            content = content,
        )
    }
}

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val containerColor = Color.Transparent
    val shape = ButtonDefaults.shape
    // Unspecified prevents M3's minimumInteractiveComponentSize from inflating the layout node to
    // 48dp, which would make the glass taller than the visible button border.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        IconButton(
            onClick = onClick,
            modifier = modifier.glassEffect(containerColor, shape),
            enabled = enabled,
            shape = shape,
            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent),
            content = content,
        )
    }
}

@Composable
fun GlassFilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val containerColor = MaterialTheme.colorScheme.primary
    val shape = ButtonDefaults.shape
    // Unspecified prevents M3's minimumInteractiveComponentSize from inflating the layout node to
    // 48dp, which would make the glass taller than the visible button border.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        FilledIconButton(
            onClick = onClick,
            modifier = modifier.glassEffect(containerColor, shape),
            enabled = enabled,
            shape = shape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Transparent),
            content = content,
        )
    }
}

@Composable
fun GlassOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val containerColor = MaterialTheme.colorScheme.surface
    val shape = ButtonDefaults.shape
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.glassEffect(containerColor, shape),
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = GLASS_BORDER_ALPHA)),
            content = content,
        )
    }
}
