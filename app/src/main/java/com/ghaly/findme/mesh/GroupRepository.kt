package com.ghaly.findme.mesh

import android.content.Context
import android.util.Base64
import com.ghaly.findme.model.Group
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Groups the user has created or joined, persisted to disk so they survive app
 * restarts. The "join code" is just the group's id+name+key base64-encoded
 * together — meant to be read aloud or typed in by someone standing right there,
 * since there's no internet to send an invite link over.
 */
object GroupRepository {
    private const val PREFS = "find_me_groups"
    private const val KEY_GROUPS = "groups_json"

    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    val groups: StateFlow<List<Group>> = _groups

    private var loaded = false

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_GROUPS, null) ?: return
        val array = JSONArray(json)
        val list = mutableListOf<Group>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            list += Group(
                id = o.getString("id"),
                name = o.getString("name"),
                keyBase64 = o.getString("key"),
                adminId = o.getString("admin"),
                createdTs = o.optLong("ts", System.currentTimeMillis())
            )
        }
        _groups.value = list
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
        val group = Group(
            id = UUID.randomUUID().toString().take(8),
            name = name,
            keyBase64 = Crypto.newKeyBase64(),
            adminId = DeviceIdentity.id(context),
            createdTs = System.currentTimeMillis()
        )
        _groups.value = _groups.value + group
        persist(context)
        return group
    }

    /** Returns null if the code is malformed. */
    fun joinFromCode(context: Context, code: String): Group? {
        ensureLoaded(context)
        return try {
            val decoded = String(Base64.decode(code.trim(), Base64.NO_WRAP), Charsets.UTF_8)
            val o = JSONObject(decoded)
            val group = Group(
                id = o.getString("id"),
                name = o.getString("name"),
                keyBase64 = o.getString("key"),
                adminId = o.getString("admin"),
                createdTs = System.currentTimeMillis()
            )
            if (_groups.value.none { it.id == group.id }) {
                _groups.value = _groups.value + group
                persist(context)
            }
            group
        } catch (e: Exception) {
            null
        }
    }

    fun codeFor(group: Group): String {
        val o = JSONObject()
            .put("id", group.id)
            .put("name", group.name)
            .put("key", group.keyBase64)
            .put("admin", group.adminId)
        return Base64.encodeToString(o.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }
}
