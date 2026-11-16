package com.music.bitchord.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Thick-stroke, round-capped icons in the spirit of Telegram's modern icon set.
 * Drawn as strokes so the 2.2px weight + round joins read as a single polished
 * family. Tint is applied by [androidx.compose.material3.Icon].
 *
 * [Home] is the exception and says why it is one.
 */
object BitChordIcons {

    private const val STROKE = 2.2f
    private val stroke = SolidColor(Color.Black)

    /** Compact list and quotation-bubble shapes used by the player controls. */
    val Queue: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_queue", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                for (y in listOf(6f, 12f, 18f)) {
                    moveTo(8f, y)
                    lineTo(21f, y)
                }
            }
            path(fill = stroke) {
                for (y in listOf(6f, 12f, 18f)) {
                    moveTo(4.35f, y)
                    arcToRelative(1.25f, 1.25f, 0f, true, true, -2.5f, 0f)
                    arcToRelative(1.25f, 1.25f, 0f, true, true, 2.5f, 0f)
                    close()
                }
            }
        }.build()
    }

    /**
     * The audio output button: a play triangle with two sound waves round its tip — a short arc close to it
     * and a long one, about 230 degrees, wrapping a good way past it. Measured off the reference glyph.
     */
    val AudioOutput: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_audio_output", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = stroke, stroke = stroke, strokeLineWidth = 1.2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(2.2f, 7f)
                lineTo(9.4f, 12f)
                lineTo(2.2f, 17f)
                close()
            }
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
                val cx = 10.9f
                for ((radius, spread) in listOf(4.1f to 60f, 9.5f to 115f)) {
                    val a = Math.toRadians(spread.toDouble())
                    val dx = (radius * Math.cos(a)).toFloat()
                    val dy = (radius * Math.sin(a)).toFloat()
                    moveTo(cx + dx, 12f - dy)
                    arcTo(radius, radius, 0f, spread > 90f, true, cx + dx, 12f + dy)
                }
            }
        }.build()
    }

    /**
     * The DJ's ring, held still: the thick wobbling loop of its visualizer as one frame of it, with two echoes
     * of itself trailing off to either side, as fainter loops — the colours of the live one reduced to the
     * glyph's own, so it takes whatever tint the bar gives it.
     */
    val DjRing: ImageVector by lazy {
        fun ring(time: Float, dx: Float, dy: Float, radius: Float, push: Float): List<Pair<Float, Float>> {
            val points = 96
            return (0..points).map { i ->
                val a = 2f * Math.PI.toFloat() * i / points
                val wobble = 0.55f * Math.sin((2 * a + 0.9f * time + 0.3f).toDouble()) +
                    0.32f * Math.sin((3 * a - 1.3f * time + 1.1f).toDouble()) +
                    0.22f * Math.sin((4 * a + 1.7f * time + 2.0f).toDouble()) +
                    0.13f * Math.sin((5 * a - 2.3f * time).toDouble())
                val r = radius * (1f + push * wobble.toFloat())
                (12f + dx + r * Math.cos(a.toDouble()).toFloat()) to (12f + dy + r * Math.sin(a.toDouble()).toFloat())
            }
        }
        ImageVector.Builder(
            name = "bc_dj_ring", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            // The echoes first, fainter and off to one side each, then the ring over them.
            for ((time, dx, dy, alpha) in listOf(
                listOf(-0.6f, 1.2f, -0.9f, 0.34f),
                listOf(-0.3f, -1.0f, 1.0f, 0.52f),
            )) {
                path(
                    stroke = stroke, strokeAlpha = alpha, strokeLineWidth = 2.5f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                ) {
                    val pts = ring(time, dx, dy, 7.8f, 0.13f)
                    moveTo(pts[0].first, pts[0].second)
                    pts.drop(1).forEach { lineTo(it.first, it.second) }
                    close()
                }
            }
            path(
                stroke = stroke, strokeLineWidth = 3.1f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) {
                val pts = ring(0.4f, 0f, 0f, 7.8f, 0.13f)
                moveTo(pts[0].first, pts[0].second)
                pts.drop(1).forEach { lineTo(it.first, it.second) }
                close()
            }
        }.build()
    }

    /** Automix: two overlapping circles, a ring and a ring with a dot — one song crossing into the next. */
    val Automix: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_automix", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                for (cx in listOf(8.4f, 15.6f)) {
                    moveTo(cx + 5.2f, 12f)
                    arcToRelative(5.2f, 5.2f, 0f, true, true, -10.4f, 0f)
                    arcToRelative(5.2f, 5.2f, 0f, true, true, 10.4f, 0f)
                    close()
                }
            }
            path(fill = stroke) {
                moveTo(15.6f + 2.2f, 12f)
                arcToRelative(2.2f, 2.2f, 0f, true, true, -4.4f, 0f)
                arcToRelative(2.2f, 2.2f, 0f, true, true, 4.4f, 0f)
                close()
            }
        }.build()
    }

    val LyricsQuote: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_lyrics_quote", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6f, 3.5f)
                lineTo(18f, 3.5f)
                quadTo(21f, 3.5f, 21f, 6.5f)
                lineTo(21f, 15f)
                quadTo(21f, 18f, 18f, 18f)
                lineTo(10f, 18f)
                lineTo(6f, 21f)
                lineTo(6f, 18f)
                quadTo(3f, 18f, 3f, 15f)
                lineTo(3f, 6.5f)
                quadTo(3f, 3.5f, 6f, 3.5f)
                close()
            }
            path(fill = stroke) {
                for (x in listOf(7.6f, 12.8f)) {
                    moveTo(x, 8f)
                    lineTo(x + 3f, 8f)
                    lineTo(x + 3f, 11.1f)
                    quadTo(x + 3f, 13.2f, x + 0.5f, 13.7f)
                    lineTo(x + 0.5f, 12.3f)
                    quadTo(x + 1.7f, 12f, x + 1.7f, 11f)
                    lineTo(x, 11f)
                    close()
                }
            }
        }.build()
    }

    val Play: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_play",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = stroke,
            ) {
                moveTo(6.8f, 4.8f)
                lineTo(19.2f, 12f)
                lineTo(6.8f, 19.2f)
                close()
            }
        }.build()
    }

    /**
     * The one solid icon in the set, and the tab bar is why.
     *
     * Selection here is carried by tint alone — see `GlassNavBar`, where the
     * unselected tabs are the same glyph at 65% — so the home tab is the anchor
     * of the bar rather than a state of it, and a filled silhouette reads as
     * that anchor at 24dp where a four-stroke outline reads as detail.
     *
     * Filled *and* stroked, with the same path. The outline of a house is all
     * corners — an apex, two shoulders, two door jambs — and stroking the fill
     * with a round-joined pen is what rounds every one of them at once, for a
     * geometry that stays nine straight lines. The numbers below are therefore
     * inset by half the stroke: the pen puts back what they give up, and the
     * door notch is drawn oversized for the same reason, since the pen closes
     * in on it from both jambs.
     */
    val Home: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_home",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                fill = stroke,
                stroke = stroke,
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Roof, flush with the walls rather than overhanging them: an
                // eave narrower than the pen it is drawn with comes out as a
                // blob on the corner instead of a ledge.
                moveTo(12f, 4.05f)
                lineTo(19.75f, 11.35f)
                // Right wall, down to the floor
                lineTo(19.75f, 19.65f)
                // Door, cut up out of the bottom edge
                lineTo(14.95f, 19.65f)
                lineTo(14.95f, 15.6f)
                lineTo(9.05f, 15.6f)
                lineTo(9.05f, 19.65f)
                // Left wall, back up to the apex
                lineTo(4.25f, 19.65f)
                lineTo(4.25f, 11.35f)
                close()
            }
        }.build()
    }

    /**
     * The phone tab bar's Explore, Library and Search glyphs: solid-weight
     * iOS shapes rather than this file's stroked family, taken from the
     * outlines of Flutter's cupertino_icons font (MIT) — square_grid_2x2
     * with its four counters dropped so the squares fill solid,
     * square_stack_fill and search.
     *
     * Each is the glyph laid out the way the font sets it: the 512-unit em
     * box is the viewport, the baseline sits at the font's ascent share of
     * it, and the advance is centred. So they keep the font's own optical
     * size and weight against one another at any icon size.
     */
    val TabExplore: ImageVector by lazy {
        fontGlyph(
            "bc_tab_explore",
            "M99.5 230.24Q58.5 230.24 58.5 188.24V93.24Q58.5 51.24 99.5 51.24H197.5Q238.5 51.24 238.5 93.24V188.24Q238.5 230.24 197.5 230.24Z" +
                "M314.5 230.24Q273.5 230.24 273.5 188.24V93.24Q273.5 51.24 314.5 51.24H412.5Q453.5 51.24 453.5 93.24V188.24Q453.5 230.24 412.5 230.24Z" +
                "M99.5 445.24Q58.5 445.24 58.5 403.24V307.24Q58.5 266.24 99.5 266.24H197.5Q238.5 266.24 238.5 307.24V403.24Q238.5 445.24 197.5 445.24Z" +
                "M314.5 445.24Q273.5 445.24 273.5 403.24V307.24Q273.5 266.24 314.5 266.24H412.5Q453.5 266.24 453.5 307.24V403.24Q453.5 445.24 412.5 445.24Z",
        )
    }

    /**
     * Two units lower than the font sets it: the back card's top edge rises
     * just past the em box, and the viewport would clip it flat.
     */
    val TabLibrary: ImageVector by lazy {
        fontGlyph(
            "bc_tab_library",
            "M154 27.24Q156 0.24 185 0.24H322Q352 0.24 353 27.24Z" +
                "M116 87.24Q121 56.24 150 56.24H356Q385 56.24 390 87.24Z" +
                "M133 500.24Q66 500.24 66 434.24V187.24Q66 121.24 133 121.24H378Q446 121.24 446 187.24V434.24Q446 500.24 385 500.24Z" +
                // The Auden mark cut out of the body, as Apple Music's library icon has its note: an even-odd hole.
                "M237.1 353.5C237.9 353.9 239.4 354.9 240.3 354.9C240.7 354.8 241.1 354.6 241.4 354.5C242.1 354.2 242.7 353.7 242.9 353.0L243.0 352.7C249.8 323.0 256.9 293.3 263.8 263.5L271.5 230.3L274.2 218.9C274.6 217.1 275.0 215.2 275.5 213.4C276.0 211.7 276.8 210.0 278.0 208.7C279.2 207.3 281.0 206.5 282.8 206.3C285.8 205.8 296.0 207.7 298.7 209.2C303.1 211.7 302.3 216.1 302.4 220.4C302.4 221.8 302.7 223.2 302.8 224.6C303.1 227.4 303.8 230.1 304.8 232.6C307.9 241.3 313.1 247.7 319.2 254.4C320.2 255.5 321.1 256.7 322.1 257.8C323.7 259.5 325.4 261.1 327.0 262.8C340.2 277.1 351.0 291.4 352.0 311.6C352.6 321.9 351.2 330.3 346.7 339.7C342.8 347.9 335.9 356.0 328.9 361.7C327.2 363.1 325.5 364.6 323.4 365.5C319.7 367.2 315.7 363.6 316.7 359.7C317.2 357.7 320.6 353.9 321.9 351.8C326.3 344.8 329.1 336.2 328.5 327.9C328.4 326.6 328.5 325.2 328.0 324.0L327.7 323.3L327.5 323.1C325.1 323.2 322.7 323.7 320.3 324.1L308.8 326.0L271.4 332.3L270.1 332.6L269.5 332.8L269.3 332.9C267.6 337.7 266.8 342.8 265.7 347.7L258.7 377.9C257.1 384.5 256.0 391.1 253.5 397.4C251.5 402.2 247.8 407.7 244.0 411.3C243.1 412.2 241.9 413.0 240.9 413.9C239.3 415.2 237.7 416.5 235.9 417.6C233.5 419.0 230.9 420.1 228.4 421.2C227.0 421.7 225.6 422.3 224.1 422.7C220.5 423.8 216.6 424.6 212.8 425.0C212.0 425.1 211.1 425.1 210.3 425.2C208.9 425.3 207.7 425.6 206.3 425.6C204.8 425.6 203.3 425.3 201.8 425.1C184.1 423.8 166.7 415.7 161.1 397.6C158.4 389.0 159.6 380.1 163.7 372.1C167.8 364.1 174.6 357.4 182.5 353.2C188.7 349.8 195.4 347.4 202.5 346.3C203.3 346.2 204.1 346.2 204.9 346.1C207.2 345.9 209.2 345.7 211.6 345.8C212.7 345.8 213.8 346.0 214.9 346.1C222.0 346.8 228.9 348.9 235.2 352.4L236.4 353.1L236.8 353.3L237.1 353.5ZM320.2 304.5C319.0 302.8 317.8 301.0 316.5 299.4C309.8 291.6 300.2 285.3 291.2 280.5C289.0 279.2 286.7 277.8 284.3 276.8C284.0 276.7 283.6 276.6 283.2 276.5L282.8 276.5L282.5 276.5C281.3 279.0 280.9 281.9 280.3 284.6C279.1 289.7 277.9 294.7 276.8 299.8L274.9 307.8C274.7 308.8 274.4 309.7 274.3 310.7L274.3 311.7L274.4 312.0L274.4 312.3C284.3 310.7 294.3 309.2 304.2 307.3C309.3 306.4 314.5 305.8 319.6 304.7L320.2 304.5Z",
            evenOdd = true,
        )
    }

    val TabSearch: ImageVector by lazy {
        fontGlyph(
            "bc_tab_search",
            "M219 374.24Q151 374.24 102.5 325.74Q54 277.24 54 209.24Q54 141.24 102.5 92.74Q151 44.24 219 44.24Q287 44.24 335.5 92.74Q384 141.24 384 209.24Q384 264.24 350 309.24L451 410.24Q458 417.24 458 427.24Q458 438.24 451 445.24Q444 452.24 434 452.24Q423 452.24 416 445.24L315 343.24Q271 374.24 219 374.24Z" +
                "M128 300.24Q166 338.24 219 338.24Q272 338.24 310 300.24Q348 262.24 348 209.24Q348 156.24 310 118.24Q272 80.24 219 80.24Q166 80.24 128 118.24Q90 156.24 90 209.24Q90 262.24 128 300.24Z",
        )
    }

    /** A filled glyph outline in the 512-unit em box — see [TabExplore]. */
    private fun fontGlyph(name: String, pathData: String, evenOdd: Boolean = false): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 512f, viewportHeight = 512f,
        ).addPath(
            pathData = addPathNodes(pathData), fill = stroke,
            pathFillType = if (evenOdd) androidx.compose.ui.graphics.PathFillType.EvenOdd else androidx.compose.ui.graphics.PathFillType.NonZero,
        ).build()

    val Search: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_search",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Lens (full circle from two arcs)
                moveTo(4.6f, 11f)
                arcToRelative(6.4f, 6.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 12.8f, 0f)
                arcToRelative(6.4f, 6.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, -12.8f, 0f)
                // Handle
                moveTo(15.9f, 15.9f)
                lineTo(20.4f, 20.4f)
            }
        }.build()
    }

    val Explore: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_explore",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Compass dial
                moveTo(3.4f, 12f)
                arcToRelative(8.6f, 8.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, 17.2f, 0f)
                arcToRelative(8.6f, 8.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, -17.2f, 0f)
                // Needle
                moveTo(15.4f, 8.6f)
                lineTo(13.6f, 13.6f)
                lineTo(8.6f, 15.4f)
                lineTo(10.4f, 10.4f)
                close()
            }
        }.build()
    }

    val Shuffle: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_shuffle",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Strand that crosses downwards, with its arrow head
                moveTo(3.4f, 7.4f); lineTo(7f, 7.4f); lineTo(16.6f, 16.6f); lineTo(20.6f, 16.6f)
                moveTo(18.1f, 14.1f); lineTo(20.6f, 16.6f); lineTo(18.1f, 19.1f)
                // Strand that crosses upwards, broken around the intersection
                moveTo(3.4f, 16.6f); lineTo(7f, 16.6f); lineTo(9.8f, 13.9f)
                moveTo(13.9f, 10.1f); lineTo(16.6f, 7.4f); lineTo(20.6f, 7.4f)
                moveTo(18.1f, 4.9f); lineTo(20.6f, 7.4f); lineTo(18.1f, 9.9f)
            }
        }.build()
    }

    val Repeat: ImageVector by lazy { repeatLoop("bc_repeat") }

    /**
     * Two straight runs joined by semicircles, with the arrow heads lying flat
     * at the ends of the straights. Putting them on the curves instead — as a
     * first pass did — makes the glyph read as a refresh/sync symbol.
     */
    private fun repeatLoop(name: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(8.6f, 7.6f)
                lineTo(15.4f, 7.6f)
                arcToRelative(4.4f, 4.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, 8.8f)
                lineTo(8.6f, 16.4f)
                arcToRelative(4.4f, 4.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, -8.8f)
                close()
                // Direction of travel: right along the top, left along the bottom.
                moveTo(13.5f, 5.7f); lineTo(15.4f, 7.6f); lineTo(13.5f, 9.5f)
                moveTo(10.5f, 14.5f); lineTo(8.6f, 16.4f); lineTo(10.5f, 18.3f)
            }
        }.build()

    /** AutoPlay's lemniscate. */
    val Infinity: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_infinity",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 12f)
                curveTo(10.1f, 9.1f, 8.7f, 8f, 7.1f, 8f)
                arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = false, 0f, 8f)
                curveTo(8.7f, 16f, 10.1f, 14.9f, 12f, 12f)
                curveTo(13.9f, 9.1f, 15.3f, 8f, 16.9f, 8f)
                arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, 8f)
                curveTo(15.3f, 16f, 13.9f, 14.9f, 12f, 12f)
            }
        }.build()
    }

    /** Beamed pair of notes, for instrumental stretches in the lyrics. */
    val MusicNote: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_music_note",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            // Heads are solid; stems and beam keep the family's stroke weight.
            path(fill = stroke) {
                moveTo(4.2f, 17.7f)
                arcToRelative(2.9f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 5.8f, 0f)
                arcToRelative(2.9f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, -5.8f, 0f)
                close()
                moveTo(14.2f, 15.9f)
                arcToRelative(2.9f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 5.8f, 0f)
                arcToRelative(2.9f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, -5.8f, 0f)
                close()
            }
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(10f, 17.7f); lineTo(10f, 6.7f)
                moveTo(20f, 15.9f); lineTo(20f, 4.9f)
                moveTo(10f, 6.7f); lineTo(20f, 4.9f)
            }
        }.build()
    }

    /**
     * Two beamed notes, filled, sitting level — what the lyrics show through an
     * instrumental section. Apart from [MusicNote], which stays the artwork
     * placeholder elsewhere.
     */
    val Instrumental: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_instrumental",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(
                "M19.952 1.651a.75.75 0 0 1 .298.599V16.303a3 3 0 0 1-2.176 2.884l-1.32.377a2.553 2.553 0 1 1-1.403-4.909" +
                    "l2.311-.66a1.5 1.5 0 0 0 1.088-1.442V6.994l-9 2.572v9.737a3 3 0 0 1-2.176 2.884l-1.32.377" +
                    "a2.553 2.553 0 1 1-1.402-4.909l2.31-.66a1.5 1.5 0 0 0 1.088-1.442V5.25a.75.75 0 0 1 .544-.721" +
                    "l10.5-3a.75.75 0 0 1 .658.122Z",
            ),
            pathFillType = PathFillType.EvenOdd,
            fill = stroke,
        ).build()
    }

    /** Speech bubble with two lines of words. */
    val Lyrics: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_lyrics",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6.2f, 4.6f)
                lineTo(17.8f, 4.6f)
                arcToRelative(2.8f, 2.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2.8f, 2.8f)
                lineTo(20.6f, 13.6f)
                arcToRelative(2.8f, 2.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2.8f, 2.8f)
                lineTo(10.6f, 16.4f)
                lineTo(6.8f, 19.6f)
                lineTo(6.8f, 16.4f)
                lineTo(6.2f, 16.4f)
                arcToRelative(2.8f, 2.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2.8f, -2.8f)
                lineTo(3.4f, 7.4f)
                arcToRelative(2.8f, 2.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2.8f, -2.8f)
                close()
                moveTo(7.6f, 9f); lineTo(16.4f, 9f)
                moveTo(7.6f, 12.1f); lineTo(13.2f, 12.1f)
            }
        }.build()
    }

    /** Plain chevron — a disclosure hint, not a directional arrow. */
    val ChevronRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_chevron_right",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9.5f, 6.2f)
                lineTo(15.3f, 12f)
                lineTo(9.5f, 17.8f)
            }
        }.build()
    }

    /**
     * The player's like control, in two weights.
     *
     * Filled rather than merely tinted when set: the player draws every glyph
     * white on artwork, where a colour change alone is the one signal the
     * backdrop can swallow. A shape change survives any album cover.
     */
    val Heart: ImageVector by lazy { heart("bc_heart", filled = false) }

    val HeartFilled: ImageVector by lazy { heart("bc_heart_filled", filled = true) }

    private fun heart(name: String, filled: Boolean): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = if (filled) stroke else null,
            ) {
                // Two lobes meeting at the top notch, falling to a single point.
                moveTo(12f, 20f)
                curveTo(12f, 20f, 3.2f, 14.6f, 3.2f, 8.9f)
                arcToRelative(4.5f, 4.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 8.8f, -1.5f)
                arcToRelative(4.5f, 4.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 8.8f, 1.5f)
                curveTo(20.8f, 14.6f, 12f, 20f, 12f, 20f)
                close()
            }
        }.build()

    /**
     * Favorite, the way Apple Music draws it: a five-point star whose tips
     * are softened by the round stroke join rather than cut to a point.
     * Filled when set, for the reason [Heart] gives.
     */
    val Star: ImageVector by lazy { star("bc_star", filled = false) }

    val StarFilled: ImageVector by lazy { star("bc_star_filled", filled = true) }

    private fun star(name: String, filled: Boolean): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = if (filled) stroke else null,
            ) {
                // Outer radius 9, inner 0.44 of it, centred on its own bounds
                // so the shorter lower half doesn't sit the star high.
                moveTo(12f, 3.86f)
                lineTo(14.33f, 9.66f)
                lineTo(20.56f, 10.08f)
                lineTo(15.77f, 14.08f)
                lineTo(17.29f, 20.14f)
                lineTo(12f, 16.82f)
                lineTo(6.71f, 20.14f)
                lineTo(8.23f, 14.08f)
                lineTo(3.44f, 10.08f)
                lineTo(9.67f, 9.66f)
                close()
            }
        }.build()

    /** Adding something — a new playlist, on the library shelf. */
    val Plus: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_plus",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 5f); lineTo(12f, 19f)
                moveTo(5f, 12f); lineTo(19f, 12f)
            }
        }.build()
    }

    /**
     * A tick — the other half of [Plus]. Saving something to the library swaps
     * one for the other in place, so the two are drawn on the same 14-unit span
     * and at the same weight; a tick sized to its own bounding box would jump.
     */
    val Check: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_check",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5f, 12.8f); lineTo(9.6f, 17.4f); lineTo(19f, 6.9f)
            }
        }.build()
    }

    /** Arrow pointing down into a tray — offline download. */
    val Download: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_download",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Vertical stem
                moveTo(12f, 4.5f); lineTo(12f, 15.5f)
                // Arrow head
                moveTo(7.5f, 11f); lineTo(12f, 15.5f); lineTo(16.5f, 11f)
                // Tray base
                moveTo(4.5f, 18f); lineTo(19.5f, 18f)
            }
        }.build()
    }

    /**
     * Clock face with two hands — a download asked for but not yet on disk.
     *
     * The dial is the same 8.6 radius as [Explore]'s, so the two sit at the same
     * optical weight when they appear in the same row of header buttons. The
     * hands are one polyline through the centre rather than two strokes: the
     * round join is then the pivot, which is what stops the middle reading as a
     * pair of lines that happen to cross.
     */
    val Clock: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_clock",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Dial (full circle from two arcs)
                moveTo(3.4f, 12f)
                arcToRelative(8.6f, 8.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, 17.2f, 0f)
                arcToRelative(8.6f, 8.6f, 0f, isMoreThanHalf = true, isPositiveArc = true, -17.2f, 0f)
                // Minute hand up, hour hand down to the right
                moveTo(12f, 7.4f); lineTo(12f, 12f); lineTo(15.4f, 13.8f)
            }
        }.build()
    }

    /**
     * A pushpin, not a map marker: a round head with a straight needle
     * kicked out to one side, the way it sits once it's stuck into something.
     * A teardrop reads as "location" the moment it's this small — the two
     * disjoint strokes here are what keep it read as "pinned" instead.
     */
    val Pin: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_pin",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Round head.
                moveTo(9.6f, 8.4f)
                arcToRelative(3.4f, 3.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 6.8f, 0f)
                arcToRelative(3.4f, 3.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, -6.8f, 0f)
                // Needle, kicked out from the underside of the head to a point.
                moveTo(10.8f, 10.8f)
                lineTo(5.6f, 19.6f)
            }
        }.build()
    }

    /** Speed gauge used by high-performance display settings. */
    val Performance: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_performance",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Open gauge, leaving room for the base line.
                moveTo(4.1f, 16.8f)
                arcToRelative(8.4f, 8.4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 15.8f, 0f)
                // Needle and hub.
                moveTo(12f, 15.8f)
                lineTo(16.7f, 9.3f)
                moveTo(10.9f, 16.9f)
                arcToRelative(1.1f, 1.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 2.2f, 0f)
                arcToRelative(1.1f, 1.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, -2.2f, 0f)
                // Stable baseline.
                moveTo(5.4f, 19.5f)
                lineTo(18.6f, 19.5f)
            }
        }.build()
    }

    /** Four frame-time bars, used by refresh-rate selection. */
    val FrameRate: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_frame_rate",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(5f, 15.5f); lineTo(5f, 8.5f)
                moveTo(9.7f, 18.5f); lineTo(9.7f, 5.5f)
                moveTo(14.3f, 16.5f); lineTo(14.3f, 7.5f)
                moveTo(19f, 14.5f); lineTo(19f, 9.5f)
            }
        }.build()
    }

    val Library: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_library",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Three upright spines + one leaning — Apple Music's library glyph, thickened
                moveTo(4.6f, 4.8f); lineTo(4.6f, 19.2f)
                moveTo(9.2f, 4.8f); lineTo(9.2f, 19.2f)
                moveTo(13.8f, 4.8f); lineTo(13.8f, 19.2f)
                moveTo(17.2f, 5.6f); lineTo(20.6f, 18.9f)
            }
        }.build()
    }

    /** 2x2 grid icon (4 rounded squares) for switching layout to grid view. */
    val GridView: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_grid_view",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Top-left
                moveTo(6.5f, 4.5f)
                lineTo(8.5f, 4.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, 2f)
                lineTo(10.5f, 8.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, 2f)
                lineTo(6.5f, 10.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, -2f)
                lineTo(4.5f, 6.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, -2f)
                close()

                // Top-right
                moveTo(15.5f, 4.5f)
                lineTo(17.5f, 4.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, 2f)
                lineTo(19.5f, 8.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, 2f)
                lineTo(15.5f, 10.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, -2f)
                lineTo(13.5f, 6.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, -2f)
                close()

                // Bottom-left
                moveTo(6.5f, 13.5f)
                lineTo(8.5f, 13.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, 2f)
                lineTo(10.5f, 17.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, 2f)
                lineTo(6.5f, 19.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, -2f)
                lineTo(4.5f, 15.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, -2f)
                close()

                // Bottom-right
                moveTo(15.5f, 13.5f)
                lineTo(17.5f, 13.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, 2f)
                lineTo(19.5f, 17.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, 2f)
                lineTo(15.5f, 19.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, -2f)
                lineTo(13.5f, 15.5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, -2f)
                close()
            }
        }.build()
    }

    /** List view icon (horizontal rows) for switching layout to list view. */
    val ListView: ImageVector by lazy {
        ImageVector.Builder(
            name = "bc_list_view",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = stroke,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Three horizontal lines with bullet markers
                moveTo(4.5f, 6.5f); lineTo(5.5f, 6.5f)
                moveTo(9.5f, 6.5f); lineTo(19.5f, 6.5f)

                moveTo(4.5f, 12f); lineTo(5.5f, 12f)
                moveTo(9.5f, 12f); lineTo(19.5f, 12f)

                moveTo(4.5f, 17.5f); lineTo(5.5f, 17.5f)
                moveTo(9.5f, 17.5f); lineTo(19.5f, 17.5f)
            }
        }.build()
    }
}
