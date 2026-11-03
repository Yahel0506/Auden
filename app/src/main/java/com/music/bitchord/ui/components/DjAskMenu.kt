package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R

/**
 * What the glass menu off the DJ's button holds: a field to say what to hear, and, while the DJ is
 * playing one of its songs, the two ways to steer it and the way to stop it.
 *
 * Drawn inside an [IosMenuPopover], so it is the same material as the menus off the player's ⋯.
 * Sending hands the text to [onSend] and leaves the closing to the host: the player is already
 * turning into the DJ's by then, and the menu goes with the keyboard.
 */
@Composable
fun DjAskMenu(
    canSteer: Boolean,
    onSend: (String) -> Unit,
    onMore: () -> Unit,
    onLess: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = iosMenuColors()
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    // Up with the keyboard: this menu is only ever opened to type into.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val send = { text.trim().takeIf { it.isNotEmpty() }?.let(onSend) }

    Column {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.pressed)
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) {
                    Text(
                        stringResource(R.string.dj_request_hint),
                        color = colors.secondaryLabel,
                        fontSize = 15.sp,
                        maxLines = 2,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(MAX_REQUEST) },
                    textStyle = TextStyle(color = colors.label, fontSize = 15.sp),
                    cursorBrush = SolidColor(colors.label),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.focusRequester(focus),
                )
            }
            val ready = text.isNotBlank()
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (ready) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = ready,
                    ) { send() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.Send,
                    contentDescription = stringResource(R.string.dj_start),
                    tint = if (ready) MaterialTheme.colorScheme.onPrimary else colors.secondaryLabel,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            stringResource(R.string.dj_request_examples),
            color = colors.secondaryLabel,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 8.dp),
        )
        if (canSteer) {
            IosMenuSeparator(colors)
            IosMenuRow(label = stringResource(R.string.dj_more_like_this), icon = Icons.Rounded.ThumbUp, onClick = onMore)
            IosMenuRow(label = stringResource(R.string.dj_less_like_this), icon = Icons.Rounded.ThumbDown, onClick = onLess)
        }
        IosMenuSeparator(colors)
        IosMenuRow(
            label = stringResource(R.string.dj_stop),
            icon = Icons.Rounded.Close,
            onClick = onStop,
            labelColor = MaterialTheme.colorScheme.error,
            iconTint = MaterialTheme.colorScheme.error,
        )
    }
}

private const val MAX_REQUEST = 140
