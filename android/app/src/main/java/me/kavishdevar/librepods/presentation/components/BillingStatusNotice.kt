package me.kavishdevar.librepods.presentation.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import me.kavishdevar.librepods.R

@Composable
fun BillingStatusNotice(ready: Boolean, failed: Boolean, onRetry: () -> Unit) {
    if (ready && !failed) return
    Text(stringResource(if (failed) R.string.purchase_status_unavailable else R.string.purchase_status_loading),
        style = MaterialTheme.typography.bodyMedium)
    if (failed) {
        Spacer(Modifier.height(8.dp))
        StyledButton(onClick = onRetry, backdrop = rememberLayerBackdrop()) {
            Text(stringResource(R.string.retry_purchase_status))
        }
    }
    Spacer(Modifier.height(16.dp))
}
