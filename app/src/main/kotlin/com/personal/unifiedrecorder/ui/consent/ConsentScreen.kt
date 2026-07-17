package com.personal.unifiedrecorder.ui.consent

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.personal.unifiedrecorder.R
import com.personal.unifiedrecorder.ui.ConsentCallbacks
import com.personal.unifiedrecorder.ui.theme.UnifiedRecorderTheme

/**
 * Consent / legal disclaimer screen (Req 10.1). Shows the Consent_Notice text
 * stating that recording-consent laws vary by jurisdiction and are the user's
 * responsibility, and keeps it visible until the user acts on it via
 * Acknowledge or Dismiss. Stateless: all state is hoisted through [callbacks].
 */
@Composable
fun ConsentScreen(
    callbacks: ConsentCallbacks,
    modifier: Modifier = Modifier
) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.consent_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(16.dp))
            Card {
                Text(
                    text = stringResource(R.string.consent_body),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = callbacks.onAcknowledge,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.consent_acknowledge))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = callbacks.onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.consent_dismiss))
            }
        }
    }
}

/**
 * Full Consent_Notice content surfaced from the dashboard control after
 * acknowledgment (Req 10.7). Rendered as a dismissible dialog.
 */
@Composable
fun ConsentDetailsDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.consent_view_full)) },
        text = { Text(stringResource(R.string.consent_body)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.consent_close))
            }
        }
    )
}

@Preview(showBackground = true)
@Composable
private fun ConsentScreenPreview() {
    UnifiedRecorderTheme {
        ConsentScreen(callbacks = ConsentCallbacks())
    }
}
