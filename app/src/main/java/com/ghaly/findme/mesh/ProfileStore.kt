package com.ghaly.findme.mesh

import android.content.Context
import com.ghaly.findme.model.Profile

/** My own profile, persisted locally. Name itself still lives in [DeviceIdentity]
 *  (kept for backward compatibility with existing saved data); this adds the rest. */
object ProfileStore {
    private const val PREFS = "find_me_profile"
    private const val KEY_PHONE = "phone"
    private const val KEY_DESC = "description"
    private const val KEY_PHOTO = "photo_b64"

    fun get(context: Context): Profile {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Profile(
            deviceId = DeviceIdentity.id(context),
            name = DeviceIdentity.name(context),
            phone = prefs.getString(KEY_PHONE, null)?.ifEmpty { null },
            description = prefs.getString(KEY_DESC, null)?.ifEmpty { null },
            photoBase64 = prefs.getString(KEY_PHOTO, null)
        )
    }

    fun save(context: Context, phone: String?, description: String?, photoBase64: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PHONE, phone.orEmpty())
            .putString(KEY_DESC, description.orEmpty())
            .apply {
                if (photoBase64 != null) putString(KEY_PHOTO, photoBase64)
            }
            .apply()
    }
}
