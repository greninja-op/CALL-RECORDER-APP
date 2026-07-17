package com.personal.unifiedrecorder.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.personal.unifiedrecorder.R
import com.personal.unifiedrecorder.core.onboarding.OnboardingStateEvaluator
import com.personal.unifiedrecorder.ui.OnboardingCallbacks
import com.personal.unifiedrecorder.ui.OnboardingUiState
import com.personal.unifiedrecorder.ui.theme.UnifiedRecorderTheme

/**
 * Mandatory onboarding / configuration screen (Req 9). Presents the required
 * directory picker, accessibility-enable guidance, Restricted-Settings guidance
 * (Android 13+), settings deep-link buttons with an error slot, and a
 * setup-complete state. Visible state is driven by [OnboardingUiState] (mapped
 * from the core [OnboardingStateEvaluator]); all intents are hoisted through
 * [callbacks].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    callbacks: OnboardingCallbacks,
    modifier: Modifier = Modifier
) {
    // SAF directory picker: launches ACTION_OPEN_DOCUMENT_TREE and reports the
    // chosen tree URI back through the callback (Req 9.2).
    val directoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            callbacks.onDirectoryChosen(uri.toString())
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.onboarding_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (state.onboarding.setupComplete) {
                SetupCompleteCard(onContinue = callbacks.onContinue)
            }

            StorageStep(state = state, onPickDirectory = { directoryPicker.launch(null) })

            AccessibilityStep(
                state = state,
                onOpenAccessibilitySettings = callbacks.onOpenAccessibilitySettings
            )

            if (state.showRestrictedSettingsHelp) {
                RestrictedSettingsStep(onOpenAppInfo = callbacks.onOpenAppInfoSettings)
            }

            // Error slot shown when a settings screen cannot be opened (Req 9.7);
            // the instructions above remain visible.
            state.settingsErrorMessage?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageStep(state: OnboardingUiState, onPickDirectory: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.onboarding_storage_heading),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(stringResource(R.string.onboarding_storage_body))
            Button(onClick = onPickDirectory, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_pick_directory))
            }
            if (state.directoryBound) {
                Text(
                    text = stringResource(
                        R.string.onboarding_directory_bound,
                        state.boundDirectoryLabel.orEmpty()
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(
                    text = stringResource(R.string.onboarding_directory_not_bound),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun AccessibilityStep(state: OnboardingUiState, onOpenAccessibilitySettings: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.onboarding_accessibility_heading),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (state.accessibilityEnabled) {
                Text(
                    text = stringResource(R.string.onboarding_accessibility_enabled),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                // Step-by-step instructions shown while accessibility is off (Req 9.4).
                Text(stringResource(R.string.onboarding_accessibility_body))
                Text(stringResource(R.string.onboarding_accessibility_step_1))
                Text(stringResource(R.string.onboarding_accessibility_step_2))
                Text(stringResource(R.string.onboarding_accessibility_step_3))
                OutlinedButton(
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.onboarding_open_accessibility))
                }
            }
        }
    }
}

@Composable
private fun RestrictedSettingsStep(onOpenAppInfo: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.onboarding_restricted_heading),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(stringResource(R.string.onboarding_restricted_body))
            Text(stringResource(R.string.onboarding_restricted_step_1))
            Text(stringResource(R.string.onboarding_restricted_step_2))
            Text(stringResource(R.string.onboarding_restricted_step_3))
            OutlinedButton(onClick = onOpenAppInfo, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_open_app_info))
            }
        }
    }
}

@Composable
private fun SetupCompleteCard(onContinue: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.onboarding_complete_heading),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = stringResource(R.string.onboarding_complete_body),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_continue))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingScreenPreview() {
    UnifiedRecorderTheme {
        OnboardingScreen(
            state = OnboardingUiState(
                onboarding = OnboardingStateEvaluator.evaluate(
                    accessibilityEnabled = false,
                    directoryBound = false
                ),
                accessibilityEnabled = false,
                directoryBound = false,
                boundDirectoryLabel = null,
                showRestrictedSettingsHelp = true,
                settingsErrorMessage = null
            ),
            callbacks = OnboardingCallbacks()
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingCompletePreview() {
    UnifiedRecorderTheme {
        OnboardingScreen(
            state = OnboardingUiState(
                onboarding = OnboardingStateEvaluator.evaluate(
                    accessibilityEnabled = true,
                    directoryBound = true
                ),
                accessibilityEnabled = true,
                directoryBound = true,
                boundDirectoryLabel = "UnifiedCallRecorder",
                showRestrictedSettingsHelp = false,
                settingsErrorMessage = null
            ),
            callbacks = OnboardingCallbacks()
        )
    }
}
