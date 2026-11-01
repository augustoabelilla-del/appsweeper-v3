package com.example.appsweeper

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val Teal = Color(0xFF00E5D4)
private val Bg = Color(0xFF16171A)
private val CardBg = Color(0xFF26272C)
private val Amber = Color(0xFFFFB74D)

/** Bumped every time the screen comes back, so the list and results refresh. */
private val resumeTick = mutableStateOf(0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Teal,
                    onPrimary = Color.Black,
                    background = Bg,
                    surface = CardBg,
                    onSurface = Color(0xFFE6E6E6)
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = Bg) { SweeperApp() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.value += 1
    }
}

@Composable
fun SweeperApp() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    val scope = rememberCoroutineScope()

    var ram by remember { mutableStateOf(Killer.ram(ctx)) }
    var apps by remember { mutableStateOf(Killer.launchedApps(ctx)) }
    var includeUser by remember { mutableStateOf(store.includeUser) }
    var includeSystem by remember { mutableStateOf(store.includeSystem) }
    var useCustom by remember { mutableStateOf(store.useCustomList) }
    var useRoot by remember { mutableStateOf(store.useRoot) }
    var exceptions by remember { mutableStateOf(store.exceptions()) }
    var customList by remember { mutableStateOf(store.customList()) }
    var schedules by remember { mutableStateOf(store.schedules()) }
    var failed by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var lastRun by remember { mutableStateOf(store.lastRun) }

    fun refresh() {
        ram = Killer.ram(ctx)
        apps = Killer.launchedApps(ctx)
        lastRun = store.lastRun
    }

    var useAccess by remember { mutableStateOf(store.useAccessibility) }
    var hideScr by remember { mutableStateOf(store.hideScreens) }
    var note by remember { mutableStateOf(store.lastNote) }
    val accessOn = remember(resumeTick.value) { ForceStopService.isRunning() }

    LaunchedEffect(resumeTick.value) {
        refresh()
        note = store.lastNote
        failed = store.failedPackages().map { AppItem(it, Killer.labelOf(ctx, it), false) }
    }

    fun runNow() {
        scope.launch {
            val r = withContext(Dispatchers.Default) { Killer.sweep(ctx, fromUi = true) }
            failed = r.failed
            refresh()
            message = if (useAccess && accessOn && r.attempted > 0) {
                "Closing ${r.attempted} apps with Accessibility. The screen will flip through each app's info page, then return here."
            } else {
                "Sent close request to ${r.attempted} apps"
            }
        }
    }

    val visible = apps.filter { (it.isSystem && includeSystem) || (!it.isSystem && includeUser) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("App Sweeper", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Teal)
        }

        // --- RAM + run button ---
        item {
            Card {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Gauge(ram.percent)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${apps.size}", color = Teal, fontSize = 22.sp)
                        Text("Launched apps", fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(gb(ram.freeBytes) + " GB", color = Teal, fontSize = 22.sp)
                        Text("Free of ${gb(ram.totalBytes)} GB", fontSize = 12.sp)
                    }
                    FilledIconButton(
                        onClick = { runNow() },
                        modifier = Modifier.size(64.dp)
                    ) { Icon(Icons.Default.PlayArrow, "Run now", Modifier.size(36.dp)) }
                }
                if (lastRun > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Last run: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(lastRun)) + " (${store.lastRunCount} apps)",
                        fontSize = 12.sp
                    )
                }
            }
        }

        message?.let {
            item { Text(it, color = Teal, fontSize = 13.sp) }
        }
        if (note.isNotBlank()) {
            item { Text(note, color = Amber, fontSize = 13.sp) }
        }

        // --- Options ---
        item {
            Card {
                ToggleRow("User apps", includeUser) { includeUser = it; store.includeUser = it }
                ToggleRow("System apps", includeSystem) { includeSystem = it; store.includeSystem = it }
                ToggleRow("Only my custom list", useCustom) { useCustom = it; store.useCustomList = it }
                ToggleRow("Use root (force-stop)", useRoot) { useRoot = it; store.useRoot = it }
                ToggleRow("Accessibility force-stop", useAccess) { useAccess = it; store.useAccessibility = it }
                if (useAccess) {
                    ToggleRow("Cover screen while closing", hideScr) { hideScr = it; store.hideScreens = it }
                }
                if (useAccess && !accessOn) {
                    Text(
                        "The Accessibility service is OFF. Open Accessibility settings and switch on App Sweeper.",
                        fontSize = 12.sp, color = Amber
                    )
                    TextButton(onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) { Text("Open Accessibility settings") }
                } else if (useAccess) {
                    Text(
                        "Accessibility is ON. Sweeps open each app's info page and tap Force stop. " +
                            "The screen must be on and unlocked.",
                        fontSize = 12.sp
                    )
                }
                Text(
                    "Without root, Android only lets apps end other apps' background processes. " +
                        "Root gives a true force-stop.",
                    fontSize = 11.sp
                )
            }
        }

        // --- Exact alarm permission helper ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                item {
                    Card {
                        Text("Exact alarms are off, so schedules may run a little late.", fontSize = 13.sp)
                        TextButton(onClick = {
                            ctx.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                            )
                        }) { Text("Allow exact alarms") }
                    }
                }
            }
        }

        // --- Schedules ---
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Schedules", color = Teal, fontSize = 18.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Add schedule") }
            }
        }
        if (schedules.isEmpty()) {
            item { Text("No schedules yet. Tap + to add one.", fontSize = 13.sp) }
        }
        items(schedules, key = { "s${it.id}" }) { s ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.label(), fontSize = 16.sp)
                        if (s.enabled) {
                            Text(
                                "Next: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                    .format(Date(Scheduler.nextTrigger(s))),
                                fontSize = 11.sp
                            )
                        }
                    }
                    Switch(checked = s.enabled, onCheckedChange = { on ->
                        val updated = s.copy(enabled = on)
                        schedules = schedules.map { if (it.id == s.id) updated else it }
                        store.saveSchedules(schedules)
                        Scheduler.schedule(ctx, updated)
                    })
                    IconButton(onClick = {
                        Scheduler.cancel(ctx, s)
                        schedules = schedules.filter { it.id != s.id }
                        store.saveSchedules(schedules)
                    }) { Icon(Icons.Default.Delete, "Delete") }
                }
            }
        }

        // --- Launched apps ---
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (useCustom) "Custom list" else "Launched apps",
                    color = Teal, fontSize = 18.sp, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { refresh() }) { Text("Refresh") }
            }
            if (useCustom) Text("Tick the apps the schedule should close.", fontSize = 12.sp)
        }
        items(visible, key = { "a${it.pkg}" }) { app ->
            val protected = app.pkg in exceptions
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (useCustom) {
                        Checkbox(
                            checked = app.pkg in customList,
                            onCheckedChange = { on ->
                                customList = if (on) customList + app.pkg else customList - app.pkg
                                store.setCustomList(customList)
                            }
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(app.label, fontSize = 15.sp)
                        Text(if (app.isSystem) "System" else "User", fontSize = 11.sp)
                    }
                    TextButton(onClick = {
                        exceptions = if (protected) exceptions - app.pkg else exceptions + app.pkg
                        store.setExceptions(exceptions)
                    }) { Text(if (protected) "Protected" else "Protect") }
                    IconButton(
                        enabled = !protected,
                        onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.Default) { Killer.kill(ctx, app.pkg, useRoot) }
                                if (!ok) failed = failed + app
                                refresh()
                            }
                        }
                    ) { Icon(Icons.Default.Close, "Close app") }
                }
            }
        }

        // --- Closing failures ---
        if (failed.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Closing failure", color = Teal, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { failed = emptyList() }) { Icon(Icons.Default.Delete, "Clear") }
                }
            }
            items(failed.distinctBy { it.pkg }, key = { "f${it.pkg}" }) { app ->
                Card { Text(app.label, fontSize = 15.sp) }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }

    if (showAdd) {
        AddScheduleDialog(
            onDismiss = { showAdd = false },
            onAdd = { template ->
                val s = template.copy(id = store.nextScheduleId())
                schedules = schedules + s
                store.saveSchedules(schedules)
                Scheduler.schedule(ctx, s)
                showAdd = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddScheduleDialog(onDismiss: () -> Unit, onAdd: (Schedule) -> Unit) {
    var intervalMode by remember { mutableStateOf(false) }
    var interval by remember { mutableStateOf(60) }
    val time = rememberTimePickerState(initialHour = 3, initialMinute = 0, is24Hour = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New schedule") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !intervalMode, onClick = { intervalMode = false }, label = { Text("Daily at") })
                    FilterChip(selected = intervalMode, onClick = { intervalMode = true }, label = { Text("Every") })
                }
                Spacer(Modifier.height(12.dp))
                if (intervalMode) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60, 120, 360, 720).forEach { m ->
                            FilterChip(
                                selected = interval == m,
                                onClick = { interval = m },
                                label = { Text(if (m < 60) "$m min" else "${m / 60} h") }
                            )
                        }
                    }
                } else {
                    TimePicker(state = time)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onAdd(
                    Schedule(
                        id = 0,
                        intervalMode = intervalMode,
                        hour = time.hour,
                        minute = time.minute,
                        intervalMinutes = interval
                    )
                )
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(CardBg, RoundedCornerShape(14.dp))
            .padding(14.dp),
        content = content
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Gauge(percent: Int) {
    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(Color(0xFF55565C), 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(Teal, 135f, 270f * percent / 100f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$percent%", color = Teal, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("RAM", fontSize = 11.sp)
        }
    }
}

private fun gb(bytes: Long): String = "%.2f".format(bytes / 1024.0 / 1024.0 / 1024.0)
