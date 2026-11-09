package com.ghaly.findme.repo

import android.content.Context
import com.ghaly.findme.model.ChatMessage
import com.ghaly.findme.model.InactivityAlert
import com.ghaly.findme.model.ListingKind
import com.ghaly.findme.model.MarketListing
import com.ghaly.findme.model.PeerPosition
import com.ghaly.findme.model.Post
import com.ghaly.findme.model.PostComment
import com.ghaly.findme.model.Profile
import com.ghaly.findme.model.SavedPlace
import com.ghaly.findme.model.SosAlert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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

    /** Last few positions per peer (in-memory only, not disk-cached — a trail
     *  that's briefly wrong after a restart is harmless, unlike the roster
     *  itself). Used to work out which way someone was moving when last
     *  heard from, so if they go quiet you have more to go on than just a
     *  single dot: a bearing from YOU to THEM tells you which way to walk;
     *  this tells you which way THEY were walking. */
    private const val HISTORY_LIMIT = 5
    private val history = ConcurrentHashMap<String, MutableList<Triple<Double, Double, Long>>>()

    /** Call once, early (e.g. the mesh service's onCreate), to load the cached roster. */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        loadFromDisk()
    }

    fun upsert(position: PeerPosition) {
        _peers.update { it + (position.deviceId to position) }
        recordHistory(position)
        saveToDisk()
    }

    private fun recordHistory(position: PeerPosition) {
        if (position.lat == 0.0 && position.lon == 0.0) return
        val list = history.getOrPut(position.deviceId) { mutableListOf() }
        synchronized(list) {
            list.add(Triple(position.lat, position.lon, position.ts))
            while (list.size > HISTORY_LIMIT) list.removeAt(0)
        }
    }

    /** Bearing (degrees, 0 = north) this peer appeared to be moving in, based on
     *  their last few reported positions. Null if we don't have enough recent
     *  points, or the spread between them is too small to tell real movement
     *  apart from ordinary GPS jitter while someone is just standing still. */
    fun headingOf(deviceId: String): Float? {
        val list = history[deviceId] ?: return null
        val snapshot = synchronized(list) { list.toList() }
        if (snapshot.size < 2) return null
        val (oldLat, oldLon, _) = snapshot.first()
        val (newLat, newLon, _) = snapshot.last()
        val results = FloatArray(2)
        android.location.Location.distanceBetween(oldLat, oldLon, newLat, newLon, results)
        if (results[0] < 20f) return null // too little movement to trust as a heading
        return results[1]
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

/** SOS alerts — disk-cached like the peer/profile rosters. In-memory-only was
 *  an oversight: if the service restarts (OOM-kill, a phone reboot) while a
 *  real SOS is still active, the alert isn't actually resolved — losing it
 *  from memory would make it silently vanish from everyone's screen with
 *  nothing about the underlying emergency having changed. */
object SosRepository {
    private const val PREFS = "find_me_sos_cache"
    private const val KEY_DATA = "sos_json"

    private var appContext: Context? = null
    private val _alerts = MutableStateFlow<Map<String, SosAlert>>(emptyMap())
    val alerts: StateFlow<Map<String, SosAlert>> = _alerts

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        loadFromDisk()
    }

    fun upsert(alert: SosAlert) {
        _alerts.update { it + (alert.senderId to alert) }
        saveToDisk()
    }

    fun clear(senderId: String) {
        _alerts.update { map ->
            val existing = map[senderId] ?: return@update map
            map + (senderId to existing.copy(active = false))
        }
        saveToDisk()
    }

    private fun loadFromDisk() {
        val ctx = appContext ?: return
        try {
            val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DATA, null) ?: return
            val arr = JSONArray(json)
            val loaded = mutableMapOf<String, SosAlert>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("senderId")
                loaded[id] = SosAlert(
                    id = o.getString("id"),
                    senderId = id,
                    senderName = o.getString("senderName"),
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    ts = o.getLong("ts"),
                    active = o.getBoolean("active")
                )
            }
            if (loaded.isNotEmpty()) _alerts.value = loaded
        } catch (e: Exception) {
            // corrupted/old cache format — just start empty, nothing to recover
        }
    }

    private fun saveToDisk() {
        val ctx = appContext ?: return
        try {
            val arr = JSONArray()
            for (a in _alerts.value.values) {
                arr.put(
                    JSONObject()
                        .put("id", a.id)
                        .put("senderId", a.senderId)
                        .put("senderName", a.senderName)
                        .put("lat", a.lat)
                        .put("lon", a.lon)
                        .put("ts", a.ts)
                        .put("active", a.active)
                )
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DATA, arr.toString())
                .apply()
        } catch (e: Exception) {
            // best-effort cache
        }
    }
}

/** Same reasoning and pattern as [SosRepository] — an active inactivity alert
 *  surviving a service restart matters just as much as an SOS does. */
object InactivityRepository {
    private const val PREFS = "find_me_inactivity_cache"
    private const val KEY_DATA = "inactivity_json"

    private var appContext: Context? = null
    private val _alerts = MutableStateFlow<Map<String, InactivityAlert>>(emptyMap())
    val alerts: StateFlow<Map<String, InactivityAlert>> = _alerts

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        loadFromDisk()
    }

    fun upsert(alert: InactivityAlert) {
        _alerts.update { it + (alert.senderId to alert) }
        saveToDisk()
    }

    fun clear(senderId: String) {
        _alerts.update { map ->
            val existing = map[senderId] ?: return@update map
            map + (senderId to existing.copy(active = false))
        }
        saveToDisk()
    }

    private fun loadFromDisk() {
        val ctx = appContext ?: return
        try {
            val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DATA, null) ?: return
            val arr = JSONArray(json)
            val loaded = mutableMapOf<String, InactivityAlert>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("senderId")
                loaded[id] = InactivityAlert(
                    senderId = id,
                    senderName = o.getString("senderName"),
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    ts = o.getLong("ts"),
                    active = o.getBoolean("active")
                )
            }
            if (loaded.isNotEmpty()) _alerts.value = loaded
        } catch (e: Exception) {
            // corrupted/old cache format — just start empty, nothing to recover
        }
    }

    private fun saveToDisk() {
        val ctx = appContext ?: return
        try {
            val arr = JSONArray()
            for (a in _alerts.value.values) {
                arr.put(
                    JSONObject()
                        .put("senderId", a.senderId)
                        .put("senderName", a.senderName)
                        .put("lat", a.lat)
                        .put("lon", a.lon)
                        .put("ts", a.ts)
                        .put("active", a.active)
                )
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DATA, arr.toString())
                .apply()
        } catch (e: Exception) {
            // best-effort cache
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

/** Community posts/comments, disk-cached the same way everything else here is —
 *  these were memory-only until this pass, which meant a service restart (the
 *  OS killing the app in the background, say) silently wiped the whole board.
 *  Capped to the most recent [MAX_POSTS]/[MAX_COMMENTS_PER_POST] so the cache
 *  can't grow without bound over a weeks-long deployment. */
object PostRepository {
    private const val PREFS = "find_me_posts_cache"
    private const val KEY_POSTS = "posts_json"
    private const val KEY_COMMENTS = "comments_json"
    private const val MAX_POSTS = 200
    private const val MAX_COMMENTS_PER_POST = 200

    private var appContext: Context? = null
    private val _posts = MutableStateFlow<List<Post>>(emptyList())
    val posts: StateFlow<List<Post>> = _posts

    private val _comments = MutableStateFlow<Map<String, List<PostComment>>>(emptyMap())
    val comments: StateFlow<Map<String, List<PostComment>>> = _comments

    private val seenPostIds = mutableSetOf<String>()
    private val seenCommentIds = mutableSetOf<String>()

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        loadFromDisk()
    }

    fun addPost(post: Post) {
        if (!seenPostIds.add(post.id)) return
        _posts.update { (it + post).takeLast(MAX_POSTS) }
        saveToDisk()
    }

    fun addComment(comment: PostComment) {
        if (!seenCommentIds.add(comment.id)) return
        _comments.update { map ->
            val list = (map[comment.postId].orEmpty() + comment).takeLast(MAX_COMMENTS_PER_POST)
            map + (comment.postId to list)
        }
        saveToDisk()
    }

    fun commentsFor(postId: String): List<PostComment> = _comments.value[postId].orEmpty()

    private fun loadFromDisk() {
        val ctx = appContext ?: return
        try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(KEY_POSTS, null)?.let { json ->
                val arr = JSONArray(json)
                val loaded = mutableListOf<Post>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    loaded += Post(
                        id = o.getString("id"),
                        authorId = o.getString("authorId"),
                        authorName = o.getString("authorName"),
                        text = o.getString("text"),
                        ts = o.getLong("ts")
                    )
                }
                _posts.value = loaded
                seenPostIds.addAll(loaded.map { it.id })
            }
            prefs.getString(KEY_COMMENTS, null)?.let { json ->
                val obj = JSONObject(json)
                val loaded = mutableMapOf<String, List<PostComment>>()
                for (postId in obj.keys()) {
                    val arr = obj.getJSONArray(postId)
                    val list = mutableListOf<PostComment>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list += PostComment(
                            id = o.getString("id"),
                            postId = postId,
                            authorId = o.getString("authorId"),
                            authorName = o.getString("authorName"),
                            text = o.getString("text"),
                            ts = o.getLong("ts")
                        )
                    }
                    loaded[postId] = list
                    seenCommentIds.addAll(list.map { it.id })
                }
                _comments.value = loaded
            }
        } catch (e: Exception) {
            // corrupted/old cache format — just start empty, nothing to recover
        }
    }

    private fun saveToDisk() {
        val ctx = appContext ?: return
        try {
            val postsArr = JSONArray()
            for (p in _posts.value) {
                postsArr.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("authorId", p.authorId)
                        .put("authorName", p.authorName)
                        .put("text", p.text)
                        .put("ts", p.ts)
                )
            }
            val commentsObj = JSONObject()
            for ((postId, list) in _comments.value) {
                val arr = JSONArray()
                for (c in list) {
                    arr.put(
                        JSONObject()
                            .put("id", c.id)
                            .put("authorId", c.authorId)
                            .put("authorName", c.authorName)
                            .put("text", c.text)
                            .put("ts", c.ts)
                    )
                }
                commentsObj.put(postId, arr)
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_POSTS, postsArr.toString())
                .putString(KEY_COMMENTS, commentsObj.toString())
                .apply()
        } catch (e: Exception) {
            // best-effort cache
        }
    }
}

/** Items/services people on the mesh are offering — equipment or gold for
 *  sale, an on-site service, or a freelance gig. Spreads mesh-wide the same
 *  way posts do; disk-cached so the board survives an app restart; capped
 *  so it can't grow without bound. A listing with [MarketListing.promoted]
 *  true always sorts first — that's the lever for actually making money
 *  from this screen, by selling that placement the same way Ghaly already
 *  sells his own client work (direct contact, not an in-app payment). */
object MarketRepository {
    private const val PREFS = "find_me_market_cache"
    private const val KEY_LISTINGS = "listings_json"
    private const val MAX_LISTINGS = 300

    private var appContext: Context? = null
    private val _listings = MutableStateFlow<List<MarketListing>>(emptyList())
    val listings: StateFlow<List<MarketListing>> = _listings

    private val seenIds = mutableSetOf<String>()

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        loadFromDisk()
    }

    /** Adds a listing we made ourselves, or one that arrived over the mesh.
     *  Upserts by id: a repeat delivery of a listing we already have (our
     *  own broadcast looping back, or a later carrier-pigeon replay) just
     *  refreshes it in place — which matters because that's also how a
     *  promoted-flag change reaches everyone, not only a brand-new listing. */
    fun add(listing: MarketListing) {
        val isNew = seenIds.add(listing.id)
        if (isNew) {
            _listings.update { (it + listing).takeLast(MAX_LISTINGS) }
        } else {
            _listings.update { list -> list.map { if (it.id == listing.id) listing else it } }
        }
        saveToDisk()
    }

    fun remove(id: String) {
        _listings.update { list -> list.filter { it.id != id } }
        saveToDisk()
    }

    private fun loadFromDisk() {
        val ctx = appContext ?: return
        try {
            val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LISTINGS, null) ?: return
            val arr = JSONArray(json)
            val loaded = mutableListOf<MarketListing>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                loaded += MarketListing(
                    id = o.getString("id"),
                    authorId = o.getString("authorId"),
                    authorName = o.getString("authorName"),
                    kind = try { ListingKind.valueOf(o.getString("kind")) } catch (e: Exception) { ListingKind.FOR_SALE },
                    title = o.getString("title"),
                    details = o.optString("details", ""),
                    priceText = o.optString("priceText", ""),
                    ts = o.optLong("ts", System.currentTimeMillis()),
                    promoted = o.optBoolean("promoted", false),
                    photoBase64 = o.optString("photo", "").ifEmpty { null }
                )
            }
            _listings.value = loaded
            seenIds.addAll(loaded.map { it.id })
        } catch (e: Exception) {
            // corrupted/old cache format — just start empty, nothing to recover
        }
    }

    private fun saveToDisk() {
        val ctx = appContext ?: return
        try {
            val arr = JSONArray()
            for (l in _listings.value) {
                arr.put(
                    JSONObject()
                        .put("id", l.id)
                        .put("authorId", l.authorId)
                        .put("authorName", l.authorName)
                        .put("kind", l.kind.name)
                        .put("title", l.title)
                        .put("details", l.details)
                        .put("priceText", l.priceText)
                        .put("ts", l.ts)
                        .put("promoted", l.promoted)
                        .put("photo", l.photoBase64 ?: "")
                )
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LISTINGS, arr.toString())
                .apply()
        } catch (e: Exception) {
            // best-effort cache
        }
    }
}

/** Places the user marked on this one phone — their own current spot, a
 *  campsite, a water source, where the vehicle is parked. Purely local
 *  (never sent over the mesh — this is "remember where I've been", not
 *  "tell others where I am"), disk-cached the same way the peer/profile
 *  rosters are so it survives an app restart. */
object SavedPlaceRepository {
    private const val PREFS = "find_me_saved_places"
    private const val KEY_DATA = "places_json"

    private var appContext: Context? = null
    private val _places = MutableStateFlow<List<SavedPlace>>(emptyList())
    val places: StateFlow<List<SavedPlace>> = _places

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        loadFromDisk()
    }

    fun add(name: String, lat: Double, lon: Double, sharedByName: String? = null): SavedPlace {
        val place = SavedPlace(
            id = UUID.randomUUID().toString(),
            name = name,
            lat = lat,
            lon = lon,
            ts = System.currentTimeMillis(),
            sharedByName = sharedByName
        )
        _places.update { it + place }
        saveToDisk()
        return place
    }

    fun rename(id: String, newName: String) {
        _places.update { list -> list.map { if (it.id == id) it.copy(name = newName) else it } }
        saveToDisk()
    }

    fun remove(id: String) {
        _places.update { list -> list.filter { it.id != id } }
        saveToDisk()
    }

    /** Marks an already-saved (previously private) place as now shared with
     *  the team under [sharedByName], returning the updated place so the
     *  caller can broadcast it — or null if no such place exists. */
    fun markShared(id: String, sharedByName: String): SavedPlace? {
        var updated: SavedPlace? = null
        _places.update { list -> list.map {
            if (it.id == id) it.copy(sharedByName = sharedByName).also { p -> updated = p } else it
        } }
        if (updated != null) saveToDisk()
        return updated
    }

    /** A place shared by someone (possibly ourselves) over the mesh.
     *  [placeId] is the place's own stable id — fixed forever once first
     *  shared, separate from whatever envelope id carried it this time —
     *  so repeated carrier-pigeon replays of the same place update the
     *  existing entry instead of piling up duplicates. */
    fun receiveShared(placeId: String, name: String, lat: Double, lon: Double, ts: Long, sharedByName: String) {
        val existing = _places.value.find { it.id == placeId }
        if (existing != null) {
            // Already known — refresh name in case it was renamed upstream,
            // but never overwrite a newer local copy with a stale replay.
            if (ts >= existing.ts) {
                _places.update { list -> list.map {
                    if (it.id == placeId) it.copy(name = name, ts = ts, sharedByName = sharedByName) else it
                } }
                saveToDisk()
            }
            return
        }
        val place = SavedPlace(id = placeId, name = name, lat = lat, lon = lon, ts = ts, sharedByName = sharedByName)
        _places.update { it + place }
        saveToDisk()
    }

    private fun loadFromDisk() {
        val ctx = appContext ?: return
        try {
            val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DATA, null) ?: return
            val arr = JSONArray(json)
            val loaded = mutableListOf<SavedPlace>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                loaded += SavedPlace(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    ts = o.optLong("ts", System.currentTimeMillis()),
                    sharedByName = o.optString("sharedByName", "").ifEmpty { null }
                )
            }
            _places.value = loaded
        } catch (e: Exception) {
            // corrupted/old cache format — just start empty, nothing to recover
        }
    }

    private fun saveToDisk() {
        val ctx = appContext ?: return
        try {
            val arr = JSONArray()
            for (p in _places.value) {
                arr.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("name", p.name)
                        .put("lat", p.lat)
                        .put("lon", p.lon)
                        .put("ts", p.ts)
                        .put("sharedByName", p.sharedByName ?: "")
                )
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DATA, arr.toString())
                .apply()
        } catch (e: Exception) {
            // best-effort cache — losing it just means the saved places are gone
        }
    }
}
