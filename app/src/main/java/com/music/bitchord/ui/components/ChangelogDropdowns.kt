package com.music.bitchord.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.music.bitchord.ui.icons.SfIcons

/** One module's part of a changelog: its heading and the Markdown under it. */
data class ChangelogSection(val title: String, val body: String) {
    /** How many entries (bullets) it holds, for the count beside its name. */
    val entries: Int get() = body.lineSequence().count { it.startsWith("- ") }
}

/** A changelog split at its `##` headings: the text before the first one, and one section per heading. */
class ParsedChangelog(val intro: String, val sections: List<ChangelogSection>)

/** Splits [notes] (Markdown with `##` module headings, as the changelog folder keeps it) into its sections. */
fun parseChangelog(notes: String): ParsedChangelog {
    val intro = StringBuilder()
    val sections = ArrayList<ChangelogSection>()
    var title: String? = null
    val body = StringBuilder()
    fun flush() {
        title?.let { sections += ChangelogSection(it, body.toString().trim()) }
        body.clear()
    }
    notes.lineSequence().forEach { line ->
        if (line.startsWith("## ")) {
            flush()
            title = line.removePrefix("## ").trim()
        } else if (title == null) {
            intro.appendLine(line)
        } else {
            body.appendLine(line)
        }
    }
    flush()
    return ParsedChangelog(intro.toString().trim(), sections)
}

/**
 * A changelog as dropdowns: one row per module of the app (Player, Library, Settings…), closed, and each opens only
 * when its own row is pressed — nothing is expanded for you. The few lines a release may carry before its first module
 * are shown as they are.
 */
@Composable
fun ChangelogDropdowns(
    notes: String,
    contentColor: Color,
    secondaryColor: Color,
    ruleColor: Color,
    modifier: Modifier = Modifier,
) {
    val parsed = remember(notes) { parseChangelog(notes) }
    // Which are open, kept through a rotation; none at first.
    var open by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    Column(modifier.fillMaxWidth()) {
        if (parsed.intro.isNotBlank()) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                RichText(style = RichTextStyle.Default) { Markdown(parsed.intro) }
            }
            Spacer(Modifier.height(10.dp))
        }
        parsed.sections.forEachIndexed { index, section ->
            val isOpen = section.title in open
            if (index > 0 || parsed.intro.isNotBlank()) Box(Modifier.fillMaxWidth().height(0.6.dp).background(ruleColor))
            ChangelogDropdown(
                section = section,
                open = isOpen,
                onToggle = { open = ArrayList(if (isOpen) open - section.title else open + section.title) },
                contentColor = contentColor,
                secondaryColor = secondaryColor,
            )
        }
    }
}

@Composable
private fun ChangelogDropdown(
    section: ChangelogSection,
    open: Boolean,
    onToggle: () -> Unit,
    contentColor: Color,
    secondaryColor: Color,
) {
    val turn by animateFloatAsState(if (open) 90f else 0f, spring(dampingRatio = 1f, stiffness = 300f), label = "dropdown chevron")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(section.title, color = contentColor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (section.entries > 0) {
                Text(section.entries.toString(), color = secondaryColor, fontSize = 15.sp)
                Spacer(Modifier.width(10.dp))
            }
            Icon(
                SfIcons.ChevronRight,
                contentDescription = null,
                tint = secondaryColor,
                modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = turn },
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = IntSize.VisibilityThreshold)) + fadeIn(spring(stiffness = 300f)),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = IntSize.VisibilityThreshold)) + fadeOut(spring(stiffness = 300f)),
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CompositionLocalProvider(LocalContentColor provides contentColor) {
                    RichText(style = RichTextStyle.Default) { Markdown(section.body) }
                }
            }
        }
    }
}
