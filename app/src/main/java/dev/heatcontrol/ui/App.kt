package dev.heatcontrol.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.heatcontrol.ble.Boost
import dev.heatcontrol.ble.HeaterRepository
import dev.heatcontrol.ble.HeaterState
import dev.heatcontrol.ble.Link
import dev.heatcontrol.protocol.Advance
import dev.heatcontrol.protocol.ModeType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun App(repo: HeaterRepository, permissionsGranted: Boolean, onRequestPermissions: () -> Unit) {
    var screen by remember { mutableStateOf("home") }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!permissionsGranted) {
            PermissionScreen(onRequestPermissions)
            return@Surface
        }
        when (screen) {
            "schedule" -> {
                BackHandler { screen = "home" }
                ScheduleScreen(repo, onBack = { screen = "home" })
            }
            else -> HomeScreen(repo, onOpenSchedule = { screen = "schedule" })
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Heat.flame))
        Spacer(Modifier.height(24.dp))
        Text("Heaters need Bluetooth access", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Allow \"Nearby devices\" so the app can find and control your heaters.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Allow") }
    }
}

@Composable
private fun HomeScreen(repo: HeaterRepository, onOpenSchedule: () -> Unit) {
    val heaters by repo.states.collectAsState()
    val scanning by repo.scanning.collectAsState()
    val boosts by repo.boosts.collectAsState()
    var renaming by remember { mutableStateOf<HeaterState?>(null) }

    LazyColumn(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Header(
                heaters = heaters,
                onSchedule = onOpenSchedule,
                onRefresh = { repo.start() },
            )
        }
        if (scanning) item { LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(50))) }
        if (!repo.bluetoothOn) item {
            Text("Bluetooth is off - turn it on to reach the heaters.", color = MaterialTheme.colorScheme.error)
        }
        if (heaters.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (scanning) "Looking for heaters..." else "No heaters found yet",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { repo.start() }, enabled = !scanning) { Text("Scan again") }
            }
        }
        if (heaters.size > 1) item { BothCard(heaters, boosts, repo) }
        items(heaters, key = { it.address }) { h ->
            HeaterCard(h, boosts[h.address], repo, onRename = { renaming = h })
        }
        item { Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(16.dp)) }
    }

    renaming?.let { h ->
        var text by remember(h.address) { mutableStateOf(h.friendlyName) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Name ${h.advName.substringBefore('<')}") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("e.g. Lounge") }) },
            confirmButton = { TextButton({ repo.rename(h.address, text); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(heaters: List<HeaterState>, onSchedule: () -> Unit, onRefresh: () -> Unit) {
    val connected = heaters.count { it.link == Link.Ready }
    val subtitle = when {
        heaters.isEmpty() -> "Searching nearby"
        connected == heaters.size -> "${heaters.size} heaters · all connected"
        connected == 0 -> "${heaters.size} heaters"
        else -> "${heaters.size} heaters · $connected connected"
    }
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Heating", style = MaterialTheme.typography.headlineLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CircleIcon(Icons.Default.DateRange, "Weekly schedule", enabled = heaters.isNotEmpty(), onClick = onSchedule)
        Spacer(Modifier.width(10.dp))
        CircleIcon(Icons.Default.Refresh, "Refresh", onClick = onRefresh)
    }
}

@Composable
private fun CircleIcon(icon: ImageVector, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** Hero card with a flame gradient that controls every heater at once. */
@Composable
private fun BothCard(heaters: List<HeaterState>, boosts: Map<String, Boost>, repo: HeaterRepository) {
    val target = heaters.mapNotNull { it.target }.maxOrNull()
    val range = heaters.maxOf { it.range.first }..heaters.minOf { it.range.last }
    val modes = heaters.mapNotNull { it.mode?.modeType }.distinct()
    val mode = modes.singleOrNull()
    val off = mode == ModeType.STANDBY
    val bg = if (off) Brush.linearGradient(listOf(Color(0xFF3A3F4B), Color(0xFF23262E))) else Heat.flame
    val c = heroColors

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(bg).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (heaters.size == 2) "Both heaters" else "All heaters", style = MaterialTheme.typography.titleLarge, color = c.text)
                Text(heaters.joinToString(" & ") { it.displayName }, style = MaterialTheme.typography.bodySmall, color = c.subtle)
            }
        }
        TemperatureDial(
            current = target, range = range,
            caption = when {
                mode == null && modes.isNotEmpty() -> "Mixed modes"
                mode == null -> "Connecting"
                else -> modeCaption(mode)
            },
            captionColor = c.subtle, colors = c, busy = heaters.any { it.busy },
        ) { repo.setTemperature(null, it) }
        ModeSelector(mode, c) { repo.setMode(null, it) }
        if (mode == ModeType.SCHEDULE) {
            AdvanceRow(heaters.any { it.advance == Advance.ADVANCE }, c) { repo.advance(null, it) }
        }
        val allBoosted = heaters.all { boosts.containsKey(it.address) }
        BoostRow(
            boost = if (allBoosted) boosts.values.maxByOrNull { it.until } else null,
            colors = c,
            onBoost = { repo.boost(null, it) },
            onStop = { repo.stopBoost(null) },
        )
    }
}

@Composable
private fun HeaterCard(h: HeaterState, boost: Boost?, repo: HeaterRepository, onRename: () -> Unit) {
    val c = cardColors()
    val mode = h.mode?.modeType
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onRename)) {
                    Text(h.displayName, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.Edit, "Rename", Modifier.size(16.dp), tint = c.subtle)
                }
                Text(
                    listOfNotNull(h.advName.substringBefore('<'), h.firmware?.let { "fw $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = c.subtle,
                )
            }
            LinkPill(h, c)
        }
        if (h.link is Link.Pairing) {
            Text(
                "Pairing... if your phone asks for a code, enter ${h.pin ?: "the code on the heater display"}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary,
            )
        }
        h.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        TemperatureDial(
            current = h.target, range = h.range,
            caption = when {
                mode != null -> modeCaption(mode) + if (h.advance == Advance.ADVANCE) " · advanced" else ""
                h.link == Link.Ready -> "Reading..."
                else -> "Not connected"
            },
            captionColor = Heat.modeColor(mode), colors = c, busy = h.busy,
        ) { repo.setTemperature(h.address, it) }
        ModeSelector(mode, c) { repo.setMode(h.address, it) }
        if (mode == ModeType.SCHEDULE) {
            AdvanceRow(h.advance == Advance.ADVANCE, c) { repo.advance(h.address, it) }
        }
        BoostRow(boost, c, onBoost = { repo.boost(h.address, it) }, onStop = { repo.stopBoost(h.address) })
    }
}

private fun modeCaption(m: ModeType) = when (m) {
    ModeType.STANDBY -> "Off"
    ModeType.SCHEDULE -> "On schedule"
    else -> m.label
}

@Composable
private fun LinkPill(h: HeaterState, c: OnColors) {
    val (text, color) = when (h.link) {
        Link.Ready -> "Connected" to Heat.Leaf
        Link.Connecting -> "Connecting" to Heat.Amber
        Link.Pairing -> "Pairing" to Heat.Ice
        Link.Disconnected -> "Idle" to Heat.Slate
        is Link.Failed -> "Failed" to MaterialTheme.colorScheme.error
    }
    StatusPill(text, color, c, busy = h.busy || h.link == Link.Connecting)
}

private val BOOST_CHOICES = listOf(30 to "30m", 60 to "1h", 120 to "2h", 180 to "3h")

@Composable
private fun BoostRow(boost: Boost?, colors: OnColors, onBoost: (Int) -> Unit, onStop: () -> Unit) {
    if (boost != null) {
        val until = Instant.ofEpochMilli(boost.until).atZone(ZoneId.systemDefault()).toLocalTime()
            .format(DateTimeFormatter.ofPattern("HH:mm"))
        val ended = boost.until <= System.currentTimeMillis()
        val prev = modeCaption(boost.prevMode).lowercase()
        Banner(
            text = when {
                ended && boost.restoreFailed -> "Boost ended at $until · will switch back to $prev when in range"
                ended -> "Boost ended · switching back to $prev"
                else -> "Boosting to ${boost.temp}° until $until, then back to $prev"
            },
            accent = colors.chipSelected ?: Heat.Ember, colors = colors, action = "Stop", onAction = onStop,
        )
        return
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Boost", style = MaterialTheme.typography.labelLarge, color = colors.subtle, modifier = Modifier.padding(end = 4.dp))
        BOOST_CHOICES.forEach { (min, label) -> Pill(label, colors) { onBoost(min) } }
    }
}

@Composable
private fun AdvanceRow(on: Boolean, colors: OnColors, onSet: (Boolean) -> Unit) {
    if (on) {
        Banner("Skipped ahead to the next schedule period", colors.chipSelected ?: Heat.Amber, colors, "Cancel") { onSet(false) }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Jump to the next schedule period", style = MaterialTheme.typography.bodySmall,
                color = colors.subtle, modifier = Modifier.weight(1f),
            )
            Pill("Advance", colors) { onSet(true) }
        }
    }
}
