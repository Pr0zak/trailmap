package com.trailmap.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmap.data.MyVitalsOffer
import com.trailmap.data.PastedKey

/**
 * Connect trailmap to a myvitals server, and keep it synced. Reached from the Rides tab and
 * the map's Layers menu, and opened by itself when the myvitals app sends its connection.
 */
@Composable
fun MyVitalsScreen(vm: TrailsViewModel, onBack: () -> Unit) {
    val ui by vm.state.collectAsStateWithLifecycle()
    // Going back is a "no" to whatever the myvitals app sent, so it isn't still waiting here
    // next time. The system back gesture counts too.
    val leave = {
        vm.dismissMyVitalsOffer()
        onBack()
    }
    BackHandler(enabled = ui.myVitalsOffer != null, onBack = leave)
    MyVitalsContent(
        ui = ui,
        onBack = leave,
        onConnect = vm::connectMyVitals,
        onSync = vm::syncMyVitals,
        onSetAutoSync = vm::setMyVitalsAutoSync,
        onDisconnect = vm::disconnectMyVitals,
        onAcceptOffer = vm::acceptMyVitalsOffer,
        onDismissOffer = vm::dismissMyVitalsOffer,
    )
}

/** Stateless body of [MyVitalsScreen], so it can be rendered with sample state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MyVitalsContent(
    ui: TrailsUiState,
    onBack: () -> Unit,
    onConnect: (String, String) -> Unit,
    onSync: () -> Unit,
    onSetAutoSync: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    onAcceptOffer: () -> Unit,
    onDismissOffer: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("myvitals", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "trailmap reads the rides and walks you've recorded, and local trail conditions, from " +
                    "your own myvitals server. Nothing is written back or sent anywhere else, and the " +
                    "tracks are kept on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                // Opened by a handoff at a cold start, the screen is up before the saved
                // connection has been read; the form would flash, and fill from nothing.
                !ui.myVitals.settingsLoaded -> Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
                ui.myVitals.connected -> {
                    ui.myVitalsOffer?.let { offer ->
                        if (ui.myVitalsOfferCurrent) {
                            AlreadyConnectedNote(offer, onDismissOffer)
                        } else {
                            OfferCard(offer, busy = ui.myVitals.connecting, error = ui.myVitalsOfferError, onAcceptOffer, onDismissOffer)
                        }
                    }
                    Connected(ui, onSync, onSetAutoSync, onDisconnect)
                }
                else -> ConnectForm(ui, onConnect)
            }
        }
    }
}

@Composable
private fun ConnectForm(ui: TrailsUiState, onConnect: (String, String) -> Unit) {
    val offer = ui.myVitalsOffer
    var url by rememberSaveable { mutableStateOf(offer?.url ?: ui.myVitals.url) }
    // Not rememberSaveable: saved state is handed to the system, and the key stays in the app.
    // Recreated, the field starts from the offer's key again, the only copy there is.
    var token by remember { mutableStateOf(offer?.token.orEmpty()) }
    var reveal by rememberSaveable { mutableStateOf(false) }
    // Why the last paste went nowhere; typing clears it.
    var pasteProblem by remember { mutableStateOf<String?>(null) }
    // The offer whose address and key are in the fields: only its id, never the key. Saved, so
    // an activity recreated by a font or language change doesn't lay the offer over whatever
    // the user has typed since, while a new offer (even an identical re-send) still fills in.
    var appliedOfferId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(offer?.id) {
        if (offer != null && offer.id != appliedOfferId) {
            url = offer.url
            token = offer.token
            pasteProblem = null
            appliedOfferId = offer.id
        }
    }
    val clipboard = LocalClipboardManager.current
    val busy = ui.myVitals.connecting
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (offer != null) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Filled.Link, contentDescription = null,
                        Modifier.padding(top = 2.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Filled in from the myvitals app",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        OfferHost(offer)
                        Text(
                            "Check that's your server, then tap Connect.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            singleLine = true,
            label = { Text("Server address") },
            placeholder = { Text("http://myvitals.local:8000") },
            supportingText = { Text("The backend's address, or the dashboard's — either works.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = {
                token = it
                pasteProblem = null
            },
            singleLine = true,
            label = { Text("Access key") },
            isError = pasteProblem != null,
            supportingText = {
                Text(
                    pasteProblem
                        ?: ("The Access key from the myvitals app (Settings → Connection & sync). There, " +
                            "“Send to trailmap” fills this in for you."),
                )
            },
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                Row {
                    // A copied key usually carries a stray newline or space at the ends, which
                    // the server won't take; those are trimmed. Anything else wrong with it is
                    // said under the field, and the field is left as it was.
                    IconButton(
                        onClick = {
                            when (val pasted = PastedKey.of(clipboard.getText()?.text)) {
                                is PastedKey.Key -> {
                                    token = pasted.key
                                    pasteProblem = null
                                }
                                is PastedKey.Rejected -> pasteProblem = pasted.message
                            }
                        },
                        enabled = !busy,
                    ) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "Paste access key")
                    }
                    IconButton(onClick = { reveal = !reveal }) {
                        Icon(
                            if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (reveal) "Hide access key" else "Show access key",
                        )
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        ui.myVitals.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = { onConnect(url, token) },
            enabled = !busy && url.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
                Text("Connecting…")
            } else {
                Text("Connect")
            }
        }
        Text(
            "The server has to be reachable from the phone: on the same network, or over a VPN such " +
                "as Tailscale when you're out.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The host an offer points at, large: that is what the user has to recognise before trusting it.
 * The full address follows, smaller, when it says more than the host does.
 */
@Composable
private fun OfferHost(offer: MyVitalsOffer) {
    val host = offer.host ?: offer.url
    Text(
        host,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    if (offer.url != host) {
        Text(offer.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/**
 * The myvitals app sent a connection while one is already working. Nothing changes until "Use
 * this", and then only if the new one connects; if it doesn't, why is said here, not under
 * the working connection.
 */
@Composable
private fun OfferCard(offer: MyVitalsOffer, busy: Boolean, error: String?, onAccept: () -> Unit, onDismiss: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp)) {
            Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.Link, contentDescription = null,
                    Modifier.padding(top = 2.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "The myvitals app sent a connection to",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    OfferHost(offer)
                    Text(
                        "The one below stays unless this one connects.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            Row(Modifier.align(Alignment.End).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Dismiss") }
                Spacer(Modifier.width(4.dp))
                Button(onClick = onAccept, enabled = !busy) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Connecting…")
                    } else {
                        Text(if (error != null) "Try again" else "Use this")
                    }
                }
            }
        }
    }
}

/** The myvitals app sent the connection trailmap already uses: nothing to switch, just say so. */
@Composable
private fun AlreadyConnectedNote(offer: MyVitalsOffer, onDismiss: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                "Already connected to ${offer.host ?: offer.url}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun Connected(
    ui: TrailsUiState,
    onSync: () -> Unit,
    onSetAutoSync: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
) {
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    val v = ui.myVitals
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Connected", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(v.url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val rides = ui.recorded.count { it.kind.ride }
            val foot = ui.recorded.count { it.kind.onFoot }
            Text(
                "$rides ${if (rides == 1) "ride" else "rides"} · $foot on foot · " +
                    "${ui.conditions.size} trail ${if (ui.conditions.size == 1) "system" else "systems"} with conditions",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (v.syncing) "Syncing…" else "Synced ${ago(v.lastSync)}" +
                    (if (v.lastConditionsSync > 0) " · conditions ${ago(v.lastConditionsSync)}" else ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            v.error?.let {
                Text(
                    "Last try failed: $it What's on the phone is still shown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.size(8.dp))
            Button(onClick = onSync, enabled = !v.syncing) {
                if (v.syncing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Filled.Sync, contentDescription = null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("Sync now")
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Sync automatically", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (v.autoSync) {
                    "When trailmap opens, at most once an hour, and trail conditions every 15 minutes " +
                        "while it's open."
                } else {
                    "Only when you tap Sync now."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = v.autoSync, onCheckedChange = onSetAutoSync)
    }
    HorizontalDivider()
    OutlinedButton(
        onClick = { confirmDisconnect = true },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Disconnect") }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect myvitals?") },
            text = {
                Text(
                    "This removes the server address, the access key and the ${ui.recorded.size} activities " +
                        "stored on this phone. Nothing on your server changes.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    onDisconnect()
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
        )
    }
}
