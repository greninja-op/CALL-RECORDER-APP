package com.personal.unifiedrecorder.ui.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.personal.unifiedrecorder.R
import com.personal.unifiedrecorder.core.model.PermissionDisplay
import com.personal.unifiedrecorder.core.model.RuntimePermission
import com.personal.unifiedrecorder.core.policy.PermissionStateMapper
import com.personal.unifiedrecorder.ui.PermissionCallbacks
import com.personal.unifiedrecorder.ui.PermissionUiState
import com.personal.unifiedrecorder.ui.theme.UnifiedRecorderTheme

/**
 * Runtime-permission status screen (Req 8). Renders each required permission's
 * GRANTED / NOT_GRANTED state from [PermissionStateMapper.PermissionState],
 * offers a request button, surfaces the denied messages, and shows the
 * settings directive when a permission is permanently denied. Stateless: all
 * intents are hoisted through [callbacks].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(
    state: PermissionUiState,
    callbacks: PermissionCallbacks,
    modifier: Modifier = Modifier
) {
    val permissionState = state.permissionState
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.permission_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.permission_intro))

            // Complete binary status map, one row per required permission (Req 8.5).
            permissionState.required
                .sortedBy { it.ordinal }
                .forEach { permission ->
                    PermissionRow(
                        permission = permission,
                        display = permissionState.statusMap[permission]
                            ?: PermissionDisplay.NOT_GRANTED
                    )
                }

            // Per-permission denied messaging naming each denied permission (Req 8.3).
            if (permissionState.deniedMessages.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.permission_denied_heading),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        permissionState.deniedMessages.forEach { message ->
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // Settings directive when at least one permission is permanently denied (Req 8.4).
            if (permissionState.settingsDirective) {
                Card {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(stringResource(R.string.permission_settings_directive))
                        OutlinedButton(
                            onClick = callbacks.onOpenSettings,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.permission_open_settings))
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            if (permissionState.requestSet.isNotEmpty()) {
                Button(
                    onClick = callbacks.onRequestPermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.permission_request))
                }
            } else {
                Button(
                    onClick = callbacks.onContinue,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.permission_continue))
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(permission: RuntimePermission, display: PermissionDisplay) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(permissionLabelRes(permission)),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            val granted = display == PermissionDisplay.GRANTED
            Text(
                text = stringResource(
                    if (granted) R.string.permission_granted else R.string.permission_not_granted
                ),
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun permissionLabelRes(permission: RuntimePermission): Int = when (permission) {
    RuntimePermission.RECORD_AUDIO -> R.string.permission_name_record_audio
    RuntimePermission.READ_PHONE_STATE -> R.string.permission_name_read_phone_state
    RuntimePermission.MODIFY_AUDIO_SETTINGS -> R.string.permission_name_modify_audio_settings
    RuntimePermission.POST_NOTIFICATIONS -> R.string.permission_name_post_notifications
}

@Preview(showBackground = true)
@Composable
private fun PermissionScreenPreview() {
    UnifiedRecorderTheme {
        PermissionScreen(
            state = PermissionUiState(
                permissionState = PermissionStateMapper.map(
                    apiLevel = 34,
                    granted = setOf(RuntimePermission.RECORD_AUDIO),
                    permanentlyDenied = setOf(RuntimePermission.READ_PHONE_STATE)
                )
            ),
            callbacks = PermissionCallbacks()
        )
    }
}
