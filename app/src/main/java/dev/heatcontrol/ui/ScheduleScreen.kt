package dev.heatcontrol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.heatcontrol.ble.HeaterRepository
import dev.heatcontrol.protocol.DaySchedule
import dev.heatcontrol.protocol.Slot
import kotlinx.coroutines.launch

/**
 * Edits a 7-day timer (4 heating periods per day) and saves it into the heaters, which then
 * run it on their own clock - the phone doesn't need to be nearby.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(repo: HeaterRepository, onBack: () -> Unit) {
    val heaters by repo.states.collectAsState()
    val week = remember {
        mutableStateListOf<DaySchedule>().apply {
            addAll((0..6).map { d -> if (d < 5) DaySchedule.default(d) else weekendDefault(d) })
        }
    }
    var day by remember { mutableIntStateOf(0) }
    var target by remember { mutableStateOf<String?>(null) } // null = all heaters
    var loadedFrom by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Pair<Int, Boolean>?>(null) } // slot index, isStart
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Pull the current schedule off the heaters once, then copy it into the editor.
    LaunchedEffect(Unit) { repo.readSchedules() }
    val source = heaters.firstOrNull { (target == null || it.address == target) && it.schedule.size == 7 }
    LaunchedEffect(source?.address, source?.schedule, target) {
        if (source != null && loadedFrom != source.address) {
            (0..6).forEach { d -> source.schedule[d]?.let { week[d] = it } }
            loadedFrom = source.address
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Weekly schedule", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val c = cardColors()
            Text("Apply to", style = MaterialTheme.typography.labelLarge, color = c.subtle)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (heaters.size == 2) "Both" else "All heaters", c, filled = if (target == null) Heat.Ember else null) { target = null }
                heaters.forEach { h ->
                    Pill(h.displayName, c, filled = if (target == h.address) Heat.Ember else null) {
                        target = h.address; loadedFrom = null
                    }
                }
            }
            Text(
                when {
                    loadedFrom != null -> "Loaded from ${heaters.firstOrNull { it.address == loadedFrom }?.displayName}"
                    heaters.any { it.busy } -> "Reading schedules from heaters..."
                    else -> "Showing a default schedule"
                },
                style = MaterialTheme.typography.bodySmall, color = c.subtle,
            )

            // Day picker: seven circles, today's selection in ember.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DaySchedule.DAY_NAMES.forEachIndexed { i, n ->
                    val sel = day == i
                    Box(
                        Modifier.size(42.dp).clip(CircleShape)
                            .background(if (sel) Heat.Ember else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { day = i },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(n.take(2), style = MaterialTheme.typography.labelLarge, color = if (sel) Color.White else c.text)
                    }
                }
            }

            val d = week[day]
            d.slots.forEachIndexed { i, s ->
                SlotCard(
                    index = i, slot = s,
                    onToggle = { on ->
                        week[day] = d.withSlot(i, if (on) defaultSlotFor(i) else Slot.EMPTY)
                    },
                    onEditStart = { editing = i to true },
                    onEditEnd = { editing = i to false },
                    onTemp = { t -> week[day] = d.withSlot(i, s.copy(temperature = t.coerceIn(5, 30))) },
                )
            }

            Text("Copy ${DaySchedule.DAY_NAMES[day]} to", style = MaterialTheme.typography.labelLarge, color = c.subtle)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Weekdays", c) { (0..4).forEach { week[it] = d.copy(day = it) } }
                Pill("Weekend", c) { (5..6).forEach { week[it] = d.copy(day = it) } }
                Pill("Every day", c) { (0..6).forEach { week[it] = d.copy(day = it) } }
            }

            Spacer(Modifier.height(8.dp))
            val canSave = heaters.isNotEmpty() && heaters.none { it.busy }
            Box(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(50))
                    .background(if (canSave) Heat.flame else Brush.linearGradient(listOf(c.track, c.track)))
                    .clickable(enabled = canSave) {
                        repo.writeSchedule(target, week.toList())
                        scope.launch { snack.showSnackbar("Saving schedule and switching to Schedule mode...") }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        target != null -> "Save to ${heaters.firstOrNull { it.address == target }?.displayName}"
                        heaters.size == 2 -> "Save to both heaters"
                        else -> "Save to all heaters"
                    },
                    style = MaterialTheme.typography.titleMedium, color = Color.White,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    editing?.let { (i, isStart) ->
        val s = week[day].slots[i]
        val state = rememberTimePickerState(
            initialHour = if (isStart) s.startH else s.endH,
            initialMinute = if (isStart) s.startM else s.endM,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (isStart) "Start time" else "End time") },
            text = { TimeInput(state) },
            confirmButton = {
                TextButton({
                    val ns = if (isStart) s.copy(startH = state.hour, startM = state.minute)
                    else s.copy(endH = state.hour, endM = state.minute)
                    week[day] = week[day].withSlot(i, ns)
                    editing = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SlotCard(
    index: Int,
    slot: Slot,
    onToggle: (Boolean) -> Unit,
    onEditStart: () -> Unit,
    onEditEnd: () -> Unit,
    onTemp: (Int) -> Unit,
) {
    val c = cardColors()
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape)
                        .background(if (slot.isEmpty) c.chip else tempColor(slot.temperature)),
                    contentAlignment = Alignment.Center,
                ) { Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = if (slot.isEmpty) c.subtle else Color.White) }
                Spacer(Modifier.width(10.dp))
                Text(
                    if (slot.isEmpty) "Period ${index + 1} · off" else "Period ${index + 1}",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                    color = if (slot.isEmpty) c.subtle else c.text,
                )
                Switch(
                    checked = !slot.isEmpty, onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedTrackColor = Heat.Ember, checkedThumbColor = Color.White),
                )
            }
            if (!slot.isEmpty) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(hm(slot.startH, slot.startM), c, onClick = onEditStart)
                    Text("→", Modifier.padding(horizontal = 8.dp), color = c.subtle)
                    Pill(hm(slot.endH, slot.endM), c, onClick = onEditEnd)
                    Spacer(Modifier.weight(1f))
                    SmallRound("−", c) { onTemp(slot.temperature - 1) }
                    Text(
                        "${slot.temperature}°", Modifier.width(48.dp), style = MaterialTheme.typography.titleLarge,
                        color = tempColor(slot.temperature), textAlign = TextAlign.Center,
                    )
                    SmallRound("+", c) { onTemp(slot.temperature + 1) }
                }
            }
        }
    }
}

/** Cool blue for low temperatures through amber to ember for warm ones. */
private fun tempColor(t: Int) = when {
    t < 17 -> Heat.Ice
    t < 20 -> Heat.Leaf
    t < 22 -> Heat.Amber
    else -> Heat.Ember
}

@Composable
private fun SmallRound(label: String, c: OnColors, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(c.chip).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.titleMedium, color = c.text) }
}

private fun hm(h: Int, m: Int) = "%02d:%02d".format(h, m)

private fun DaySchedule.withSlot(i: Int, s: Slot) = copy(slots = slots.toMutableList().also { it[i] = s })

private fun defaultSlotFor(i: Int) = when (i) {
    0 -> Slot(6, 30, 8, 30, 21)
    1 -> Slot(17, 0, 22, 30, 21)
    2 -> Slot(12, 0, 13, 0, 20)
    else -> Slot(20, 0, 21, 0, 20)
}

private fun weekendDefault(d: Int) = DaySchedule(d, listOf(Slot(8, 0, 22, 30, 21), Slot.EMPTY, Slot.EMPTY, Slot.EMPTY))
