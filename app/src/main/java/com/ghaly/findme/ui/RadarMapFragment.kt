package com.ghaly.findme.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ghaly.findme.R
import com.ghaly.findme.model.PeerPosition
import com.ghaly.findme.model.SavedPlace
import com.ghaly.findme.repo.PeerRepository
import com.ghaly.findme.repo.SavedPlaceRepository
import com.ghaly.findme.repo.TrailRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.util.Locale

class RadarMapFragment : Fragment(R.layout.fragment_radar_map), SensorEventListener {

    companion object {
        private const val ARG_FOLLOW_DEVICE_ID = "follow_device_id"

        /** A plain tab switch uses the no-arg constructor (same as every other
         *  tab). This one is for arriving already pointed at someone — from an
         *  SOS card, say — without waiting for the peer list to load first. */
        fun newInstance(followDeviceId: String): RadarMapFragment = RadarMapFragment().apply {
            arguments = Bundle().apply { putString(ARG_FOLLOW_DEVICE_ID, followDeviceId) }
        }
    }

    private lateinit var radar: RadarMapView
    private lateinit var navigationHint: TextView
    private lateinit var accuracyPill: TextView
    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // "Real map" mode (osmdroid/OpenStreetMap) — off by default; the radar
    // above is the always-available, zero-data safety view, and this is an
    // opt-in extra for whoever has downloaded their area ahead of time.
    private lateinit var offlineMapView: MapView
    private lateinit var mapToggleButton: Button
    private lateinit var downloadAreaButton: Button
    private var mapMode = false
    private var myLocationMarker: Marker? = null
    private val peerMarkers = mutableMapOf<String, Marker>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        radar = view.findViewById(R.id.radarView)
        navigationHint = view.findViewById(R.id.navigationHint)
        accuracyPill = view.findViewById(R.id.accuracyPill)
        val emptyText = view.findViewById<View>(R.id.mapEmptyText)

        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        // Saved places are purely local — not tied to the mesh service, so load
        // them directly here too (harmless no-op if the service already has).
        SavedPlaceRepository.init(requireContext())
        TrailRepository.init(requireContext())

        // Arrived here already pointed at someone (from an SOS/inactivity
        // card) — set it before the peer list even loads; updateNavigationHint
        // re-resolves it from PeerRepository as soon as that collect fires.
        arguments?.getString(ARG_FOLLOW_DEVICE_ID)?.let { radar.followedDeviceId = it }

        radar.onPeerTap = { peer ->
            showPeerActionMenu(radar, peer)
        }
        radar.onPlaceTap = { place ->
            showPlaceActionMenu(radar, place)
        }
        radar.headingOf = { deviceId -> PeerRepository.headingOf(deviceId) }

        val qiblaButton = view.findViewById<Button>(R.id.qiblaButton)
        qiblaButton.setOnClickListener {
            radar.showQibla = !radar.showQibla
            qiblaButton.backgroundTintList = if (radar.showQibla) {
                android.content.res.ColorStateList.valueOf(resources.getColor(R.color.teal_400, null))
            } else {
                null
            }
        }

        view.findViewById<Button>(R.id.savePlaceButton).setOnClickListener {
            showSavePlaceDialog(radar)
        }
        view.findViewById<Button>(R.id.myPlacesButton).setOnClickListener {
            showPlacesListDialog(radar)
        }
        view.findViewById<Button>(R.id.enterCoordinatesButton).setOnClickListener {
            showEnterCoordinatesDialog(radar)
        }
        view.findViewById<Button>(R.id.recenterButton).setOnClickListener {
            radar.resetView()
        }

        offlineMapView = view.findViewById(R.id.offlineMapView)
        offlineMapView.setTileSource(TileSourceFactory.MAPNIK)
        offlineMapView.setMultiTouchControls(true)
        offlineMapView.setUseDataConnection(true)
        offlineMapView.minZoomLevel = 3.0
        offlineMapView.maxZoomLevel = 19.0
        offlineMapView.controller.setZoom(14.0)

        mapToggleButton = view.findViewById(R.id.mapToggleButton)
        downloadAreaButton = view.findViewById(R.id.downloadAreaButton)
        mapToggleButton.setOnClickListener { setMapMode(!mapMode) }
        downloadAreaButton.setOnClickListener { downloadCurrentArea() }
        // Long-press is deliberately the only way to clear the trail — this is
        // the one way back for someone who's actually lost, so it shouldn't be
        // a single careless tap away from being wiped.
        view.findViewById<Button>(R.id.myPlacesButton).setOnLongClickListener {
            showClearTrailDialog()
            true
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PeerRepository.peers.collect { map ->
                    val values = map.values.toList()
                    radar.peers = values
                    emptyText.visibility = if (values.isEmpty()) View.VISIBLE else View.GONE
                    updateNavigationHint()
                    updatePeerMarkers(values)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SavedPlaceRepository.places.collect {
                    radar.savedPlaces = it
                    updateNavigationHint()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val mesh = (requireActivity() as? MainActivity)?.mesh
                    val mine = mesh?.myLocation()
                    radar.myLocation = mine
                    updateNavigationHint()
                    updateAccuracyPill(mesh?.myAccuracyM())
                    updateMyLocationMarker(mine)
                    // The service itself is asked (in onResume/onPause below) to take
                    // GPS fixes every 5s while this screen is up, instead of its normal
                    // battery-saving 30-120s — poll in step with that so a fresh fix
                    // actually reaches the screen promptly instead of sitting around
                    // for up to 10s before the UI notices it.
                    delay(3_000L)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                TrailRepository.points.collect { points ->
                    radar.trail = points.map { it.lat to it.lon }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                com.ghaly.findme.repo.LinkRepository.linked.collect { links ->
                    radar.linkedDeviceIds = links.map { it.deviceId }.toSet()
                }
            }
        }
    }

    /** The dot on the radar looks exactly as confident whether the fix behind
     *  it is accurate to 5 meters or 200 — this is the one honest signal of
     *  how much to trust it. Colors mirror the daylight pill: calm when the
     *  fix is tight, escalating as it gets fuzzy enough that "you are here"
     *  starts being a rough guess rather than a fact. */
    private fun updateAccuracyPill(accuracyM: Float?) {
        if (accuracyM == null) {
            accuracyPill.visibility = View.GONE
            return
        }
        accuracyPill.text = getString(R.string.gps_accuracy_fmt, accuracyM.toInt())
        accuracyPill.setTextColor(
            resources.getColor(
                when {
                    accuracyM <= 20f -> R.color.sand_900
                    accuracyM <= 75f -> R.color.amber_500
                    else -> R.color.sos_red
                },
                null
            )
        )
        accuracyPill.visibility = View.VISIBLE
    }

    /** Accepts "lat, lon" or "lat lon" in plain decimal degrees — e.g.
     *  "20.9316, -17.0466" — and rejects anything else outright rather than
     *  guessing at a different format, since a silently-misparsed coordinate
     *  out here is far worse than an error asking the person to re-type it. */
    private fun parseCoordinates(raw: String): Pair<Double, Double>? {
        val parts = raw.trim().split(Regex("[,\\s]+")).filter { it.isNotBlank() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
        return lat to lon
    }

    /** Lets someone punch in a coordinate heard over the radio or read off a
     *  paper map — rather than only ever being able to mark "where I'm
     *  standing right now" — and either just keep it for later ("Save") or
     *  start navigating to it immediately ("Go"). Both reuse the exact same
     *  saved-place machinery (radar dot, nav pill, stop-navigating) as a
     *  place marked by standing on it, so nothing else needs to know the
     *  difference. */
    private fun showEnterCoordinatesDialog(radar: RadarMapView) {
        val nameInput = EditText(requireContext())
        nameInput.hint = getString(R.string.coordinates_name_hint)
        val coordsInput = EditText(requireContext())
        coordsInput.hint = getString(R.string.coordinates_hint)
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(requireContext())
        container.orientation = android.widget.LinearLayout.VERTICAL
        container.setPadding(padding, padding / 2, padding, 0)
        container.addView(nameInput)
        container.addView(coordsInput)

        fun savedPlaceFrom(): SavedPlace? {
            val coords = parseCoordinates(coordsInput.text?.toString().orEmpty())
            if (coords == null) {
                Toast.makeText(requireContext(), R.string.invalid_coordinates, Toast.LENGTH_SHORT).show()
                return null
            }
            val name = nameInput.text?.toString()?.trim().orEmpty()
                .ifEmpty { getString(R.string.entered_location_default_name) }
            return SavedPlaceRepository.add(name, coords.first, coords.second, null)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.enter_coordinates_title)
            .setView(container)
            .setPositiveButton(R.string.coordinates_go) { _, _ ->
                val place = savedPlaceFrom() ?: return@setPositiveButton
                radar.followedPlaceId = place.id
                updateNavigationHint()
                Toast.makeText(requireContext(), getString(R.string.now_navigating_fmt, place.name), Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.coordinates_save_only) { _, _ ->
                val place = savedPlaceFrom() ?: return@setNeutralButton
                Toast.makeText(requireContext(), getString(R.string.place_saved_fmt, place.name), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showClearTrailDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_trail_dialog_title)
            .setMessage(R.string.clear_trail_dialog_message)
            .setPositiveButton(R.string.place_action_delete) { _, _ ->
                TrailRepository.clear()
                Toast.makeText(requireContext(), R.string.trail_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // SENSOR_DELAY_UI is plenty smooth for a compass readout and far lighter
        // on the battery than GAME/FASTEST — this needs to run for hours in
        // someone's pocket, not just look good in a demo.
        rotationSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        // Someone's actually looking at the distance/bearing readout right now —
        // switch GPS fixes to a short, frequent cadence so it feels live instead
        // of jumping once every 30-120s. Reverted in onPause so an idle phone
        // goes right back to the battery-aware schedule.
        (requireActivity() as? MainActivity)?.mesh?.setLiveLocationBoost(true)
        offlineMapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
        (requireActivity() as? MainActivity)?.mesh?.setLiveLocationBoost(false)
        offlineMapView.onPause()
    }

    /** Switches between the always-available offline radar and the real
     *  (OpenStreetMap) map. Centers on the last known fix the first time map
     *  mode turns on so it doesn't open on a random patch of ocean. */
    private fun setMapMode(enabled: Boolean) {
        mapMode = enabled
        radar.visibility = if (enabled) View.GONE else View.VISIBLE
        offlineMapView.visibility = if (enabled) View.VISIBLE else View.GONE
        downloadAreaButton.visibility = if (enabled) View.VISIBLE else View.GONE
        mapToggleButton.backgroundTintList = if (enabled) {
            android.content.res.ColorStateList.valueOf(resources.getColor(R.color.teal_400, null))
        } else {
            null
        }
        if (enabled) {
            radar.myLocation?.let { (lat, lon) -> offlineMapView.controller.setCenter(GeoPoint(lat, lon)) }
        }
    }

    /** Keeps a single reusable marker for "you" in sync with every fresh GPS
     *  fix, same data the radar dot already uses. */
    private fun updateMyLocationMarker(location: Pair<Double, Double>?) {
        if (!::offlineMapView.isInitialized || location == null) return
        val marker = myLocationMarker ?: Marker(offlineMapView).also {
            it.title = getString(R.string.map_center_you)
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            offlineMapView.overlays.add(it)
            myLocationMarker = it
        }
        marker.position = GeoPoint(location.first, location.second)
        if (mapMode) offlineMapView.invalidate()
    }

    /** Mirrors the radar's teammate dots onto the real-map markers, reusing
     *  the exact same tap menu (profile / follow / ring) either view is in. */
    private fun updatePeerMarkers(peers: List<PeerPosition>) {
        if (!::offlineMapView.isInitialized) return
        val seen = mutableSetOf<String>()
        for (peer in peers) {
            seen.add(peer.deviceId)
            val marker = peerMarkers.getOrPut(peer.deviceId) {
                Marker(offlineMapView).also { offlineMapView.overlays.add(it) }
            }
            marker.position = GeoPoint(peer.lat, peer.lon)
            marker.title = peer.name
            marker.setOnMarkerClickListener { _, _ ->
                showPeerActionMenu(radar, peer)
                true
            }
        }
        val stale = peerMarkers.keys - seen
        for (deviceId in stale) {
            val marker = peerMarkers.remove(deviceId)
            if (marker != null) offlineMapView.overlays.remove(marker)
        }
        if (mapMode) offlineMapView.invalidate()
    }

    /** Downloads every tile in the area currently on screen, a couple of
     *  zoom levels either side of the current one, so this same area keeps
     *  working with no connection at all afterward — the actual point of a
     *  "download this area" button for people who may be headed somewhere
     *  with no signal. Needs a connection only for this one step. */
    private fun downloadCurrentArea() {
        val connectivity = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connectivity?.activeNetwork
        val caps = network?.let { connectivity.getNetworkCapabilities(it) }
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (!online) {
            Toast.makeText(requireContext(), R.string.download_area_need_connection, Toast.LENGTH_LONG).show()
            return
        }

        val boundingBox = offlineMapView.boundingBox
        val currentZoom = offlineMapView.zoomLevelDouble.toInt()
        val zoomMin = (currentZoom - 2).coerceAtLeast(offlineMapView.minZoomLevel.toInt())
        val zoomMax = (currentZoom + 3).coerceAtMost(offlineMapView.maxZoomLevel.toInt())

        val progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        progressBar.setPadding(padding, padding, padding, padding)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.downloading_area_title)
            .setMessage(R.string.downloading_area_message)
            .setView(progressBar)
            .setCancelable(false)
            .show()

        val cacheManager = CacheManager(offlineMapView)
        cacheManager.downloadAreaAsync(requireContext(), boundingBox, zoomMin, zoomMax, object : CacheManager.CacheManagerCallback {
            override fun onTaskComplete() {
                dialog.dismiss()
                Toast.makeText(
                    requireContext(),
                    String.format(Locale.US, getString(R.string.download_area_done_fmt), progressBar.max),
                    Toast.LENGTH_LONG
                ).show()
            }

            override fun onTaskFailed(errors: Int) {
                dialog.dismiss()
                Toast.makeText(requireContext(), R.string.download_area_failed, Toast.LENGTH_LONG).show()
            }

            override fun downloadStarted() {}

            override fun setPossibleTilesInArea(total: Int) {
                activity?.runOnUiThread { progressBar.max = total }
            }

            override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                activity?.runOnUiThread { progressBar.progress = progress }
            }
        })
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        val azimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        radar.deviceHeadingDeg = ((azimuthDeg % 360f) + 360f) % 360f
        updateNavigationHint()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** The pill at the top of the map: for whoever/whatever is currently being
     *  followed, a compass-relative arrow (rotates with the phone, so "←" really
     *  does mean "turn left from where you're facing right now") plus distance —
     *  this is the direct answer to "which way do I actually walk", instead of
     *  making the user work it out from a fixed north-up line themselves. */
    private fun updateNavigationHint() {
        val mine = radar.myLocation
        val followedDeviceId = radar.followedDeviceId
        val followedPlaceId = radar.followedPlaceId
        if (mine == null || (followedDeviceId == null && followedPlaceId == null)) {
            navigationHint.visibility = View.GONE
            return
        }
        val target: Pair<String, Pair<Double, Double>>? = when {
            followedDeviceId != null -> PeerRepository.peers.value[followedDeviceId]
                ?.let { it.name to (it.lat to it.lon) }
            else -> SavedPlaceRepository.places.value.firstOrNull { it.id == followedPlaceId }
                ?.let { it.name to (it.lat to it.lon) }
        }
        if (target == null) {
            navigationHint.visibility = View.GONE
            return
        }
        val (name, pos) = target
        val results = FloatArray(2)
        Location.distanceBetween(mine.first, mine.second, pos.first, pos.second, results)
        val relativeBearing = ((results[1] - radar.deviceHeadingDeg) % 360f + 360f) % 360f
        navigationHint.text = "$name   ${bearingArrow(relativeBearing)}   ${distanceLabel(results[0])}"
        navigationHint.visibility = View.VISIBLE
    }

    /** Tapping a dot offers a quick menu instead of jumping straight to the
     *  profile — "Follow" draws a live direction line to that person until
     *  cancelled, so you can keep walking while glancing at the radar. */
    private fun showPeerActionMenu(radar: RadarMapView, peer: PeerPosition) {
        val isFollowing = radar.followedDeviceId == peer.deviceId
        val mesh = (requireActivity() as? MainActivity)?.mesh
        // Ringing only makes sense — and only works — for someone we hold a
        // live direct Bluetooth/Wi-Fi link to right now, not someone we only
        // know about via multi-hop relay. Hiding the option otherwise avoids
        // offering something that would just silently fail.
        val canRing = mesh?.isDirectNeighbor(peer.deviceId) == true
        val options = mutableListOf(
            getString(R.string.peer_action_view_profile),
            if (isFollowing) getString(R.string.peer_action_stop_following) else getString(R.string.peer_action_follow)
        )
        if (canRing) options.add(getString(R.string.peer_action_ring))

        AlertDialog.Builder(requireContext())
            .setTitle(peer.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showPeerProfileDialog(peer, radar.myLocation)
                    1 -> if (isFollowing) {
                        radar.followedDeviceId = null
                        updateNavigationHint()
                        Toast.makeText(requireContext(), getString(R.string.stopped_following_fmt, peer.name), Toast.LENGTH_SHORT).show()
                    } else {
                        radar.followedDeviceId = peer.deviceId
                        updateNavigationHint()
                        Toast.makeText(requireContext(), getString(R.string.now_following_fmt, peer.name), Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        val sent = mesh?.sendRing(peer.deviceId) == true
                        val message = if (sent) {
                            getString(R.string.ring_sent_fmt, peer.name)
                        } else {
                            getString(R.string.ring_in_range_only)
                        }
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    /** Saves the current GPS fix under a name the user picks — "Zouérat camp",
     *  "water point", wherever. Purely local; works the same whether the spot
     *  is a few meters away or you'll later be hundreds of km from it. */
    private fun showSavePlaceDialog(radar: RadarMapView) {
        if (radar.myLocation == null) {
            Toast.makeText(requireContext(), R.string.no_location_yet, Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(requireContext())
        input.hint = getString(R.string.save_place_dialog_hint)
        val shareCheckbox = android.widget.CheckBox(requireContext())
        shareCheckbox.text = getString(R.string.share_with_team)
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(requireContext())
        container.orientation = android.widget.LinearLayout.VERTICAL
        container.setPadding(padding, padding / 2, padding, 0)
        container.addView(input)
        container.addView(shareCheckbox)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.save_place_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                // Re-read the location HERE, not when the dialog opened — the
                // person may spend several seconds typing a name, and GPS can
                // easily have moved on to a better (or just different) fix by
                // the time they actually confirm. Using the stale fix from
                // dialog-open time is exactly what was making a saved place
                // land somewhere other than where the person actually was.
                val location = radar.myLocation
                if (location == null) {
                    Toast.makeText(requireContext(), R.string.no_location_yet, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val name = input.text?.toString()?.trim().orEmpty()
                    .ifEmpty { getString(R.string.map_center_you) }
                val mesh = (requireActivity() as? MainActivity)?.mesh
                val sharedByName = if (shareCheckbox.isChecked)
                    com.ghaly.findme.mesh.DeviceIdentity.name(requireContext()) else null
                val place = SavedPlaceRepository.add(name, location.first, location.second, sharedByName)
                if (sharedByName != null) mesh?.sharePlace(place)
                Toast.makeText(requireContext(), getString(R.string.place_saved_fmt, name), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The full list of saved places, each with distance/bearing from here right
     *  now — useful for picking one to navigate to even when it's far enough
     *  that its dot is just sitting clamped at the edge of the radar. */
    private fun showPlacesListDialog(radar: RadarMapView) {
        val places = SavedPlaceRepository.places.value
        if (places.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_saved_places, Toast.LENGTH_SHORT).show()
            return
        }
        val mine = radar.myLocation
        val labels = places.map { place ->
            val base = if (mine != null) {
                val results = FloatArray(2)
                android.location.Location.distanceBetween(mine.first, mine.second, place.lat, place.lon, results)
                "${place.name}  ·  ${distanceLabel(results[0])} ${bearingArrow(results[1])}"
            } else {
                place.name
            }
            val shared = place.sharedByName
            if (shared != null) "$base  ·  ${getString(R.string.shared_by_fmt, shared)}" else base
        }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.my_places)
            .setItems(labels) { _, index -> showPlaceActionMenu(radar, places[index]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Navigate / Rename / Delete for one saved place — reachable either by
     *  tapping its marker on the radar or picking it from the full list. */
    private fun showPlaceActionMenu(radar: RadarMapView, place: SavedPlace) {
        val isNavigating = radar.followedPlaceId == place.id
        val alreadyShared = place.sharedByName != null
        val options = mutableListOf(
            getString(if (isNavigating) R.string.place_action_stop_navigating else R.string.place_action_navigate),
            getString(R.string.place_action_rename),
            getString(R.string.place_action_delete)
        )
        if (!alreadyShared) options.add(getString(R.string.share_with_team))
        val title = if (place.sharedByName != null)
            "${place.name}  (${getString(R.string.shared_by_fmt, place.sharedByName)})" else place.name
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        if (isNavigating) {
                            radar.followedPlaceId = null
                            updateNavigationHint()
                            Toast.makeText(requireContext(), getString(R.string.stopped_navigating_fmt, place.name), Toast.LENGTH_SHORT).show()
                        } else {
                            radar.followedPlaceId = place.id
                            updateNavigationHint()
                            Toast.makeText(requireContext(), getString(R.string.now_navigating_fmt, place.name), Toast.LENGTH_SHORT).show()
                        }
                    }
                    1 -> showRenamePlaceDialog(radar, place)
                    2 -> {
                        if (radar.followedPlaceId == place.id) { radar.followedPlaceId = null; updateNavigationHint() }
                        SavedPlaceRepository.remove(place.id)
                        Toast.makeText(requireContext(), getString(R.string.place_deleted_fmt, place.name), Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        val myName = com.ghaly.findme.mesh.DeviceIdentity.name(requireContext())
                        val shared = SavedPlaceRepository.markShared(place.id, myName)
                        if (shared != null) {
                            (requireActivity() as? MainActivity)?.mesh?.sharePlace(shared)
                            Toast.makeText(requireContext(), getString(R.string.place_shared_fmt, place.name), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun showRenamePlaceDialog(radar: RadarMapView, place: SavedPlace) {
        val input = EditText(requireContext())
        input.setText(place.name)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rename_place_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) SavedPlaceRepository.rename(place.id, name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
