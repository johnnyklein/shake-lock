package com.shakelock

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Shown on top of a blocked app (or everything, during a whole-phone lock). */
class BlockedActivity : ComponentActivity() {
    private var appLabel by mutableStateOf("")
    private lateinit var store: LockStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = LockStore(this)
        appLabel = labelFor(intent.getStringExtra(EXTRA_PACKAGE) ?: store.lastLockPkg)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // Back does nothing while the whole phone is locked.
            override fun handleOnBackPressed() {
                if (!store.isPhoneLocked()) goHome()
            }
        })
        val insteadLabel = store.insteadApp
            ?.takeIf { packageManager.getLaunchIntentForPackage(it) != null }
            ?.let { labelFor(it) }
        setContent {
            ShakeLockTheme {
                BlockedScreen(
                    appLabel = appLabel,
                    store = store,
                    insteadLabel = insteadLabel,
                    onDone = ::goHome,
                    onEmergencyCall = ::openDialer,
                    onOpenInstead = ::openInstead,
                    onUnlockEarly = ::unlockEarly,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        appLabel = labelFor(intent.getStringExtra(EXTRA_PACKAGE))
    }

    private fun labelFor(pkg: String?): String {
        if (pkg == null) return "This app"
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun openDialer() {
        startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openInstead() {
        val pkg = store.insteadApp ?: return
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun unlockEarly() {
        store.endLockEarly()
        StatsLog(this).add(EarlyUnlockEvent(System.currentTimeMillis()))
        val pkg = store.lastLockPkg
        if (store.activeLock == LockScope.APPS && pkg != null) {
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        finish()
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
    }
}

private const val UNLOCK_PHRASE = "I choose to keep scrolling"
private const val UNLOCK_WAIT_SECONDS = 30

@Composable
private fun BlockedScreen(
    appLabel: String,
    store: LockStore,
    insteadLabel: String?,
    onDone: () -> Unit,
    onEmergencyCall: () -> Unit,
    onOpenInstead: () -> Unit,
    onUnlockEarly: () -> Unit,
) {
    val phoneLock = store.activeLock == LockScope.PHONE
    val remaining by produceState(store.remainingMillis()) {
        while (value > 0) {
            delay(1000)
            value = store.remainingMillis()
        }
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (remaining > 0) {
                    Text(
                        if (phoneLock) "Phone locked" else "$appLabel is locked",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    val why = when {
                        store.lastTrigger == Trigger.REENTRY -> "Your ${store.reentryMinutes} minutes back are up."
                        store.lastSessionMs >= 60_000 -> "You were on $appLabel for ${formatDuration(store.lastSessionMs)}."
                        else -> null
                    }
                    if (why != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            why,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(
                        formatRemaining(remaining),
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Put the phone down. Go do something else.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(40.dp))
                    if (insteadLabel != null) {
                        Button(onClick = onOpenInstead) { Text("Open $insteadLabel instead") }
                    }
                    if (phoneLock) {
                        OutlinedButton(onClick = onEmergencyCall) { Text("Phone / emergency call") }
                    }
                    if (store.earlyUnlock) {
                        Spacer(Modifier.height(24.dp))
                        EarlyUnlock(onUnlockEarly)
                    }
                } else {
                    Text("Lock is over", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(40.dp))
                    Button(onClick = onDone) { Text("OK") }
                }
            }
        }
    }
}

/** Getting back in early is possible, but takes a 30 s wait and typing a sentence. */
@Composable
private fun EarlyUnlock(onUnlock: () -> Unit) {
    var stage by remember { mutableIntStateOf(0) } // 0 = button, 1 = waiting, 2 = typing
    var secondsLeft by remember { mutableIntStateOf(UNLOCK_WAIT_SECONDS) }
    var typed by remember { mutableStateOf("") }

    LaunchedEffect(stage) {
        if (stage == 1) {
            secondsLeft = UNLOCK_WAIT_SECONDS
            while (secondsLeft > 0) {
                delay(1000)
                secondsLeft--
            }
            stage = 2
        }
    }

    when (stage) {
        0 -> TextButton(onClick = { stage = 1 }) { Text("Unlock early") }
        1 -> Text(
            "Still want in? Take a breath first… $secondsLeft s",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Type \"$UNLOCK_PHRASE\" to unlock",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = onUnlock,
                enabled = typed.trim().equals(UNLOCK_PHRASE, ignoreCase = true),
            ) { Text("Unlock") }
        }
    }
}
