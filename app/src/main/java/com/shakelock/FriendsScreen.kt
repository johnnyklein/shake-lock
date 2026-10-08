package com.shakelock

import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val NUKES_PER_DAY = 3
private const val DAY_MS = 24 * 3_600_000L

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
            "Add a friend, then nuke them while they doomscroll: their blocked apps get bombed and locked for 30 seconds. " +
                "Only people you share your code with can nuke you, 3 times a day max.",
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
    var friends by remember { mutableStateOf<List<Profile>?>(null) }
    var recent by remember { mutableStateOf<List<Nuke>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val nukeStatus = remember { mutableStateMapOf<String, String>() }
    var accept by remember { mutableStateOf(store.acceptNukes) }

    LaunchedEffect(reload) {
        runCatching {
            me = Cloud.me() ?: Cloud.ensureProfile(store.cloudName ?: "Me")
            friends = Cloud.friends()
            recent = Cloud.recentNukes()
        }.onFailure { loadError = "Couldn't load: ${it.message}" }
    }

    val names = (friends.orEmpty() + listOfNotNull(me)).associate { it.id to it.name }
    fun nukesLeft(friendId: String) =
        NUKES_PER_DAY - recent.count { it.sender == me?.id && it.target == friendId && System.currentTimeMillis() - it.createdAtMillis < DAY_MS }

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
                        "Share it with a friend. Whoever enters it can nuke you (and you them).",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = me != null,
                        onClick = {
                            val text = "Add me on Shake Lock so we can nuke each other's doomscrolling 💥 My code: ${me?.code}\n" +
                                "Get the app: https://github.com/johnnyklein/shake-lock/releases/latest"
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF2B2896)),
                    ) { Text("Share code") }
                }
            }
        }

        item { AddFriendCard { reload++ } }

        loadError?.let { error ->
            item {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
        }

        item {
            Text(
                "Friends",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
            )
        }

        val list = friends
        when {
            list == null && loadError == null -> item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            list.isNullOrEmpty() -> item {
                Text(
                    "No friends yet. Share your code or enter theirs above.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            else -> items(list, key = { it.id }) { friend ->
                val left = nukesLeft(friend.id)
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(friend.name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(friend.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                nukeStatus[friend.id] ?: "$left of $NUKES_PER_DAY nukes left today",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            enabled = left > 0 && nukeStatus[friend.id] != "Launching… 🚀",
                            onClick = {
                                nukeStatus[friend.id] = "Launching… 🚀"
                                scope.launch {
                                    runCatching {
                                        val sent = Cloud.sendNuke(friend.id)
                                        // Give their phone a moment to report back.
                                        delay(3000)
                                        Cloud.nuke(sent.id)
                                    }.onSuccess { result ->
                                        nukeStatus[friend.id] = when (result?.status) {
                                            "hit" -> "💥 Direct hit! They were scrolling."
                                            "expired" -> "Fizzled, they've got nukes switched off."
                                            else -> "Armed. It hits when they next open a blocked app."
                                        }
                                    }.onFailure { nukeStatus[friend.id] = it.message ?: "Didn't launch" }
                                    recent = runCatching { Cloud.recentNukes() }.getOrDefault(recent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Color(0xFF3B0A00)),
                        ) { Text("💥 Nuke", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        if (recent.isNotEmpty()) {
            item {
                Text(
                    "Recent nukes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
                )
            }
            items(recent, key = { it.id }) { nuke ->
                val time = SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(nuke.createdAtMillis))
                val line = if (nuke.sender == me?.id) "You → ${names[nuke.target] ?: "?"}" else "${names[nuke.sender] ?: "?"} → you"
                val outcome = when (nuke.status) {
                    "hit" -> "💥 hit"
                    "expired" -> "fizzled"
                    else -> "armed"
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
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Can be nuked", fontWeight = FontWeight.Medium)
                        Text(
                            "Switch off for a shield: incoming nukes fizzle.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = accept, onCheckedChange = { accept = it; store.acceptNukes = it })
                }
            }
        }
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
                            runCatching { Cloud.addFriend(code) }
                                .onSuccess {
                                    message = "Added ${it.name} 🎉"
                                    code = ""
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
