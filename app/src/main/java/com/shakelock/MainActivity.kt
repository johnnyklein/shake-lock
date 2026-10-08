package com.shakelock

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var serviceEnabled by mutableStateOf(false)
    private var batteryUnrestricted by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = LockStore(this)
        val stats = StatsLog(this)
        setContent {
            ShakeLockTheme {
                MainScreen(store, stats, serviceEnabled, batteryUnrestricted)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check every time we come back from the settings screens.
        serviceEnabled = isServiceEnabled()
        batteryUnrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, LockService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}

private data class AppEntry(val packageName: String, val label: String, val icon: ImageBitmap)

private val APP_LOCK_MINUTES = listOf(1, 5, 10, 15, 30)

private val PHONE_LOCK_MINUTES = listOf(1, 2, 3)

private val SENSITIVITIES = listOf("Gentle" to 1.6f, "Normal" to 2.0f, "Hard" to 2.6f)

@Composable
private fun MainScreen(store: LockStore, stats: StatsLog, serviceEnabled: Boolean, batteryUnrestricted: Boolean) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "Shake Lock",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
            )
            TabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Setup") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Stats") })
            }
            if (tab == 0) SetupScreen(store, stats, serviceEnabled, batteryUnrestricted) else StatsScreen(stats)
        }
    }
}

@Composable
private fun SetupScreen(store: LockStore, stats: StatsLog, serviceEnabled: Boolean, batteryUnrestricted: Boolean) {
    val context = LocalContext.current
    var blocked by remember { mutableStateOf(store.blockedApps) }
    var scope by remember { mutableStateOf(store.shakeLocks) }
    var appMinutes by remember { mutableIntStateOf(store.lockMinutes) }
    var phoneMinutes by remember { mutableIntStateOf(store.phoneLockMinutes) }
    var query by remember { mutableStateOf("") }
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) { loadApps(context) }
    }
    // Blocked apps first, in the order they were when the screen opened (so rows don't jump on tap).
    val initiallyBlocked = remember { store.blockedApps }
    val sortedApps = remember(apps) { apps?.sortedByDescending { it.packageName in initiallyBlocked } }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            HeroCard(store, stats, serviceEnabled, scope, if (scope == LockScope.PHONE) phoneMinutes else appMinutes, blocked, apps)
        }

        if (!serviceEnabled) {
            item {
                WarningCard(
                    "Turn on Shake Lock",
                    "Accessibility settings → Shake Lock (maybe under \"Installed apps\" or \"Downloaded apps\") → on.",
                ) {
                    Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                        Text("Open accessibility settings")
                    }
                }
            }
        }

        if (!batteryUnrestricted) {
            item { BatteryCard() }
        }

        item {
            SettingsCard("When you shake") {
                LockScopeSelector(scope) {
                    scope = it
                    store.shakeLocks = it
                }
                Text(
                    if (scope == LockScope.PHONE) "Everything except calls and your \"instead\" app is blocked, even the home screen."
                    else "All the apps you tick below get locked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
                Text("Lock for", style = MaterialTheme.typography.labelLarge)
                val phone = scope == LockScope.PHONE
                MinuteChips(
                    if (phone) PHONE_LOCK_MINUTES else APP_LOCK_MINUTES,
                    if (phone) phoneMinutes else appMinutes,
                ) {
                    if (phone) {
                        phoneMinutes = it
                        store.phoneLockMinutes = it
                    } else {
                        appMinutes = it
                        store.lockMinutes = it
                    }
                }
            }
        }

        item { SmartLockCard(store) }

        item { FlashcardCard(store) }

        item { InsteadAppCard(store, apps) }

        item { CalibrateCard(store) }

        item {
            Text(
                "Apps to block",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp),
            )
            Text(
                "${blocked.size} selected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
        }

        val list = sortedApps
        if (list == null) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else {
            val filtered = list.filter { it.label.contains(query.trim(), ignoreCase = true) }
            items(filtered, key = { it.packageName }) { app ->
                val checked = app.packageName in blocked
                val toggle = {
                    blocked = if (checked) blocked - app.packageName else blocked + app.packageName
                    store.blockedApps = blocked
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(if (checked) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable(onClick = toggle)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(
                        app.label,
                        fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    Checkbox(checked = checked, onCheckedChange = { toggle() })
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Big status card: ready / locked / off, the blocked apps and today's numbers. */
@Composable
private fun HeroCard(
    store: LockStore,
    stats: StatsLog,
    serviceEnabled: Boolean,
    scope: LockScope,
    minutes: Int,
    blocked: Set<String>,
    apps: List<AppEntry>?,
) {
    val remaining by produceState(store.remainingMillis()) {
        while (true) {
            value = store.remainingMillis()
            delay(1000)
        }
    }
    val today by produceState(0 to 0L) {
        value = withContext(Dispatchers.IO) {
            val events = stats.read(startOfDay(System.currentTimeMillis()))
            val escapes = events.count { it is LockEvent && it.trigger == Trigger.SHAKE }
            val useMs = events.filterIsInstance<UseEvent>().sumOf { it.until - it.at }
            escapes to useMs
        }
    }
    val white = Color.White
    val muted = Color.White.copy(alpha = 0.75f)

    Box(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(MaterialTheme.shapes.large)
            .background(HeroGradient)
            .padding(22.dp)
    ) {
        Column {
            val (status, title, subtitle) = when {
                !serviceEnabled -> Triple("OFF", "Not running yet", "Turn on the accessibility service below.")
                remaining > 0 -> Triple(
                    "LOCKED",
                    formatRemaining(remaining),
                    if (store.activeLock == LockScope.PHONE) "Your whole phone is locked." else "Your blocked apps are locked.",
                )
                blocked.isEmpty() -> Triple("ALMOST", "Pick your apps", "Tick the apps you doomscroll in, at the bottom.")
                else -> Triple(
                    "READY",
                    "Shake to escape",
                    "Shake while in one of these to lock " +
                        (if (scope == LockScope.PHONE) "your phone" else "them") + " for $minutes min.",
                )
            }
            Text(status, color = if (status == "LOCKED") Coral else muted, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(title, color = white, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = muted, style = MaterialTheme.typography.bodyMedium)

            val icons = apps?.filter { it.packageName in blocked }.orEmpty()
            if (icons.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy((-8).dp), verticalAlignment = Alignment.CenterVertically) {
                    icons.take(6).forEach {
                        Image(
                            it.icon,
                            contentDescription = it.label,
                            modifier = Modifier.size(34.dp).clip(CircleShape).border(2.dp, white, CircleShape).background(white),
                        )
                    }
                    if (icons.size > 6) {
                        Text("  +${icons.size - 6}", color = muted, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = white.copy(alpha = 0.2f))
            Spacer(Modifier.height(12.dp))
            val (escapes, useMs) = today
            Text(
                "Today: $escapes ${if (escapes == 1) "escape" else "escapes"} · ${formatDuration(useMs)} in blocked apps",
                color = muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LockScopeSelector(scope: LockScope, onSelect: (LockScope) -> Unit) {
    val options = listOf(LockScope.APPS to "Lock the apps", LockScope.PHONE to "Lock the phone")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (option, label) ->
            SegmentedButton(
                selected = scope == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) { Text(label) }
        }
    }
}

@Composable
private fun SettingsCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun WarningCard(title: String, text: String, actions: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.height(4.dp))
            Text(text, color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            actions()
        }
    }
}

/** Phones like Xiaomi freeze restricted background apps, which stops the lock from kicking in. */
@Composable
private fun BatteryCard() {
    val context = LocalContext.current
    val appUri = Uri.parse("package:${context.packageName}")
    WarningCard(
        "Let Shake Lock run in the background",
        "Otherwise your phone freezes it and you can open blocked apps during a lock. " +
            "On Xiaomi: App settings → Battery saver → No restrictions, and turn on Autostart.",
    ) {
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, appUri))
        }) { Text("Allow background use") }
        TextButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri))
        }) { Text("Open app settings") }
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun MinuteChips(options: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text("$option min") })
        }
    }
}

/** Experiments, each with its own switch so they can be tried one by one. */
@Composable
private fun SmartLockCard(store: LockStore) {
    var escalate by remember { mutableStateOf(store.escalate) }
    var charge by remember { mutableStateOf(store.chargeByShaking) }
    var reentry by remember { mutableStateOf(store.reentryLimit) }
    var reentryMinutes by remember { mutableIntStateOf(store.reentryMinutes) }
    var early by remember { mutableStateOf(store.earlyUnlock) }
    var nudge by remember { mutableStateOf(store.nudge) }
    var nudgeMinutes by remember { mutableIntStateOf(store.nudgeMinutes) }

    SettingsCard("Smart locks", "Optional extras, try them one by one.") {
        SwitchRow(
            "Escalating locks",
            "Every lock today doubles the time: 5 → 10 → 20 min (max 60, whole phone max 10).",
            escalate,
        ) { escalate = it; store.escalate = it }
        SwitchRow(
            "Keep shaking = longer lock",
            "One shake starts the lock, every extra second you keep shaking adds a minute.",
            charge,
        ) { charge = it; store.chargeByShaking = it }
        SwitchRow(
            "Limited comeback",
            "After a lock you get $reentryMinutes min in blocked apps, then it locks again (for the next hour).",
            reentry,
        ) { reentry = it; store.reentryLimit = it }
        if (reentry) {
            MinuteChips(listOf(3, 5, 10), reentryMinutes) { reentryMinutes = it; store.reentryMinutes = it }
        }
        SwitchRow(
            "Unlock early, with friction",
            "Lock screen gets an unlock button. With flash cards on you answer Spanish cards, otherwise you wait 30 s and type a sentence.",
            early,
        ) { early = it; store.earlyUnlock = it }
        SwitchRow(
            "Scroll nudge",
            "A soft buzz every $nudgeMinutes min of scrolling, as a reminder that you can shake out.",
            nudge,
        ) { nudge = it; store.nudge = it }
        if (nudge) {
            MinuteChips(listOf(10, 20, 30), nudgeMinutes) { nudgeMinutes = it; store.nudgeMinutes = it }
        }
    }
}

/** Spanish micro-learning on the lock screen. */
@Composable
private fun FlashcardCard(store: LockStore) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(store.flashcards) }
    var unlockCards by remember { mutableIntStateOf(store.unlockCards) }
    val known = remember { Srs(context).knownCount() }
    val total = remember { SpanishDeck.load(context).size }

    SettingsCard("Learn while locked", "Spanish → English, $known of $total words known so far.") {
        SwitchRow(
            "Spanish flash cards",
            "Practise common words on the lock screen. Wrong answers come back soon, known words fade out.",
            enabled,
        ) { enabled = it; store.flashcards = it }
        if (enabled) {
            Spacer(Modifier.height(4.dp))
            Text("Right answers to unlock early", style = MaterialTheme.typography.labelLarge)
            Text(
                "Only used when Unlock early is on in Smart locks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15).forEach { option ->
                    FilterChip(
                        selected = option == unlockCards,
                        onClick = { unlockCards = option; store.unlockCards = option },
                        label = { Text("$option cards") },
                    )
                }
            }
        }
    }
}

/** The app the lock screen points you to instead, e.g. To-Dodo. */
@Composable
private fun InsteadAppCard(store: LockStore, apps: List<AppEntry>?) {
    var instead by remember { mutableStateOf(store.insteadApp) }
    var picking by remember { mutableStateOf(false) }

    // First run: pick To-Dodo automatically if it's installed.
    LaunchedEffect(apps) {
        if (apps != null && !store.insteadAppChosen) {
            apps.firstOrNull { it.label.contains("dodo", ignoreCase = true) }?.let {
                instead = it.packageName
                store.insteadApp = it.packageName
            }
        }
    }

    SettingsCard("Do this instead", "Gets a button on the lock screen and always stays usable.") {
        val chosen = apps?.firstOrNull { it.packageName == instead }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (chosen != null) {
                Image(chosen.icon, contentDescription = null, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(
                chosen?.label ?: "Nothing chosen",
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = { picking = true }, enabled = apps != null) { Text("Change") }
        }
    }

    if (picking && apps != null) {
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
            title = { Text("Do this instead") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item {
                        Text(
                            "Nothing",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    instead = null
                                    store.insteadApp = null
                                    picking = false
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                    items(apps, key = { it.packageName }) { app ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    instead = app.packageName
                                    store.insteadApp = app.packageName
                                    picking = false
                                }
                                .padding(vertical = 6.dp),
                        ) {
                            Image(app.icon, contentDescription = null, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(app.label)
                        }
                    }
                }
            },
        )
    }
}

/** Sensitivity, a live shake tester and service diagnostics - folded away by default. */
@Composable
private fun CalibrateCard(store: LockStore) {
    var open by rememberSaveable { mutableStateOf(false) }
    var threshold by remember { mutableFloatStateOf(store.shakeThreshold) }
    val diag by Diagnostics.state.collectAsState()

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { open = !open },
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Shake sensitivity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        SENSITIVITIES.firstOrNull { it.second == threshold }?.first ?: "Custom",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(if (open) "▴" else "▾", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open) {
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    SENSITIVITIES.forEach { (name, g) ->
                        FilterChip(
                            selected = threshold == g,
                            onClick = {
                                threshold = g
                                store.shakeThreshold = g
                            },
                            label = { Text("$name (${g}g)") },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                ShakeTester(threshold)
                Spacer(Modifier.height(16.dp))
                Text("Service status", fontWeight = FontWeight.SemiBold)
                listOf(
                    "Running" to if (diag.serviceRunning) "yes" else "no",
                    "Last app seen" to (diag.lastApp ?: "–"),
                    "Last blocked app" to (diag.lastBlockedApp ?: "–"),
                    "Sensor readings" to diag.sensorEvents.toString(),
                    "Strongest shake" to "%.1fg".format(diag.peakG),
                    "Locks this run" to diag.shakes.toString(),
                ).forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(k, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(v, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Try a shake right here, with the same detector the service uses. */
@Composable
private fun ShakeTester(threshold: Float) {
    val context = LocalContext.current
    var currentThreshold by remember { mutableFloatStateOf(threshold) }
    currentThreshold = threshold
    var peak by remember { mutableFloatStateOf(1f) }
    var detected by remember { mutableIntStateOf(0) }
    var lastShakeG by remember { mutableFloatStateOf(0f) }

    DisposableEffect(Unit) {
        val sm = context.getSystemService(SensorManager::class.java)
        val detector = ShakeDetector({ currentThreshold }) { peakG ->
            detected++
            lastShakeG = peakG
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                peak = maxOf(peak, detector.onSensorChanged(event))
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        sm.registerListener(listener, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (detected > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp)
    ) {
        Column {
            Text(
                if (detected > 0) "Shake detected! ($detected) · %.1fg".format(lastShakeG) else "Shake the phone to test",
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Strongest: %.1fg · needs %.1fg, 3 times".format(peak, threshold),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { peak = 1f; detected = 0 }) { Text("Reset") }
        }
    }
}

private fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcherIntent, 0)
        .distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName != context.packageName }
        .map {
            AppEntry(
                packageName = it.activityInfo.packageName,
                label = it.loadLabel(pm).toString(),
                icon = it.loadIcon(pm).toBitmap(96, 96).asImageBitmap(),
            )
        }
        .sortedBy { it.label.lowercase() }
}
