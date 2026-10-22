package io.embodit.jieyan.ui.session.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.jieyan.R
import io.embodit.jieyan.ui.session.SessionEvent
import io.embodit.jieyan.ui.session.SessionUiState
import io.embodit.jieyan.ui.session.debug.probe
import io.embodit.jieyan.ui.theme.JieyanColors
import io.embodit.jieyan.ui.theme.JieyanType

/** The lobby card is 640 x 384 dp, shown at 0.56 x 0.34 m, 1.2 m straight ahead. */
internal val LobbyCardWidth = 640.dp
internal val LobbyCardHeight = 384.dp

/**
 * S1 / S2 (design-doc §3 "配对卡"): title row with a hollow waiting dot, the address, the 4-digit PIN,
 * the camera permission row and one line of help. With the permission denied the row turns to the
 * error colour and offers 授权.
 *
 * [state] is null while the panel is parked out of view: nothing is drawn then.
 */
@Composable
internal fun LobbyCard(state: SessionUiState?, onEvent: (SessionEvent) -> Unit) {
    Box(modifier = Modifier.size(LobbyCardWidth, LobbyCardHeight)) {
        if (state == null) return@Box
        val scheme = PicoTheme.colorScheme
        Column(
            modifier =
                Modifier
                    .size(LobbyCardWidth, LobbyCardHeight)
                    .clip(RoundedCornerShape(32.dp))
                    .backgroundMaterial(enable = true, style = Material.Regular)
                    .then(glassRefresh())
                    .probe("lobby")
                    .padding(horizontal = 36.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // waiting: a hollow dot in labelSecondary (design semantic "等待")
                Box(modifier = Modifier.size(12.dp).border(2.dp, scheme.labelSecondary, CircleShape).probe("wait_dot"))
                Text(text = stringResource(R.string.lobby_title), color = scheme.labelPrimary, style = JieyanType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                Text(
                    text = state.address ?: stringResource(R.string.lobby_address_unknown),
                    color = if (state.address != null) scheme.labelPrimary else scheme.alert,
                    style = JieyanType.address,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.probe("address"),
                )
                Text(text = state.pin, color = scheme.labelPrimary, style = JieyanType.pin, maxLines = 1, modifier = Modifier.probe("pin"))
            }
            PermissionRow(state, onEvent)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (state.pinLocked) stringResource(R.string.lobby_locked) else stringResource(R.string.lobby_hint),
                color = if (state.pinLocked) scheme.error else scheme.labelSecondary,
                style = JieyanType.body,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.probe("hint"),
            )
        }
    }
}

private val GrantButtonSize = ButtonDefaults.Small.copy(height = 36.dp, minWidth = 72.dp)

@Composable
private fun PermissionRow(state: SessionUiState, onEvent: (SessionEvent) -> Unit) {
    val scheme = PicoTheme.colorScheme
    if (state.permissionGranted) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(40.dp).probe("perm")) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(scheme.passable))
            Text(text = stringResource(R.string.lobby_perm_granted), color = scheme.labelPrimary, style = JieyanType.body, maxLines = 1)
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.error.copy(alpha = 0.18f))
                    .border(1.dp, scheme.error, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .probe("perm"),
        ) {
            Text(text = stringResource(R.string.lobby_perm_denied), color = scheme.error, style = JieyanType.body, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Button(
                onClick = { onEvent(SessionEvent.GrantClicked) },
                modifier = Modifier.probe("grant"),
                size = GrantButtonSize,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = scheme.interaction, contentColor = JieyanColors.InkOnChalk),
            ) {
                Text(text = stringResource(R.string.lobby_grant), color = JieyanColors.InkOnChalk, style = JieyanType.body)
            }
        }
    }
    Spacer(modifier = Modifier.height(0.dp))
}
