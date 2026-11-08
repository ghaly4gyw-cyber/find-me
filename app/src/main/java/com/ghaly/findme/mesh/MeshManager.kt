package com.ghaly.findme.mesh

import android.content.Context
import android.util.Log
import com.ghaly.findme.model.ChatMessage
import com.ghaly.findme.model.Envelope
import com.ghaly.findme.model.MsgType
import com.ghaly.findme.model.PeerPosition
import com.ghaly.findme.model.SosAlert
import com.ghaly.findme.repo.ChatRepository
import com.ghaly.findme.repo.InactivityRepository
import com.ghaly.findme.repo.PeerRepository
import com.ghaly.findme.repo.ProfileRepository
import com.ghaly.findme.repo.SosRepository
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
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

    /** Fires with a short human-readable status/error string at every meaningful
     *  step (advertising/discovery started or failed, an endpoint found,
     *  connection succeeded/failed) — the UI shows this directly so a failure
     *  that would otherwise only exist in Logcat is actually visible on the
     *  phone during a real test. */
    var onStatusChanged: ((String) -> Unit)? = null

    fun start() {
        if (running) return
        running = true
        report("Starting mesh…")
        startAdvertising()
        startDiscovery()
    }

    private fun report(message: String) {
        Log.d(TAG, message)
        onStatusChanged?.invoke(message)
    }

    private fun errorText(e: Exception): String {
        val code = (e as? ApiException)?.statusCode
        return if (code != null) statusText(code) else (e.message ?: e.javaClass.simpleName)
    }

    /** Turns a Nearby Connections status code into something a non-Android-developer
     *  can actually act on — this is the whole point of surfacing it in the UI.
     *  Only maps codes that are actually documented/named constants, rather than
     *  guessing at raw numbers — an unrecognized code still shows its number so
     *  it can be looked up precisely instead of being silently swallowed. */
    @Suppress("DEPRECATION")
    private fun statusText(code: Int): String = when (code) {
        ConnectionsStatusCodes.STATUS_RADIO_ERROR ->
            "Bluetooth/Wi-Fi radio error (code $code) — try turning Bluetooth and Wi-Fi off and back on"
        ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR -> "Bluetooth error on this phone (code $code)"
        ConnectionsStatusCodes.STATUS_ERROR -> "Nearby Connections error (code $code)"
        ConnectionsStatusCodes.STATUS_OUT_OF_ORDER_API_CALL -> "Internal timing error (code $code)"
        ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING -> "Already advertising (code $code)"
        ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING -> "Already searching (code $code)"
        ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT -> "Already connected to that phone (code $code)"
        ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> "Connection rejected by the other phone (code $code)"
        ConnectionsStatusCodes.STATUS_NOT_CONNECTED_TO_ENDPOINT -> "Not connected to that phone (code $code)"
        ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR ->
            "Lost the connection (code $code) — likely moved out of range or a flaky radio"
        ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_COARSE_LOCATION ->
            "Missing location permission (code $code) — also check that Location services (GPS) are turned ON in system settings, not just granted to the app"
        else -> "Nearby Connections error, code $code — likely Bluetooth, Wi-Fi, or Location turned off, or Google Play Services needs an update"
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
        ).addOnSuccessListener {
            report("Advertising as \"$myName\" — waiting to be found…")
        }.addOnFailureListener { e ->
            Log.w(TAG, "startAdvertising failed", e)
            report("Advertising FAILED: ${errorText(e)}")
        }
    }

    // ------------------------------------------------------------------ discovery

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            SERVICE_ID, endpointDiscoveryCallback, options
        ).addOnSuccessListener {
            report("Searching for nearby phones…")
        }.addOnFailureListener { e ->
            Log.w(TAG, "startDiscovery failed", e)
            report("Search FAILED: ${errorText(e)}")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            report("Found a nearby phone (${info.endpointName}) — connecting…")
            connectionsClient.requestConnection(myName, endpointId, connectionLifecycleCallback)
                .addOnFailureListener { e ->
                    Log.w(TAG, "requestConnection failed", e)
                    report("Connect to ${info.endpointName} FAILED: ${errorText(e)}")
                }
        }

        override fun onEndpointLost(endpointId: String) {
            handleDisconnect(endpointId)
        }
    }

    // -------------------------------------------------------------------- lifecycle

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // MVP: trust anyone running the app, accept automatically.
            report("Connection offered by ${info.endpointName} — accepting…")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (!result.status.isSuccess) {
                Log.d(TAG, "connection to $endpointId failed: ${result.status}")
                report("Connection FAILED: ${statusText(result.status.statusCode)}")
                return
            }
            report("Connected! Exchanging position/profile…")
            connectedEndpoints.add(endpointId)
            // Introduce ourselves so the other side can map endpointId -> deviceId,
            // and so it immediately has our last known position and profile.
            sendEnvelopeTo(endpointId, buildPositionEnvelope(lastLat, lastLon))
            sendEnvelopeTo(endpointId, buildProfileEnvelope())
            // "Carrier pigeon" relay: also hand over everything we've cached about
            // the rest of the mesh (other people's last known positions/profiles and
            // any active SOS/inactivity alerts). This is how word about someone who
            // isn't directly reachable right now still crosses to a brand-new phone —
            // whoever we just met carries it onward from here, hop by hop, even
            // though they never talked to that person directly.
            relayCachedKnowledgeTo(endpointId)
        }

        override fun onDisconnected(endpointId: String) {
            handleDisconnect(endpointId)
        }
    }

    /** True if [deviceId] is someone we currently hold a live, direct Nearby
     *  Connections link to (not just someone we've heard about via relay). */
    private fun isDirectNeighbor(deviceId: String): Boolean =
        endpointToDevice.values.contains(deviceId)

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
        // Only the FIRST envelope we ever see on a given endpoint tells us who's
        // actually on the other end of that direct link. After the carrier-pigeon
        // relay was added, a newly-connected endpoint also forwards envelopes that
        // originated from totally different (not-directly-connected) people — if we
        // kept overwriting the mapping with every envelope's originId, this endpoint
        // would get mis-attributed to whichever peer's cached data arrived last, and
        // a later disconnect would mark the WRONG person as disconnected. putIfAbsent
        // keeps the mapping pinned to the real direct neighbor for this endpoint's
        // whole lifetime.
        endpointToDevice.putIfAbsent(endpointId, envelope.originId)

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
                        // A position that arrived via a multi-hop relay or via the
                        // carrier-pigeon cache replay is NOT the same as that person
                        // being physically nearby right now — only mark them "in
                        // range" if they're an actual direct Nearby Connections
                        // neighbor of ours at this moment. Getting this wrong on a
                        // desert safety app would tell someone help is right next to
                        // them when it's really hours away through several relays.
                        connected = isDirectNeighbor(envelope.originId)
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
            MsgType.PROFILE -> {
                val obj = JSONObject(envelope.payload)
                com.ghaly.findme.repo.ProfileRepository.upsert(
                    com.ghaly.findme.model.Profile(
                        deviceId = envelope.originId,
                        name = envelope.originName,
                        phone = obj.optString("phone", "").ifEmpty { null },
                        description = obj.optString("description", "").ifEmpty { null },
                        photoBase64 = obj.optString("photo", "").ifEmpty { null },
                        ts = envelope.ts
                    )
                )
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

    /** Call after the user edits their profile, so connected peers get the update. */
    fun sendProfile() {
        broadcast(buildProfileEnvelope())
    }

    private fun buildProfileEnvelope(): Envelope {
        val profile = ProfileStore.get(context)
        val payload = JSONObject()
            .put("phone", profile.phone ?: "")
            .put("description", profile.description ?: "")
            .put("photo", profile.photoBase64 ?: "")
        return Envelope(
            id = UUID.randomUUID().toString(),
            type = MsgType.PROFILE,
            originId = myId,
            originName = myName,
            hop = 0,
            ts = System.currentTimeMillis(),
            payload = payload.toString()
        )
    }

    /** Replays everything we currently know about the rest of the mesh to a
     *  freshly-connected endpoint, as fresh (hop = 0) envelopes under each
     *  original person's own identity — so it keeps traveling just like a
     *  live message would, and the newly-met phone's own cache fills in
     *  immediately instead of waiting for that person to come back into range. */
    private fun relayCachedKnowledgeTo(endpointId: String) {
        for (peer in PeerRepository.peers.value.values) {
            sendEnvelopeTo(endpointId, Envelope(
                id = UUID.randomUUID().toString(),
                type = MsgType.POSITION,
                originId = peer.deviceId,
                originName = peer.name,
                hop = 0,
                ts = peer.ts,
                payload = JSONObject().put("lat", peer.lat).put("lon", peer.lon).toString()
            ))
        }
        for (profile in ProfileRepository.profiles.value.values) {
            sendEnvelopeTo(endpointId, Envelope(
                id = UUID.randomUUID().toString(),
                type = MsgType.PROFILE,
                originId = profile.deviceId,
                originName = profile.name,
                hop = 0,
                ts = profile.ts,
                payload = JSONObject()
                    .put("phone", profile.phone ?: "")
                    .put("description", profile.description ?: "")
                    .put("photo", profile.photoBase64 ?: "")
                    .toString()
            ))
        }
        for (alert in SosRepository.alerts.value.values) {
            if (!alert.active) continue
            sendEnvelopeTo(endpointId, Envelope(
                id = UUID.randomUUID().toString(),
                type = MsgType.SOS,
                originId = alert.senderId,
                originName = alert.senderName,
                hop = 0,
                ts = alert.ts,
                payload = JSONObject().put("lat", alert.lat).put("lon", alert.lon).toString()
            ))
        }
        for (alert in InactivityRepository.alerts.value.values) {
            if (!alert.active) continue
            sendEnvelopeTo(endpointId, Envelope(
                id = UUID.randomUUID().toString(),
                type = MsgType.INACTIVE,
                originId = alert.senderId,
                originName = alert.senderName,
                hop = 0,
                ts = alert.ts,
                payload = JSONObject().put("lat", alert.lat).put("lon", alert.lon).toString()
            ))
        }
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
