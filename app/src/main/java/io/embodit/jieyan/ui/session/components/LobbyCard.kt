package io.embodit.jieyan.ui.session.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
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

/** The lobby card is 640 x 448 dp (v3 C11), shown at 0.56 x 0.39 m, 1.2 m straight ahead. */
internal val LobbyCardWidth = 640.dp
internal val LobbyCardHeight = 448.dp

/**
 * S1 / S2 / S2m / S2s / S1u (design v3 §3 "配对卡改版"): title row with a hollow waiting dot; 「电脑浏览器
 * 打开」 + the address without `http://` | the 4-digit PIN; two permission rows (camera, microphone) that
 * turn to [授权] when denied and to [去设置] after "don't ask again"; a divider and the two ways in when
 * the company Wi-Fi isolates clients (① a phone hotspot, ② USB + `adb forward`).
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // waiting: a hollow dot in labelSecondary (design semantic "等待")
                Box(modifier = Modifier.size(12.dp).border(2.dp, scheme.labelSecondary, CircleShape).probe("wait_dot"))
                Text(text = stringResource(R.string.lobby_title), color = scheme.labelPrimary, style = JieyanType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = stringResource(R.string.lobby_open_in_browser), color = scheme.labelSecondary, style = JieyanType.body, maxLines = 1)
                if (!state.httpsOn) {
                    Text(text = stringResource(R.string.lobby_usb_only), color = scheme.labelSecondary, style = JieyanType.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("usb_only"))
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.address ?: stringResource(R.string.lobby_address_unknown),
                        color = if (state.address != null) scheme.labelPrimary else scheme.alert,
                        style = JieyanType.address,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).probe("address"),
                    )
                    Text(text = stringResource(R.string.lobby_pin_label), color = scheme.labelSecondary, style = JieyanType.body, modifier = Modifier.padding(end = 12.dp))
                    Text(text = state.pin, color = scheme.labelPrimary, style = JieyanType.pin, maxLines = 1, modifier = Modifier.probe("pin"))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                PermissionRow(
                    name = "perm",
                    granted = state.permissionGranted,
                    needsSettings = state.cameraNeedsSettings,
                    grantedText = stringResource(R.string.lobby_perm_granted),
                    deniedText = stringResource(R.string.lobby_perm_denied),
                    deniedColor = scheme.error,
                    modifier = Modifier.weight(1f),
                ) { onEvent(SessionEvent.GrantClicked) }
                PermissionRow(
                    name = "mic_perm",
                    granted = state.micGranted,
                    needsSettings = state.micNeedsSettings,
                    grantedText = stringResource(R.string.lobby_mic_granted),
                    deniedText = stringResource(R.string.lobby_mic_denied),
                    deniedColor = scheme.alert,
                    modifier = Modifier.weight(1f),
                ) { onEvent(SessionEvent.MicGrantClicked) }
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(scheme.fillTertiary))
            if (state.pinLocked) {
                Text(text = stringResource(R.string.lobby_locked), color = scheme.error, style = JieyanType.body, maxLines = 1, modifier = Modifier.probe("hint"))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.probe("hint")) {
                    Text(text = stringResource(R.string.lobby_help_title), color = scheme.labelSecondary, style = JieyanType.body, maxLines = 1)
                    Text(text = stringResource(R.string.lobby_help_hotspot), color = scheme.labelPrimary, style = JieyanType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(text = stringResource(R.string.lobby_help_usb), color = scheme.labelPrimary, style = JieyanType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 18.dp)) {
                        Text(
                            text = stringResource(R.string.lobby_help_usb_cmd),
                            color = scheme.labelPrimary,
                            style = JieyanType.mono,
                            maxLines = 1,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(scheme.fillTertiary).padding(horizontal = 8.dp, vertical = 3.dp).probe("adb_cmd"),
                        )
                        Text(text = stringResource(R.string.lobby_help_usb_then), color = scheme.labelPrimary, style = JieyanType.body, maxLines = 1)
                    }
                }
            }
        }
    }
}

private val GrantButtonSize = ButtonDefaults.Small.copy(height = 32.dp, minWidth = 64.dp)

/** One permission: a passable dot + 「… · 已授权」, or a tinted pill with the reason and [授权] / [去设置]. */
@Composable
private fun PermissionRow(
    name: String,
    granted: Boolean,
    needsSettings: Boolean,
    grantedText: String,
    deniedText: String,
    deniedColor: Color,
    modifier: Modifier,
    onGrant: () -> Unit,
) {
    val scheme = PicoTheme.colorScheme
    if (granted) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.height(40.dp).probe(name)) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(scheme.passable))
            Text(text = grantedText, color = scheme.labelPrimary, style = JieyanType.label, maxLines = 1)
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier =
                modifier
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(deniedColor.copy(alpha = 0.18f))
                    .border(1.dp, deniedColor, RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp)
                    .probe(name),
        ) {
            Text(text = deniedText, color = deniedColor, style = JieyanType.label, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Button(
                onClick = onGrant,
                modifier = Modifier.probe(if (name == "perm") "grant" else "mic_grant"),
                size = GrantButtonSize,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = scheme.interaction, contentColor = JieyanColors.InkOnChalk),
            ) {
                Text(text = stringResource(if (needsSettings) R.string.lobby_settings else R.string.lobby_grant), color = JieyanColors.InkOnChalk, style = JieyanType.label)
            }
        }
    }
}
