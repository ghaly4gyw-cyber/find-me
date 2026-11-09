package com.ghaly.findme.mesh

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import com.ghaly.findme.repo.SavedPlaceRepository
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
        watchdogHandler.postDelayed(connectionWatchdog, 5_000L)
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
        watchdogHandler.removeCallbacks(connectionWatchdog)
        pendingConnectionAtMs.clear()
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
            // P2P_CLUSTER has every phone both advertising AND discovering at once, so
            // when two phones find each other around the same moment, BOTH can call
            // requestConnection() on each other simultaneously. Nearby Connections
            // handles that race badly — the handshake can get stuck at "connecting"
            // forever instead of either succeeding or failing cleanly, which is
            // exactly the symptom reported: two real phones a few meters apart,
            // status frozen on "Found ... — connecting…". The fix is a simple
            // deterministic tie-breaker: only the phone whose own name sorts lower
            // initiates the request; the other just waits to receive it — nothing is
            // lost since it's still advertising, so the incoming request still
            // reaches it normally via onConnectionInitiated below.
            if (myName >= info.endpointName) {
                report("Found a nearby phone (${info.endpointName}) — waiting for it to connect to us…")
                return
            }
            report("Found a nearby phone (${info.endpointName}) — connecting…")
            pendingConnectionAtMs[endpointId] = System.currentTimeMillis()
            connectionsClient.requestConnection(myName, endpointId, connectionLifecycleCallback)
                .addOnFailureListener { e ->
                    Log.w(TAG, "requestConnection failed", e)
                    pendingConnectionAtMs.remove(endpointId)
                    report("Connect to ${info.endpointName} FAILED: ${errorText(e)}")
                }
        }

        override fun onEndpointLost(endpointId: String) {
            pendingConnectionAtMs.remove(endpointId)
            handleDisconnect(endpointId)
        }
    }

    /** endpointId -> when we called requestConnection on it, for the stuck-connection
     *  watchdog below. A handshake that never resolves either way (no documented
     *  Nearby Connections timeout fires in practice on some devices/OEM radios)
     *  would otherwise leave that phone invisible to this one forever, with no
     *  error ever shown — this is what actually retries it. */
    private val pendingConnectionAtMs = ConcurrentHashMap<String, Long>()
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val CONNECTION_STUCK_TIMEOUT_MS = 20_000L

    private val connectionWatchdog = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val stuck = pendingConnectionAtMs.entries
                .filter { now - it.value > CONNECTION_STUCK_TIMEOUT_MS }
                .map { it.key }
            for (endpointId in stuck) {
                pendingConnectionAtMs.remove(endpointId)
                if (!connectedEndpoints.contains(endpointId)) {
                    report("Connection attempt timed out — retrying…")
                    connectionsClient.disconnectFromEndpoint(endpointId)
                }
            }
            if (running) watchdogHandler.postDelayed(this, 5_000L)
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
            pendingConnectionAtMs.remove(endpointId)
            if (!result.status.isSuccess) {
                Log.d(TAG, "connection to $endpointId failed: ${result.status}")
                report("Connection FAILED: ${statusText(result.status.statusCode)}")
                return
            }
            report("Connected! Exchanging position/profile…")
            connectedEndpoints.add(endpointId)
            // Introduce ourselves so the other side can map endpointId -> deviceId,
            // and so it immediately has our last known position and profile.
            sendEnvelopeTo(endpointId, buildPositionEnvelope(lastLat, lastLon, lastBatteryPct))
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
    fun isDirectNeighbor(deviceId: String): Boolean =
        endpointToDevice.values.contains(deviceId)

    private fun handleDisconnect(endpointId: String) {
        pendingConnectionAtMs.remove(endpointId)
        connectedEndpoints.remove(endpointId)
        val deviceId = endpointToDevice.remove(endpointId)
        if (deviceId != null) {
            PeerRepository.markDisconnected(deviceId)
        }
    }

    // ---------------------------------------------------------------------- payload

    /** Set by the UI to get notified the instant a push-to-talk clip arrives.
     *  [senderName] is best-effort — null if we haven't yet learned who's on
     *  the other end of that direct link (shouldn't normally happen, since a
     *  position/chat envelope is what establishes that mapping in the first
     *  place and phones are usually connected for a while before anyone talks). */
    var onVoiceClip: ((bytes: ByteArray, senderName: String?) -> Unit)? = null

    /** Set by the service to get notified when another phone pings us with
     *  [sendRing] — "make some noise so I can find you." */
    var onRingReceived: (() -> Unit)? = null

    /** Set by the service to push a loud, attention-grabbing notification
     *  the moment someone else's SOS first reaches this phone — not just a
     *  quiet new row in the SOS tab's list, which only whoever already has
     *  that tab open would notice. Fires once per genuinely new SOS, not on
     *  every carrier-pigeon replay of one already known. */
    var onSosReceived: ((senderName: String) -> Unit)? = null

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
                val senderDeviceId = endpointToDevice[endpointId]
                val senderName = senderDeviceId?.let { com.ghaly.findme.repo.PeerRepository.peers.value[it]?.name }
                report("Voice clip received (${bytes.size} bytes) — playing…")
                onVoiceClip?.invoke(bytes, senderName)
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
                        connected = isDirectNeighbor(envelope.originId),
                        batteryPct = obj.optInt("battery", -1).let { if (it in 0..100) it else null }
                    )
                )
            }
            MsgType.RING -> {
                onRingReceived?.invoke()
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
                            targetId = targetId,
                            photoBase64 = obj.optString("photo", "").ifEmpty { null }
                        )
                    )
                }
            }
            MsgType.SOS -> {
                val obj = JSONObject(envelope.payload)
                // A carrier-pigeon replay of an SOS we already know about mints a
                // fresh envelope id every time it's relayed to a newly-connected
                // phone, so this handler runs again on every reconnect while the
                // alert stays active. Only alert the person for a genuinely new
                // or newly-reactivated SOS — not someone else's phone reconnecting
                // to us — by checking whether it was already active before this
                // upsert, and never for an echo of our own SOS.
                val wasAlreadyActive = SosRepository.alerts.value[envelope.originId]?.active == true
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
                if (!wasAlreadyActive && envelope.originId != myId) {
                    onSosReceived?.invoke(envelope.originName)
                }
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
                        // The photo, if any, travels as its own ciphertext field
                        // ("ctPhoto") rather than being bundled into the same
                        // encrypted blob as the text — keeps the two independent
                        // so a photo-less message's payload doesn't grow just
                        // because the group sometimes shares photos.
                        val ctPhoto = obj.optString("ctPhoto", "")
                        val photo = if (ctPhoto.isNotEmpty()) Crypto.decrypt(ctPhoto, group.keyBase64) else null
                        com.ghaly.findme.repo.GroupChatRepository.add(
                            groupId,
                            ChatMessage(
                                id = envelope.id,
                                senderId = envelope.originId,
                                senderName = envelope.originName,
                                text = plaintext,
                                ts = envelope.ts,
                                isMine = false,
                                photoBase64 = photo
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
            MsgType.PLACE -> {
                val obj = JSONObject(envelope.payload)
                SavedPlaceRepository.receiveShared(
                    placeId = obj.optString("placeId", ""),
                    name = obj.optString("name", ""),
                    lat = obj.optDouble("lat", 0.0),
                    lon = obj.optDouble("lon", 0.0),
                    ts = envelope.ts,
                    sharedByName = envelope.originName
                )
            }
            MsgType.LISTING -> {
                val obj = JSONObject(envelope.payload)
                com.ghaly.findme.repo.MarketRepository.add(
                    com.ghaly.findme.model.MarketListing(
                        // A cache replay to a later-connected phone resends this
                        // same listing under a fresh envelope id (so it still
                        // floods normally) — "listingId" in the payload is what
                        // stays stable so it doesn't show up twice there.
                        id = obj.optString("listingId", envelope.id),
                        authorId = envelope.originId,
                        authorName = envelope.originName,
                        kind = try {
                            com.ghaly.findme.model.ListingKind.valueOf(obj.optString("kind", "FOR_SALE"))
                        } catch (e: Exception) { com.ghaly.findme.model.ListingKind.FOR_SALE },
                        title = obj.optString("title", ""),
                        details = obj.optString("details", ""),
                        priceText = obj.optString("priceText", ""),
                        ts = envelope.ts,
                        promoted = obj.optBoolean("promoted", false),
                        photoBase64 = obj.optString("photo", "").ifEmpty { null }
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
    @Volatile private var lastBatteryPct: Int? = null

    /** My own last known GPS fix, or null if we don't have one yet. */
    fun myLocation(): Pair<Double, Double>? = if (hasFix) lastLat to lastLon else null

    /** Call whenever we get a fresh GPS fix; broadcasts it and remembers it for new connections. */
    fun sendPosition(lat: Double, lon: Double, batteryPct: Int? = null) {
        lastLat = lat
        lastLon = lon
        hasFix = true
        lastBatteryPct = batteryPct
        broadcast(buildPositionEnvelope(lat, lon, batteryPct))
    }

    /** [targetId] null = send to everyone; otherwise a direct message to just that peer
     *  (still physically relayed by everyone, but only shown by the recipient). */
    fun sendChat(text: String, targetId: String? = null, photoBase64: String? = null): ChatMessage {
        val payload = JSONObject().put("text", text)
        if (targetId != null) payload.put("to", targetId)
        if (photoBase64 != null) payload.put("photo", photoBase64)
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
            targetId = targetId,
            photoBase64 = photoBase64
        )
    }

    /** Direct-sends a push-to-talk clip to every phone we're currently connected to —
     *  NOT flood-relayed like other message types, so it never travels past direct
     *  Bluetooth/Wi-Fi range. Returns false if there's nobody directly in range. */
    fun sendVoiceClip(bytes: ByteArray): Boolean {
        val targets = connectedEndpoints.toList()
        if (targets.isEmpty()) {
            report("No phone directly in range — voice clip not sent.")
            return false
        }
        val payload = Payload.fromBytes(bytes)
        for (endpointId in targets) {
            connectionsClient.sendPayload(endpointId, payload)
        }
        report("Voice clip (${bytes.size} bytes) sent to ${targets.size} nearby phone(s).")
        return true
    }

    fun directPeerCount(): Int = connectedEndpoints.size

    /** Makes a specific nearby phone flash/beep its own physical SOS signal
     *  for a few seconds — for finding someone or something (a dropped
     *  phone) that's out of sight but still in direct mesh range. Only
     *  works, and only makes sense, for a live direct neighbor — this isn't
     *  a mesh-wide broadcast, so the hop is pinned at MAX_HOP: the recipient
     *  never relays it onward. Returns false if that phone isn't a direct
     *  neighbor right now. */
    fun sendRing(targetDeviceId: String): Boolean {
        val endpointId = endpointToDevice.entries.firstOrNull { it.value == targetDeviceId }?.key
            ?: return false
        val envelope = Envelope(
            id = UUID.randomUUID().toString(),
            type = MsgType.RING,
            originId = myId,
            originName = myName,
            hop = MAX_HOP,
            ts = System.currentTimeMillis(),
            payload = "{}"
        )
        sendEnvelopeTo(endpointId, envelope)
        return true
    }

    fun sendGroupMessage(groupId: String, text: String, photoBase64: String? = null): ChatMessage? {
        val group = GroupRepository.findById(context, groupId) ?: return null
        val ct = Crypto.encrypt(text, group.keyBase64)
        val payload = JSONObject().put("groupId", groupId).put("ct", ct)
        if (photoBase64 != null) {
            payload.put("ctPhoto", Crypto.encrypt(photoBase64, group.keyBase64))
        }
        val envelope = Envelope(
            id = UUID.randomUUID().toString(),
            type = MsgType.GROUP_CHAT,
            originId = myId,
            originName = myName,
            hop = 0,
            ts = System.currentTimeMillis(),
            payload = payload.toString()
        )
        seenIds.add(envelope.id)
        broadcast(envelope)
        val mine = ChatMessage(
            id = envelope.id,
            senderId = myId,
            senderName = myName,
            text = text,
            ts = envelope.ts,
            isMine = true,
            photoBase64 = photoBase64
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

    /** Broadcasts a saved place to the whole team, e.g. a water source or
     *  where the truck is parked. The envelope's own id is random (so it
     *  dedupes/relays normally for this hop), but [place.id] travels inside
     *  the payload as a stable "placeId" — that's what every phone uses to
     *  recognize it's the same place again later, including when it comes
     *  back around through the carrier-pigeon cache replay below. */
    fun sharePlace(place: com.ghaly.findme.model.SavedPlace) {
        val payload = JSONObject()
            .put("placeId", place.id)
            .put("name", place.name)
            .put("lat", place.lat)
            .put("lon", place.lon)
        val envelope = Envelope(
            id = UUID.randomUUID().toString(),
            type = MsgType.PLACE,
            originId = myId,
            originName = myName,
            hop = 0,
            ts = place.ts,
            payload = payload.toString()
        )
        seenIds.add(envelope.id)
        broadcast(envelope)
    }

    /** Broadcasts a market listing (for-sale / service / freelance offer) to
     *  the whole team. Unlike a saved place, a listing has no separate local
     *  "private" state — every listing is meant to be seen, so this is just
     *  a normal broadcast, same shape as a post. */
    fun sendListing(kind: com.ghaly.findme.model.ListingKind, title: String, details: String, priceText: String, promoted: Boolean = false, photoBase64: String? = null) {
        val listingId = UUID.randomUUID().toString()
        val envelope = Envelope(
            id = listingId,
            type = MsgType.LISTING,
            originId = myId,
            originName = myName,
            hop = 0,
            ts = System.currentTimeMillis(),
            payload = JSONObject()
                .put("listingId", listingId)
                .put("kind", kind.name)
                .put("title", title)
                .put("details", details)
                .put("priceText", priceText)
                .put("promoted", promoted)
                .put("photo", photoBase64 ?: "")
                .toString()
        )
        seenIds.add(envelope.id)
        broadcast(envelope)
        com.ghaly.findme.repo.MarketRepository.add(
            com.ghaly.findme.model.MarketListing(
                id = listingId, authorId = myId, authorName = myName, kind = kind,
                title = title, details = details, priceText = priceText, ts = envelope.ts, promoted = promoted,
                photoBase64 = photoBase64
            )
        )
    }

    /** Marks one of our own listings as promoted/sponsored (or un-marks it)
     *  and rebroadcasts it under its own stable listingId so every phone's
     *  copy upserts to match, instead of only updating this one phone. */
    fun setListingPromoted(listing: com.ghaly.findme.model.MarketListing, promoted: Boolean) {
        val updated = listing.copy(promoted = promoted)
        com.ghaly.findme.repo.MarketRepository.add(updated)
        val envelope = Envelope(
            id = UUID.randomUUID().toString(),
            type = MsgType.LISTING,
            originId = updated.authorId,
            originName = updated.authorName,
            hop = 0,
            ts = updated.ts,
            payload = JSONObject()
                .put("listingId", updated.id)
                .put("kind", updated.kind.name)
                .put("title", updated.title)
                .put("details", updated.details)
                .put("priceText", updated.priceText)
                .put("promoted", updated.promoted)
                .put("photo", updated.photoBase64 ?: "")
                .toString()
        )
        broadcast(envelope)
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
                payload = JSONObject().put("lat", peer.lat).put("lon", peer.lon).let {
                    if (peer.batteryPct != null) it.put("battery", peer.batteryPct) else it
                }.toString()
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
        for (place in SavedPlaceRepository.places.value) {
            val sharedBy = place.sharedByName ?: continue // never leak purely-private places
            sendEnvelopeTo(endpointId, Envelope(
                id = UUID.randomUUID().toString(),
                type = MsgType.PLACE,
                originId = place.id, // not a real device id, but fine: receivers only read originName
                originName = sharedBy,
                hop = 0,
                ts = place.ts,
                payload = JSONObject()
                    .put("placeId", place.id)
                    .put("name", place.name)
                    .put("lat", place.lat)
                    .put("lon", place.lon)
                    .toString()
            ))
        }
        for (listing in com.ghaly.findme.repo.MarketRepository.listings.value) {
            sendEnvelopeTo(endpointId, Envelope(
                id = UUID.randomUUID().toString(),
                type = MsgType.LISTING,
                originId = listing.authorId,
                originName = listing.authorName,
                hop = 0,
                ts = listing.ts,
                payload = JSONObject()
                    .put("listingId", listing.id)
                    .put("kind", listing.kind.name)
                    .put("title", listing.title)
                    .put("details", listing.details)
                    .put("priceText", listing.priceText)
                    .put("promoted", listing.promoted)
                    .put("photo", listing.photoBase64 ?: "")
                    .toString()
            ))
        }
    }

    private fun buildPositionEnvelope(lat: Double, lon: Double, batteryPct: Int?) = Envelope(
        id = UUID.randomUUID().toString(),
        type = MsgType.POSITION,
        originId = myId,
        originName = myName,
        hop = 0,
        ts = System.currentTimeMillis(),
        payload = JSONObject().put("lat", lat).put("lon", lon).let {
            if (batteryPct != null) it.put("battery", batteryPct) else it
        }.toString()
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
