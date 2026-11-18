package com.music.bitchord.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.auth.GoogleAccountSession
import com.music.bitchord.auth.YouTubeProfile
import com.music.bitchord.ui.icons.SfIcons
import dev.chrisbanes.haze.HazeState

/**
 * Which of the signed-in accounts' profiles is in use, as one of the app's alerts ([AlertScaffold]): each account named
 * over its profiles as rows of the card, the one in use ticked, and the answers as capsules — adding an account,
 * managing them, closing.
 */
@Composable
fun ProfileSelectorAlert(
    accounts: List<GoogleAccountSession>,
    activeAccountId: String?,
    activeProfileId: String?,
    hazeState: HazeState,
    onSelect: (GoogleAccountSession, YouTubeProfile) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (GoogleAccountSession) -> Unit,
    onDismiss: () -> Unit,
) {
    var managing by remember { mutableStateOf(false) }
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss, width = 300.dp) {
        val close = animatedExit(onDismiss)
        Text(
            stringResource(R.string.switch_account),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            accounts.forEach { account ->
                AlertRule()
                Text(
                    account.email.ifBlank { account.name.ifBlank { stringResource(R.string.accounts) } },
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                account.profiles.forEach { profile ->
                    ProfileRow(
                        profile = profile,
                        selected = account.accountId == activeAccountId && profile.profileId == activeProfileId,
                        managing = managing,
                        onClick = {
                            onSelect(account, profile)
                            close()
                        },
                        onRemove = { onRemoveAccount(account) },
                    )
                }
            }
        }
        AlertGap()
        AlertAction(label = stringResource(R.string.add_account), emphasised = false, onClick = onAddAccount)
        AlertAction(
            label = stringResource(if (managing) R.string.done else R.string.manage_accounts),
            emphasised = false,
            onClick = { managing = !managing },
        )
        AlertAction(label = stringResource(R.string.done), emphasised = true, onClick = close)
    }
}

@Composable
private fun ProfileRow(
    profile: YouTubeProfile,
    selected: Boolean,
    managing: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val description = stringResource(if (selected) R.string.selected_account else R.string.switch_account)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.RadioButton, onClick = if (managing) onRemove else onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (profile.avatar != null) {
            AsyncImage(profile.avatar, null, Modifier.size(38.dp).clip(CircleShape))
        } else {
            Icon(SfIcons.AccountPlaceholder, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
            Text(
                profile.handle.ifBlank { stringResource(if (profile.isBrandAccount) R.string.brand_account else R.string.personal) },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected && !managing) {
            Icon(Icons.Rounded.Check, stringResource(R.string.selected_account), tint = accentColor())
        }
        if (managing) Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error)
    }
}
