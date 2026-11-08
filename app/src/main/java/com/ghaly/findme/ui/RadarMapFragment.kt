package com.ghaly.findme.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ghaly.findme.R
import com.ghaly.findme.repo.PeerRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RadarMapFragment : Fragment(R.layout.fragment_radar_map) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val radar = view.findViewById<RadarMapView>(R.id.radarView)
        val emptyText = view.findViewById<View>(R.id.mapEmptyText)

        radar.onPeerTap = { peer ->
            showPeerProfileDialog(peer, radar.myLocation)
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
                while (true) {
                    val mesh = (requireActivity() as? MainActivity)?.mesh
                    radar.myLocation = mesh?.myLocation()
                    delay(10_000L)
                }
            }
        }
    }
}
