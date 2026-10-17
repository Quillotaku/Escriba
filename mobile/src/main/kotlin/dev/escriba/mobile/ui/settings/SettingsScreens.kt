package dev.escriba.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.escriba.mobile.R
import dev.escriba.datalayer.PairingFailure
import dev.escriba.datalayer.PairingWait
import androidx.annotation.StringRes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.escriba.datalayer.AppLog
import dev.escriba.datalayer.PairingState
import dev.escriba.datalayer.Peer
import dev.escriba.mobile.EscribaApp
import dev.escriba.mobile.ui.EnrolledVoicesCard
import dev.escriba.mobile.ui.components.EsCard
import dev.escriba.mobile.ui.components.EscribaIcons
import dev.escriba.mobile.ui.components.Overline
import dev.escriba.mobile.ui.components.SettingRow
import dev.escriba.mobile.ui.components.StatusChip
import dev.escriba.mobile.ui.components.Tone
import dev.escriba.mobile.ui.theme.Escriba
import dev.escriba.mobile.ui.theme.Space
import dev.escriba.mobile.ui.today.HM
import dev.escriba.mobile.ui.today.local
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

/** What only the activity can do (open system settings). */
class SystemActions(
    val isBatteryExempt: () -> Boolean,
    val requestBatteryExemption: () -> Unit,
    val openAppSettings: () -> Unit,
)

enum class SettingsPage { WATCH, VAULT, VOICES, MODELS, BENCHMARK, STORAGE, BATTERY, LOG }

/** Scrolling column with the system's margins. */
@Composable
fun Page(padding: PaddingValues, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = Space.s4, end = Space.s4, top = padding.calculateTopPadding() + Space.s2, bottom = padding.calculateBottomPadding() + Space.s6),
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) { content() }
}

@Composable
fun SettingsScreen(app: EscribaApp, padding: PaddingValues, open: (SettingsPage) -> Unit) {
    val c = Escriba.colors
    val dark by app.appearance.dark.collectAsStateWithLifecycle()
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val peer by app.link.keys.peer.collectAsStateWithLifecycle()
    val folder by app.models.folder.collectAsStateWithLifecycle()
    val voices by app.db.sessions().observeVoices().collectAsStateWithLifecycle(emptyList())

    Page(padding) {
        Overline(stringResource(R.string.appearance))
        EsCard {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val isDark = dark ?: systemDark
                listOf(false to R.string.light, true to R.string.dark).forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = isDark == value,
                        onClick = { app.appearance.set(value) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(stringResource(label)) }
                }
            }
        }
        Overline("Escriba", Modifier.padding(top = Space.s3))
        EsCard(Modifier) {
            val rows = listOf(
                Triple(EscribaIcons.Watch, stringResource(R.string.settings_watch), peer?.let { stringResource(R.string.paired_with, it.name) } ?: stringResource(R.string.watch_not_paired)) to SettingsPage.WATCH,
                Triple(Icons.Rounded.Edit, stringResource(R.string.settings_vault), app.vault.displayPath() ?: stringResource(R.string.not_chosen)) to SettingsPage.VAULT,
                Triple(EscribaIcons.People, stringResource(R.string.settings_voices), if (voices.isEmpty()) stringResource(R.string.none) else voices.joinToString { it.name }) to SettingsPage.VOICES,
                Triple(
                    EscribaIcons.Waveform,
                    stringResource(R.string.settings_models),
                    if (folder == null) stringResource(R.string.no_models_folder)
                    else pluralStringResource(R.plurals.models_count, app.models.models.value.size, app.models.models.value.size),
                ) to SettingsPage.MODELS,
                Triple(Icons.Rounded.Delete, stringResource(R.string.settings_storage), stringResource(R.string.settings_storage_hint)) to SettingsPage.STORAGE,
                Triple(EscribaIcons.Battery, stringResource(R.string.settings_battery), stringResource(R.string.settings_battery_hint)) to SettingsPage.BATTERY,
                Triple(EscribaIcons.Notes, stringResource(R.string.settings_log), stringResource(R.string.settings_log_hint)) to SettingsPage.LOG,
            )
            rows.forEachIndexed { i, (row, page) ->
                if (i > 0) HorizontalDivider(color = c.rule)
                SettingRow(row.first, row.second, row.third, onClick = { open(page) })
            }
        }
        EsCard {
            SettingRow(Icons.Rounded.PlayArrow, stringResource(R.string.tour), stringResource(R.string.tour_hint), onClick = { app.onboarding.restart() })
        }
    }
}

// --- Voices ------------------------------------------------------------------

@Composable
fun VoicesPage(app: EscribaApp, padding: PaddingValues) {
    Page(padding) {
        Text(
            stringResource(R.string.voices_page_hint),
            style = MaterialTheme.typography.bodyLarge, color = Escriba.colors.inkMuted,
        )
        EnrolledVoicesCard(app)
    }
}

// --- Watch -------------------------------------------------------------------

private val DATE = DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm", dev.escriba.mobile.ui.today.APP_LOCALE)

@Composable
fun WatchPage(app: EscribaApp, padding: PaddingValues) {
    val scope = rememberCoroutineScope()
    val peer by app.link.keys.peer.collectAsStateWithLifecycle()
    val pairing by app.pairing.state.collectAsStateWithLifecycle()
    val status by app.inbox.watchStatus.collectAsStateWithLifecycle()
    var nodes by remember { mutableStateOf<List<Peer>?>(null) }
    val c = Escriba.colors

    LaunchedEffect(Unit) {
        while (true) {
            nodes = runCatching { app.link.peers() }.getOrDefault(emptyList())
            delay(10_000)
        }
    }

    Page(padding) {
        EsCard {
            val p = peer
            if (p == null) {
                StatusChip(stringResource(R.string.watch_not_paired), Tone.WAIT)
                Text(stringResource(R.string.watch_not_paired_hint), style = MaterialTheme.typography.bodyLarge, color = c.ink)
            } else {
                val connected = nodes?.firstOrNull { it.id == p.nodeId }
                when {
                    nodes == null -> StatusChip(stringResource(R.string.checking), Tone.BUSY)
                    connected == null -> StatusChip(stringResource(R.string.not_connected), Tone.WAIT)
                    connected.nearby -> StatusChip(stringResource(R.string.connected_bluetooth), Tone.DONE)
                    else -> StatusChip(stringResource(R.string.cloud_only), Tone.WAIT)
                }
                Text(p.name, style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(stringResource(R.string.paired_since, p.pairedAtMs.local().format(DATE), p.keyId), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            }
            status?.let { st ->
                val phase = stringResource(
                    when (st.phase) {
                        "CAPTURING" -> R.string.phase_recording
                        "PAUSED" -> R.string.phase_paused
                        else -> R.string.phase_stopped
                    },
                )
                Text(
                    stringResource(R.string.watch_status_line, st.atMs.local().format(HM), phase, st.pendingFragments, (st.pendingBytes / 1024).toInt()) +
                        (st.batteryPct?.let { " · " + stringResource(if (st.charging) R.string.watch_battery_charging else R.string.watch_battery, it.toInt()) } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
                )
            }
        }

        when (val state = pairing) {
            is PairingState.Code -> EsCard {
                Text(stringResource(R.string.pairing_check_code), style = MaterialTheme.typography.bodyLarge, color = c.ink)
                Text(state.code, style = Escriba.type.metric.copy(fontSize = 40.sp, lineHeight = 44.sp), color = c.ink)
                when {
                    state.localConfirmed && !state.peerConfirmed -> Text(stringResource(R.string.pairing_confirm_on_watch), color = c.inkMuted)
                    state.peerConfirmed && !state.localConfirmed -> Text(stringResource(R.string.pairing_watch_confirmed), color = c.inkMuted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    if (!state.localConfirmed) Button(onClick = { scope.launch { runCatching { app.pairing.confirm() } } }) { Text(stringResource(R.string.pairing_matches)) }
                    OutlinedButton(onClick = { app.pairing.cancel() }) { Text(stringResource(R.string.pairing_no_match)) }
                }
            }
            is PairingState.Waiting -> EsCard {
                Text(stringResource(state.wait.text), style = MaterialTheme.typography.bodyLarge, color = c.ink)
                OutlinedButton(onClick = { app.pairing.cancel() }) { Text(stringResource(R.string.cancel)) }
            }
            else -> {
                (state as? PairingState.Failed)?.let { Text(stringResource(it.reason.text), color = c.danger, style = MaterialTheme.typography.bodyMedium) }
                (state as? PairingState.Done)?.let { StatusChip(stringResource(R.string.pairing_done), Tone.DONE) }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    if (peer == null) Button(onClick = { app.pairing.openWindowAsPhone() }) { Text(stringResource(R.string.pair_watch)) }
                    else OutlinedButton(onClick = { app.pairing.openWindowAsPhone() }) { Text(stringResource(R.string.pair_again)) }
                    OutlinedButton(onClick = {
                        scope.launch { runCatching { app.link.pingNearby() }.onFailure { AppLog.e("ui", "Failed to send the ping", it) } }
                    }) { Text(stringResource(R.string.test_connection)) }
                }
            }
        }
    }
}

// --- Battery -----------------------------------------------------------------

@Composable
fun BatteryPage(actions: SystemActions, padding: PaddingValues) {
    var exempt by remember { mutableStateOf(actions.isBatteryExempt()) }
    LaunchedEffect(Unit) {
        while (true) {
            exempt = actions.isBatteryExempt()
            delay(2_000)
        }
    }
    val c = Escriba.colors
    @Composable
    fun Step(title: String, body: String, extra: @Composable () -> Unit = {}) {
        EsCard {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink)
            Text(body, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            extra()
        }
    }
    Page(padding) {
        Text(
            stringResource(R.string.battery_intro),
            style = MaterialTheme.typography.bodyLarge, color = c.inkMuted,
        )
        Step(stringResource(R.string.battery_step1), stringResource(if (exempt) R.string.battery_step1_exempt else R.string.battery_step1_optimized)) {
            if (exempt) StatusChip(stringResource(R.string.battery_exempt), Tone.DONE) else Button(onClick = actions.requestBatteryExemption) { Text(stringResource(R.string.battery_exempt_action)) }
        }
        Step(
            stringResource(R.string.battery_step2),
            stringResource(R.string.battery_step2_body),
        ) { OutlinedButton(onClick = actions.openAppSettings) { Text(stringResource(R.string.battery_open_settings)) } }
        Step(stringResource(R.string.battery_step3), stringResource(R.string.battery_step3_body))
        Step(stringResource(R.string.battery_step4), stringResource(R.string.battery_step4_body))
    }
}

// --- Log ---------------------------------------------------------------------

@Composable
fun LogPage(padding: PaddingValues) {
    val log by AppLog.events.entries.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex) }
    val c = Escriba.colors
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = Space.s4, end = Space.s4, top = padding.calculateTopPadding() + Space.s2, bottom = padding.calculateBottomPadding() + Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        item { OutlinedButton(onClick = { AppLog.events.clear() }) { Text(stringResource(R.string.clear)) } }
        items(log) { entry ->
            Text(entry.toString(), style = Escriba.type.time.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal), color = c.ink)
        }
    }
}

@get:StringRes
private val PairingWait.text: Int
    get() = when (this) {
        PairingWait.PHONE -> R.string.pairing_wait_phone
        PairingWait.WATCH -> R.string.pairing_wait_watch
        PairingWait.CODE -> R.string.pairing_wait_code
    }

@get:StringRes
private val PairingFailure.text: Int
    get() = when (this) {
        PairingFailure.NO_NEARBY_PHONE -> R.string.pairing_failed_no_phone
        PairingFailure.NOT_DIRECTLY_CONNECTED -> R.string.pairing_failed_not_direct
        PairingFailure.COMMITMENT_MISMATCH -> R.string.pairing_failed_commitment
        PairingFailure.BAD_CONFIRMATION -> R.string.pairing_failed_confirmation
    }
