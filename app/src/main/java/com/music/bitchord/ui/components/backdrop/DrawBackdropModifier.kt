/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 */
package com.music.bitchord.ui.components.backdrop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.music.bitchord.ui.components.backdrop.backdrops.LayerBackdrop
import com.music.bitchord.ui.components.backdrop.highlight.Highlight
import com.music.bitchord.ui.components.backdrop.highlight.HighlightElement
import com.music.bitchord.ui.components.backdrop.internal.ShapeProvider
import com.music.bitchord.ui.components.backdrop.internal.recordLayer
import com.music.bitchord.ui.components.backdrop.shadow.InnerShadow
import com.music.bitchord.ui.components.backdrop.shadow.InnerShadowElement
import com.music.bitchord.ui.components.backdrop.shadow.Shadow
import com.music.bitchord.ui.components.backdrop.shadow.ShadowElement

private val DefaultHighlight = { Highlight.Default }
private val DefaultShadow = { Shadow.Default }
private val DefaultOnDrawBackdrop: DrawScope.(DrawScope.() -> Unit) -> Unit = { it() }

fun Modifier.drawPlainBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    onDrawBehind: (DrawScope.() -> Unit)? = null,
    onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = DefaultOnDrawBackdrop,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null
): Modifier {
    val shapeProvider = ShapeProvider(shape)
    return this
        .then(
            if (layerBlock != null) {
                Modifier.graphicsLayer(layerBlock)
            } else {
                Modifier
            }
        )
        .then(
            DrawBackdropElement(
                backdrop = backdrop,
                shapeProvider = shapeProvider,
                effects = effects,
                layerBlock = layerBlock,
                exportedBackdrop = exportedBackdrop,
                onDrawBehind = onDrawBehind,
                onDrawBackdrop = onDrawBackdrop,
                onDrawSurface = onDrawSurface,
                onDrawFront = onDrawFront,
                backdropScale = 1f
            )
        )
}

fun Modifier.drawBackdrop(
    backdrop: Backdrop,
    shape: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    highlight: (() -> Highlight?)? = DefaultHighlight,
    shadow: (() -> Shadow?)? = DefaultShadow,
    innerShadow: (() -> InnerShadow?)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    exportedBackdrop: LayerBackdrop? = null,
    onDrawBehind: (DrawScope.() -> Unit)? = null,
    onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = DefaultOnDrawBackdrop,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    onDrawFront: (DrawScope.() -> Unit)? = null,
    // Vendored addition: records the backdrop into a layer at this fraction of the
    // surface resolution and draws it scaled back up, so the RenderEffect chain
    // processes far fewer pixels. Pixel-sized effect parameters (blur radius, lens
    // refraction) must be pre-multiplied by the same factor by the caller; the blur
    // hides the upscaling. 1f keeps the original full resolution behavior.
    backdropScale: Float = 1f,
    // Vendored addition: how strongly the page behind lends its own colour to the rim (0 = none). The backdrop
    // is drawn again, brighter and more saturated, over a thin ring along the edge and fading in for a few dp.
    edgeTint: Float = 0f
): Modifier {
    val shapeProvider = ShapeProvider(shape)
    return this
        .then(
            if (layerBlock != null) {
                Modifier.graphicsLayer(layerBlock)
            } else {
                Modifier
            }
        )
        .then(
            if (innerShadow != null) {
                InnerShadowElement(
                    shapeProvider = shapeProvider,
                    shadow = innerShadow
                )
            } else {
                Modifier
            }
        )
        .then(
            if (shadow != null) {
                ShadowElement(
                    shapeProvider = shapeProvider,
                    shadow = shadow
                )
            } else {
                Modifier
            }
        )
        .then(
            if (highlight != null) {
                HighlightElement(
                    shapeProvider = shapeProvider,
                    highlight = highlight
                )
            } else {
                Modifier
            }
        )
        .then(
            DrawBackdropElement(
                backdrop = backdrop,
                shapeProvider = shapeProvider,
                effects = effects,
                layerBlock = layerBlock,
                exportedBackdrop = exportedBackdrop,
                onDrawBehind = onDrawBehind,
                onDrawBackdrop = onDrawBackdrop,
                onDrawSurface = onDrawSurface,
                onDrawFront = onDrawFront,
                backdropScale = backdropScale.coerceIn(0.05f, 1f),
                edgeTint = edgeTint
            )
        )
}

private class DrawBackdropElement(
    val backdrop: Backdrop,
    val shapeProvider: ShapeProvider,
    val effects: BackdropEffectScope.() -> Unit,
    val layerBlock: (GraphicsLayerScope.() -> Unit)?,
    val exportedBackdrop: LayerBackdrop?,
    val onDrawBehind: (DrawScope.() -> Unit)?,
    val onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit,
    val onDrawSurface: (DrawScope.() -> Unit)?,
    val onDrawFront: (DrawScope.() -> Unit)?,
    val backdropScale: Float,
    val edgeTint: Float = 0f
) : ModifierNodeElement<DrawBackdropNode>() {

    override fun create(): DrawBackdropNode {
        return DrawBackdropNode(
            backdrop = backdrop,
            shapeProvider = shapeProvider,
            effects = effects,
            layerBlock = layerBlock,
            exportedBackdrop = exportedBackdrop,
            onDrawBehind = onDrawBehind,
            onDrawBackdrop = onDrawBackdrop,
            onDrawSurface = onDrawSurface,
            onDrawFront = onDrawFront,
            backdropScale = backdropScale,
            edgeTint = edgeTint
        )
    }

    override fun update(node: DrawBackdropNode) {
        node.backdrop = backdrop
        node.shapeProvider = shapeProvider
        node.effects = effects
        node.layerBlock = layerBlock
        if (node.exportedBackdrop != exportedBackdrop) {
            node.exportedBackdrop?.layerCoordinates = null
            node.exportedBackdrop = exportedBackdrop
        }
        node.onDrawBehind = onDrawBehind
        node.onDrawBackdrop = onDrawBackdrop
        node.onDrawSurface = onDrawSurface
        node.onDrawFront = onDrawFront
        node.backdropScale = backdropScale
        node.edgeTint = edgeTint
        node.invalidateDrawCache()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "drawBackdrop"
        properties["backdrop"] = backdrop
        properties["shapeProvider"] = shapeProvider
        properties["effects"] = effects
        properties["layerBlock"] = layerBlock
        properties["exportedBackdrop"] = exportedBackdrop
        properties["onDrawBehind"] = onDrawBehind
        properties["onDrawBackdrop"] = onDrawBackdrop
        properties["onDrawSurface"] = onDrawSurface
        properties["onDrawFront"] = onDrawFront
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DrawBackdropElement) return false

        if (backdrop != other.backdrop) return false
        if (shapeProvider != other.shapeProvider) return false
        if (effects != other.effects) return false
        if (layerBlock != other.layerBlock) return false
        if (exportedBackdrop != other.exportedBackdrop) return false
        if (onDrawBehind != other.onDrawBehind) return false
        if (onDrawBackdrop != other.onDrawBackdrop) return false
        if (onDrawSurface != other.onDrawSurface) return false
        if (onDrawFront != other.onDrawFront) return false
        if (backdropScale != other.backdropScale) return false
        if (edgeTint != other.edgeTint) return false

        return true
    }

    override fun hashCode(): Int {
        var result = backdrop.hashCode()
        result = 31 * result + shapeProvider.hashCode()
        result = 31 * result + effects.hashCode()
        result = 31 * result + (layerBlock?.hashCode() ?: 0)
        result = 31 * result + (exportedBackdrop?.hashCode() ?: 0)
        result = 31 * result + (onDrawBehind?.hashCode() ?: 0)
        result = 31 * result + onDrawBackdrop.hashCode()
        result = 31 * result + (onDrawSurface?.hashCode() ?: 0)
        result = 31 * result + (onDrawFront?.hashCode() ?: 0)
        result = 31 * result + backdropScale.hashCode()
        result = 31 * result + edgeTint.hashCode()
        return result
    }
}

private class DrawBackdropNode(
    var backdrop: Backdrop,
    var shapeProvider: ShapeProvider,
    var effects: BackdropEffectScope.() -> Unit,
    var layerBlock: (GraphicsLayerScope.() -> Unit)?,
    var exportedBackdrop: LayerBackdrop?,
    var onDrawBehind: (DrawScope.() -> Unit)?,
    var onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit,
    var onDrawSurface: (DrawScope.() -> Unit)?,
    var onDrawFront: (DrawScope.() -> Unit)?,
    var backdropScale: Float,
    var edgeTint: Float = 0f
) : LayoutModifierNode, DrawModifierNode, GlobalPositionAwareModifierNode, ObserverModifierNode, Modifier.Node() {

    private val effectScope =
        object : BackdropEffectScopeImpl() {

            override val shape: Shape get() = shapeProvider.innerShape
        }

    private var graphicsLayer: GraphicsLayer? = null

    private val layoutLayerBlock: GraphicsLayerScope.() -> Unit = {
        clip = true
        shape = shapeProvider.shape
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
    }

    private var layoutCoordinates: LayoutCoordinates? by mutableStateOf(null, neverEqualPolicy())

    private var padding by mutableFloatStateOf(0f)

    private val recordBackdropBlock: (DrawScope.() -> Unit) = {
        val canvas = drawContext.canvas
        val padding = padding
        val scale = backdropScale

        if (padding != 0f) {
            canvas.translate(padding, padding)
        }
        // Vendored addition: draw the backdrop content downscaled so the recorded
        // layer (and therefore the RenderEffect chain) covers fewer pixels.
        if (scale != 1f) {
            canvas.scale(scale, scale)
        }
        onDrawBackdrop {
            with(backdrop) {
                drawBackdrop(
                    density = effectScope,
                    coordinates = layoutCoordinates,
                    layerBlock = layerBlock
                )
            }
        }
        if (scale != 1f) {
            canvas.scale(1f / scale, 1f / scale)
        }
        if (padding != 0f) {
            canvas.translate(-padding, -padding)
        }
    }

    private val drawBackdropLayer: DrawScope.() -> Unit = {
        val layer = graphicsLayer
        if (layer != null) {
            val padding = padding
            val scale = backdropScale

            recordLayer(
                this@DrawBackdropNode,
                layer,
                size = IntSize(
                    ((size.width * scale).toInt() + padding.toInt() * 2).coerceAtLeast(1),
                    ((size.height * scale).toInt() + padding.toInt() * 2).coerceAtLeast(1)
                ),
                block = recordBackdropBlock
            )

            layer.topLeft =
                if (padding != 0f) IntOffset(-padding.toInt(), -padding.toInt())
                else IntOffset.Zero
            drawRecordedBackdrop()
        }
    }

    // Vendored change: the export below draws the layer this frame already
    // recorded, rather than recording the backdrop a second time. Upstream
    // re-runs the whole capture and effect chain for the export, which doubles
    // the surface's cost on every frame it is exported.
    private val drawRecordedBackdrop: DrawScope.() -> Unit = {
        val layer = graphicsLayer
        if (layer != null) {
            val scale = backdropScale
            if (scale != 1f) {
                // Vendored addition: stretch the low resolution layer back over the
                // full surface; the blur in the effect chain masks the upscaling.
                scale(1f / scale, pivot = Offset.Zero) {
                    drawLayer(layer)
                }
            } else {
                drawLayer(layer)
            }
        }
    }

    private val tintPaint = androidx.compose.ui.graphics.Paint().apply {
        colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
            androidx.compose.ui.graphics.ColorMatrix().apply {
                setToSaturation(1.5f)
                // Some white stays in it: the line is the page's colour lightened, not the colour alone.
                timesAssign(
                    androidx.compose.ui.graphics.ColorMatrix(
                        floatArrayOf(
                            1f, 0f, 0f, 0f, 70f,
                            0f, 1f, 0f, 0f, 70f,
                            0f, 0f, 1f, 0f, 70f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                )
            },
        )
    }

    /**
     * The page behind, in its own colours and more saturated, along the rim: the backdrop drawn a second time through
     * one thin line at the edge. A single line, not a fade, so there is no stepping to see.
     */
    private fun DrawScope.drawEdgeTint() {
        if (graphicsLayer == null) return
        val inset = 1.2.dp.toPx()
        if (size.minDimension <= inset * 2f) return
        val outline = shapeProvider.shape.createOutline(size, layoutDirection, this)
        val inner = androidx.compose.ui.graphics.Path().apply {
            when (outline) {
                is androidx.compose.ui.graphics.Outline.Rounded -> {
                    val r = outline.roundRect
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            r.left + inset, r.top + inset, r.right - inset, r.bottom - inset,
                            androidx.compose.ui.geometry.CornerRadius(
                                (r.topLeftCornerRadius.x - inset).coerceAtLeast(0f),
                                (r.topLeftCornerRadius.y - inset).coerceAtLeast(0f),
                            ),
                        ),
                    )
                }
                is androidx.compose.ui.graphics.Outline.Generic -> addPath(outline.path)
                is androidx.compose.ui.graphics.Outline.Rectangle -> addRect(outline.rect.deflate(inset))
            }
        }
        tintPaint.alpha = edgeTint.coerceIn(0f, 1f)
        clipPath(inner, androidx.compose.ui.graphics.ClipOp.Difference) {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height), tintPaint)
                drawRecordedBackdrop()
                // Only along the top and the bottom: the line fades out before the round ends, so it does not go
                // round the pill.
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                        0f to androidx.compose.ui.graphics.Color.Transparent,
                        0.22f to androidx.compose.ui.graphics.Color.Black,
                        0.78f to androidx.compose.ui.graphics.Color.Black,
                        1f to androidx.compose.ui.graphics.Color.Transparent,
                    ),
                    size = size,
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
                canvas.restore()
            }
        }
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(IntOffset.Zero, layerBlock = layoutLayerBlock)
        }
    }

    override fun ContentDrawScope.draw() {
        if (effectScope.update(this, backdropScale)) {
            updateEffects()
        }

        onDrawBehind?.invoke(this)
        drawBackdropLayer()
        onDrawSurface?.invoke(this)
        if (edgeTint > 0f) drawEdgeTint()
        drawContent()
        onDrawFront?.invoke(this)

        exportedBackdrop?.graphicsLayer?.let { layer ->
            recordLayer(this@DrawBackdropNode, layer) {
                onDrawBehind?.invoke(this)
                drawRecordedBackdrop()
                onDrawSurface?.invoke(this)
                onDrawFront?.invoke(this)
                // Vendored addition: what only the exported copy carries.
                this@DrawBackdropNode.exportedBackdrop?.onExport?.invoke(this)
            }
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            if (backdrop.isCoordinatesDependent) {
                layoutCoordinates = coordinates
            } else {
                if (layoutCoordinates != null) {
                    layoutCoordinates = null
                }
            }
            exportedBackdrop?.layerCoordinates = coordinates
        }
    }

    override fun onObservedReadsChanged() {
        invalidateDrawCache()
    }

    fun invalidateDrawCache() {
        observeEffects()
    }

    private fun observeEffects() {
        observeReads { updateEffects() }
    }

    private fun updateEffects() {
        if (!isRenderEffectSupported()) return

        effectScope.apply(effects)
        graphicsLayer?.renderEffect = effectScope.renderEffect
        padding = effectScope.padding
    }

    override fun onAttach() {
        val graphicsContext = requireGraphicsContext()
        graphicsLayer = graphicsContext.createGraphicsLayer()

        observeEffects()
    }

    override fun onDetach() {
        val graphicsContext = requireGraphicsContext()
        graphicsLayer?.let { layer ->
            graphicsContext.releaseGraphicsLayer(layer)
            graphicsLayer = null
        }

        effectScope.reset()
        layoutCoordinates = null
        exportedBackdrop?.layerCoordinates = null
    }
}
