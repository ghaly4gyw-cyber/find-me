package com.ghaly.findme.model

/** Kinds of message that travel over the mesh. */
enum class MsgType { POSITION, CHAT, SOS, SOS_CLEAR, INACTIVE, INACTIVE_CLEAR, POST, POST_COMMENT }

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
    val isMine: Boolean = false,
    /** null = sent to everyone; otherwise the deviceId of the one intended recipient. */
    val targetId: String? = null
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

data class InactivityAlert(
    val senderId: String,
    val senderName: String,
    val lat: Double,
    val lon: Double,
    val ts: Long,
    val active: Boolean = true
)

data class Post(
    val id: String,
    val authorId: String,
    val authorName: String,
    val text: String,
    val ts: Long
)

data class PostComment(
    val id: String,
    val postId: String,
    val authorId: String,
    val authorName: String,
    val text: String,
    val ts: Long
)
