package com.music.bitchord.ui.cover

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import kotlin.random.Random

/** How a cover's background is laid out: the colours run across it, down it, out from the middle, in from the corners, or not at all. */
enum class BackgroundStyle { HORIZONTAL, VERTICAL, RADIAL, CORNERS, SOLID, MESH, AURORA, SUNBURST, STRIPES, WAVES, DOTS, RIPPLE }

/** The typefaces a text can be set in: few, but each of them its own voice. */
enum class CoverFont { PRO, SANS, CONDENSED, SERIF, MONO, TYPEWRITER, SCRIPT, HANDWRITTEN, SMALLCAPS }

/** What is done to a text's shape after it is set. */
enum class TextEffect { NONE, SHADOW, NEON, GLITCH, EXTRUDE, ECHO, ARC, WAVE, DISPLACE, STACK, FLAG, SPHERE }

/** The plates a playlist's title can be set on. */
enum class LabelStyle { GLASS, TICKET, SLEEVE, CASSETTE, NEON, ONAIR, NOWPLAYING, CHROME, STAMP, LED }

/** The shapes that can be dropped onto a cover. */
/** The looks the stickers come in, each with its own palette and way of drawing. */
enum class StickerGroup { AUDEN, VINTAGE, FUTURE, Y2K, GRAPHIC }

enum class StickerKind(val group: StickerGroup) {
    LOGO(StickerGroup.AUDEN), RING(StickerGroup.AUDEN), BLEND(StickerGroup.AUDEN), STAR(StickerGroup.AUDEN),
    AUTOMIX(StickerGroup.AUDEN), LOSSLESS(StickerGroup.AUDEN), DJ(StickerGroup.AUDEN),

    RETRO_SUN(StickerGroup.VINTAGE), ARCHES(StickerGroup.VINTAGE), HIFI(StickerGroup.VINTAGE), RECORD(StickerGroup.VINTAGE),
    TAPE(StickerGroup.VINTAGE), ATOMIC(StickerGroup.VINTAGE),

    HUD(StickerGroup.FUTURE), WIREFRAME(StickerGroup.FUTURE), PULSE(StickerGroup.FUTURE), GRID_FLOOR(StickerGroup.FUTURE),
    SYSTEM(StickerGroup.FUTURE), BRACKETS(StickerGroup.FUTURE),

    CHROME_STAR(StickerGroup.Y2K), BUBBLES(StickerGroup.Y2K), BUTTERFLY(StickerGroup.Y2K), FLIP_PHONE(StickerGroup.Y2K),
    SMILEY(StickerGroup.Y2K),

    ASTERISK(StickerGroup.GRAPHIC), SQUIGGLE(StickerGroup.GRAPHIC), HALVES(StickerGroup.GRAPHIC), FLOWER(StickerGroup.GRAPHIC),
    ARROW(StickerGroup.GRAPHIC), BOLT(StickerGroup.GRAPHIC),
}

/** What can be done to a picture; any number at once, each with its own strength. */
enum class ImageEffect { GRAYSCALE, SEPIA, DUOTONE, INVERT, POSTERIZE, BLUR, PIXELATE, HALFTONE, GLITCH, GLOW, VIGNETTE }

/** The outline a picture is cut to. */
enum class ImageShape { RECTANGLE, ROUNDED, CIRCLE }

/** The colours a cover is made of: ten to pick from, each with a lighter and a darker end. */
object CoverPalette {
    /** The system hues; the last slot of the picker is the neutral ramp, see [shade]. */
    val hues = intArrayOf(
        0xFFFF3B30.toInt(), // red
        0xFFFF9500.toInt(), // orange
        0xFFFFD60A.toInt(), // yellow
        0xFF30D158.toInt(), // green
        0xFF64D2FF.toInt(), // cyan
        0xFF0A84FF.toInt(), // blue
        0xFF5E5CE6.toInt(), // indigo
        0xFFFF2D92.toInt(), // pink
        0xFFBF5AF2.toInt(), // purple
    )

    /** [base] taken toward black below the middle of [t] and toward white above it; the middle is [base] itself. */
    fun shade(base: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return if (k < 0.5f) mix(0xFF000000.toInt(), base, k * 2f) else mix(base, 0xFFFFFFFF.toInt(), (k - 0.5f) * 2f)
    }

    /** The neutral ramp: black to white. */
    fun gray(t: Float): Int = mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), t.coerceIn(0f, 1f))

    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1f - t) + ((b shr shift) and 0xFF) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun random(rng: Random): Int = shade(hues[rng.nextInt(hues.size)], 0.4f + rng.nextFloat() * 0.3f)
}

@Immutable
data class CoverBackground(
    val style: BackgroundStyle,
    val colors: List<Int>,
    /** What the patterned styles are laid out from; a new one is a new arrangement of the same colours. */
    val seed: Int = 1,
    /** 0..1: film grain over everything. */
    val grain: Float = 0f,
) {
    companion object {
        fun random(rng: Random = Random): CoverBackground {
            val first = rng.nextInt(CoverPalette.hues.size)
            // A second hue a few steps round the wheel, so the pair is a gradient and not a wash.
            val second = (first + 2 + rng.nextInt(4)) % CoverPalette.hues.size
            val styles = arrayOf(BackgroundStyle.VERTICAL, BackgroundStyle.RADIAL, BackgroundStyle.CORNERS, BackgroundStyle.MESH, BackgroundStyle.AURORA)
            return CoverBackground(
                style = styles[rng.nextInt(styles.size)],
                colors = listOf(
                    CoverPalette.shade(CoverPalette.hues[first], 0.5f),
                    CoverPalette.shade(CoverPalette.hues[second], 0.5f + rng.nextFloat() * 0.2f),
                ),
                seed = rng.nextInt(1000),
            )
        }
    }
}

/** One thing on the cover, placed by its centre (as a fraction of the cover), a scale and a rotation in degrees. */
@Immutable
sealed class CoverLayer {
    abstract val id: Long
    abstract val cx: Float
    abstract val cy: Float
    abstract val scale: Float
    abstract val rotation: Float

    abstract fun placed(cx: Float = this.cx, cy: Float = this.cy, scale: Float = this.scale, rotation: Float = this.rotation): CoverLayer
}

@Immutable
data class TextLayer(
    override val id: Long,
    val text: String,
    /** 100 is the size a title is first set in. */
    val size: Float = 100f,
    /** 100..900, as far as the typeface has them. */
    val weight: Int = 700,
    /** 0 upright .. 1 leaning. */
    val slant: Float = 0f,
    val fill: Int = 0xFFFFFFFF.toInt(),
    /** Null for no outline. */
    val stroke: Int? = null,
    val effect: TextEffect = TextEffect.NONE,
    val effectA: Float = 3f,
    val effectB: Float = 8f,
    /** Space between letters, in ems. */
    val spacing: Float = 0f,
    /** Line height as a multiple of the usual. */
    val leading: Float = 1f,
    val caps: Boolean = false,
    val opacity: Float = 1f,
    val font: CoverFont = CoverFont.PRO,
    /** How wide the letters are drawn: 1 as designed, up to 3 times as wide. */
    val stretch: Float = 1f,
    override val cx: Float = 0.5f,
    override val cy: Float = 0.5f,
    override val scale: Float = 1f,
    override val rotation: Float = 0f,
) : CoverLayer() {
    override fun placed(cx: Float, cy: Float, scale: Float, rotation: Float) =
        copy(cx = cx, cy = cy, scale = scale, rotation = rotation)
}

/** The two numbers an effect starts from, so choosing one shows it at a good strength. */
fun TextEffect.defaults(): Pair<Float, Float> = when (this) {
    TextEffect.NONE -> 3f to 8f
    TextEffect.SHADOW -> 10f to 14f
    TextEffect.NEON -> 10f to 6f
    TextEffect.GLITCH -> 8f to 4f
    TextEffect.EXTRUDE -> 14f to 45f
    TextEffect.ECHO -> 4f to 14f
    TextEffect.ARC -> 6f to 3f
    TextEffect.WAVE -> 8f to 4f
    TextEffect.DISPLACE -> 2f to 13f
    TextEffect.STACK, TextEffect.FLAG -> 3f to 8f
    TextEffect.SPHERE -> 5f to 5f
}

@Immutable
data class LabelLayer(
    override val id: Long,
    val style: LabelStyle,
    val text: String,
    /** The second line some plates carry: how many songs the playlist holds. */
    val extra: String = "",
    override val cx: Float = 0.5f,
    override val cy: Float = 0.5f,
    override val scale: Float = 1f,
    override val rotation: Float = 0f,
) : CoverLayer() {
    override fun placed(cx: Float, cy: Float, scale: Float, rotation: Float) =
        copy(cx = cx, cy = cy, scale = scale, rotation = rotation)
}

@Immutable
class ImageLayer(
    override val id: Long,
    val bitmap: Bitmap,
    val effects: Map<ImageEffect, Float> = emptyMap(),
    val shape: ImageShape = ImageShape.RECTANGLE,
    override val cx: Float = 0.5f,
    override val cy: Float = 0.5f,
    override val scale: Float = 1f,
    override val rotation: Float = 0f,
) : CoverLayer() {
    override fun placed(cx: Float, cy: Float, scale: Float, rotation: Float) =
        ImageLayer(id, bitmap, effects, shape, cx, cy, scale, rotation)

    fun with(effects: Map<ImageEffect, Float> = this.effects, shape: ImageShape = this.shape) =
        ImageLayer(id, bitmap, effects, shape, cx, cy, scale, rotation)
}

@Immutable
data class StickerLayer(
    override val id: Long,
    val kind: StickerKind,
    override val cx: Float = 0.5f,
    override val cy: Float = 0.5f,
    override val scale: Float = 1f,
    override val rotation: Float = 0f,
) : CoverLayer() {
    override fun placed(cx: Float, cy: Float, scale: Float, rotation: Float) =
        copy(cx = cx, cy = cy, scale = scale, rotation = rotation)
}

/** What a text is set to by "Randomize": a size, weight and lean, a colour and, now and then, an effect. */
fun TextLayer.randomized(rng: Random = Random): TextLayer {
    val effect = if (rng.nextFloat() < 0.5f) TextEffect.entries[1 + rng.nextInt(TextEffect.entries.size - 1)] else TextEffect.NONE
    val (a, b) = effect.defaults()
    return copy(
        size = 70f + rng.nextFloat() * 90f,
        weight = intArrayOf(300, 500, 700, 900)[rng.nextInt(4)],
        slant = if (rng.nextBoolean()) 0f else rng.nextFloat(),
        fill = if (rng.nextFloat() < 0.5f) 0xFFFFFFFF.toInt() else CoverPalette.random(rng),
        effect = effect,
        effectA = a,
        effectB = b,
        spacing = if (rng.nextBoolean()) 0f else rng.nextFloat() * 0.25f,
        font = CoverFont.entries[rng.nextInt(CoverFont.entries.size)],
        stretch = if (rng.nextFloat() < 0.3f) 1.3f + rng.nextFloat() * 0.9f else 1f,
    )
}
