package com.ghaly.findme.model

/** A peer's self-described profile, broadcast once on connect and whenever they change it. */
data class Profile(
    val deviceId: String,
    val name: String,
    val phone: String? = null,
    val description: String? = null,
    /** small base64-encoded JPEG thumbnail, or null if they haven't set a photo */
    val photoBase64: String? = null,
    val ts: Long = System.currentTimeMillis()
)
