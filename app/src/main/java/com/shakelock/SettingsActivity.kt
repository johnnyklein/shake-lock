package com.shakelock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val APP_LOCK_MINUTES = listOf(1, 2, 3, 5, 10, 15, 20, 30, 45, 60)

val PHONE_LOCK_MINUTES = listOf(1, 2, 3, 5, 10)

/** Everything that isn't needed day to day: lock options, extras, sensitivity. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = LockStore(this)
        setContent {
            ShakeLockTheme {
                SettingsScreen(store, onBack = ::finish)
            }
        }
    }
}

@Composable
private fun SettingsScreen(store: LockStore, onBack: () -> Unit) {
    val context = LocalContext.current
    var scope by remember { mutableStateOf(store.shakeLocks) }
    var appMinutes by remember { mutableIntStateOf(store.lockMinutes) }
    var phoneMinutes by remember { mutableIntStateOf(store.phoneLockMinutes) }
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) { loadApps(context) }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    SettingsCard("When you shake") {
                        LockScopeSelector(scope) {
                            scope = it
                            store.shakeLocks = it
                        }
                        Text(
                            if (scope == LockScope.PHONE) "Everything except calls and your \"instead\" app is blocked, even the home screen."
                            else "All the apps you ticked get locked.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                        )
                        Text("Lock for", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(4.dp))
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
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
