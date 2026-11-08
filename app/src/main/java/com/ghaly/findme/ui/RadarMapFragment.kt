package com.ghaly.findme.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RadarMapFragment : Fragment(R.layout.fragment_radar_map) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val radar = view.findViewById<RadarMapView>(R.id.radarView)
        val emptyText = view.findViewById<View>(R.id.mapEmptyText)

        // Saved places are purely local — not tied to the mesh service, so load
        // them directly here too (harmless no-op if the service already has).
        SavedPlaceRepository.init(requireContext())

        radar.onPeerTap = { peer ->
            showPeerActionMenu(radar, peer)
        }
        radar.onPlaceTap = { place ->
            showPlaceActionMenu(radar, place)
        }
        radar.headingOf = { deviceId -> PeerRepository.headingOf(deviceId) }

        view.findViewById<Button>(R.id.savePlaceButton).setOnClickListener {
            showSavePlaceDialog(radar)
        }
        view.findViewById<Button>(R.id.myPlacesButton).setOnClickListener {
            showPlacesListDialog(radar)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PeerRepository.peers.collect { map ->
                    val values = map.values.toList()
                    radar.peers = values
                    emptyText.visibility = if (values.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SavedPlaceRepository.places.collect { radar.savedPlaces = it }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val mesh = (requireActivity() as? MainActivity)?.mesh
                    radar.myLocation = mesh?.myLocation()
                    delay(10_000L)
                }
            }
        }
    }

    /** Tapping a dot offers a quick menu instead of jumping straight to the
     *  profile — "Follow" draws a live direction line to that person until
     *  cancelled, so you can keep walking while glancing at the radar. */
    private fun showPeerActionMenu(radar: RadarMapView, peer: PeerPosition) {
        val isFollowing = radar.followedDeviceId == peer.deviceId
        val options = if (isFollowing) {
            arrayOf(getString(R.string.peer_action_view_profile), getString(R.string.peer_action_stop_following))
        } else {
            arrayOf(getString(R.string.peer_action_view_profile), getString(R.string.peer_action_follow))
        }
        AlertDialog.Builder(requireContext())
            .setTitle(peer.name)
            .setItems(options) { _, which ->
                if (which == 0) {
                    showPeerProfileDialog(peer, radar.myLocation)
                } else if (isFollowing) {
                    radar.followedDeviceId = null
                    Toast.makeText(requireContext(), getString(R.string.stopped_following_fmt, peer.name), Toast.LENGTH_SHORT).show()
                } else {
                    radar.followedDeviceId = peer.deviceId
                    Toast.makeText(requireContext(), getString(R.string.now_following_fmt, peer.name), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    /** Saves the current GPS fix under a name the user picks — "Zouérat camp",
     *  "water point", wherever. Purely local; works the same whether the spot
     *  is a few meters away or you'll later be hundreds of km from it. */
    private fun showSavePlaceDialog(radar: RadarMapView) {
        val location = radar.myLocation
        if (location == null) {
            Toast.makeText(requireContext(), R.string.no_location_yet, Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(requireContext())
        input.hint = getString(R.string.save_place_dialog_hint)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.save_place_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                    .ifEmpty { getString(R.string.map_center_you) }
                SavedPlaceRepository.add(name, location.first, location.second)
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
            if (mine != null) {
                val results = FloatArray(2)
                android.location.Location.distanceBetween(mine.first, mine.second, place.lat, place.lon, results)
                "${place.name}  ·  ${distanceLabel(results[0])} ${bearingArrow(results[1])}"
            } else {
                place.name
            }
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
        val options = mutableListOf(
            getString(if (isNavigating) R.string.place_action_stop_navigating else R.string.place_action_navigate),
            getString(R.string.place_action_rename),
            getString(R.string.place_action_delete)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(place.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        if (isNavigating) {
                            radar.followedPlaceId = null
                            Toast.makeText(requireContext(), getString(R.string.stopped_navigating_fmt, place.name), Toast.LENGTH_SHORT).show()
                        } else {
                            radar.followedPlaceId = place.id
                            Toast.makeText(requireContext(), getString(R.string.now_navigating_fmt, place.name), Toast.LENGTH_SHORT).show()
                        }
                    }
                    1 -> showRenamePlaceDialog(radar, place)
                    2 -> {
                        if (radar.followedPlaceId == place.id) radar.followedPlaceId = null
                        SavedPlaceRepository.remove(place.id)
                        Toast.makeText(requireContext(), getString(R.string.place_deleted_fmt, place.name), Toast.LENGTH_SHORT).show()
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
