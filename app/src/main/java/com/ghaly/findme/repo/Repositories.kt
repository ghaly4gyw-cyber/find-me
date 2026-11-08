package com.ghaly.findme.repo

import android.content.Context
import com.ghaly.findme.model.ChatMessage
import com.ghaly.findme.model.InactivityAlert
import com.ghaly.findme.model.PeerPosition
import com.ghaly.findme.model.Post
import com.ghaly.findme.model.PostComment
import com.ghaly.findme.model.Profile
import com.ghaly.findme.model.SosAlert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-memory, process-wide stores for mesh state. Kept simple on purpose (no DB)
 * for the MVP: state only needs to survive while the app/service is alive.
 *
 * [PeerRepository] and [ProfileRepository] additionally cache the last known
 * roster to disk (plain SharedPreferences JSON) so that if the app/service
 * restarts — a crash, a phone reboot, Android killing it under memory
 * pressure — the roster doesn't come back completely empty. On a safety app,
 * "I don't know where anyone was" for even a minute after a restart is worse
 * than showing slightly-stale cached positions until fresh ones arrive.
 */
object PeerRepository {
    private const val PREFS = "find_me_peers_cache"
    private const val KEY_DATA = "peers_json"

    private var appContext: Context? = null
    private val _peers = MutableStateFlow<Map<String, PeerPosition>>(emptyMap())
    val peers: StateFlow<Map<String, PeerPosition>> = _peers

    /** Call once, early (e.g. the mesh service's onCreate), to load the cached roster. */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        loadFromDisk()
    }

    fun upsert(position: PeerPosition) {
        _peers.update { it + (position.deviceId to position) }
        saveToDisk()
    }

    fun markDisconnected(deviceId: String) {
        _peers.update { map ->
            val existing = map[deviceId] ?: return@update map
            map + (deviceId to existing.copy(connected = false))
        }
        saveToDisk()
    }

    private fun loadFromDisk() {
        val ctx = appContext ?: return
        try {
            val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DATA, null) ?: return
            val arr = JSONArray(json)
            val loaded = mutableMapOf<String, PeerPosition>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("deviceId")
                loaded[id] = PeerPosition(
                    deviceId = id,
                    name = o.getString("name"),
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    ts = o.getLong("ts"),
                    connected = false // nothing is "live" right after a restart
                )
            }
            if (loaded.isNotEmpty()) _peers.value = loaded
        } catch (e: Exception) {
            // corrupted/old cache format — just start empty, nothing to recover
        }
    }

    private fun saveToDisk() {
        val ctx = appContext ?: return
        try {
            val arr = JSONArray()
            for (p in _peers.value.values) {
                arr.put(
                    JSONObject()
                        .put("deviceId", p.deviceId)
                        .put("name", p.name)
                        .put("lat", p.lat)
                        .put("lon", p.lon)
                        .put("ts", p.ts)
                )
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DATA, arr.toString())
                .apply()
        } catch (e: Exception) {
            // best-effort cache — losing it just means a slower warm-up next time
        }
    }
}

object ChatRepository {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    fun add(message: ChatMessage) {
        _messages.update { it + message }
    }
}

object SosRepository {
    private val _alerts = MutableStateFlow<Map<String, SosAlert>>(emptyMap())
    val alerts: StateFlow<Map<String, SosAlert>> = _alerts

    fun upsert(alert: SosAlert) {
        _alerts.update { it + (alert.senderId to alert) }
    }

    fun clear(senderId: String) {
        _alerts.update { map ->
            val existing = map[senderId] ?: return@update map
            map + (senderId to existing.copy(active = false))
        }
    }
}

object InactivityRepository {
    private val _alerts = MutableStateFlow<Map<String, InactivityAlert>>(emptyMap())
    val alerts: StateFlow<Map<String, InactivityAlert>> = _alerts

    fun upsert(alert: InactivityAlert) {
        _alerts.update { it + (alert.senderId to alert) }
    }

    fun clear(senderId: String) {
        _alerts.update { map ->
            val existing = map[senderId] ?: return@update map
            map + (senderId to existing.copy(active = false))
        }
    }
}

object ProfileRepository {
    private const val PREFS = "find_me_profiles_cache"
    private const val KEY_DATA = "profiles_json"

    private var appContext: Context? = null
    private val _profiles = MutableStateFlow<Map<String, Profile>>(emptyMap())
    val profiles: StateFlow<Map<String, Profile>> = _profiles

    /** Call once, early (e.g. the mesh service's onCreate), to load cached peer profiles. */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        loadFromDisk()
    }

    fun upsert(profile: Profile) {
        _profiles.update { it + (profile.deviceId to profile) }
        saveToDisk()
    }

    fun get(deviceId: String): Profile? = _profiles.value[deviceId]

    private fun loadFromDisk() {
        val ctx = appContext ?: return
        try {
            val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DATA, null) ?: return
            val arr = JSONArray(json)
            val loaded = mutableMapOf<String, Profile>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("deviceId")
                loaded[id] = Profile(
                    deviceId = id,
                    name = o.getString("name"),
                    phone = o.optString("phone", "").ifEmpty { null },
                    description = o.optString("description", "").ifEmpty { null },
                    photoBase64 = o.optString("photo", "").ifEmpty { null },
                    ts = o.optLong("ts", System.currentTimeMillis())
                )
            }
            if (loaded.isNotEmpty()) _profiles.value = loaded
        } catch (e: Exception) {
            // corrupted/old cache format — just start empty, nothing to recover
        }
    }

    private fun saveToDisk() {
        val ctx = appContext ?: return
        try {
            val arr = JSONArray()
            for (p in _profiles.value.values) {
                arr.put(
                    JSONObject()
                        .put("deviceId", p.deviceId)
                        .put("name", p.name)
                        .put("phone", p.phone ?: "")
                        .put("description", p.description ?: "")
                        .put("photo", p.photoBase64 ?: "")
                        .put("ts", p.ts)
                )
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DATA, arr.toString())
                .apply()
        } catch (e: Exception) {
            // best-effort cache — losing it just means a slower warm-up next time
        }
    }
}

object GroupChatRepository {
    private val _messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<ChatMessage>>> = _messages

    private val seenIds = mutableSetOf<String>()

    fun add(groupId: String, message: ChatMessage) {
        if (!seenIds.add(message.id)) return
        _messages.update { map ->
            map + (groupId to (map[groupId].orEmpty() + message))
        }
    }

    fun forGroup(groupId: String): List<ChatMessage> = _messages.value[groupId].orEmpty()
}

object PostRepository {
    private val _posts = MutableStateFlow<List<Post>>(emptyList())
    val posts: StateFlow<List<Post>> = _posts

    private val _comments = MutableStateFlow<Map<String, List<PostComment>>>(emptyMap())
    val comments: StateFlow<Map<String, List<PostComment>>> = _comments

    private val seenPostIds = mutableSetOf<String>()
    private val seenCommentIds = mutableSetOf<String>()

    fun addPost(post: Post) {
        if (!seenPostIds.add(post.id)) return
        _posts.update { it + post }
    }

    fun addComment(comment: PostComment) {
        if (!seenCommentIds.add(comment.id)) return
        _comments.update { map ->
            val list = map[comment.postId].orEmpty() + comment
            map + (comment.postId to list)
        }
    }

    fun commentsFor(postId: String): List<PostComment> = _comments.value[postId].orEmpty()
}
