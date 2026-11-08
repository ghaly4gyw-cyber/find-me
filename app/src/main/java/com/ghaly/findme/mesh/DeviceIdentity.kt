package com.ghaly.findme.mesh

import android.content.Context
import java.util.UUID

/**
 * A stable identity for this phone, persisted locally so it survives app restarts.
 * The display name is editable by the user (defaults to "Miner-xxxx").
 */
object DeviceIdentity {
    private const val PREFS = "find_me_identity"
    private const val KEY_ID = "device_id"
    private const val KEY_NAME = "device_name"

    private var cachedId: String? = null
    private var cachedName: String? = null

    fun id(context: Context): String {
        cachedId?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_ID, id).apply()
        }
        cachedId = id
        return id
    }

    fun name(context: Context): String {
        cachedName?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var name = prefs.getString(KEY_NAME, null)
        if (name == null) {
            name = "Miner-" + id(context).takeLast(4).uppercase()
            prefs.edit().putString(KEY_NAME, name).apply()
        }
        cachedName = name
        return name
    }

    fun setName(context: Context, newName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_NAME, newName).apply()
        cachedName = newName
    }
}
