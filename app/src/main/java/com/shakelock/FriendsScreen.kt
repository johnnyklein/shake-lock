package com.shakelock

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val NUKES_PER_DAY = 3
// Easter egg: tap an empty nuke button this often within this window for one secret extra nuke.
private const val SECRET_TAPS = 5
private const val SECRET_WINDOW_MS = 10_000L

@Composable
fun FriendsScreen(store: LockStore) {
    if (!Cloud.configured) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "Friends need a server, and this build doesn't have one set up yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    var name by remember { mutableStateOf(store.cloudName) }
    val current = name
    if (current == null) {
        SetupName { picked ->
            store.cloudName = picked
            name = picked
            Cloud.inboxStarter?.invoke()
        }
    } else {
        FriendsList(store)
    }
}

/** First visit: pick the name your friends see. */
@Composable
private fun SetupName(onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("💥", fontSize = 56.sp)
        Spacer(Modifier.height(12.dp))
        Text("Nuke your friends", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Catch a friend doomscrolling and nuke them: their blocked apps get bombed and locked for 30 seconds. " +
                "It only hits if they're scrolling right then. Only friends you accept can nuke you, 3 times a day max.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.take(30) },
            label = { Text("Your name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = input.isNotBlank() && !busy,
            onClick = {
                busy = true
                error = null
                scope.launch {
                    runCatching { Cloud.ensureProfile(input.trim()) }
                        .onSuccess { onDone(it.name) }
                        .onFailure { error = "Couldn't connect: ${it.message}" }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (busy) "Setting up…" else "Let's go") }
    }
}

@Composable
private fun FriendsList(store: LockStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf<Profile?>(null) }
    var connections by remember { mutableStateOf<List<Connection>?>(null) }
    var recent by remember { mutableStateOf<List<Nuke>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var accept by remember { mutableStateOf(store.acceptNukes) }
    var hideHits by remember { mutableStateOf(store.hideHits) }
    var confirmNewCode by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf<Profile?>(null) }

    // Back from the launch screen (or anywhere): refresh.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) reload++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(reload) {
        runCatching {
            me = Cloud.me() ?: Cloud.ensureProfile(store.cloudName ?: "Me")
            // The server resets your nukes at your midnight, so it needs your time zone.
            Cloud.setSettings(TimeZone.getDefault().id, store.hideHits)
            connections = Cloud.connections()
            recent = Cloud.recentNukes()
            loadError = null
        }.onFailure { loadError = "Couldn't load: ${it.message}" }
    }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onFailure { Toast.makeText(context, it.message ?: "Didn't work", Toast.LENGTH_SHORT).show() }
            reload++
        }
    }

    val friends = connections.orEmpty().filter { it.state == Connection.State.FRIEND }.map { it.profile }
    val incoming = connections.orEmpty().filter { it.state == Connection.State.INCOMING }.map { it.profile }
    val outgoing = connections.orEmpty().filter { it.state == Connection.State.OUTGOING }.map { it.profile }
    val names = (connections.orEmpty().map { it.profile } + listOfNotNull(me)).associate { it.id to it.name }
    // Resets at midnight; the secret bonus nuke doesn't count.
    fun nukesLeft(friendId: String): Int {
        val today = startOfDay(System.currentTimeMillis())
        return NUKES_PER_DAY - recent.count { it.sender == me?.id && it.target == friendId && !it.bonus && it.createdAtMillis >= today }
    }
    val emptyTaps = remember { mutableMapOf<String, MutableList<Long>>() }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(HeroGradient)
                    .padding(22.dp)
            ) {
                Column {
                    Text("YOUR CODE", color = Color.White.copy(alpha = 0.75f), fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        me?.code ?: "······",
                        color = Color.White,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp,
                    )
                    Text(
                        "Friends enter it to send you a request. Only people you accept can nuke you.",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            enabled = me != null,
                            onClick = {
                                val text = "Add me on Airlock so we can nuke each other's doomscrolling 💥 My code: ${me?.code}\n" +
                                    "Get the app: https://github.com/johnnyklein/shake-lock/releases/latest"
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF2B2896)),
                        ) { Text("Share code") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(enabled = me != null, onClick = { confirmNewCode = true }) {
                            Text("New code", color = Color.White.copy(alpha = 0.85f))
                        }
                    }
                }
            }
        }

        item { AddFriendCard { reload++ } }

        loadError?.let { error ->
            item {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
        }

        if (incoming.isNotEmpty() || outgoing.isNotEmpty()) {
            item { SectionHeader("Requests") }
            items(incoming, key = { "in-" + it.id }) { person ->
                PersonRow(person.name, "Wants to be friends") {
                    TextButton(onClick = { act { Cloud.respondFriend(person.id, accept = false) } }) { Text("Decline") }
                    Button(onClick = { act { Cloud.respondFriend(person.id, accept = true) } }) { Text("Accept") }
                }
            }
            items(outgoing, key = { "out-" + it.id }) { person ->
                PersonRow(person.name, "Waiting for them to accept") {
                    TextButton(onClick = { act { Cloud.removeFriend(person.id) } }) { Text("Cancel") }
                }
            }
        }

        item { SectionHeader("Friends") }

        when {
            connections == null && loadError == null -> item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            friends.isEmpty() -> item {
                Text(
                    "No friends yet. Share your code or enter theirs above.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            else -> items(friends, key = { it.id }) { friend ->
                val left = nukesLeft(friend.id)
                fun openLaunch(bonus: Boolean) = context.startActivity(
                    Intent(context, NukeLaunchActivity::class.java)
                        .putExtra(NukeLaunchActivity.EXTRA_TARGET_ID, friend.id)
                        .putExtra(NukeLaunchActivity.EXTRA_TARGET_NAME, friend.name)
                        .putExtra(NukeLaunchActivity.EXTRA_BONUS, bonus)
                )
                PersonRow(friend.name, "$left of $NUKES_PER_DAY nukes left today") {
                    Button(
                        onClick = {
                            if (left > 0) {
                                openLaunch(bonus = false)
                            } else {
                                // Out of nukes... unless you hammer the button.
                                val now = System.currentTimeMillis()
                                val taps = emptyTaps.getOrPut(friend.id) { mutableListOf() }
                                taps.removeAll { now - it > SECRET_WINDOW_MS }
                                taps += now
                                if (taps.size >= SECRET_TAPS) {
                                    taps.clear()
                                    openLaunch(bonus = true)
                                } else if (taps.size == 1) {
                                    Toast.makeText(context, "No nukes left for today", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = if (left > 0) {
                            ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Color(0xFF3B0A00))
                        } else {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    ) { Text("💥 Nuke", fontWeight = FontWeight.Bold) }
                    FriendMenu(
                        onRemove = { act { Cloud.removeFriend(friend.id) } },
                        onBlock = { confirmBlock = friend },
                    )
                }
            }
        }

        if (recent.isNotEmpty()) {
            item { SectionHeader("Recent nukes") }
            items(recent, key = { it.id }) { nuke ->
                val time = SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(nuke.createdAtMillis))
                val line = if (nuke.sender == me?.id) "You → ${names[nuke.target] ?: "?"}" else "${names[nuke.sender] ?: "?"} → you"
                val outcome = when (nuke.status) {
                    "hit" -> "💥 hit"
                    "expired" -> "missed"
                    "hidden" -> "launched"
                    else -> "no answer"
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text(line, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("$outcome · $time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Privacy", fontWeight = FontWeight.SemiBold)
                    ToggleRow("Can be nuked", "Switch off for a shield: incoming nukes miss.", accept) {
                        accept = it
                        store.acceptNukes = it
                    }
                    ToggleRow("Hide if I was scrolling", "Friends who nuke you only see \"launched\", not whether it hit.", hideHits) {
                        hideHits = it
                        store.hideHits = it
                        act { Cloud.setSettings(TimeZone.getDefault().id, it) }
                    }
                }
            }
        }
    }

    if (confirmNewCode) {
        AlertDialog(
            onDismissRequest = { confirmNewCode = false },
            title = { Text("Get a new code?") },
            text = { Text("Your current code stops working. Friends you already have stay your friends.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmNewCode = false
                    act { me = Cloud.newCode() }
                }) { Text("New code") }
            },
            dismissButton = { TextButton(onClick = { confirmNewCode = false }) { Text("Cancel") } },
        )
    }
    confirmBlock?.let { person ->
        AlertDialog(
            onDismissRequest = { confirmBlock = null },
            title = { Text("Block ${person.name}?") },
            text = { Text("They're removed as a friend and can't send you a request again. They won't be told.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmBlock = null
                    act { Cloud.blockUser(person.id) }
                }) { Text("Block") }
            },
            dismissButton = { TextButton(onClick = { confirmBlock = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** A person with their initial, name, a status line and actions on the right. */
@Composable
private fun PersonRow(name: String, status: String, actions: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            actions()
        }
    }
}

@Composable
private fun FriendMenu(onRemove: () -> Unit, onBlock: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Remove friend") }, onClick = { open = false; onRemove() })
            DropdownMenuItem(text = { Text("Block") }, onClick = { open = false; onBlock() })
        }
    }
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AddFriendCard(onAdded: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Add a friend", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().filter(Char::isLetterOrDigit).take(6) },
                    placeholder = { Text("Their code") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = 3.sp),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                FilledTonalButton(
                    enabled = code.length == 6 && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            runCatching { Cloud.requestFriend(code) }
                                .onSuccess { answer ->
                                    message = when (answer.result) {
                                        "requested" -> "Request sent to ${answer.name}. They need to accept."
                                        "accepted" -> "You're now friends with ${answer.name} 🎉"
                                        "already" -> "You're already friends with ${answer.name}."
                                        "self" -> "That's your own code."
                                        "rate_limited" -> "Too many tries. Wait an hour."
                                        else -> "No one with that code."
                                    }
                                    if (answer.result == "requested" || answer.result == "accepted") code = ""
                                    onAdded()
                                }
                                .onFailure { message = it.message?.lineSequence()?.firstOrNull() ?: "Didn't work" }
                            busy = false
                        }
                    },
                ) { Text("Add") }
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
