package com.ghaly.findme.mesh

import android.content.Context
import android.util.Log
import com.ghaly.findme.model.ChatMessage
import com.ghaly.findme.model.Envelope
import com.ghaly.findme.model.MsgType
import com.ghaly.findme.model.PeerPosition
import com.ghaly.findme.model.SosAlert
import com.ghaly.findme.repo.ChatRepository
import com.ghaly.findme.repo.PeerRepository
import com.ghaly.findme.repo.SosRepository
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Wraps Google's Nearby Connections API into a simple flood-relay mesh.
 *
 * Every message (position / chat / SOS) is wrapped in an [Envelope] and sent as JSON
 * bytes to every directly-connected endpoint. When a device receives an envelope it
 * hasn't seen before, it applies it locally AND re-sends it to its own other
 * neighbours (hop count + 1), up to [MAX_HOP]. That's what lets a message travel
 * further than direct Bluetooth/Wi-Fi range, hand to hand.
 */
class MeshManager(private val context: Context) {

    companion object {
        private const val TAG = "MeshManager"
        private const val SERVICE_ID = "com.ghaly.findme.SERVICE"
        private const val MAX_HOP = 8
        private const val SEEN_CACHE_LIMIT = 500
        private val STRATEGY = Strategy.P2P_CLUSTER
    }

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)

    /** endpointIds we currently have a live Nearby connection to. */
    private val connectedEndpoints = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** endpointId (Nearby's handle for a direct link) -> deviceId (our stable identity),
     *  populated once we've received at least one message from that endpoint. */
    private val endpointToDevice = ConcurrentHashMap<String, String>()

    /** message ids we've already processed, so we don't relay the same thing forever */
    private val seenIds = object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            if (size >= SEEN_CACHE_LIMIT) {
                val oldest = iterator()
                if (oldest.hasNext()) {
                    oldest.next()
                    oldest.remove()
                }
            }
            return super.add(element)
        }
    }

    private val myId get() = DeviceIdentity.id(context)
    private val myName get() = DeviceIdentity.name(context)

    var running = false
        private set

    fun start() {
        if (running) return
        running = true
        startAdvertising()
        startDiscovery()
    }

    fun stop() {
        running = false
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        endpointToDevice.clear()
        connectedEndpoints.clear()
    }

    // ---------------------------------------------------------------- advertising

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            myName, SERVICE_ID, connectionLifecycleCallback, options
        ).addOnFailureListener { e -> Log.w(TAG, "startAdvertising failed", e) }
    }

    // ------------------------------------------------------------------ discovery

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            SERVICE_ID, endpointDiscoveryCallback, options
        ).addOnFailureListener { e -> Log.w(TAG, "startDiscovery failed", e) }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            connectionsClient.requestConnection(myName, endpointId, connectionLifecycleCallback)
                .addOnFailureListener { e -> Log.w(TAG, "requestConnection failed", e) }
        }

        override fun onEndpointLost(endpointId: String) {
            handleDisconnect(endpointId)
        }
    }

    // -------------------------------------------------------------------- lifecycle

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // MVP: trust anyone running the app, accept automatically.
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (!result.status.isSuccess) {
                Log.d(TAG, "connection to $endpointId failed: ${result.status}")
                return
            }
            connectedEndpoints.add(endpointId)
            // Introduce ourselves so the other side can map endpointId -> deviceId,
            // and so it immediately has our last known position.
            sendEnvelopeTo(endpointId, buildPositionEnvelope(lastLat, lastLon))
        }

        override fun onDisconnected(endpointId: String) {
            handleDisconnect(endpointId)
        }
    }

    private fun handleDisconnect(endpointId: String) {
        connectedEndpoints.remove(endpointId)
        val deviceId = endpointToDevice.remove(endpointId)
        if (deviceId != null) {
            PeerRepository.markDisconnected(deviceId)
        }
    }

    // ---------------------------------------------------------------------- payload

    /** Set by the UI to get notified the instant a push-to-talk clip arrives. */
    var onVoiceClip: ((bytes: ByteArray) -> Unit)? = null

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            // Every JSON envelope starts with '{' — anything else is a raw voice
            // clip, sent directly (not flooded/relayed) between in-range phones.
            if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) {
                val json = String(bytes, StandardCharsets.UTF_8)
                try {
                    val envelope = parseEnvelope(json)
                    handleIncoming(endpointId, envelope)
                } catch (e: Exception) {
                    Log.w(TAG, "bad payload from $endpointId", e)
                }
            } else {
                onVoiceClip?.invoke(bytes)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // not needed for small JSON payloads; bytes payloads complete in one shot
        }
    }

    private fun handleIncoming(endpointId: String, envelope: Envelope) {
        endpointToDevice[endpointId] = envelope.originId

        if (envelope.originId == myId) return // our own message, looped back
        if (seenIds.contains(envelope.id)) return
        seenIds.add(envelope.id)

        applyLocally(envelope)

        if (envelope.hop < MAX_HOP) {
            relay(envelope, excludeEndpointId = endpointId)
        }
    }

    private fun applyLocally(envelope: Envelope) {
        when (envelope.type) {
            MsgType.POSITION -> {
                val obj = JSONObject(envelope.payload)
                PeerRepository.upsert(
                    PeerPosition(
                        deviceId = envelope.originId,
                        name = envelope.originName,
                        lat = obj.optDouble("lat", 0.0),
                        lon = obj.optDouble("lon", 0.0),
                        ts = envelope.ts,
                        connected = true
                    )
                )
            }
            MsgType.CHAT -> {
                val obj = JSONObject(envelope.payload)
                val targetId = obj.optString("to", "").ifEmpty { null }
                // Only show it if it's a public message, or it's a DM addressed to me.
                // (We still relayed it above regardless, so it can keep hopping to its
                // actual recipient even through phones that aren't the recipient.)
                if (targetId == null || targetId == myId) {
                    ChatRepository.add(
                        ChatMessage(
                            id = envelope.id,
                            senderId = envelope.originId,
                            senderName = envelope.originName,
                            text = obj.optString("text", ""),
                            ts = envelope.ts,
                            isMine = false,
                            targetId = targetId
                        )
                    )
                }
            }
            MsgType.SOS -> {
                val obj = JSONObject(envelope.payload)
                SosRepository.upsert(
                    SosAlert(
                        id = envelope.id,
                        senderId = envelope.originId,
                        senderName = envelope.originName,
                        lat = obj.optDouble("lat", 0.0),
                        lon = obj.optDouble("lon", 0.0),
                        ts = envelope.ts,
                        active = true
                    )
                )
            }
            MsgType.SOS_CLEAR -> {
                SosRepository.clear(envelope.originId)
            }
            MsgType.INACTIVE -> {
                val obj = JSONObject(envelope.payload)
                com.ghaly.findme.repo.InactivityRepository.upsert(
                    com.ghaly.findme.model.InactivityAlert(
                        senderId = envelope.originId,
                        senderName = envelope.originName,
                        lat = obj.optDouble("lat", 0.0),
                        lon = obj.optDouble("lon", 0.0),
                        ts = envelope.ts,
                        active = true
                    )
                )
            }
            MsgType.INACTIVE_CLEAR -> {
                com.ghaly.findme.repo.InactivityRepository.clear(envelope.originId)
            }
            MsgType.POST -> {
                val obj = JSONObject(envelope.payload)
                com.ghaly.findme.repo.PostRepository.addPost(
                    com.ghaly.findme.model.Post(
                        id = envelope.id,
                        authorId = envelope.originId,
                        authorName = envelope.originName,
                        text = obj.optString("text", ""),
                        ts = envelope.ts
                    )
                )
            }
            MsgType.GROUP_CHAT -> {
                val obj = JSONObject(envelope.payload)
                val groupId = obj.optString("groupId", "")
                val ct = obj.optString("ct", "")
                val group = GroupRepository.findById(context, groupId)
                if (group != null) {
                    val plaintext = Crypto.decrypt(ct, group.keyBase64)
                    if (plaintext != null) {
                        com.ghaly.findme.repo.GroupChatRepository.add(
                            groupId,
                            ChatMessage(
                                id = envelope.id,
                                senderId = envelope.originId,
                                senderName = envelope.originName,
                                text = plaintext,
                                ts = envelope.ts,
                                isMine = false
                            )
                        )
                    }
                }
                // if we're not in this group, we simply can't read it — but we still
                // relay the (still-encrypted) envelope below like anything else.
            }
            MsgType.POST_COMMENT -> {
                val obj = JSONObject(envelope.payload)
                com.ghaly.findme.repo.PostRepository.addComment(
                    com.ghaly.findme.model.PostComment(
                        id = envelope.id,
                        postId = obj.optString("postId", ""),
                        authorId = envelope.originId,
                        authorName = envelope.originName,
                        text = obj.optString("text", ""),
                        ts = envelope.ts
                    )
                )
            }
        }
    }

    private fun relay(envelope: Envelope, excludeEndpointId: String) {
        val bumped = envelope.copy(hop = envelope.hop + 1)
        val json = toJson(bumped)
        val bytesPayload = Payload.fromBytes(json.toByteArray(StandardCharsets.UTF_8))
        for (endpointId in connectedEndpoints) {
            if (endpointId == excludeEndpointId) continue
            connectionsClient.sendPayload(endpointId, bytesPayload)
        }
    }

    private fun sendEnvelopeTo(endpointId: String, envelope: Envelope) {
        val json = toJson(envelope)
        connectionsClient.sendPayload(
            endpointId, Payload.fromBytes(json.toByteArray(StandardCharsets.UTF_8))
        )
    }

    private fun broadcast(envelope: Envelope) {
        val json = toJson(envelope)
        val bytesPayload = Payload.fromBytes(json.toByteArray(StandardCharsets.UTF_8))
        for (endpointId in connectedEndpoints) {
            connectionsClient.sendPayload(endpointId, bytesPayload)
        }
    }

    // ----------------------------------------------------------------- public sends

    @Volatile private var lastLat: Double = 0.0
    @Volatile private var lastLon: Double = 0.0
    @Volatile private var hasFix: Boolean = false

    /** My own last known GPS fix, or null if we don't have one yet. */
    fun myLocation(): Pair<Double, Double>? = if (hasFix) lastLat to lastLon else null

    /** Call whenever we get a fresh GPS fix; broadcasts it and remembers it for new connections. */
    fun sendPosition(lat: Double, lon: Double) {
        lastLat = lat
        lastLon = lon
        hasFix = true
        broadcast(buildPositionEnvelope(lat, lon))
    }

    /** [targetId] null = send to everyone; otherwise a direct message to just that peer
     *  (still physically relayed by everyone, but only shown by the recipient). */
    fun sendChat(text: String, targetId: String? = null): ChatMessage {
        val payload = JSONObject().put("text", text)
        if (targetId != null) payload.put("to", targetId)
        val envelope = Envelope(
            id = UUID.randomUUID().toString(),
            type = MsgType.CHAT,
            originId = myId,
            originName = myName,
            hop = 0,
            ts = System.currentTimeMillis(),
            payload = payload.toString()
        )
        seenIds.add(envelope.id)
        broadcast(envelope)
        return ChatMessage(
            id = envelope.id,
            senderId = myId,
            senderName = myName,
            text = text,
            ts = envelope.ts,
            isMine = true,
            targetId = targetId
        )
    }

    /** Direct-sends a push-to-talk clip to every phone we're currently connected to —
     *  NOT flood-relayed like other message types, so it never travels past direct
     *  Bluetooth/Wi-Fi range. Returns false if there's nobody directly in range. */
    fun sendVoiceClip(bytes: ByteArray): Boolean {
        if (connectedEndpoints.isEmpty()) return false
        val payload = Payload.fromBytes(bytes)
        for (endpointId in connectedEndpoints) {
            connectionsClient.sendPayload(endpointId, payload)
        }
        return true
    }

    fun directPeerCount(): Int = connectedEndpoints.size

    fun sendGroupMessage(groupId: String, text: String): ChatMessage? {
        val group = GroupRepository.findById(context, groupId) ?: return null
        val ct = Crypto.encrypt(text, group.keyBase64)
        val envelope = Envelope(
            id = UUID.randomUUID().toString(),
            type = MsgType.GROUP_CHAT,
            originId = myId,
            originName = myName,
            hop = 0,
            ts = System.currentTimeMillis(),
            payload = JSONObject().put("groupId", groupId).put("ct", ct).toString()
        )
        seenIds.add(envelope.id)
        broadcast(envelope)
        val mine = ChatMessage(
            id = envelope.id,
            senderId = myId,
            senderName = myName,
            text = text,
            ts = envelope.ts,
            isMine = true
        )
        com.ghaly.findme.repo.GroupChatRepository.add(groupId, mine)
        return mine
    }

    fun sendPost(text: String) {
        val envelope = Envelope(
            id = UUID.randomUUID().toString(),
            type = MsgType.POST,
            originId = myId,
            originName = myName,
            hop = 0,
            ts = System.currentTimeMillis(),
            payload = JSONObject().put("text", text).toString()
        )
        seenIds.add(envelope.id)
        broadcast(envelope)
        com.ghaly.findme.repo.PostRepository.addPost(
            com.ghaly.findme.model.Post(envelope.id, myId, myName, text, envelope.ts)
        )
    }

    fun sendComment(postId: String, text: String) {
        val envelope = Envelope(
            id = UUID.randomUUID().toString(),
            type = MsgType.POST_COMMENT,
            originId = myId,
            originName = myName,
            hop = 0,
            ts = System.currentTimeMillis(),
            payload = JSONObject().put("postId", postId).put("text", text).toString()
        )
        seenIds.add(envelope.id)
        broadcast(envelope)
        com.ghaly.findme.repo.PostRepository.addComment(
            com.ghaly.findme.model.PostComment(envelope.id, postId, myId, myName, text, envelope.ts)
        )
    }

    /** Broadcast that this phone has had no user interaction for a while (dead-man's switch). */
    fun sendInactive(lat: Double, lon: Double) {
        val envelope = Envelope(
            id = UUID.randomUUID().toString(),
            type = MsgType.INACTIVE,
            originId = myId,
            originName = myName,
            hop = 0,
            ts = System.currentTimeMillis(),
            payload = JSONObject().put("lat", lat).put("lon", lon).toString()
        )
        seenIds.add(envelope.id)
        broadcast(envelope)
        com.ghaly.findme.repo.InactivityRepository.upsert(
            com.ghaly.findme.model.InactivityAlert(myId, myName, lat, lon, envelope.ts, true)
        )
    }

    fun clearInactive() {
        val envelope = Envelope(
            id = UUID.randomUUID().toString(),
            type = MsgType.INACTIVE_CLEAR,
            originId = myId,
            originName = myName,
            hop = 0,
            ts = System.currentTimeMillis(),
            payload = "{}"
        )
        seenIds.add(envelope.id)
        broadcast(envelope)
        com.ghaly.findme.repo.InactivityRepository.clear(myId)
    }

    /** SOS skips nothing special in this simple sender (everything already sends
     * immediately), but it's always broadcast first/alone so it's never queued
     * behind other traffic. */
    fun sendSos(lat: Double, lon: Double) {
        val envelope = Envelope(
            id = UUID.randomUUID().toString(),
            type = MsgType.SOS,
            originId = myId,
            originName = myName,
            hop = 0,
            ts = System.currentTimeMillis(),
            payload = JSONObject().put("lat", lat).put("lon", lon).toString()
        )
        seenIds.add(envelope.id)
        broadcast(envelope)
        SosRepository.upsert(SosAlert(envelope.id, myId, myName, lat, lon, envelope.ts, true))
    }

    fun clearSos() {
        val envelope = Envelope(
            id = UUID.randomUUID().toString(),
            type = MsgType.SOS_CLEAR,
            originId = myId,
            originName = myName,
            hop = 0,
            ts = System.currentTimeMillis(),
            payload = "{}"
        )
        seenIds.add(envelope.id)
        broadcast(envelope)
        SosRepository.clear(myId)
    }

    private fun buildPositionEnvelope(lat: Double, lon: Double) = Envelope(
        id = UUID.randomUUID().toString(),
        type = MsgType.POSITION,
        originId = myId,
        originName = myName,
        hop = 0,
        ts = System.currentTimeMillis(),
        payload = JSONObject().put("lat", lat).put("lon", lon).toString()
    )

    // ------------------------------------------------------------------ (de)serialize

    private fun toJson(e: Envelope): String = JSONObject()
        .put("id", e.id)
        .put("type", e.type.name)
        .put("originId", e.originId)
        .put("originName", e.originName)
        .put("hop", e.hop)
        .put("ts", e.ts)
        .put("payload", e.payload)
        .toString()

    private fun parseEnvelope(json: String): Envelope {
        val obj = JSONObject(json)
        return Envelope(
            id = obj.getString("id"),
            type = MsgType.valueOf(obj.getString("type")),
            originId = obj.getString("originId"),
            originName = obj.getString("originName"),
            hop = obj.optInt("hop", 0),
            ts = obj.optLong("ts", System.currentTimeMillis()),
            payload = obj.optString("payload", "{}")
        )
    }
}
