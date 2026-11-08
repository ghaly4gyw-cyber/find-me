package com.ghaly.findme.model

/** Kinds of message that travel over the mesh. */
enum class MsgType { POSITION, CHAT, SOS, SOS_CLEAR }

/**
 * Envelope every mesh message is wrapped in, so any device can relay it
 * on to its own neighbours without understanding the payload.
 *
 * - [id] identifies this specific message so relays don't loop it forever.
 * - [originId] / [originName] identify who first sent it (not who is relaying it).
 * - [hop] counts how many devices have already relayed it, so we can cap relay depth.
 * - [ts] is the origin device's clock time in millis when it was first sent.
 */
data class Envelope(
    val id: String,
    val type: MsgType,
    val originId: String,
    val originName: String,
    val hop: Int,
    val ts: Long,
    val payload: String
)

data class PeerPosition(
    val deviceId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val ts: Long,
    /** true while we have a live mesh connection to this peer right now */
    val connected: Boolean
)

data class ChatMessage(
    val id: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val ts: Long,
    val isMine: Boolean = false
)

data class SosAlert(
    val id: String,
    val senderId: String,
    val senderName: String,
    val lat: Double,
    val lon: Double,
    val ts: Long,
    val active: Boolean = true
)
