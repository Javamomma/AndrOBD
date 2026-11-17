package com.fr3ts0n.ecu.gui.androbd.fold

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Split view for the Galaxy Fold inner screen: sensor list on the left, live graphs on the
 * right. On the narrow cover screen it becomes a list that opens one graph at a time.
 *
 * Declares every size-related config change in the manifest, so folding/unfolding just
 * re-lays out this screen; the adapter connection lives in MainActivity, which also survives.
 */
class FoldDashboardActivity : AppCompatActivity() {

    companion object {
        /** window width from which the list and graphs sit side by side */
        const val SPLIT_MIN_WIDTH_DP = 600
        const val EXTRA_SESSIONS = "sessions"
        /** open the newest saved session right away (test builds) */
        const val EXTRA_OPEN_LATEST = "open_latest"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        SessionRecorder.init(this)
        val startInSessions = intent.getBooleanExtra(EXTRA_SESSIONS, false)
        val openLatest = intent.getBooleanExtra(EXTRA_OPEN_LATEST, false)
        setContent {
            FoldTheme {
                FoldApp(startInSessions, openLatest, onExit = ::finish, onShare = ::shareCsv)
            }
        }
    }

    private fun shareCsv(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Export session"))
    }
}

// ====================================================================== navigation

private sealed interface Screen {
    data object Live : Screen
    data object Sessions : Screen
    data class Replay(val id: Long) : Screen
}

@Composable
private fun FoldApp(startInSessions: Boolean, openLatest: Boolean, onExit: () -> Unit, onShare: (File) -> Unit) {
    val palette = LocalPalette.current
    val stack = remember { mutableStateListOf<Screen>(if (startInSessions) Screen.Sessions else Screen.Live) }
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onExit() }

    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !palette.dark
            isAppearanceLightNavigationBars = !palette.dark
        }
    }
    BackHandler(enabled = stack.size > 1) { pop() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(palette.bgTop, palette.bgBottom)))
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AnimatedContent(
            targetState = stack.last(),
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "screen",
        ) { screen ->
            when (screen) {
                Screen.Live -> LiveScreen(onSessions = { push(Screen.Sessions) }, onExit = onExit)
                Screen.Sessions -> SessionsScreen(openLatest, onBack = ::pop, onOpen = { push(Screen.Replay(it)) })
                is Screen.Replay -> ReplayScreen(screen.id, onBack = ::pop, onShare = onShare)
            }
        }
    }
}

// ====================================================================== shared models

private class RowModel(
    val key: String,
    val label: String,
    val color: Color,
    val value: String,
    val units: String,
    val spark: Series,
    val sparkFrom: Long,
    val sparkTo: Long,
    val hold: Boolean,
)

private class ChartModel(
    val key: String,
    val label: String,
    val color: Color,
    val value: String,
    val units: String,
    val series: Series,
    val from: Long,
    val to: Long,
    val hold: Boolean,
    val live: Boolean,
    val sessionStart: Long,
)

private const val MAX_CHARTS = 4
private const val WINDOW_ALL = Long.MAX_VALUE
private val WINDOWS = listOf("30s" to 30_000L, "2m" to 120_000L, "10m" to 600_000L, "All" to WINDOW_ALL)

/** sensible first graphs: rpm, speed, coolant temperature … then list order */
private val PREFERRED = listOf(
    "engine_speed", "vehicle_speed", "engine_coolant_temperature",
    "engine_load_calculated", "throttle_position_abs", "intake_manifold_pressure",
)

private fun defaultPicks(keys: List<String>, n: Int = 3): List<String> =
    (PREFERRED.filter { it in keys } + keys).distinct().take(n)

private fun MutableList<String>.toggle(key: String) {
    if (key in this) remove(key) else {
        if (size >= MAX_CHARTS) removeAt(0)
        add(key)
    }
}

// ====================================================================== live screen

@Composable
private fun LiveScreen(onSessions: () -> Unit, onExit: () -> Unit) {
    val palette = LocalPalette.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(250)
            value = System.currentTimeMillis()
        }
    }
    var windowMs by remember { mutableLongStateOf(120_000L) }
    val selected = remember { mutableStateListOf<String>() }
    var userPicked by remember { mutableStateOf(false) }
    var focus by remember { mutableStateOf<String?>(null) }

    val mode = LiveTelemetry.mode
    val fresh = now - LiveTelemetry.lastSampleAt < 4_000
    val channels = remember(now) { LiveTelemetry.channels() }
    // until the user picks graphs, follow the defaults as sensors come in (rpm, speed, coolant …)
    LaunchedEffect(channels.size) {
        if (!userPicked) {
            val picks = defaultPicks(channels.map { it.key })
            if (picks != selected.toList()) {
                selected.clear()
                selected.addAll(picks)
            }
        }
    }

    val rows = channels.map { ch ->
        RowModel(
            key = ch.key,
            label = ch.label,
            color = palette.accent(ch.order),
            value = LiveTelemetry.valueText(ch.key),
            units = LiveTelemetry.units(ch.key),
            spark = LiveTelemetry.series(ch.key, now - 60_000),
            sparkFrom = now - 60_000,
            sparkTo = now,
            hold = fresh,
        )
    }
    val byKey = rows.associateBy { it.key }
    fun chartFor(key: String): ChartModel? {
        val r = byKey[key] ?: return null
        val s = LiveTelemetry.series(key, if (windowMs == WINDOW_ALL) 0L else now - windowMs)
        val from = if (windowMs == WINDOW_ALL) (s.t.firstOrNull() ?: now - 60_000).coerceAtMost(now - 10_000) else now - windowMs
        return ChartModel(key, r.label, r.color, r.value, r.units, s, from, now, hold = fresh, live = true, sessionStart = from)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullWidth = maxWidth
        val wide = fullWidth >= FoldDashboardActivity.SPLIT_MIN_WIDTH_DP.dp
        Column(Modifier.fillMaxSize()) {
            TopBar(
                title = "AndrOBD",
                onBack = if (!wide && focus != null) ({ focus = null }) else null,
            ) {
                StatusPill(mode, LiveTelemetry.deviceName, fresh)
                Spacer(Modifier.weight(1f))
                RecBadge(SessionRecorder.recordingSince, now)
                IconBtn(FoldIcons.History, "Saved sessions", onSessions)
            }
            if (rows.isEmpty()) {
                LiveEmptyState(mode, onExit, onSessions)
            } else {
                DataLayout(
                    wide = wide,
                    listWidth = (fullWidth * 0.38f).coerceIn(280.dp, 400.dp),
                    rows = rows,
                    selected = selected,
                    chartFor = ::chartFor,
                    onToggle = {
                        userPicked = true
                        selected.toggle(it)
                    },
                    windowMs = windowMs,
                    onWindow = { windowMs = it },
                    focus = focus,
                    onFocus = { focus = it },
                    listHint = "Tap to graph",
                    chartsHint = "Tap a sensor on the left to graph it — up to $MAX_CHARTS at once. Press and hold a graph to read exact values.",
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.LiveEmptyState(mode: LinkMode, onExit: () -> Unit, onSessions: () -> Unit) {
    val connected = mode == LinkMode.ONLINE || mode == LinkMode.DEMO
    EmptyState(
        icon = if (connected) FoldIcons.Chart else FoldIcons.Car,
        title = if (connected) "Waiting for live data…" else "Not connected",
        body = if (connected) {
            "Choose OBD services → Data on the main screen if nothing shows up in a few seconds."
        } else {
            "Go back and tap Connect to reach your car. Live data shows up here, and every drive is saved on this phone automatically."
        },
        primary = "Back to AndrOBD" to onExit,
        secondary = "Saved sessions" to onSessions,
    )
}

// ====================================================================== list + graphs layout

@Composable
private fun DataLayout(
    wide: Boolean,
    listWidth: Dp,
    rows: List<RowModel>,
    selected: List<String>,
    chartFor: (String) -> ChartModel?,
    onToggle: (String) -> Unit,
    windowMs: Long?,
    onWindow: (Long) -> Unit,
    focus: String?,
    onFocus: (String?) -> Unit,
    listHint: String,
    chartsHint: String,
) {
    if (wide) {
        Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
            SensorList(rows, selected, listHint, onClick = onToggle, modifier = Modifier.width(listWidth).fillMaxHeight())
            Spacer(Modifier.width(16.dp))
            ChartPane(
                charts = selected.mapNotNull(chartFor),
                windowMs = windowMs,
                onWindow = onWindow,
                onRemove = onToggle,
                emptyHint = chartsHint,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    } else {
        BackHandler(enabled = focus != null) { onFocus(null) }
        val chart = focus?.let(chartFor)
        Box(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
            if (chart != null) {
                ChartPane(listOf(chart), windowMs, onWindow, onRemove = { onFocus(null) }, emptyHint = "", modifier = Modifier.fillMaxSize())
            } else {
                SensorList(rows, emptyList(), "Tap for a graph", onClick = { onFocus(it) }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun SensorList(rows: List<RowModel>, selected: List<String>, hint: String, onClick: (String) -> Unit, modifier: Modifier) {
    val palette = LocalPalette.current
    Column(modifier) {
        SectionHeader("SENSORS", "${rows.size}", hint)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(rows, key = { it.key }) { row ->
                SensorCard(row, selected = row.key in selected, onClick = { onClick(row.key) })
            }
        }
    }
}

@Composable
private fun SensorCard(row: RowModel, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(18.dp)
    val bg = if (selected) row.color.copy(alpha = if (palette.dark) 0.11f else 0.07f).compositeOver(palette.surface) else palette.surface
    val border = if (selected) row.color.copy(alpha = 0.7f) else palette.outline
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(if (selected) 1.5.dp else 1.dp, border, shape)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 38.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) row.color else row.color.copy(alpha = 0.45f))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.label,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row {
                Text(
                    row.value,
                    style = MaterialTheme.typography.titleLarge.merge(Numeric),
                    color = palette.text,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    row.units,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textFaint,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Sparkline(row.spark, row.sparkFrom, row.sparkTo, row.color, row.hold, Modifier.size(width = 78.dp, height = 36.dp))
    }
}

@Composable
private fun ChartPane(
    charts: List<ChartModel>,
    windowMs: Long?,
    onWindow: (Long) -> Unit,
    onRemove: (String) -> Unit,
    emptyHint: String,
    modifier: Modifier,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(36.dp)) {
            SectionHeader("GRAPHS", if (charts.isEmpty()) "" else "${charts.size}/$MAX_CHARTS", null, Modifier.weight(1f))
            if (windowMs != null) WindowChips(windowMs, onWindow)
        }
        Spacer(Modifier.height(8.dp))
        if (charts.isEmpty()) {
            EmptyState(FoldIcons.Chart, "No graphs yet", emptyHint)
        } else {
            charts.forEachIndexed { i, c ->
                if (i > 0) Spacer(Modifier.height(12.dp))
                ChartCard(c, onRemove = { onRemove(c.key) }, modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun ChartCard(c: ChartModel, onRemove: () -> Unit, modifier: Modifier) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(22.dp)
    val stats = c.series.stats(c.from, c.to)
    BoxWithConstraints(
        modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(c.color.copy(alpha = if (palette.dark) 0.07f else 0.04f).compositeOver(palette.surface), palette.surface)
                )
            )
            .border(1.dp, palette.outline, shape)
            .padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 10.dp)
    ) {
        val compact = maxHeight < 190.dp
        val roomy = maxWidth > 340.dp
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(c.color))
                Spacer(Modifier.width(8.dp))
                Text(
                    c.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconBtn(FoldIcons.Close, "Remove graph", onRemove, size = 34.dp)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Row(Modifier.weight(1f)) {
                    Text(
                        c.value,
                        style = (if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall).merge(Numeric),
                        color = palette.text,
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        c.units,
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textFaint,
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                if (stats != null && roomy) {
                    StatBlock("MIN", stats.min)
                    StatBlock("AVG", stats.avg)
                    StatBlock("MAX", stats.max)
                    Spacer(Modifier.width(8.dp))
                }
            }
            Spacer(Modifier.height(if (compact) 4.dp else 10.dp))
            LineChart(
                series = c.series,
                from = c.from,
                to = c.to,
                color = c.color,
                units = c.units,
                live = c.live,
                holdToEnd = c.hold,
                sessionStart = c.sessionStart,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

@Composable
private fun StatBlock(label: String, value: Float) {
    val palette = LocalPalette.current
    Column(Modifier.padding(start = 14.dp), horizontalAlignment = Alignment.End) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = palette.textFaint)
        Text(formatNumber(value), style = MaterialTheme.typography.titleSmall.merge(Numeric), color = palette.text)
    }
}

@Composable
private fun WindowChips(windowMs: Long, onWindow: (Long) -> Unit) {
    val palette = LocalPalette.current
    val pill = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(pill)
            .background(palette.surface)
            .border(1.dp, palette.outline, pill)
            .padding(3.dp)
    ) {
        for ((label, ms) in WINDOWS) {
            val sel = ms == windowMs
            val accent = palette.accents[0]
            Box(
                Modifier
                    .clip(pill)
                    .background(if (sel) accent.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onWindow(ms) }
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (sel) accent else palette.textDim,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

// ====================================================================== saved sessions

@Composable
private fun SessionsScreen(openLatest: Boolean, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val tick by produceState(0) {
        while (true) {
            delay(2_000)
            value++
        }
    }
    var reload by remember { mutableStateOf(0) }
    val sessions by produceState<List<SessionSummary>?>(null, tick / 5, reload, SessionRecorder.changeCount) {
        value = withContext(Dispatchers.IO) { SessionRecorder.listSessions() }
    }
    val bytes by produceState(0L, sessions) { value = withContext(Dispatchers.IO) { SessionRecorder.databaseBytes(context) } }
    var confirm by remember { mutableStateOf<SessionSummary?>(null) }
    val scope = rememberCoroutineScope()
    var openedLatest by remember { mutableStateOf(false) }
    LaunchedEffect(sessions) {
        val newest = sessions?.firstOrNull()
        if (openLatest && !openedLatest && newest != null) {
            openedLatest = true
            onOpen(newest.id)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Saved sessions", onBack = onBack) {}
        Text(
            "Stored on this phone · ${formatBytes(bytes)} · recorded automatically whenever live data runs",
            style = MaterialTheme.typography.bodySmall,
            color = palette.textFaint,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        )
        val list = sessions
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = palette.accents[0])
            }
            list.isEmpty() -> EmptyState(
                FoldIcons.History,
                "No sessions yet",
                "Connect to your car (or start Demo) and open live data. Every drive is saved here, ready to replay or export as CSV.",
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 330.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.id }) { s ->
                    SessionCard(s, onOpen = { onOpen(s.id) }, onDelete = { confirm = s })
                }
            }
        }
    }

    confirm?.let { s ->
        DeleteDialog(
            s,
            onConfirm = {
                confirm = null
                scope.launch {
                    withContext(Dispatchers.IO) { SessionRecorder.delete(s.id) }
                    reload++
                }
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun SessionCard(s: SessionSummary, onOpen: () -> Unit, onDelete: () -> Unit) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(20.dp)
    val recording = SessionRecorder.recordingSince == s.startedAt
    val day = DateFormat.getDateInstance(DateFormat.FULL).format(Date(s.startedAt))
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.surface)
            .border(1.dp, palette.outline, shape)
            .clickable(onClick = onOpen)
            .padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(day, style = MaterialTheme.typography.titleMedium, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            when {
                recording -> Badge("REC", palette.rec)
                s.isDemo -> Badge("DEMO", palette.demo)
                else -> Badge("LIVE", palette.live)
            }
            IconBtn(FoldIcons.Delete, "Delete session", onDelete, size = 38.dp)
        }
        Text(
            buildString {
                append(time.format(Date(s.startedAt)))
                append(" – ")
                append(time.format(Date(s.endedAt)))
                s.device?.let { append(" · ").append(it) }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textDim,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            Metric("DURATION", formatDurationWords(s.durationMs))
            Metric("SENSORS", "${s.channels}")
            Metric("SAMPLES", formatNumber(s.samples.toFloat()))
        }
    }
}

@Composable
private fun DeleteDialog(s: SessionSummary, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surfaceHigh,
        title = { Text("Delete this session?", color = palette.text) },
        text = {
            Text(
                "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.startedAt))} · " +
                    "${formatNumber(s.samples.toFloat())} samples. This can't be undone.",
                color = palette.textDim,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = palette.rec) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep", color = palette.textDim) } },
    )
}

// ====================================================================== replay a saved session

@Composable
private fun ReplayScreen(id: Long, onBack: () -> Unit, onShare: (File) -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by produceState<SessionData?>(null, id) { value = withContext(Dispatchers.IO) { SessionRecorder.load(id) } }
    val selected = remember { mutableStateListOf<String>() }
    var focus by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }

    val d = data
    LaunchedEffect(d) {
        if (d != null && selected.isEmpty()) selected.addAll(defaultPicks(d.channels.map { it.key }))
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullWidth = maxWidth
        val wide = fullWidth >= FoldDashboardActivity.SPLIT_MIN_WIDTH_DP.dp
        Column(Modifier.fillMaxSize()) {
            val title = d?.let {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.summary.startedAt))
            } ?: "Session"
            TopBar(title, onBack = if (!wide && focus != null) ({ focus = null }) else onBack) {
                if (d != null) {
                    Badge(if (d.summary.isDemo) "DEMO" else "LIVE", if (d.summary.isDemo) palette.demo else palette.live)
                    Spacer(Modifier.width(8.dp))
                    Text(formatDurationWords(d.summary.durationMs), style = MaterialTheme.typography.labelLarge, color = palette.textDim)
                    Spacer(Modifier.weight(1f))
                    IconBtn(FoldIcons.Share, "Export CSV", onClick = {
                        if (!exporting) {
                            exporting = true
                            scope.launch {
                                val file = withContext(Dispatchers.IO) { SessionRecorder.exportCsv(context, id) }
                                exporting = false
                                if (file != null) onShare(file) else Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    })
                }
            }
            if (d == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = palette.accents[0]) }
            } else {
                val start = d.summary.startedAt
                val end = maxOf(d.summary.endedAt, start + 1_000)
                val rows = d.channels.map { ch ->
                    val s = d.series[ch.key] ?: Series.EMPTY
                    RowModel(
                        key = ch.key,
                        label = ch.label,
                        color = palette.accent(ch.order),
                        value = s.v.lastOrNull()?.let(::formatNumber) ?: "—",
                        units = ch.units,
                        spark = s,
                        sparkFrom = start,
                        sparkTo = end,
                        hold = false,
                    )
                }
                val byKey = rows.associateBy { it.key }
                DataLayout(
                    wide = wide,
                    listWidth = (fullWidth * 0.38f).coerceIn(280.dp, 400.dp),
                    rows = rows,
                    selected = selected,
                    chartFor = { key ->
                        byKey[key]?.let { r ->
                            val s = d.series[key] ?: Series.EMPTY
                            val st = s.stats(start, end)
                            ChartModel(
                                key, r.label, r.color,
                                value = st?.let { "${formatNumber(it.avg)}" } ?: "—",
                                units = r.units + if (st != null) "  avg" else "",
                                series = s, from = start, to = end, hold = false, live = false, sessionStart = start,
                            )
                        }
                    },
                    onToggle = { selected.toggle(it) },
                    windowMs = null,
                    onWindow = {},
                    focus = focus,
                    onFocus = { focus = it },
                    listHint = "Last value · tap to graph",
                    chartsHint = "Tap a sensor to graph the whole session. Press and hold a graph to read exact values.",
                )
            }
        }
    }
}

// ====================================================================== small building blocks

@Composable
private fun TopBar(title: String, onBack: (() -> Unit)?, content: @Composable RowScope.() -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(start = if (onBack != null) 6.dp else 20.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconBtn(FoldIcons.Back, "Back", onBack)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 260.dp),
        )
        Spacer(Modifier.width(14.dp))
        content()
    }
}

@Composable
private fun SectionHeader(title: String, count: String, hint: String?, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = palette.textFaint)
        if (count.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(count, style = MaterialTheme.typography.labelSmall.merge(Numeric), color = palette.textDim)
        }
        if (hint != null) {
            Spacer(Modifier.weight(1f))
            Text(hint, style = MaterialTheme.typography.labelSmall, color = palette.textFaint)
        }
    }
}

@Composable
private fun StatusPill(mode: LinkMode, device: String?, fresh: Boolean) {
    val palette = LocalPalette.current
    val (label, color) = when (mode) {
        LinkMode.DEMO -> (if (fresh) "DEMO" else "DEMO · PAUSED") to palette.demo
        LinkMode.ONLINE -> (if (fresh) "LIVE" else "CONNECTED") to palette.live
        else -> "OFFLINE" to palette.textFaint
    }
    val pill = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(pill)
            .background(color.copy(alpha = 0.13f))
            .border(1.dp, color.copy(alpha = 0.35f), pill)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulseDot(color, pulsing = fresh && mode != LinkMode.OFFLINE)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        if (mode == LinkMode.ONLINE && !device.isNullOrBlank()) {
            Text(" · $device", style = MaterialTheme.typography.labelSmall, color = palette.textDim, maxLines = 1)
        }
    }
}

@Composable
private fun PulseDot(color: Color, pulsing: Boolean) {
    Box(Modifier.size(10.dp), contentAlignment = Alignment.Center) {
        if (pulsing) {
            val t = rememberInfiniteTransition(label = "pulse")
            val k by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "k")
            // read the animation only while drawing: no recomposition per frame
            Box(
                Modifier
                    .size(10.dp)
                    .graphicsLayer {
                        scaleX = 1f + k * 1.2f
                        scaleY = 1f + k * 1.2f
                        alpha = 1f - k
                    }
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.45f))
            )
        }
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
    }
}

@Composable
private fun RecBadge(since: Long, now: Long) {
    if (since <= 0L) return
    val palette = LocalPalette.current
    val pill = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(pill)
            .background(palette.rec.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(palette.rec))
        Spacer(Modifier.width(6.dp))
        Text(
            "REC ${formatDuration(now - since)}",
            style = MaterialTheme.typography.labelSmall.merge(Numeric),
            color = palette.rec,
        )
    }
    Spacer(Modifier.width(4.dp))
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

@Composable
private fun Metric(label: String, value: String) {
    val palette = LocalPalette.current
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = palette.textFaint)
        Text(value, style = MaterialTheme.typography.titleMedium.merge(Numeric), color = palette.text)
    }
}

@Composable
private fun IconBtn(icon: ImageVector, description: String, onClick: () -> Unit, size: Dp = 44.dp) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = palette.textDim, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun ColumnScope.EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
) {
    val palette = LocalPalette.current
    Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 420.dp)) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(palette.accents[0].copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = palette.accents[0], modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = palette.text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = palette.textDim, textAlign = TextAlign.Center)
            if (primary != null || secondary != null) {
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    primary?.let { (label, action) -> PillButton(label, filled = true, onClick = action) }
                    secondary?.let { (label, action) -> PillButton(label, filled = false, onClick = action) }
                }
            }
        }
    }
}

@Composable
private fun PillButton(label: String, filled: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val accent = palette.accents[0]
    val pill = RoundedCornerShape(50)
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (filled) palette.bgBottom else accent,
        modifier = Modifier
            .clip(pill)
            .background(if (filled) accent else Color.Transparent)
            .border(1.dp, accent.copy(alpha = if (filled) 0f else 0.6f), pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}
