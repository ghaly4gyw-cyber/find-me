package com.ghaly.findme.model

/** Kinds of message that travel over the mesh. */
enum class MsgType { POSITION, CHAT, SOS, SOS_CLEAR, INACTIVE, INACTIVE_CLEAR, POST, POST_COMMENT, GROUP_CHAT, PROFILE, PLACE, LISTING }

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

/** What a market listing is offering. */
enum class ListingKind { FOR_SALE, SERVICE, FREELANCE }

/** A thing someone on the mesh is offering: equipment or gold for sale, a
 *  service they can do on-site, or freelance work they can do remotely
 *  (phone/network needed for that last one, but the listing itself still
 *  spreads over the offline mesh same as a post). Buyer and seller agree
 *  price/payment themselves via the contact info on the author's own
 *  profile — this app never touches money directly. */
data class MarketListing(
    val id: String,
    val authorId: String,
    val authorName: String,
    val kind: ListingKind,
    val title: String,
    val details: String,
    val priceText: String,
    val ts: Long,
    /** A listing someone paid Ghaly to feature gets pinned to the top of
     *  the Market feed. Nothing here enforces payment — that's arranged
     *  the same way his other client work is, by contacting him directly —
     *  this flag is just what makes a paid-for listing actually stand out. */
    val promoted: Boolean = false,
    /** Small base64 JPEG thumbnail, same idea as a profile photo — optional,
     *  and kept deliberately tiny since this travels over the Bluetooth/Wi-Fi
     *  mesh (and possibly several relay hops) like everything else here. */
    val photoBase64: String? = null
)

/** A spot the user marked themselves — their own current position, a
 *  campsite, a water source, where the truck is parked, etc. Purely local:
 *  never sent over the mesh, just saved on this one phone so it can be found
 *  again later (e.g. navigate back to where you started digging). */
data class SavedPlace(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val ts: Long,
    /** null = private, only ever saved locally. Non-null = the name of
     *  whoever shared this place with the team over the mesh (which may
     *  be this same device, if the user chose to share one of their own). */
    val sharedByName: String? = null
)
