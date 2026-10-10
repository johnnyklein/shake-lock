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
    private var setup by mutableStateOf(SetupState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = LockStore(this)
        val stats = StatsLog(this)
        setContent {
            ShakeLockTheme {
                MainScreen(store, stats, setup)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the settings screens: the helper bubble's job is done, re-check everything.
        Bubble.hide(this)
        setup = SetupState(
            serviceEnabled = Setup.serviceEnabled(this),
            batteryUnrestricted = Setup.batteryUnrestricted(this),
            canShowBubble = Setup.canShowBubble(this),
            restricted = Setup.restrictedSettings(this),
        )
    }
}

private data class SetupState(
    val serviceEnabled: Boolean = true,
    val batteryUnrestricted: Boolean = true,
    val canShowBubble: Boolean = true,
    val restricted: Boolean? = null,
) {
    val done get() = serviceEnabled && batteryUnrestricted
}

private data class AppEntry(val packageName: String, val label: String, val icon: ImageBitmap)

private val APP_LOCK_MINUTES = listOf(1, 5, 10, 15, 30)

private val PHONE_LOCK_MINUTES = listOf(1, 2, 3)

private val SENSITIVITIES = listOf("Gentle" to 1.6f, "Normal" to 2.0f, "Hard" to 2.6f)

@Composable
private fun MainScreen(store: LockStore, stats: StatsLog, setup: SetupState) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "Airlock",
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
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Friends") })
            }
            when (tab) {
                0 -> SetupScreen(store, stats, setup)
                1 -> StatsScreen(stats)
                else -> FriendsScreen(store)
            }
        }
    }
}

@Composable
private fun SetupScreen(store: LockStore, stats: StatsLog, setup: SetupState) {
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
            HeroCard(store, stats, setup.serviceEnabled, scope, if (scope == LockScope.PHONE) phoneMinutes else appMinutes, blocked, apps)
        }

        if (!setup.done) {
            item { SetupChecklist(setup) }
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
                !serviceEnabled -> Triple("OFF", "Not running yet", "Finish the setup below, it takes a minute.")
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

/** Step-by-step setup; the helper bubble explains each step on top of the Settings app. */
@Composable
private fun SetupChecklist(setup: SetupState) {
    val context = LocalContext.current
    val restrictedHint = "Tap ⋮ (top right) → Allow restricted settings"
    WarningCard("Set up Airlock", "A few switches so Airlock can see which app is open and keep running.") {
        if (!setup.canShowBubble) {
            SetupStep(
                mark = "?",
                title = "Helper bubble (optional)",
                text = "Shows a little tip on top of Settings, so you know exactly what to tap.",
                action = "Allow",
            ) { Setup.openBubblePermission(context) }
        }
        SetupStep(
            mark = if (setup.serviceEnabled) "✓" else "1",
            title = "Turn on Airlock",
            text = when {
                setup.serviceEnabled -> "Done."
                setup.restricted == true -> "Android blocks this for apps that aren't from the Play Store. Unlock it first, then turn Airlock on."
                else -> "One switch in the accessibility settings."
            },
            action = when {
                setup.serviceEnabled -> null
                setup.restricted == true -> "Unlock"
                else -> "Turn on"
            },
        ) {
            if (setup.restricted == true) Setup.openAppInfo(context, restrictedHint, "Then come back to Airlock.")
            else Setup.openAccessibility(context)
        }
        if (!setup.serviceEnabled && setup.restricted != true) {
            TextButton(onClick = { Setup.openAppInfo(context, restrictedHint, "Then come back and turn Airlock on.") }) {
                Text("Switch greyed out?")
            }
        }
        SetupStep(
            mark = if (setup.batteryUnrestricted) "✓" else "2",
            title = "Keep it running",
            text = when {
                setup.batteryUnrestricted && Setup.isXiaomi -> "Xiaomi also needs: App info → Battery saver → No restrictions, and Autostart on."
                setup.batteryUnrestricted -> "Done."
                Setup.isXiaomi -> "Allow it, then in App info set Battery saver → No restrictions and turn on Autostart. Otherwise Xiaomi freezes Airlock."
                else -> "Lets Airlock keep running in the background."
            },
            action = if (setup.batteryUnrestricted) null else "Allow",
        ) { Setup.requestBattery(context) }
        if (Setup.isXiaomi) {
            TextButton(onClick = {
                Setup.openAppInfo(context, "Battery saver → No restrictions", "And turn on Autostart.")
            }) { Text("Open Xiaomi battery settings") }
        }
    }
}

@Composable
private fun SetupStep(mark: String, title: String, text: String, action: String?, onAction: () -> Unit) {
    val done = mark == "✓"
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.15f)),
        ) {
            Text(
                mark,
                fontWeight = FontWeight.Bold,
                color = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            Button(onClick = onAction) { Text(action) }
        }
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

/** Optional extras. */
@Composable
private fun SmartLockCard(store: LockStore) {
    var charge by remember { mutableStateOf(store.chargeByShaking) }
    var nudge by remember { mutableStateOf(store.nudge) }
    var nudgeMinutes by remember { mutableIntStateOf(store.nudgeMinutes) }

    SettingsCard("Extras") {
        SwitchRow(
            "Keep shaking = longer lock",
            "One shake starts the lock, every extra second you keep shaking adds a minute.",
            charge,
        ) { charge = it; store.chargeByShaking = it }
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
