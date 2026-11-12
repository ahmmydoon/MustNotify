package com.salahoard.mustnotify.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.salahoard.mustnotify.api.DeviceRef
import com.salahoard.mustnotify.data.IconRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    state: DeviceListUiState,
    monitored: Set<String>,
    icons: IconRepository,
    onSelect: (DeviceRef) -> Unit,
    onRetry: () -> Unit,
    onLogout: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your inverters") },
                actions = { TextButton(onClick = onLogout) { Text("Log out") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                is DeviceListUiState.Loading, is DeviceListUiState.LoginRequired ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                is DeviceListUiState.Error -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.message)
                    Button(onClick = onRetry) { Text("Try again") }
                }

                is DeviceListUiState.Loaded ->
                    if (state.devices.isEmpty()) {
                        Text("No devices found on this account.", Modifier.align(Alignment.Center).padding(24.dp))
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(state.devices, key = { it.sn }) { device ->
                                DeviceRow(device, device.sn in monitored, icons, onClick = { onSelect(device) })
                                HorizontalDivider()
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceRef, monitoring: Boolean, icons: IconRepository, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeviceIcon(device.imageUrl, icons, Modifier.size(48.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                device.pn,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            device.productName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (monitoring) {
            Text("Monitoring", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Loads the icon through the disk-backed [IconRepository]; shows a neutral placeholder while loading or on failure. */
@Composable
fun DeviceIcon(url: String?, icons: IconRepository, modifier: Modifier = Modifier) {
    val bitmap: State<ImageBitmap?> = produceState<ImageBitmap?>(initialValue = null, url) {
        value = url?.let { source ->
            icons.get(source)?.let { bytes ->
                withContext(Dispatchers.Default) { runCatching { bytes.decodeToImageBitmap() }.getOrNull() }
            }
        }
    }
    val image = bitmap.value
    if (image != null) {
        Image(bitmap = image, contentDescription = null, modifier = modifier)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)))
    }
}
