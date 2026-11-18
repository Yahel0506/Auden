/*
 * The glass rendering itself is Kyant0/backdrop (Apache-2.0), vendored at
 * [com.music.bitchord.ui.components.backdrop] — see that package for the
 * upstream attribution. This file is the integration glue, adapted from
 * EchoMusicApp/Echo-Music's GlassEffectConfig/Modifier.liquidGlass
 * (GPL-3.0), cut down from Echo's full per-component/vibrancy-slider config
 * to the single on/off switch BitChord exposes in Settings. It is used by the
 * floating nav bar and, while enabled, the app-wide floating top controls.
 */
package com.music.bitchord.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.backdrop.Backdrop
import com.music.bitchord.ui.components.backdrop.backdrops.LayerBackdrop
import com.music.bitchord.ui.components.backdrop.drawBackdrop
import com.music.bitchord.ui.components.backdrop.effects.blur
import com.music.bitchord.ui.components.backdrop.effects.colorControls
import com.music.bitchord.ui.components.backdrop.effects.lens
import com.music.bitchord.ui.components.backdrop.highlight.Highlight
import com.music.bitchord.ui.components.backdrop.highlight.HighlightElement
import com.music.bitchord.ui.components.backdrop.internal.ShapeProvider
import com.music.bitchord.ui.components.backdrop.shadow.Shadow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

/** The backdrop content (app UI) that a liquid glass surface samples from. */
val LocalAppBackdrop = staticCompositionLocalOf<Backdrop> { error("No AppBackdrop provided") }

/**
 * Where a [liquidGlass] surface composed under it also records the glass it
 * draws — the sampled, blurred and tinted backdrop, without its rim or shadow —
 * so a lens above it can refract that surface rather than the page behind it.
 * The tab bar's travelling selection pill is the one reader; see
 * [com.music.bitchord.ui.components.floatingtabbar.GlassSelectionPill].
 */
internal val LocalGlassExport = compositionLocalOf<LayerBackdrop?> { null }

/**
 * The backdrop blur pipeline requires [android.graphics.RenderEffect] on a
 * [android.graphics.RenderNode], available from Android 12 (API 31).
 */
fun isGlassSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.S

/** Apple-matched defaults (Echo's GlassEffectConfig()), fixed rather than user sliders. */
private const val VIBRANCY = 1f
private const val BLUR_RADIUS_DP = 8f
private const val LENS_HEIGHT = 0.5f
private const val LENS_AMOUNT = 0.3f
private const val LENS_MAX_DP = 48f
private const val SURFACE_OPACITY = 0.4f

/** The slider position (0..1) the glass's blur and refraction are as designed at: 60%. */
private const val GLASS_DESIGN_POINT = 0.6f

private fun slider(opacity: Float) = (opacity / 1.5f).coerceIn(0f, 1f)

/**
 * The blur against the designed one, for a glass set to [opacity] (the stored value, 1.5 at the slider's top). The
 * more see-through the glass, the more it blurs, so what shows through stays legible; the more opaque, the less it
 * needs. Bounded both ways: at most 2.6 times the designed blur, at least half.
 */
internal fun glassBlurScale(opacity: Float): Float {
    val p = slider(opacity)
    return if (p <= GLASS_DESIGN_POINT) 1f + 1.6f * (GLASS_DESIGN_POINT - p) / GLASS_DESIGN_POINT
    else 1f - 0.5f * (p - GLASS_DESIGN_POINT) / (1f - GLASS_DESIGN_POINT)
}

/**
 * How much of the glass's colour boost is kept, for a glass set to [opacity]: the more see-through it is, the more
 * muted the colours it shows. Full at the designed point, 40% of it at the slider's bottom, a little more above.
 */
internal fun glassSaturationScale(opacity: Float): Float {
    val p = slider(opacity)
    return if (p <= GLASS_DESIGN_POINT) 0.4f + 0.6f * p / GLASS_DESIGN_POINT
    else 1f + 0.1f * (p - GLASS_DESIGN_POINT) / (1f - GLASS_DESIGN_POINT)
}

/**
 * The refraction against the designed one, bounded tighter than the blur: it costs more to draw and is the part of
 * the glass that can look wrong when strong. At most 1.2 times the designed, at least 0.4.
 */
internal fun glassRefractionScale(opacity: Float): Float {
    val p = slider(opacity)
    return if (p <= GLASS_DESIGN_POINT) 1f + 0.2f * (GLASS_DESIGN_POINT - p) / GLASS_DESIGN_POINT
    else 1f - 0.6f * (p - GLASS_DESIGN_POINT) / (1f - GLASS_DESIGN_POINT)
}

/** How much of the page's own colour the glass's edge line takes (see drawBackdrop's edgeTint). */
private const val EDGE_TINT = 0.1f

/** How much of the film's opacity is left along the middle line, against its top and bottom edges. */
private const val GLASS_MIDDLE_SHARE = 0.8f
private const val GLASS_MIDDLE_SHARE_GAIN = 0.11f

/**
 * Resolution fraction the glass surface records and processes its backdrop at.
 *
 * A third, which is what Echo's `glassResolutionScale` comes to at this blur
 * radius. It was briefly raised to a half while the sharp-text bug was being
 * chased — wrongly, as it turned out: the cause was a transparent backdrop, not
 * the resample (see MainActivity's `paintBackdrop`). A third is nine times fewer
 * pixels than full resolution through the colour matrix, the blur and the lens
 * shader, on as many as six surfaces at once in the middle of a fold, and the
 * blur is what hides the upscale.
 */
private const val GLASS_RESOLUTION_SCALE = 0.33f

/**
 * The hairline along a bar's edge, and what stands in for the glass rim
 * wherever the glass itself is not drawn.
 *
 * A surface filled with the theme's own `surface` colour has no edge of its own
 * against a dark page — it is the same near-black the page is. The glass gets
 * its edge from [Highlight], and this is that edge for everything that does not.
 */
internal val GLASS_EDGE_WIDTH = 0.4.dp
internal val GLASS_EDGE_COLOR = Color.White.copy(alpha = 0.055f)

/**
 * Icon and label colour for content sitting on a glass surface.
 *
 * Glass shows whatever is behind it rather than the theme's surface colour, so
 * the usual onSurface greys have nothing dependable to sit against. Pure black
 * or white off the theme's luminance is the only tint that holds against
 * arbitrary artwork, and it is what Echo's own glass nav bar uses.
 */
@Composable
fun glassContentColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color.Black else Color.White

/**
 * Selected-tab indicator colour for a glass surface: white in both themes.
 *
 * In light theme that is the inverse of [glassContentColor], a bright lift
 * behind a dark glyph. In dark theme it is a faint white lift over the bar's
 * dark glass rather than a black scrim pressed into it — the selected tab is
 * raised out of the bar, as on iOS, not sunk.
 */
@Composable
fun glassIndicatorColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color.Black.copy(alpha = 0.08f)
    } else {
        Color(0xFF0E0E10).copy(alpha = DARK_INDICATOR_OPACITY)
    }

/** The selected tab's pill, as Apple Music's: a darker well pressed into the milky glass. */
private const val DARK_INDICATOR_OPACITY = 0.42f

/**
 * The nav bar's glass, as Apple Music's: milkier and a touch more opaque than the app's other glass — a
 * whitish film over the page rather than a dark one — so the darker selection reads in it.
 */
@Composable
fun navGlassTint(): Color {
    val opacity by AppSettings.glassOpacity.collectAsStateWithLifecycle()
    val base = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color(0xFFFFFFFF).copy(alpha = 0.66f)
    } else {
        Color(0xFF454549).copy(alpha = 0.68f)
    }
    return base.copy(alpha = (base.alpha * opacity).coerceIn(0f, 0.97f))
}

/** How much denser the film of an alert or a menu is than the nav bar's. */
private const val ALERT_FILM_GAIN = 1.28f

/** How much more opaque the nav bar's glass grows from its centre out to its edges. */
const val NAV_GLASS_EDGE = 0.3f

/**
 * The nav bar's rim, as Apple's: a fine line, but bright, and lit from one side — light caught in the glass's
 * edge, strongest along the top and round the curved ends — rather than an even grey outline.
 */
val NAV_GLASS_RIM = Highlight(
    width = 0.45.dp,
    blurRadius = 0.2.dp,
    style = com.music.bitchord.ui.components.backdrop.highlight.HighlightStyle.Default(
        color = Color.White.copy(alpha = 0.18f),
        // Lit from above: the long top and bottom edges carry it, the round ends and corners much less.
        angle = 90f,
        falloff = 2.8f,
    ),
    // And where that light does not reach, the round ends, a fine black line of the same strength and reach:
    // the glass's edge against a bright page, where a white one is lost.
    sides = Highlight(
        width = 0.45.dp,
        blurRadius = 0.2.dp,
        style = com.music.bitchord.ui.components.backdrop.highlight.HighlightStyle.Default(
            color = Color.Black.copy(alpha = 0.27f),
            blendMode = androidx.compose.ui.graphics.BlendMode.SrcOver,
            angle = 0f,
            falloff = 1.7f,
        ),
    ),
)

/** The default rim, with the same black line where its light does not reach. */
private val GLASS_RIM_DEFAULT = Highlight.Default.copy(
    sides = Highlight(
        style = com.music.bitchord.ui.components.backdrop.highlight.HighlightStyle.Default(
            color = Color.Black.copy(alpha = 0.5f),
            blendMode = androidx.compose.ui.graphics.BlendMode.SrcOver,
            angle = 225f,
        ),
    ),
)

/** Its lens a little shallower than the app's other glass: the page bends round the ends, but only slightly. */
const val NAV_GLASS_DEPTH = 0.8f

/**
 * Its blur all but gone, as Apple's: the page reads through the bar, in its own colours (boosted by
 * [NAV_GLASS_VIBRANCY]), and the lens at the edges has something sharp to bend.
 */
const val NAV_GLASS_BLUR = 0.2f
const val NAV_GLASS_VIBRANCY = 1.95f

/**
 * A lightweight visual match for liquid glass over a stable background.
 *
 * This keeps the same translucent tint, directional highlight and hairline as
 * [liquidGlass], but intentionally performs no backdrop capture, blur, lens
 * refraction or shadow rendering. When Liquid Glass is disabled or unsupported,
 * [fallbackColor] preserves the control's existing filled appearance.
 */
@Composable
fun Modifier.lightweightLiquidGlass(
    shape: CornerBasedShape,
    fallbackColor: Color,
): Modifier {
    val useGlass = isGlassSupported()
    val glassTint = navGlassTint()
    val shapeProvider = ShapeProvider { shape }

    return clip(shape)
        .background(
            color = if (useGlass) glassTint else fallbackColor,
            shape = shape,
        )
        .then(
            if (useGlass) {
                HighlightElement(
                    shapeProvider = shapeProvider,
                    highlight = { NAV_GLASS_RIM },
                )
            } else {
                Modifier
            },
        )
        .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
}

/**
 * Just the rim of liquid glass — the directional [Highlight] that catches the
 * light along a glass edge — for a control that keeps its own fill.
 *
 * Over a flat, stable page the blur and lens of [liquidGlass] would sample a
 * single colour and change nothing a reader could see; the rim is the part
 * that reads as glass. No backdrop capture, so it costs a stroke. Nothing at
 * all while Liquid Glass is off or unsupported.
 */
@Composable
fun Modifier.liquidGlassRim(shape: CornerBasedShape): Modifier {
    if (!isGlassSupported()) return this
    val shapeProvider = remember(shape) { ShapeProvider { shape } }
    return then(
        HighlightElement(
            shapeProvider = shapeProvider,
            highlight = { Highlight.Default },
        ),
    )
}

/**
 * Renders this composable as a liquid glass surface sampling [LocalAppBackdrop]:
 * vibrancy, blur and lens refraction, then a theme-adaptive surface tint (light
 * glass on light theme, dark on dark). Returns the receiver unchanged on devices
 * without RenderEffect support — callers should still gate on [isGlassSupported]
 * to fall back to the regular Haze treatment there.
 *
 * Under "reduce dynamic blur" the surface is filled solid instead, which is what
 * that setting promises everywhere else in the app. It is checked here rather
 * than at each call site so there is one answer to it: [MainActivity] also stops
 * recording the backdrop layer when it is on, and a surface that still tried to
 * sample would be sampling a layer nothing is drawing into.
 *
 *  * for phones that can't spare the refraction shader. The vibrancy, tint, rim and
 * shadow are the same either way.
 *
 * [shape] is restricted to [CornerBasedShape] because the backdrop's lens effect
 * throws for any other shape type.
 */
@Composable
fun Modifier.liquidGlass(
    shape: CornerBasedShape,
    /** Drawn over the glass in place of the theme's default tint, when set. */
    surfaceTint: Color? = null,
    /**
     * When set, the glass grows this much more opaque towards its edges — [surfaceTint] at the centre — a
     * film that thickens at the rim, as Apple's does.
     */
    edgeThickening: Float = 0f,
    /** The rim drawn along the edge in place of the default, when set. */
    rim: Highlight? = null,
    /** The saturation boost given to what shows through, 1 the default. */
    vibrancy: Float = VIBRANCY,
    /** How deep the lens bends at the edges, 1 the default. */
    depth: Float = 1f,
    /** The blur's radius against the default, 1 as it is. */
    blur: Float = 1f,
    /** False leaves the edges undeformed: no lens, and no page colour drawn along the rim. */
    lens: Boolean = true,
    /** True lays [surfaceTint] on evenly, the film neither thinner in the middle nor thicker at the edges. */
    flatFilm: Boolean = false,
): Modifier {
    if (!isGlassSupported()) return this
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    if (reduceDynamicBlur) {
        return background(MaterialTheme.colorScheme.surface, shape)
            .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
    }
    val glassOpacity by AppSettings.glassOpacity.collectAsStateWithLifecycle()
    val backdrop = LocalAppBackdrop.current
    val exportedBackdrop = LocalGlassExport.current
    val density = LocalDensity.current
    val resolutionScale = GLASS_RESOLUTION_SCALE
    val radiusDp = BLUR_RADIUS_DP
    val blurPx = with(density) { (radiusDp * blur * glassBlurScale(glassOpacity)).dp.toPx() } * resolutionScale
    val lensHeightPx = with(density) { (LENS_HEIGHT * depth * LENS_MAX_DP).dp.toPx() } * resolutionScale
    val lensAmountPx = with(density) { (LENS_AMOUNT * LENS_MAX_DP * glassRefractionScale(glassOpacity)).dp.toPx() } * resolutionScale
    val themeTint = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color(0xFFFAFAFA)
    } else {
        Color(0xFF1C1C1E)
    }
    // A tint handed in (navGlassTint) is already scaled by the opacity setting; the default one is scaled here.
    val surfaceTintColor = surfaceTint ?: themeTint.copy(alpha = (SURFACE_OPACITY * glassOpacity).coerceIn(0f, 0.97f))

    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(saturation = 1f + 0.5f * vibrancy * glassSaturationScale(glassOpacity))
            blur(blurPx)
            if (lens && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lens(
                    refractionHeight = lensHeightPx,
                    refractionAmount = lensAmountPx,
                    depthEffect = true,
                    chromaticAberration = true,
                )
            }
        },
        highlight = { rim ?: GLASS_RIM_DEFAULT },
        shadow = { Shadow.Default },
        onDrawSurface = {
            // The film is never uniform, whatever the opacity: it is thickest along the top and bottom edges and thins
            // towards the horizontal line through the middle, which is always the most transparent part. Nothing is
            // added at the sides: the round ends are as clear as the middle.
            if (flatFilm) {
                drawRect(color = surfaceTintColor, size = size)
            } else {
                val base = surfaceTintColor.alpha
                val edge = surfaceTintColor.copy(alpha = (base + (1f - base) * edgeThickening * glassOpacity).coerceIn(0f, 0.97f))
                // The more opaque the glass is set, the less the middle falls short of its edges.
                val share = GLASS_MIDDLE_SHARE + GLASS_MIDDLE_SHARE_GAIN * (glassOpacity / 1.5f).coerceIn(0f, 1f)
                val middle = surfaceTintColor.copy(alpha = edge.alpha * share)
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to edge, 0.36f to middle, 0.64f to middle, 1f to edge,
                    ),
                    size = size,
                )
            }
        },
        exportedBackdrop = exportedBackdrop,
        backdropScale = resolutionScale,
        edgeTint = if (lens) EDGE_TINT else 0f,
    )
}

/**
 * The one glass the app is made of: the navigation bar's. Milky, a thickened rim lit from above, the page showing
 * through in its own boosted colour, a shallow lens. Every liquid-glass surface — the top bar's pills, the tab
 * bar, the player's card — is drawn with this.
 */
@Composable
fun Modifier.navGlass(shape: CornerBasedShape): Modifier = liquidGlass(
    shape = shape,
    surfaceTint = navGlassTint(),
    rim = NAV_GLASS_RIM,
    depth = NAV_GLASS_DEPTH,
    blur = NAV_GLASS_BLUR,
    vibrancy = NAV_GLASS_VIBRANCY,
    edgeThickening = NAV_GLASS_EDGE,
)

/** The film of an alert or a menu: the nav bar's tint, denser. */
@Composable
fun alertTint(): Color = navGlassTint().let { it.copy(alpha = (it.alpha * ALERT_FILM_GAIN).coerceAtMost(0.96f)) }

/**
 * The nav bar's glass for a card that holds text (the alerts): the same vibrancy, rim and tint, at the tint's own
 * strength all over, with no lens bending its edges and a softer blur than the bar's so what is behind it does not
 * fight the words.
 */
@Composable
fun Modifier.alertGlass(shape: CornerBasedShape): Modifier = liquidGlass(
    shape = shape,
    // A denser film than the bar's: these hold text, and the page behind should not fight it.
    surfaceTint = alertTint(),
    rim = NAV_GLASS_RIM,
    blur = 0.7f,
    vibrancy = NAV_GLASS_VIBRANCY,
    lens = false,
    flatFilm = true,
)

/**
 * The same glass for the surfaces that are blurred by Haze rather than drawn over the backdrop (alerts, dialogs,
 * popups): the nav bar's milky tint over a light frost, since they sit where there is no backdrop to bend.
 */
@Composable
fun navGlassHazeStyle(): HazeStyle {
    val tint = navGlassTint()
    val surface = MaterialTheme.colorScheme.surface
    return remember(tint, surface) {
        HazeStyle(backgroundColor = surface, tints = listOf(HazeTint(tint)), blurRadius = 14.dp, noiseFactor = 0f)
    }
}
