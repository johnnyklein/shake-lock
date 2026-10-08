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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
        setContent {
            ShakeLockTheme {
                MainScreen(store, StatsLog(this), serviceEnabled, batteryUnrestricted)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check every time we come back from the accessibility settings screen.
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
    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Setup") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Stats") })
            }
            if (tab == 0) SetupScreen(store, serviceEnabled, batteryUnrestricted) else StatsScreen(stats)
        }
    }
}

@Composable
private fun SetupScreen(store: LockStore, serviceEnabled: Boolean, batteryUnrestricted: Boolean) {
    val context = LocalContext.current
    var blocked by remember { mutableStateOf(store.blockedApps) }
    var scope by remember { mutableStateOf(store.shakeLocks) }
    var appMinutes by remember { mutableIntStateOf(store.lockMinutes) }
    var phoneMinutes by remember { mutableIntStateOf(store.phoneLockMinutes) }
    var threshold by remember { mutableFloatStateOf(store.shakeThreshold) }
    val diag by Diagnostics.state.collectAsState()
    var query by remember { mutableStateOf("") }
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) { loadApps(context) }
    }
    val remaining by produceState(store.remainingMillis()) {
        while (true) {
            value = store.remainingMillis()
            delay(1000)
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Shake Lock", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Caught yourself doomscrolling? Shake the phone while in one of the apps below and " +
                        if (scope == LockScope.PHONE) "your whole phone gets locked for $phoneMinutes min."
                        else "all of them get locked for $appMinutes min.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!serviceEnabled) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Turn on Shake Lock", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("Open Accessibility settings → Shake Lock (may be under \"Installed apps\" or \"Downloaded apps\") → turn it on.")
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) { Text("Open accessibility settings") }
                    }
                }
            }
        }

        if (!batteryUnrestricted) {
            item {
                BatteryCard()
            }
        }

        if (remaining > 0) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        "Locked for ${formatRemaining(remaining)} more",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        item {
            Text(
                "When you shake, lock",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                listOf(LockScope.APPS to "Selected apps", LockScope.PHONE to "Whole phone").forEach { (option, name) ->
                    FilterChip(
                        selected = scope == option,
                        onClick = {
                            scope = option
                            store.shakeLocks = option
                        },
                        label = { Text(name) },
                    )
                }
            }
            if (scope == LockScope.PHONE) {
                Text(
                    "Everything except the phone app is blocked, including the home screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Text(
                "Lock duration",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                val phone = scope == LockScope.PHONE
                (if (phone) PHONE_LOCK_MINUTES else APP_LOCK_MINUTES).forEach { option ->
                    FilterChip(
                        selected = option == if (phone) phoneMinutes else appMinutes,
                        onClick = {
                            if (phone) {
                                phoneMinutes = option
                                store.phoneLockMinutes = option
                            } else {
                                appMinutes = option
                                store.lockMinutes = option
                            }
                        },
                        label = { Text("$option min") },
                    )
                }
            }
        }

        item {
            SmartLockSection(store)
        }

        item {
            InsteadAppSection(store, apps)
        }

        item {
            Text(
                "Shake sensitivity",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
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
            ShakeTester(threshold)
        }

        item {
            StatusCard(diag)
        }

        item {
            Text(
                "Apps to block (${blocked.size} selected)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
        }

        val list = apps
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
                        .clickable(onClick = toggle)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(app.label, modifier = Modifier.weight(1f))
                    Checkbox(checked = checked, onCheckedChange = { toggle() })
                }
            }
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

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Test it here", fontWeight = FontWeight.Bold)
            Text(
                "Strongest shake: %.1fg (needs %.1fg, 3 times)".format(peak, threshold),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (detected > 0) "Shake detected! ($detected) · %.1fg".format(lastShakeG) else "No shake detected yet",
                color = if (detected > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            Button(onClick = { peak = 1f; detected = 0 }, modifier = Modifier.padding(top = 8.dp)) { Text("Reset") }
        }
    }
}

/** What the background service last saw - check this after shaking inside a blocked app. */
@Composable
private fun StatusCard(diag: Diagnostics.State) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Service status", fontWeight = FontWeight.Bold)
            val lines = listOf(
                "Service running" to if (diag.serviceRunning) "yes" else "NO - turn it on in accessibility settings",
                "Last app seen" to (diag.lastApp ?: "none yet"),
                "Last blocked app opened" to (diag.lastBlockedApp ?: "none yet"),
                "Sensor readings in blocked app" to diag.sensorEvents.toString(),
                "Strongest shake in blocked app" to "%.1fg".format(diag.peakG),
                "Locks triggered" to diag.shakes.toString(),
            )
            lines.forEach { (k, v) ->
                Text("$k: $v", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Phones like Xiaomi freeze restricted background apps, which stops the lock from kicking in. */
@Composable
private fun BatteryCard() {
    val context = LocalContext.current
    val appUri = Uri.parse("package:${context.packageName}")
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Let Shake Lock run in the background", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Otherwise your phone freezes it and you can open blocked apps during a lock. " +
                    "On Xiaomi: App settings → Battery saver → No restrictions, and turn on Autostart."
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, appUri))
            }) { Text("Allow background use") }
            Button(
                onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri)) },
                modifier = Modifier.padding(top = 4.dp),
            ) { Text("Open app settings") }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
    )
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
    ) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text("$option min") })
        }
    }
}

/** Experiments, each with its own switch so they can be tried one by one. */
@Composable
private fun SmartLockSection(store: LockStore) {
    var escalate by remember { mutableStateOf(store.escalate) }
    var strength by remember { mutableStateOf(store.strengthScales) }
    var reentry by remember { mutableStateOf(store.reentryLimit) }
    var reentryMinutes by remember { mutableIntStateOf(store.reentryMinutes) }
    var early by remember { mutableStateOf(store.earlyUnlock) }
    var nudge by remember { mutableStateOf(store.nudge) }
    var nudgeMinutes by remember { mutableIntStateOf(store.nudgeMinutes) }

    SectionTitle("Smart locks")
    SwitchRow(
        "Escalating locks",
        "Every lock today doubles the time: 5 → 10 → 20 min (max 60, whole phone max 10).",
        escalate,
    ) { escalate = it; store.escalate = it }
    SwitchRow(
        "Harder shake = longer lock",
        "Shake a bit harder for 2× the time, really hard for 3×. Try it in the tester below.",
        strength,
    ) { strength = it; store.strengthScales = it }
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
        "Lock screen gets an unlock button: wait 30 s, then type a sentence to get back in.",
        early,
    ) { early = it; store.earlyUnlock = it }
    SwitchRow(
        "Scroll nudge",
        "A soft buzz every $nudgeMinutes min of scrolling, to remind you that you can shake out.",
        nudge,
    ) { nudge = it; store.nudge = it }
    if (nudge) {
        MinuteChips(listOf(10, 20, 30), nudgeMinutes) { nudgeMinutes = it; store.nudgeMinutes = it }
    }
}

/** The app the lock screen points you to instead, e.g. To-Dodo. */
@Composable
private fun InsteadAppSection(store: LockStore, apps: List<AppEntry>?) {
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

    SectionTitle("Do this instead")
    Text(
        "The lock screen gets a button for this app, and it stays usable during a whole-phone lock.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    val chosen = apps?.firstOrNull { it.packageName == instead }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        if (chosen != null) {
            Image(chosen.icon, contentDescription = null, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(chosen?.label ?: "Nothing chosen", modifier = Modifier.weight(1f))
        TextButton(onClick = { picking = true }, enabled = apps != null) { Text("Change") }
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
