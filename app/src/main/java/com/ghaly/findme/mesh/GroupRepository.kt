package com.ghaly.findme.mesh

import android.content.Context
import android.util.Base64
import com.ghaly.findme.model.Group
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Groups the user has created or joined, persisted to disk so they survive app
 * restarts. The "join code" is a short, sayable string — 6 characters, no
 * ambiguous letters/digits (no 0/O, no 1/I) — meant to be read aloud over the
 * radio or typed in standing right there, since there's no internet to send an
 * invite link over. A long base64 blob was never something a human could
 * actually relay; this is.
 *
 * The group's id and its AES key are both derived deterministically from the
 * code (via SHA-256) rather than carried inside it, so a 6-character code is
 * enough on its own to join — no round trip needed. The trade-off: the code
 * can't also carry the group's name or who the admin is, so a device joining
 * fresh (not already in [_groups]) gets a placeholder name built from the code
 * itself, renameable afterwards with the existing "Rename" action. The keyspace
 * (32^6, about 1 billion) is small by cryptography standards, but the threat
 * model here is "keep a stranger's phone from reading our group chat", not a
 * bank — anyone who has the code was meant to have it, same trust level as a
 * Wi-Fi password read aloud.
 */
object GroupRepository {
    private const val PREFS = "find_me_groups"
    private const val KEY_GROUPS = "groups_json"

    private const val CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val CODE_LENGTH = 6

    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    val groups: StateFlow<List<Group>> = _groups

    private var loaded = false

    /** Every other disk-cached repository in this app wraps its load in a
     *  try/catch (a corrupted or old-format cache should mean "start empty",
     *  never "crash on every launch") — this one didn't, so a single
     *  malformed or pre-this-format group record made the Groups tab a
     *  permanent, un-recoverable crash the moment it loaded, surviving every
     *  restart since the bad record was sitting on disk the whole time.
     *  Guarded the same way now, with each record parsed on its own so one
     *  bad entry only drops that one group instead of wiping the list. */
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_GROUPS, null) ?: return
            val array = JSONArray(json)
            val list = mutableListOf<Group>()
            for (i in 0 until array.length()) {
                try {
                    val o = array.getJSONObject(i)
                    val code = o.optString("code", "").ifEmpty { "——————" }
                    list += Group(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        keyBase64 = o.getString("key"),
                        adminId = o.optString("admin", ""),
                        createdTs = o.optLong("ts", System.currentTimeMillis()),
                        code = code
                    )
                } catch (e: Exception) {
                    // this one record is malformed/old-format — skip it, keep the rest
                }
            }
            _groups.value = list
        } catch (e: Exception) {
            // corrupted/old cache format entirely — just start empty, nothing to recover
        }
    }

    private fun persist(context: Context) {
        val array = JSONArray()
        for (g in _groups.value) {
            array.put(
                JSONObject()
                    .put("id", g.id)
                    .put("name", g.name)
                    .put("key", g.keyBase64)
                    .put("admin", g.adminId)
                    .put("ts", g.createdTs)
                    .put("code", g.code)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_GROUPS, array.toString()).apply()
    }

    fun loadAndGet(context: Context): List<Group> {
        ensureLoaded(context)
        return _groups.value
    }

    fun findById(context: Context, id: String): Group? {
        ensureLoaded(context)
        return _groups.value.firstOrNull { it.id == id }
    }

    fun create(context: Context, name: String): Group {
        ensureLoaded(context)
        var code: String
        do {
            code = randomCode()
        } while (_groups.value.any { it.code == code })
        val group = Group(
            id = idForCode(code),
            name = name,
            keyBase64 = keyForCode(code),
            adminId = DeviceIdentity.id(context),
            createdTs = System.currentTimeMillis(),
            code = code
        )
        _groups.value = _groups.value + group
        persist(context)
        return group
    }

    /** Returns null if the code is malformed (wrong length / bad characters). */
    fun joinFromCode(context: Context, rawCode: String): Group? {
        ensureLoaded(context)
        val code = normalizeCode(rawCode) ?: return null
        val id = idForCode(code)
        val existing = _groups.value.firstOrNull { it.id == id }
        if (existing != null) return existing
        val group = Group(
            id = id,
            // The code can't carry a name, so this starts as a placeholder —
            // "Rename" (already in the group's action menu) fixes it up once,
            // same as any other saved place/profile edit in this app.
            name = context.getString(com.ghaly.findme.R.string.group_placeholder_name_fmt, code),
            keyBase64 = keyForCode(code),
            adminId = "", // unknown until learned some other way — not needed to use the group
            createdTs = System.currentTimeMillis(),
            code = code
        )
        _groups.value = _groups.value + group
        persist(context)
        return group
    }

    fun rename(context: Context, groupId: String, newName: String) {
        ensureLoaded(context)
        _groups.value = _groups.value.map { if (it.id == groupId) it.copy(name = newName) else it }
        persist(context)
    }

    /** Formatted for display/sharing, e.g. "4K2-9P7". */
    fun formattedCode(group: Group): String {
        val c = group.code
        return if (c.length == CODE_LENGTH) "${c.substring(0, 3)}-${c.substring(3)}" else c
    }

    private fun randomCode(): String {
        val random = SecureRandom()
        return (1..CODE_LENGTH).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
    }

    /** Strips spaces/dashes, uppercases, and validates against the alphabet —
     *  accepts whatever casing/spacing a person typed it in with. */
    private fun normalizeCode(input: String): String? {
        val cleaned = input.trim().uppercase().replace("-", "").replace(" ", "")
        if (cleaned.length != CODE_LENGTH) return null
        if (cleaned.any { it !in CODE_ALPHABET }) return null
        return cleaned
    }

    private fun idForCode(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(code.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP or Base64.URL_SAFE).take(12)
    }

    private fun keyForCode(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(("find-me-group-key:$code").toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP) // 32 bytes -> AES-256, matches Crypto.newKeyBase64()
    }
}
