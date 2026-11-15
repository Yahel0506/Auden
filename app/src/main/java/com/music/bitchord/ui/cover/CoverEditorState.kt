package com.music.bitchord.ui.cover

import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class CoverTab { BACKGROUND, TEXT, IMAGE, LABELS, STICKERS }

/**
 * Everything the cover editor is holding: the background, the layers in order from back to front, which of them
 * is selected, which text is being typed into and which tab is open.
 */
@Stable
class CoverEditorState(val title: String, songCount: Int = 0) {
    /** The song count as the plates that carry it show it; empty when it is not known. */
    val countText: String = if (songCount > 0) songCount.toString() else ""
    var background by mutableStateOf(CoverBackground.random())
    val layers = mutableStateListOf<CoverLayer>()
    var selectedId by mutableStateOf<Long?>(null)
    var editingTextId by mutableStateOf<Long?>(null)
    var tab by mutableStateOf(CoverTab.BACKGROUND)
    var panelOpen by mutableStateOf(true)
    private var nextId = 1L

    init {
        // A cover starts as the playlist's name, large, on colour.
        layers += TextLayer(nextId++, title.ifBlank { " " })
    }

    val selected: CoverLayer? get() = layers.firstOrNull { it.id == selectedId }

    fun select(id: Long?) {
        selectedId = id
        val layer = layers.firstOrNull { it.id == id } ?: return
        tab = when (layer) {
            is TextLayer -> CoverTab.TEXT
            is ImageLayer -> CoverTab.IMAGE
            is LabelLayer -> CoverTab.LABELS
            is StickerLayer -> CoverTab.STICKERS
        }
        panelOpen = true
    }

    fun replace(layer: CoverLayer) {
        val index = layers.indexOfFirst { it.id == layer.id }
        if (index >= 0) layers[index] = layer
    }

    private fun add(build: (Long) -> CoverLayer) {
        val layer = build(nextId++)
        layers += layer
        selectedId = layer.id
    }

    fun addText() {
        add { TextLayer(it, "") }
        editingTextId = selectedId
    }

    fun addLabel(style: LabelStyle) = add { LabelLayer(it, style, title.ifBlank { " " }, countText) }

    fun addSticker(kind: StickerKind) = add { StickerLayer(it, kind) }

    fun addImage(bitmap: Bitmap) = add { ImageLayer(it, bitmap) }

    fun bringForward(id: Long) {
        val i = layers.indexOfFirst { it.id == id }
        if (i in 0 until layers.lastIndex) layers.add(i + 1, layers.removeAt(i))
    }

    fun sendBackward(id: Long) {
        val i = layers.indexOfFirst { it.id == id }
        if (i > 0) layers.add(i - 1, layers.removeAt(i))
    }

    fun delete(id: Long) {
        layers.removeAll { it.id == id }
        if (selectedId == id) selectedId = null
        if (editingTextId == id) editingTextId = null
    }

    /** Ends typing: a text left empty is not kept. */
    fun finishEditing(text: String) {
        val id = editingTextId ?: return
        editingTextId = null
        val layer = layers.firstOrNull { it.id == id } as? TextLayer ?: return
        if (text.isBlank()) delete(id) else replace(layer.copy(text = text))
    }
}
