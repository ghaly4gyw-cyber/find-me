package com.ghaly.findme.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ghaly.findme.R
import com.ghaly.findme.model.PeerPosition
import com.ghaly.findme.repo.PeerRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RadarMapFragment : Fragment(R.layout.fragment_radar_map) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val radar = view.findViewById<RadarMapView>(R.id.radarView)
        val emptyText = view.findViewById<View>(R.id.mapEmptyText)

        radar.onPeerTap = { peer ->
            showPeerActionMenu(radar, peer)
        }
        radar.headingOf = { deviceId -> PeerRepository.headingOf(deviceId) }

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
}
