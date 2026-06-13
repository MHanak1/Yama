package net.mhanak.yama.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.kmpalette.rememberPainterDominantColorState
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.util.AlbumTintMode

/** Default transition duration when the seed colour changes (e.g. on track change). Short on purpose. */
const val DynamicColorAnimationMs = 500

/**
 * Forces a CPU-readable (software) decode so the palette extractor can sample the pixels. On Android
 * this calls `allowHardware(false)` (Coil's hardware bitmaps can't be read back); elsewhere it's a no-op
 * since those platforms never decode to hardware bitmaps. `allowHardware` is an Android-only extension,
 * so this has to be expect/actual rather than a direct call in common code.
 */
expect fun ImageRequest.Builder.readableBitmap(): ImageRequest.Builder

/**
 * Process-wide cache of extracted seed colours, keyed by a stable item id (album/artist/track uuid).
 *
 * Palette extraction means decoding the artwork and scanning its pixels — the ~half-second the first
 * paint costs. Keying the *result* by uuid means every later visit to the same album (the player bar,
 * the full player, the album detail screen, …) is an instant map hit instead of a re-decode. Only the
 * cheap seed [Color] is stored; the full [ColorScheme] is rebuilt per use so it can track light/dark.
 *
 * All access happens on the composition (main) thread — [DynamicColorTheme] reads it during
 * composition and writes it from a `LaunchedEffect` body — so a plain map needs no extra locking.
 */
object AlbumColorCache {
    private const val MaxEntries = 128
    private val cache = LinkedHashMap<String, Color>()

    fun get(key: String): Color? = cache[key]

    fun put(key: String, color: Color) {
        cache[key] = color
        if (cache.size > MaxEntries) cache.remove(cache.keys.first())
    }

    fun clear() = cache.clear()
}

/**
 * Wraps [content] in a [MaterialTheme] whose [ColorScheme] is derived from the dominant colour of the
 * image at [imageUrl], animating smoothly to the new scheme whenever the seed changes.
 *
 * Designed to be dropped over any subtree that should take on an item's colours — the full player now,
 * album/artist/genre detail screens later. Pass the item's uuid as [cacheKey] so extraction is shared
 * and cached across every surface that shows the same item (see [AlbumColorCache]).
 *
 * Flow: a small (128px) Coil request loads the artwork — reusing Coil's disk/memory cache, so no second
 * download — and [com.kmpalette] extracts its dominant colour off the main thread. [com.materialkolor]
 * turns that single seed into a complete Material 3 scheme (tracking the ambient light/dark mode), and
 * [animatedColorScheme] tweens every channel from the current scheme to the new one over
 * [animationSpec]. Until a seed resolves (or when [enabled] is false / [imageUrl] is null) the ambient
 * theme is used unchanged, so callers get a transparent wrapper in those cases.
 */
@Composable
fun DynamicColorTheme(
    imageUrl: String?,
    cacheKey: String?,
    enabled: Boolean = true,
    animationSpec: AnimationSpec<Float> = tween(DynamicColorAnimationMs),
    style: PaletteStyle = PaletteStyle.Fidelity,
    specVersion: ColorSpec.SpecVersion = ColorSpec.SpecVersion.SPEC_2021,
    content: @Composable () -> Unit,
) {
    // Deliberately no early return on [enabled] or [imageUrl]: [content] always stays in this single
    // MaterialTheme branch. Branching out would tear down and recreate the wrapped subtree whenever the
    // tint turns on/off or the artwork comes and goes — for the app-wide wrapper that means resetting the
    // NavHost to its start destination (e.g. bouncing you out of Settings the moment you change the
    // setting, or back to the library when playback starts/stops). Instead, when tinting is off or there
    // is no artwork the seed is simply null and the scheme falls back to the ambient one; gaining or
    // losing a seed later just animates between default and album colours.
    val seed = rememberSeedColor(if (enabled) imageUrl else null, if (enabled) cacheKey else null)
    val ambient = MaterialTheme.colorScheme
    val isDark = ambient.surface.luminance() < 0.5f
    // rememberDynamicColorScheme can't be called conditionally, so always build from a non-null seed
    // (falling back to the ambient primary) and only *use* it once a real seed has resolved.
    val seedScheme = rememberDynamicColorScheme(
        seedColor = seed ?: ambient.primary,
        isDark = isDark,
        style = style,
        specVersion = specVersion,
    )
    val target = if (seed != null) seedScheme else ambient

    // Anchor the animation on whatever the scheme is at mount: if the seed is already cached (known on
    // the first frame) the player just appears in the album's colours — no flash of the default on every
    // reopen. Only when the seed resolves *after* mount (cache miss) or the track changes does [target]
    // diverge from this anchor, and the transition eases.
    val initial = remember { target }
    MaterialTheme(colorScheme = animatedColorScheme(target, initial, animationSpec), content = content)
}

/**
 * Holds the artwork of the detail screen currently open (album/artist/genre/playlist), if any. A detail
 * view registers its item via [RegisterDetailTint] while it's in composition; the app reads this to
 * recolour the *whole* UI to that item and to paint the item's blurred artwork as the app background.
 *
 * Last writer wins (so across a detail→detail navigation the entering screen takes over and the leaving
 * one only clears itself if it's still the owner), mirroring [net.mhanak.yama.components.PrimaryContentFocus].
 */
@Stable
class DetailTint {
    var imageUrl: String? by mutableStateOf(null)
        private set
    var cacheKey: String? by mutableStateOf(null)
        private set
    private var owner: Any? = null

    fun register(owner: Any, imageUrl: String?, cacheKey: String?) {
        this.owner = owner
        this.imageUrl = imageUrl
        this.cacheKey = cacheKey
    }

    fun unregister(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            imageUrl = null
            cacheKey = null
        }
    }
}

val LocalDetailTint = compositionLocalOf<DetailTint?> { null }

/**
 * Registers [imageUrl]/[cacheKey] as the active detail screen's artwork for as long as this call is in
 * composition, so the app-wide theme ([AppColorTheme]) recolours to it and the shell paints it as the
 * background. No-op when no [DetailTint] is provided (e.g. an overlay drawn outside the NavHost).
 */
@Composable
fun RegisterDetailTint(imageUrl: String?, cacheKey: String?) {
    val holder = LocalDetailTint.current ?: return
    val owner = remember { Any() }
    DisposableEffect(holder, imageUrl, cacheKey) {
        holder.register(owner, imageUrl, cacheKey)
        onDispose { holder.unregister(owner) }
    }
}

/**
 * App-wide tint wrapper. Seeds the whole UI's [ColorScheme] from, in order of precedence:
 *
 * 1. the **open detail screen's** item (gated on [AlbumTintMode.tintsDetails]) — so opening an album/
 *    artist/genre/playlist recolours the entire app to it, overriding the player; then
 * 2. the **currently playing album** (gated on [AlbumTintMode.tintsEverything], the "All UI" level);
 * 3. otherwise no seed → the default theme.
 *
 * The player's [net.mhanak.yama.media.playback.PlayerStatus] ticks frequently (position updates), so
 * the collection is isolated here: this composable re-runs on every tick, but [DynamicColorTheme] is
 * skipped whenever the resolved seed is unchanged, so [content] (the whole app) is not recomposed
 * between track changes. It always delegates to a single [DynamicColorTheme] rather than branching, so
 * gaining/losing a seed just animates between default and album colours without tearing down the app.
 */
@Composable
fun AppColorTheme(tintMode: AlbumTintMode, content: @Composable () -> Unit) {
    val detail = LocalDetailTint.current
    val player = LocalAppContainer.current.playback.active
    val status by player.status.collectAsState()
    val track = status.current

    val detailUrl = detail?.imageUrl
    val imageUrl: String?
    val cacheKey: String?
    when {
        detailUrl != null && tintMode.tintsDetails -> { imageUrl = detailUrl; cacheKey = detail?.cacheKey }
        tintMode.tintsEverything -> { imageUrl = track?.imageUrl; cacheKey = track?.albumId ?: track?.id }
        else -> { imageUrl = null; cacheKey = null }
    }
    DynamicColorTheme(imageUrl = imageUrl, cacheKey = cacheKey, content = content)
}

/**
 * Resolves the dominant seed colour for [imageUrl]: a cached value for [cacheKey] is used instantly,
 * otherwise it's extracted once the artwork has loaded, then cached.
 *
 * Crucially, while the *next* track's artwork is still loading this keeps returning the *previous*
 * resolved colour rather than null. That way the theme animates straight from the old album's colours to
 * the new ones (e.g. when skipping tracks) instead of dipping through the default scheme in between. Only
 * a genuine "no artwork" ([imageUrl] == null, e.g. playback stopped) clears it back to null.
 */
@Composable
private fun rememberSeedColor(imageUrl: String?, cacheKey: String?): Color? {
    val context = LocalPlatformContext.current
    // The last colour we resolved for any track, held across track changes (see KDoc).
    var lastResolved by remember { mutableStateOf<Color?>(null) }

    // A tiny decode is plenty for palette extraction and far cheaper than the full-res artwork; the
    // fixed size also makes Coil load the request eagerly (no on-draw measure needed) since this painter
    // is never drawn — only sampled for its colours.
    //
    // [readableBitmap] is the crucial bit: Coil decodes to a GPU/hardware bitmap by default on Android,
    // and a hardware bitmap's pixels can't be read back — the palette scan silently sees nothing and
    // falls back to its default colour (so the scheme never changes). Forcing a software (readable)
    // bitmap is what makes extraction actually work. (No-op on desktop, which has no hardware bitmaps.)
    val request = remember(imageUrl) {
        ImageRequest.Builder(context).data(imageUrl).size(128, 128).readableBitmap().build()
    }
    val painter = rememberAsyncImagePainter(request)
    val dominant = rememberPainterDominantColorState()
    val painterState by painter.state.collectAsState()

    // A cache hit is honoured synchronously below (no frame delay); this only keeps [lastResolved] in
    // step for the next track and performs extraction on a miss.
    val cached = cacheKey?.let { AlbumColorCache.get(it) }
    LaunchedEffect(cacheKey, imageUrl, painterState) {
        when {
            imageUrl == null -> lastResolved = null
            cached != null -> lastResolved = cached
            painterState is AsyncImagePainter.State.Success -> {
                dominant.updateFrom(painter)
                val extracted = dominant.color
                lastResolved = extracted
                cacheKey?.let { AlbumColorCache.put(it, extracted) }
            }
        }
    }

    return when {
        imageUrl == null -> null      // no artwork (stopped / disabled) → fall back to the default theme
        cached != null -> cached      // known colour → use immediately
        else -> lastResolved          // new artwork still loading → hold the previous colour
    }
}

/**
 * Tweens from the previously shown [ColorScheme] to [target] whenever [target] changes, driving a single
 * 0→1 float and lerping every channel each frame (cheap, and avoids ~35 separate colour animations).
 * Starts settled on [initial] (the scheme at mount) so a [target] that only diverges later eases in
 * rather than snapping, while a [target] already equal to [initial] at mount shows with no animation; a
 * mid-flight change re-bases the start at the currently displayed scheme so transitions chain smoothly.
 */
@Composable
private fun animatedColorScheme(target: ColorScheme, initial: ColorScheme, spec: AnimationSpec<Float>): ColorScheme {
    val progress = remember { Animatable(1f) }
    // Start settled on [initial] (the default scheme), not [target] — so when [target] first differs
    // (the album colours resolving) it eases over instead of being there already.
    var start by remember { mutableStateOf(initial) }
    var end by remember { mutableStateOf(initial) }

    LaunchedEffect(target) {
        if (target === end) return@LaunchedEffect
        start = lerpScheme(start, end, progress.value)
        end = target
        progress.snapTo(0f)
        progress.animateTo(1f, spec)
    }

    return lerpScheme(start, end, progress.value)
}

/** Linearly interpolate every colour of two schemes. */
private fun lerpScheme(a: ColorScheme, b: ColorScheme, t: Float): ColorScheme = a.copy(
    primary = lerp(a.primary, b.primary, t),
    onPrimary = lerp(a.onPrimary, b.onPrimary, t),
    primaryContainer = lerp(a.primaryContainer, b.primaryContainer, t),
    onPrimaryContainer = lerp(a.onPrimaryContainer, b.onPrimaryContainer, t),
    inversePrimary = lerp(a.inversePrimary, b.inversePrimary, t),
    secondary = lerp(a.secondary, b.secondary, t),
    onSecondary = lerp(a.onSecondary, b.onSecondary, t),
    secondaryContainer = lerp(a.secondaryContainer, b.secondaryContainer, t),
    onSecondaryContainer = lerp(a.onSecondaryContainer, b.onSecondaryContainer, t),
    tertiary = lerp(a.tertiary, b.tertiary, t),
    onTertiary = lerp(a.onTertiary, b.onTertiary, t),
    tertiaryContainer = lerp(a.tertiaryContainer, b.tertiaryContainer, t),
    onTertiaryContainer = lerp(a.onTertiaryContainer, b.onTertiaryContainer, t),
    background = lerp(a.background, b.background, t),
    onBackground = lerp(a.onBackground, b.onBackground, t),
    surface = lerp(a.surface, b.surface, t),
    onSurface = lerp(a.onSurface, b.onSurface, t),
    surfaceVariant = lerp(a.surfaceVariant, b.surfaceVariant, t),
    onSurfaceVariant = lerp(a.onSurfaceVariant, b.onSurfaceVariant, t),
    surfaceTint = lerp(a.surfaceTint, b.surfaceTint, t),
    inverseSurface = lerp(a.inverseSurface, b.inverseSurface, t),
    inverseOnSurface = lerp(a.inverseOnSurface, b.inverseOnSurface, t),
    error = lerp(a.error, b.error, t),
    onError = lerp(a.onError, b.onError, t),
    errorContainer = lerp(a.errorContainer, b.errorContainer, t),
    onErrorContainer = lerp(a.onErrorContainer, b.onErrorContainer, t),
    outline = lerp(a.outline, b.outline, t),
    outlineVariant = lerp(a.outlineVariant, b.outlineVariant, t),
    scrim = lerp(a.scrim, b.scrim, t),
    surfaceBright = lerp(a.surfaceBright, b.surfaceBright, t),
    surfaceDim = lerp(a.surfaceDim, b.surfaceDim, t),
    surfaceContainer = lerp(a.surfaceContainer, b.surfaceContainer, t),
    surfaceContainerHigh = lerp(a.surfaceContainerHigh, b.surfaceContainerHigh, t),
    surfaceContainerHighest = lerp(a.surfaceContainerHighest, b.surfaceContainerHighest, t),
    surfaceContainerLow = lerp(a.surfaceContainerLow, b.surfaceContainerLow, t),
    surfaceContainerLowest = lerp(a.surfaceContainerLowest, b.surfaceContainerLowest, t),
)
