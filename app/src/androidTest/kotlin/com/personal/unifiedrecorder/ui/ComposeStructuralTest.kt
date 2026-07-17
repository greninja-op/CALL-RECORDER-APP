package com.personal.unifiedrecorder.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.unifiedrecorder.core.model.CallTypeFilter
import com.personal.unifiedrecorder.core.model.DashboardFilterState
import com.personal.unifiedrecorder.core.model.DirectionFilter
import com.personal.unifiedrecorder.core.model.RecordingEntry
import com.personal.unifiedrecorder.core.onboarding.OnboardingStateEvaluator
import com.personal.unifiedrecorder.core.playback.PlaybackState
import com.personal.unifiedrecorder.ui.consent.ConsentDetailsDialog
import com.personal.unifiedrecorder.ui.dashboard.DashboardScreen
import com.personal.unifiedrecorder.ui.onboarding.OnboardingScreen
import com.personal.unifiedrecorder.ui.theme.UnifiedRecorderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI structural tests (Task 24.1). These assert the presence and wiring
 * of the UI surfaces called out in the design's "Compose UI tests" section:
 *
 * - Dashboard filter/search controls exist and are wired (Req 7.2).
 * - The empty-state message renders for an empty filtered list (Req 7.9).
 * - The Onboarding screen exposes the directory-picker control (Req 9.2).
 * - The Dashboard consent control shows full content after acknowledgment (Req 10.7).
 *
 * They are compiled as part of the build but require a device/emulator to run
 * (they exercise the Compose runtime and the app's resources). They are not
 * executed in the JVM-only test pass.
 */
@RunWith(AndroidJUnit4::class)
class ComposeStructuralTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun emptyDashboardState(
        filterState: DashboardFilterState = DashboardFilterState()
    ): DashboardUiState = DashboardUiState(
        allEntries = emptyList(),
        filterState = filterState,
        playback = PlaybackState()
    )

    // Req 7.2: filter and search controls exist and are wired to their callbacks.
    @Test
    fun dashboard_filterAndSearchControls_existAndAreWired() {
        var searchChanged: String? = null
        var callTypeChanged: CallTypeFilter? = null
        var directionChanged: DirectionFilter? = null

        composeTestRule.setContent {
            UnifiedRecorderTheme {
                DashboardScreen(
                    state = emptyDashboardState(),
                    callbacks = DashboardCallbacks(
                        onSearchChange = { searchChanged = it },
                        onCallTypeFilterChange = { callTypeChanged = it },
                        onDirectionFilterChange = { directionChanged = it }
                    )
                )
            }
        }

        // Controls are present.
        composeTestRule.onNodeWithText("Search name or number").assertIsDisplayed()
        composeTestRule.onNodeWithText("WhatsApp").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cellular").assertIsDisplayed()
        composeTestRule.onNodeWithText("Incoming").assertIsDisplayed()
        composeTestRule.onNodeWithText("Outgoing").assertIsDisplayed()

        // Controls are wired: interacting forwards intents through the callbacks.
        composeTestRule.onNodeWithText("Search name or number").performTextInput("Alice")
        assertEquals("Alice", searchChanged)

        composeTestRule.onNodeWithText("WhatsApp").performClick()
        assertEquals(CallTypeFilter.WHATSAPP, callTypeChanged)

        composeTestRule.onNodeWithText("Incoming").performClick()
        assertEquals(DirectionFilter.INCOMING, directionChanged)
    }

    // Req 7.9: empty-state message renders when the active filter excludes everything.
    @Test
    fun dashboard_emptyFilteredList_showsEmptyStateMessage() {
        val entry = RecordingEntry(
            fileName = "arbitrary_recording.wav",
            parsed = null,
            metadata = null
        )
        composeTestRule.setContent {
            UnifiedRecorderTheme {
                DashboardScreen(
                    state = DashboardUiState(
                        allEntries = listOf(entry),
                        // Search text that cannot match the single entry -> empty result.
                        filterState = DashboardFilterState(searchText = "no-such-match"),
                        playback = PlaybackState()
                    ),
                    callbacks = DashboardCallbacks()
                )
            }
        }

        composeTestRule
            .onNodeWithText("No recordings match the current filters.")
            .assertIsDisplayed()
    }

    // Req 9.2: onboarding exposes the SAF directory-picker control.
    @Test
    fun onboarding_exposesDirectoryPickerControl() {
        composeTestRule.setContent {
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
                        showRestrictedSettingsHelp = false,
                        settingsErrorMessage = null
                    ),
                    callbacks = OnboardingCallbacks()
                )
            }
        }

        // The picker control launches ACTION_OPEN_DOCUMENT_TREE via
        // rememberLauncherForActivityResult(OpenDocumentTree()); here we assert
        // the control is present and interactive.
        composeTestRule.onNodeWithText("Choose storage folder").assertIsDisplayed()
        composeTestRule.onNodeWithText("Choose storage folder").performClick()
    }

    // Req 10.7: the dashboard consent control reveals the full notice content.
    @Test
    fun dashboard_consentControl_showsFullContentAfterAcknowledgment() {
        composeTestRule.setContent {
            var showFullNotice by remember { mutableStateOf(false) }
            UnifiedRecorderTheme {
                // Mirrors UnifiedRecorderApp's dashboard wiring for the consent control.
                DashboardScreen(
                    state = emptyDashboardState(),
                    callbacks = DashboardCallbacks(onShowConsent = { showFullNotice = true })
                )
                if (showFullNotice) {
                    ConsentDetailsDialog(onDismiss = { showFullNotice = false })
                }
            }
        }

        // Before activating the control the full notice body is not present.
        composeTestRule
            .onAllNodesWithText("Call recording consent laws", substring = true)
            .assertCountEquals(0)

        // Activate the dashboard consent control.
        composeTestRule.onNodeWithText("Legal notice").performClick()

        // Full Consent_Notice content is now displayed.
        composeTestRule
            .onNodeWithText("Call recording consent laws", substring = true)
            .assertIsDisplayed()
    }
}
