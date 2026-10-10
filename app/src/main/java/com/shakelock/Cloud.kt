package com.shakelock

import android.util.Log
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime

@Serializable
data class Profile(val id: String, val name: String, val code: String)

@Serializable
private data class Friendship(val a: String, val b: String)

@Serializable
data class Nuke(
    val id: Long,
    val sender: String,
    val target: String,
    @SerialName("created_at") val createdAt: String,
    val status: String = "armed",
    /** The secret extra nuke (5 taps on an empty nuke button). */
    val bonus: Boolean = false,
) {
    val createdAtMillis get() = runCatching { OffsetDateTime.parse(createdAt).toInstant().toEpochMilli() }.getOrDefault(0L)
}

/** Friends & nukes backend (Supabase). Every phone signs in anonymously. */
object Cloud {
    private const val TAG = "ShakeLockCloud"

    val configured = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_KEY.isNotBlank()

    /** Long-lived scope for network work that should outlive a screen. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val client by lazy {
        createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
        }
    }

    private suspend fun myId(): String {
        client.auth.awaitInitialization()
        if (client.auth.currentSessionOrNull() == null) client.auth.signInAnonymously()
        return client.auth.currentUserOrNull()!!.id
    }

    /** Creates or renames my profile. */
    suspend fun ensureProfile(name: String): Profile {
        myId()
        return client.postgrest.rpc("ensure_profile", buildJsonObject { put("display_name", name) }).decodeAs()
    }

    suspend fun me(): Profile? {
        val id = myId()
        return client.from("profiles").select { filter { eq("id", id) } }.decodeSingleOrNull()
    }

    suspend fun friends(): List<Profile> {
        val id = myId()
        val ids = client.from("friendships").select().decodeList<Friendship>().map { if (it.a == id) it.b else it.a }
        if (ids.isEmpty()) return emptyList()
        return client.from("profiles").select { filter { isIn("id", ids) } }.decodeList<Profile>().sortedBy { it.name.lowercase() }
    }

    suspend fun addFriend(code: String): Profile {
        myId()
        return client.postgrest.rpc("add_friend", buildJsonObject { put("friend_code", code) }).decodeAs()
    }

    suspend fun removeFriend(friendId: String) {
        myId()
        client.postgrest.rpc("remove_friend", buildJsonObject { put("friend", friendId) })
    }

    /** Nukes reset at the sender's local midnight; [bonus] uses the secret extra one. */
    suspend fun sendNuke(targetId: String, bonus: Boolean = false): Nuke {
        myId()
        return client.postgrest.rpc("send_nuke", buildJsonObject {
            put("target_id", targetId)
            put("day_start", Instant.ofEpochMilli(startOfDay(System.currentTimeMillis())).toString())
            put("use_bonus", bonus)
        }).decodeAs()
    }

    suspend fun nuke(id: Long): Nuke? = client.from("nukes").select { filter { eq("id", id) } }.decodeSingleOrNull()

    suspend fun reportNuke(id: Long, status: String) {
        runCatching {
            client.postgrest.rpc("report_nuke", buildJsonObject {
                put("nuke_id", id)
                put("new_status", status)
            })
        }.onFailure { Log.w(TAG, "report_nuke failed", it) }
    }

    /** Last nukes I sent or received, newest first. */
    suspend fun recentNukes(): List<Nuke> {
        myId()
        return client.from("nukes").select {
            order("created_at", Order.DESCENDING)
            limit(50)
        }.decodeList()
    }

    /** Nukes still flying at me (e.g. sent while my phone was offline). */
    suspend fun armedForMe(sinceMillis: Long): List<Nuke> {
        val id = myId()
        return client.from("nukes").select {
            filter {
                eq("target", id)
                eq("status", "armed")
                gt("created_at", Instant.ofEpochMilli(sinceMillis).toString())
            }
        }.decodeList()
    }

    // --- Live inbox, used by LockService ---

    private var channel: RealtimeChannel? = null

    /** Set by LockService, so the Friends screen can start the inbox right after setting up a profile. */
    var inboxStarter: (() -> Unit)? = null

    /** Subscribes to nukes aimed at me; [onNuke] runs on the main thread. Safe to call repeatedly. */
    fun listen(onNuke: (Nuke) -> Unit) {
        if (!configured || channel != null) return
        scope.launch {
            runCatching {
                val id = myId()
                val ch = client.channel("nukes-$id")
                ch.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                    table = "nukes"
                    filter("target", FilterOperator.EQ, id)
                }.onEach { onNuke(it.decodeRecord<Nuke>()) }.launchIn(scope)
                ch.subscribe()
                channel = ch
                Log.d(TAG, "listening for nukes")
            }.onFailure { Log.w(TAG, "listen failed", it) }
        }
    }
}
