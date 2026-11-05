package com.steimerbyte.backgroundcontrol

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

internal val Accent = Color(0xFFD71921)
internal val Dim = Color(0xFF7A7A7A)
internal val Panel = Color(0xFF0D0D0D)
internal val Line = Color(0xFF242424)
internal val Good = Color(0xFF39D98A)
internal val Warn = Color(0xFFE0A32E)

private const val REQ_PERMISSION = 1001
private const val TAG = "BackgroundControl"

// Bucket-IDs aus ActivityManager: 5 exempted, 10 active, 20 working set,
// 30 frequent, 40 rare, 45 restricted, 50 never.
internal fun bucketName(b: Int): String = when (b) {
    5 -> "EXEMPTED"
    10 -> "ACTIVE"
    20 -> "WORKING SET"
    30 -> "FREQUENT"
    40 -> "RARE"
    45 -> "RESTRICTED"
    50 -> "NEVER"
    else -> "?"
}

/** Chip-Farbe nach Layoutsystem: gedrosselt in Warn, exempted in Good, Rest in Dim. */
internal fun bucketTone(b: Int): Color = when (b) {
    5 -> Good
    45, 50 -> Warn
    else -> Dim
}

internal fun throttled(b: Int) = b == 45 || b == 50

/** Treffer, wenn der Suchtext in Label oder Package steckt — Gross/Klein egal. */
internal fun matches(app: AppEntry, query: String): Boolean =
    app.label.contains(query, ignoreCase = true) || app.pkg.contains(query, ignoreCase = true)

/** Rang für die BUCKET-Sortierung: 45 zuerst, unbekannte Buckets zuletzt. */
internal fun bucketRank(b: Int): Int =
    SORT_BUCKETS.indexOf(b).let { if (it < 0) SORT_BUCKETS.size else it }

internal val BUCKET_ORDER = listOf(5, 10, 20, 30, 40, 45, 50)

/** Sortierung BUCKET: gedrosselt zuerst, dann rare → frequent → working set → active → exempted. */
internal val SORT_BUCKETS = listOf(45, 50, 40, 30, 20, 10, 5)

/** Ziele, die `am set-standby-bucket` akzeptiert — exempted (5) und never (50) fehlen dort. */
internal val SETTABLE = listOf(45, 10, 20, 30, 40)

internal data class AppEntry(val pkg: String, val label: String, val bucket: Int)

internal enum class ShizukuState { UNKNOWN, DEAD, ALIVE, GRANTED, DENIED }

private val SH_BUCKET = mapOf(
    10 to "active", 20 to "working_set", 30 to "frequent", 40 to "rare", 45 to "restricted"
)

/** sh-Name des Buckets — `am` kennt nur diese fünf, "default" wird abgelehnt. */
internal fun shBucket(target: Int): String = SH_BUCKET.getValue(target)

class MainActivity : ComponentActivity() {

    internal var shizukuState by mutableStateOf(ShizukuState.UNKNOWN)
    internal var apps by mutableStateOf<List<AppEntry>>(emptyList())
    internal var loadDone by mutableIntStateOf(0)
    internal var loadTotal by mutableIntStateOf(0)
    internal var writeDone by mutableIntStateOf(0)
    internal var writeTotal by mutableIntStateOf(0)
    internal var busy by mutableStateOf(false)
    internal var summary by mutableStateOf("")
    internal var diag by mutableStateOf("")
    internal var totalPkgs by mutableIntStateOf(0)
    internal var hiddenPkgs by mutableIntStateOf(0)
    internal var shErr by mutableStateOf("")
    internal var reloadStamp by mutableIntStateOf(0)
    /** Zeitpunkt des letzten Lesevorgangs — der Kopf nennt ihn, weil nichts automatisch lädt. */
    internal var snapLabel by mutableStateOf("")
    // Systemlast: CPU über Shizuku, RAM direkt aus /proc/meminfo
    internal var cpuHistory by mutableStateOf<List<Float>>(emptyList())
    internal var ramHistory by mutableStateOf<List<Float>>(emptyList())
    internal var ramTotalKb by mutableStateOf(0L)
    internal var ramAvailKb by mutableStateOf(0L)
    internal var cpuFailed by mutableStateOf(false)
    internal var ramFailed by mutableStateOf(false)

    private val permListener = Shizuku.OnRequestPermissionResultListener { code, grantResult ->
        if (code == REQ_PERMISSION) {
            shizukuState = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ShizukuState.GRANTED
            } else {
                ShizukuState.DENIED
            }
            onShizukuReady()
        }
    }

    private val deadListener = Shizuku.OnBinderDeadListener { shizukuState = ShizukuState.DEAD }

    // Kaltstart-Rennen: der Shizuku-Provider bindet manchmal erst nach onCreate.
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshShizuku()
        if (shizukuState == ShizukuState.GRANTED) onShizukuReady()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permListener)
        Shizuku.addBinderDeadListener(deadListener)
        setContent { AppTheme { Screen() } }
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        refreshShizuku()
        onShizukuReady()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        super.onDestroy()
    }

    // ---- Shizuku ----

    internal fun refreshShizuku() {
        shizukuState = try {
            when {
                !Shizuku.pingBinder() -> ShizukuState.DEAD
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.GRANTED
                else -> ShizukuState.ALIVE
            }
        } catch (t: Throwable) {
            ShizukuState.DEAD
        }
    }

    internal fun requestShizuku() {
        refreshShizuku()
        if (shizukuState == ShizukuState.GRANTED) {
            onShizukuReady()
            return
        }
        if (shizukuState == ShizukuState.DEAD || shizukuState == ShizukuState.UNKNOWN) {
            summary = "Shizuku läuft nicht. Starte es über die Shizuku-App."
            return
        }
        runCatching { Shizuku.requestPermission(REQ_PERMISSION) }
            .onFailure { summary = "Anfrage fehlgeschlagen: ${it.message}" }
    }

    internal fun runShell(vararg cmd: String): String {
        val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
        val proc = service.newProcess(arrayOf(*cmd), null, null)
        val err = StringBuilder()
        val reader = Thread {
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(proc.errorStream)
                    .bufferedReader().use { r -> r.forEachLine { err.appendLine(it) } }
            }
        }
        reader.start()
        val out = ParcelFileDescriptor.AutoCloseInputStream(proc.inputStream)
            .bufferedReader().use { it.readText() }
        val rc = proc.waitFor()
        reader.join(2000)
        Log.d(TAG, "${cmd.joinToString(" ")} rc=$rc out=${out.take(100)} err=${err.take(200)}")
        if (out.isBlank()) diag = "rc=$rc ${err.toString().trim().take(160)}"
        return out.ifBlank { err.toString() }
    }

    private var lastShErr = ""

    /**
     * Führt ein Shell-Skript aus und reicht jede stdout-Zeile einzeln weiter (Fortschritt).
     * Liefert die Zahl der gelesenen Zeilen; bei 0 Zeilen landet stderr in [diag].
     */
    private suspend fun shellLines(script: String, onLine: suspend (String) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
            val proc = service.newProcess(arrayOf("sh", "-c", script), null, null)
            val err = StringBuilder()
            val reader = Thread {
                runCatching {
                    ParcelFileDescriptor.AutoCloseInputStream(proc.errorStream)
                        .bufferedReader().use { r -> r.forEachLine { err.appendLine(it) } }
                }
            }
            reader.start()
            var n = 0
            ParcelFileDescriptor.AutoCloseInputStream(proc.inputStream).bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    n++
                    onLine(line)
                }
            }
            val rc = proc.waitFor()
            reader.join(2000)
            lastShErr = err.toString().trim()
            // erste stderr-Zeile sichtbar machen — genau sie meldet, warum am abgelehnt hat
            lastShErr.lineSequence().firstOrNull { it.isNotBlank() }?.let { shErr = it.take(120) }
            if (n == 0) {
                diag = "sh rc=$rc ${err.toString().trim().take(160)}"
                Log.w(TAG, "shellLines ohne Ausgabe rc=$rc err=${err.toString().take(200)}")
            }
            n
        }

    private fun parseLine(line: String): Pair<String, Int?>? {
        val i = line.indexOf('=')
        if (i <= 0) return null
        return line.substring(0, i).trim() to line.substring(i + 1).trim().toIntOrNull()
    }

    // ---- Systemlast ----

    /** [cpu total, cpu idle+iowait] aus /proc/stat — für Apps gesperrt, daher über Shizuku. */
    private fun readCpuSample(): LongArray? = runCatching {
        val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
        val proc = service.newProcess(arrayOf("cat", "/proc/stat"), null, null)
        val line = ParcelFileDescriptor.AutoCloseInputStream(proc.inputStream).bufferedReader().use { r ->
            r.lineSequence().firstOrNull { it.startsWith("cpu ") }
        }
        proc.waitFor()
        val nums = line?.trim()?.split(Regex("\\s+"))?.drop(1)?.mapNotNull { it.toLongOrNull() }.orEmpty()
        if (nums.size < 5) null else longArrayOf(nums.sum(), nums[3] + nums[4])
    }.getOrNull()

    /** [MemTotal, MemAvailable] in kB — /proc/meminfo dürfen Apps selbst lesen. */
    private fun readRamSample(): LongArray? = runCatching {
        var total = 0L
        var avail = 0L
        File("/proc/meminfo").useLines { lines ->
            lines.forEach { l ->
                when {
                    l.startsWith("MemTotal:") -> total = l.filter { it.isDigit() }.toLong()
                    l.startsWith("MemAvailable:") -> avail = l.filter { it.isDigit() }.toLong()
                }
            }
        }
        if (total > 0 && avail > 0) longArrayOf(total, avail) else null
    }.getOrNull()

    /** Absolutwerte unter dem RAM-Prozentwert: "5,3/11,1 GiB". */
    private fun ramDetail(): String = if (ramTotalKb > 0) {
        String.format(
            Locale.GERMANY, "%.1f/%.1f GiB",
            (ramTotalKb - ramAvailKb) / 1048576f, ramTotalKb / 1048576f
        )
    } else {
        ""
    }

    // ---- Daten ----

    /** Alle Drittanbieter-Pakete (ungefiltert). */
    private fun listPackages(): List<String> =
        runShell("cmd", "package", "list", "packages", "-3")
            .lineSequence()
            .map { it.trim().removePrefix("package:") }
            .filter { it.isNotEmpty() && !it.contains(' ') }
            .distinct()
            .toList()

    private fun isShizuku(pkg: String) =
        pkg.startsWith("moe.shizuku") || pkg.startsWith("rikka.shizuku")

    private fun labelOf(pkg: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    internal fun onShizukuReady() {
        if (shizukuState != ShizukuState.GRANTED) return
        reload()
    }

    internal fun reload() {
        if (shizukuState != ShizukuState.GRANTED || busy) return
        lifecycleScope.launch {
            busy = true
            summary = ""
            diag = ""
            shErr = ""
            apps = emptyList()
            loadDone = 0
            loadTotal = 0
            writeDone = 0
            writeTotal = 0
            hiddenPkgs = 0
            try {
                val all = withContext(Dispatchers.IO) { listPackages() }
                totalPkgs = all.size
                val pkgs = all.filterNot(::isShizuku)
                hiddenPkgs = all.size - pkgs.size
                loadTotal = pkgs.size
                val labels = withContext(Dispatchers.Default) { pkgs.associateWith { labelOf(it) } }
                val buckets = HashMap<String, Int>()
                var sample = ""
                if (pkgs.isNotEmpty()) {
                    val script = pkgs.joinToString("; ") { p ->
                        "echo \"$p=\$(am get-standby-bucket $p)\""
                    }
                    shellLines(script) { line ->
                        val parsed = parseLine(line)
                        if (parsed != null && parsed.second != null) {
                            buckets[parsed.first] = parsed.second!!
                            loadDone = buckets.size
                        } else if (sample.isEmpty()) {
                            sample = line.take(120)
                        }
                    }
                }
                apps = buckets.map { (p, b) -> AppEntry(p, labels[p] ?: p, b) }
                if (apps.isEmpty() && pkgs.isNotEmpty()) {
                    summary = "KEINE BUCKETS GELESEN"
                    if (diag.isEmpty()) diag = sample.ifEmpty { "am lieferte keine parsebaren Zeilen" }
                } else if (apps.isNotEmpty()) {
                    snapLabel = "SCHNAPPSCHUSS " + SimpleDateFormat("HH:mm", Locale.GERMANY)
                        .format(Date()) + " · NICHT AUTOMATISCH"
                    reloadStamp++
                }
            } catch (t: Throwable) {
                summary = "LESEN FEHLGESCHLAGEN · ${t.javaClass.simpleName}"
                diag = t.message?.take(160) ?: t.toString()
                Log.e(TAG, "reload", t)
            } finally {
                busy = false
            }
        }
    }

    /** setzt den Bucket für alle übergebenen Pakete und liest jeden einzeln zurück. */
    internal fun applySelected(target: Int, names: List<String>, onDone: () -> Unit) {
        if (names.isEmpty() || busy || shizukuState != ShizukuState.GRANTED) return
        lifecycleScope.launch {
            busy = true
            summary = ""
            diag = ""
            shErr = ""
            val before = apps.associate { it.pkg to it.bucket }
            var changed = 0
            var same = 0
            var failed = 0
            writeDone = 0
            writeTotal = names.size
            try {
                val sh = shBucket(target)
                val script = names.joinToString("; ") { p ->
                    "echo \"$p=\$(am set-standby-bucket $p $sh; am get-standby-bucket $p)\""
                }
                shellLines(script) { line ->
                    writeDone++
                    val parsed = parseLine(line)
                    val pkg = parsed?.first
                    val now = parsed?.second
                    if (pkg == null || now == null) {
                        failed++
                    } else {
                        if (before[pkg] == now) same++ else changed++
                        apps = apps.map { if (it.pkg == pkg) it.copy(bucket = now) else it }
                    }
                }
                if (failed == names.size) {
                    summary = "SCHREIBEN FEHLGESCHLAGEN · $failed FEHLER"
                } else {
                    summary = buildString {
                        append("$changed GEÄNDERT · $same UNVERÄNDERT")
                        if (failed > 0) append(" · $failed FEHLER")
                        append(" · ZIEL ${bucketName(target)}")
                    }
                    // keine Änderung, aber stderr: am hat abgelehnt, nicht "war schon so"
                    if (changed == 0 && lastShErr.isNotEmpty()) diag = lastShErr.take(160)
                }
            } catch (t: Throwable) {
                summary = "SCHREIBEN FEHLGESCHLAGEN · ${t.javaClass.simpleName}"
                diag = t.message?.take(160) ?: t.toString()
                Log.e(TAG, "applySelected", t)
            } finally {
                onDone()
                busy = false
            }
        }
    }

    // ---- UI ----

    @Composable
    private fun Screen() {
        var query by rememberSaveable { mutableStateOf("") }
        var sortByBucket by rememberSaveable { mutableStateOf(true) }
        var onlyThrottled by rememberSaveable { mutableStateOf(false) }
        var selected by rememberSaveable(
            stateSaver = listSaver<Set<String>, String>(
                save = { it.toList() },
                restore = { it.toSet() }
            )
        ) { mutableStateOf(emptySet<String>()) }
        var target by rememberSaveable { mutableIntStateOf(45) }
        var justUpdated by remember { mutableStateOf(false) }

        // Hinweis nur einmal: gemerkt in den Preferences, nicht nur in dieser Sitzung.
        val prefs = remember { getSharedPreferences("ui", MODE_PRIVATE) }
        var showHint by rememberSaveable { mutableStateOf(!prefs.getBoolean("hint_seen", false)) }
        var showAbout by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(reloadStamp) {
            if (reloadStamp > 0) {
                justUpdated = true
                delay(2000)
                justUpdated = false
            }
        }

        // Läuft nur, solange die App im Vordergrund ist (RESUMED); Sampling im IO-Dispatcher.
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(Unit) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var previous = withContext(Dispatchers.IO) { readCpuSample() }
                while (true) {
                    delay(700)
                    val cpu = withContext(Dispatchers.IO) { readCpuSample() }
                    val ram = withContext(Dispatchers.IO) { readRamSample() }
                    cpuFailed = cpu == null
                    ramFailed = ram == null
                    if (cpu != null) {
                        val before = previous
                        if (before != null) {
                            val deltaTotal = cpu[0] - before[0]
                            val deltaIdle = cpu[1] - before[1]
                            if (deltaTotal > 0) {
                                val load = (1.0 - deltaIdle.toDouble() / deltaTotal).toFloat()
                                cpuHistory = (cpuHistory + load.coerceIn(0f, 1f)).takeLast(48)
                            }
                        }
                        previous = cpu
                    }
                    if (ram != null) {
                        ramTotalKb = ram[0]
                        ramAvailKb = ram[1]
                        val used = (ram[0] - ram[1]).toFloat() / ram[0]
                        ramHistory = (ramHistory + used.coerceIn(0f, 1f)).takeLast(48)
                    }
                }
            }
        }

        val q = query.trim()
        val visible = apps
            .filter { (!onlyThrottled || throttled(it.bucket)) && (q.isEmpty() || matches(it, q)) }
            .sortedWith(
                if (sortByBucket) {
                    compareBy<AppEntry>({ bucketRank(it.bucket) }, { it.label.lowercase() }, { it.pkg })
                } else {
                    compareBy<AppEntry>({ it.label.lowercase() }, { it.pkg })
                }
            )

        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
        ) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Osw(
                            "NOTHING OS · STANDBY-BUCKETS",
                            "BACKGROUND CONTROL",
                            onKickerClick = { showAbout = true }
                        ) {
                            MiniButton(
                                when {
                                    busy && writeTotal > 0 -> "SCHREIBT · $writeDone/$writeTotal"
                                    busy -> if (loadTotal > 0) "LÄDT · $loadDone/$loadTotal" else "LÄDT …"
                                    justUpdated -> "AKTUALISIERT"
                                    else -> "AKTUALISIEREN"
                                },
                                enabled = !busy,
                                selected = busy || justUpdated
                            ) { reload() }
                        }
                        if (snapLabel.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(snapLabel, style = MicroStyle, maxLines = 1)
                        }
                        Spacer(Modifier.height(12.dp))
                        LivePanel(
                            cpuHistory,
                            ramHistory,
                            when {
                                cpuHistory.isNotEmpty() -> "${(cpuHistory.last() * 100).roundToInt()} %"
                                cpuFailed -> "KEINE DATEN"
                                else -> "…"
                            },
                            when {
                                ramHistory.isNotEmpty() -> "${(ramHistory.last() * 100).roundToInt()} %"
                                ramFailed -> "KEINE DATEN"
                                else -> "…"
                            },
                            ramDetail(),
                            cpuFailed,
                            ramFailed
                        )
                        Spacer(Modifier.height(12.dp))

                        if (shizukuState != ShizukuState.GRANTED) {
                            ShizukuGate()
                        } else {
                            Distribution()
                            Spacer(Modifier.height(12.dp))
                            SearchField(query, { query = it }, visible.size)
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("SORTIERUNG", style = KickerStyle)
                                Spacer(Modifier.width(8.dp))
                                Pill("BUCKET", sortByBucket) { sortByBucket = true }
                                Spacer(Modifier.width(6.dp))
                                Pill("NAME", !sortByBucket) { sortByBucket = false }
                                Spacer(Modifier.weight(1f))
                                Pill("NUR GEDROSSELT", onlyThrottled) { onlyThrottled = !onlyThrottled }
                            }
                            Spacer(Modifier.height(10.dp))

                            when {
                                busy -> {
                                    val f = when {
                                        writeTotal > 0 -> writeDone.toFloat() / writeTotal
                                        loadTotal > 0 -> loadDone.toFloat() / loadTotal
                                        else -> 0f
                                    }
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(
                                            if (writeTotal > 0) "SCHREIBE · $writeDone / $writeTotal"
                                            else "LADE BUCKETS · $loadDone / $loadTotal",
                                            style = MicroStyle.copy(color = Accent)
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Bar(f)
                                    }
                                }

                                summary.isNotEmpty() -> OutlineBox {
                                    Text(
                                        summary,
                                        style = MicroStyle.copy(
                                            color = when {
                                                summary.contains("FEHLGESCHLAGEN") ||
                                                    summary.contains("FEHLER") -> Warn
                                                summary.contains("0 GEÄNDERT") -> Dim
                                                else -> Good
                                            }
                                        )
                                    )
                                }
                            }

                            if (shErr.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, Warn, RoundedCornerShape(2.dp))
                                        .padding(8.dp)
                                ) {
                                    Text("SHELL · $shErr", style = MicroStyle.copy(color = Warn))
                                }
                            }
                            if (diag.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(diag, style = MicroStyle.copy(color = Warn))
                            }
                        }
                    }
                }

                if (shizukuState == ShizukuState.GRANTED) {
                    if (visible.isEmpty()) {
                        item {
                            Text(
                                when {
                                    busy -> "LADE BUCKETS …"
                                    q.isNotEmpty() -> "KEIN TREFFER FÜR \"$q\"" +
                                        if (onlyThrottled) " · NUR GEDROSSELT" else ""
                                    onlyThrottled -> "KEINE GEDROSSELTEN APPS"
                                    else -> "KEINE APPS GELISTET"
                                },
                                style = MicroStyle,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                    items(visible, key = { it.pkg }) { app ->
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            AppRow(app, selected.contains(app.pkg)) {
                                selected =
                                    if (app.pkg in selected) selected - app.pkg else selected + app.pkg
                            }
                        }
                    }
                }
            }

            if (selected.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SETTABLE.forEach { b ->
                            Pill(bucketName(b), target == b, enabled = !busy) { target = b }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    WideButton(
                        "ANWENDEN · ${selected.size} → ${bucketName(target)}",
                        enabled = !busy
                    ) {
                        applySelected(target, selected.toList()) { selected = emptySet() }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (showHint) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Hint(
                        "RESTRICTED: Die App darf kaum noch im Hintergrund laufen — " +
                            "Android stuft sie nach kurzer Zeit zurück."
                    ) {
                        showHint = false
                        prefs.edit().putBoolean("hint_seen", true).apply()
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // About: Kicker antippen. Dialog, damit das Panel über der Seite liegt und BACK schließt.
        if (showAbout) {
            Dialog(
                onDismissRequest = { showAbout = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .systemBarsPadding()
                        .clickable { showAbout = false }
                        .padding(16.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    AboutPanel(
                        "BACKGROUND CONTROL",
                        "Version ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL}",
                        "Zeigt und setzt die Standby-Buckets aller Drittanbieter-Apps — " +
                            "ohne Root, über Shizuku.",
                        ABOUT_COMMANDS
                    )
                }
            }
        }
    }

    @Composable
    private fun ShizukuGate() {
        Box(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Accent, RoundedCornerShape(2.dp))
                .padding(12.dp)
        ) {
            Column {
                Text("SHIZUKU · NICHT BEREIT", style = KickerStyle.copy(color = Accent))
                Spacer(Modifier.height(8.dp))
                Text(
                    "Ohne laufenden Shizuku-Dienst und dessen Berechtigung kann diese App " +
                        "weder Buckets lesen noch setzen.",
                    style = BodyStyle.copy(color = Color.White.copy(alpha = 0.8f))
                )
                Spacer(Modifier.height(10.dp))
                WideButton("ERLAUBEN") { requestShizuku() }
                if (summary.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(summary, style = MicroStyle)
                }
            }
        }
    }

    @Composable
    private fun Distribution() {
        val counts = apps.groupingBy { it.bucket }.eachCount()
        val throttledCount = counts.entries.sumOf { if (throttled(it.key)) it.value else 0 }
        Column(Modifier.fillMaxWidth()) {
            Text(
                "VERTEILUNG · ${apps.size} APPS · $throttledCount GEDROSSELT",
                style = KickerStyle
            )
            Spacer(Modifier.height(6.dp))
            SummaryPanel(BUCKET_ORDER.map { bucketName(it) to (counts[it] ?: 0).toString() })
            Spacer(Modifier.height(4.dp))
            Text("$totalPkgs DRITTANBIETER · $hiddenPkgs SHIZUKU AUSGEBLENDET", style = MicroStyle)
        }
    }

    @Composable
    private fun AppRow(app: AppEntry, isSelected: Boolean, onClick: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(14.dp)
                    .height(14.dp)
                    .border(1.dp, if (isSelected) Accent else Line)
                    .background(if (isSelected) Accent else Color.Transparent)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    app.label, style = BodyStyle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    app.pkg, style = MonoStyle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Chip(bucketName(app.bucket), bucketTone(app.bucket))
        }
    }
}

// ---- Bausteine des Layoutsystems (Skill: nothingos-app) ----

private val MonoFamily = FontFamily.Monospace

private val TitleStyle = TextStyle(
    color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
    letterSpacing = 0.5.sp, fontFamily = MonoFamily
)
private val KickerStyle = TextStyle(
    color = Dim, fontSize = 9.sp, letterSpacing = 1.8.sp, fontFamily = MonoFamily
)
private val MicroStyle = TextStyle(
    color = Dim, fontSize = 9.sp, letterSpacing = 1.4.sp, fontFamily = MonoFamily
)
private val ValueStyle = TextStyle(
    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
    letterSpacing = 0.sp, fontFamily = MonoFamily
)
private val BodyStyle = TextStyle(
    color = Color.White, fontSize = 12.sp, letterSpacing = 0.3.sp, fontFamily = MonoFamily
)
private val MonoStyle = TextStyle(
    color = Dim, fontSize = 10.sp, letterSpacing = 0.2.sp, fontFamily = MonoFamily
)
private val ButtonStyle = TextStyle(
    fontSize = 11.sp, letterSpacing = 1.4.sp, fontFamily = MonoFamily
)

/** Kopfleiste: Kicker (antippbar → About) mit Aktion oben rechts, Titel 28 sp darunter. */
@Composable
private fun Osw(
    kicker: String,
    title: String,
    onKickerClick: () -> Unit,
    action: @Composable () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                kicker, style = KickerStyle, maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onKickerClick() }
                    .padding(vertical = 6.dp)
            )
            action()
        }
        Spacer(Modifier.height(6.dp))
        Text(title.uppercase(), style = TitleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 36 dp hoch, Line-Rahmen; Berührfläche 44 dp. */
@Composable
private fun MiniButton(
    text: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .height(44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .widthIn(min = 44.dp)
                .border(1.dp, if (selected) Accent else Line, RoundedCornerShape(2.dp))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text.uppercase(), style = ButtonStyle, maxLines = 1,
                color = if (selected) Accent else if (enabled) Color.White else Dim
            )
        }
    }
}

/** Die eine Hauptaktion: volle Breite, Accent-Rahmen, gedrückt gefüllt. */
@Composable
private fun WideButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val filled = pressed && enabled
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .border(1.dp, if (enabled) Accent else Line, RoundedCornerShape(2.dp))
            .background(if (filled) Accent else Color.Transparent)
            .clickable(
                interactionSource = interaction, indication = null,
                enabled = enabled, onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text.uppercase(), style = ButtonStyle,
            color = if (filled) Color.Black else if (enabled) Color.White else Dim
        )
    }
}

/** 32 dp hoch, aktiv Accent-Rahmen und Accent-Text; Berührfläche 44 dp. */
@Composable
private fun Pill(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .height(44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .widthIn(min = 44.dp)
                .border(1.dp, if (selected) Accent else Line, RoundedCornerShape(2.dp))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text.uppercase(), style = ButtonStyle, maxLines = 1,
                color = when {
                    selected -> Accent
                    enabled -> Dim
                    else -> Dim.copy(alpha = 0.5f)
                }
            )
        }
    }
}

/** Nur Anzeige: 24 dp, Rahmen und Text in der Tonfarbe. */
@Composable
private fun Chip(text: String, tone: Color) {
    Box(
        Modifier
            .height(24.dp)
            .border(1.dp, tone.copy(alpha = 0.55f), RoundedCornerShape(2.dp))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text.uppercase(), style = ButtonStyle, color = tone, maxLines = 1)
    }
}

/** 40 dp, Line-Rahmen, Lupe als Mikrolabel, Trefferzahl rechts, × leert. */
@Composable
private fun SearchField(text: String, onChange: (String) -> Unit, hits: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .border(1.dp, Line, RoundedCornerShape(2.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("⌕", style = MicroStyle, color = if (text.isEmpty()) Dim else Accent)
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = text,
            onValueChange = onChange,
            singleLine = true,
            textStyle = BodyStyle,
            cursorBrush = SolidColor(Accent),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text("SUCHEN · LABEL ODER PACKAGE", style = MicroStyle)
                    inner()
                }
            }
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$hits TREFFER", style = MicroStyle, maxLines = 1,
            color = if (text.isEmpty()) Dim else Accent
        )
        if (text.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            Text(
                "×", color = Accent, fontSize = 18.sp, fontFamily = MonoFamily,
                modifier = Modifier
                    .clickable { onChange("") }
                    .padding(horizontal = 4.dp, vertical = 6.dp)
            )
        }
    }
}

/** Einmaliger Hinweis, zwei Zeilen, antippbar zum Ausblenden. */
@Composable
private fun Hint(text: String, onDismiss: () -> Unit) {
    OutlineBox {
        Text(
            text, style = MicroStyle, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clickable { onDismiss() }
        )
    }
}

/** Pflicht-About (Skill Abschnitt 4): Name, Version + Gerät, Leitspruch, Zweck, Kernkommandos. */
@Composable
private fun AboutPanel(
    name: String,
    version: String,
    purpose: String,
    commands: List<String>
) {
    OutlineBox {
        Text(name.uppercase(), style = TitleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Text(version, style = MicroStyle)
        Spacer(Modifier.height(16.dp))
        Text("Function over form.", style = ValueStyle.copy(color = Accent))
        Spacer(Modifier.height(16.dp))
        Text(purpose, style = BodyStyle, maxLines = 2)
        Spacer(Modifier.height(16.dp))
        Text("KERNKOMMANDOS:", style = KickerStyle)
        Spacer(Modifier.height(6.dp))
        commands.forEach { Text(it, style = MonoStyle) }
    }
}

/** Was die App wirklich aufruft — Quelle: IShizukuService.newProcess in dieser Datei. */
private val ABOUT_COMMANDS = listOf(
    "cmd package list packages -3",
    "am get-standby-bucket <pkg>",
    "am set-standby-bucket <pkg> <bucket>",
    "  active|working_set|frequent|rare|restricted",
    "cat /proc/stat",
    "cat /proc/meminfo"
)

/** 1 dp Line, 2 dp Radius, 10 dp Innenabstand. */
@Composable
private fun OutlineBox(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Panel)
            .border(1.dp, Line, RoundedCornerShape(2.dp))
            .padding(10.dp),
        content = content
    )
}

/** Mikrolabel über Wert, bis vier Spalten je Zeile. */
@Composable
private fun SummaryPanel(items: List<Pair<String, String>>) {
    OutlineBox {
        items.chunked(4).forEachIndexed { index, row ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MicroStyle, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        Text(value, style = ValueStyle, maxLines = 1)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** CPU- und RAM-Histogramm; der Wert steht rechts in Value, die Bezugsgröße als Mikrozeile. */
@Composable
private fun LivePanel(
    cpu: List<Float>,
    ram: List<Float>,
    cpuText: String,
    ramText: String,
    ramDetail: String,
    cpuFailed: Boolean,
    ramFailed: Boolean
) {
    OutlineBox {
        LiveRow(cpu, cpuText, "CPU", cpuFailed)
        Spacer(Modifier.height(8.dp))
        LiveRow(ram, ramText, if (ramDetail.isEmpty()) "RAM" else "RAM $ramDetail", ramFailed)
    }
}

@Composable
private fun LiveRow(history: List<Float>, value: String, caption: String, failed: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Histogram(history, Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.width(132.dp), horizontalAlignment = Alignment.End) {
            Text(
                value, style = ValueStyle.copy(
                    color = when {
                        failed -> Warn
                        history.isEmpty() -> Dim
                        else -> Color.White
                    }
                ),
                maxLines = 1
            )
            Text(caption, style = MicroStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 48 Slots, 46 dp hoch, Balken 62 % der Slotbreite; belegt Accent, leer Line. */
@Composable
private fun Histogram(history: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier.height(46.dp)) {
        val slots = 48
        val slotWidth = size.width / slots
        val barWidth = slotWidth * 0.62f
        val shift = slots - history.size
        for (i in 0 until slots) {
            val index = i - shift
            val value = if (index >= 0) history[index].coerceIn(0f, 1f) else 0f
            val barHeight = if (value > 0f) (size.height * value).coerceAtLeast(2f) else 1f
            val x = i * slotWidth + (slotWidth - barWidth) / 2f
            drawRect(
                color = if (value > 0f) Accent else Line,
                topLeft = Offset(x, size.height - barHeight),
                size = Size(barWidth, barHeight)
            )
        }
    }
}

/** 2 dp hoch, Anteil in Accent, Rest Line; ab 100 % in Good. */
@Composable
private fun Bar(fraction: Float) {
    val f = fraction.coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(2.dp).background(Line)) {
        Box(Modifier.fillMaxWidth(f).height(2.dp).background(if (f >= 1f) Good else Accent))
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = Color.Black,
            surface = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}
