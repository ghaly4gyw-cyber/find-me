package com.ghaly.findme.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ghaly.findme.R
import com.ghaly.findme.mesh.VoiceRecorder
import com.ghaly.findme.repo.PeerRepository
import kotlinx.coroutines.launch

class PeersFragment : Fragment(R.layout.fragment_peers) {

    private val adapter = PeerAdapter()
    private var recorder: VoiceRecorder? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<RecyclerView>(R.id.peersList)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        val emptyText = view.findViewById<View>(R.id.emptyText)

        recorder = VoiceRecorder(requireContext())
        val pttButton = view.findViewById<Button>(R.id.pttButton)
        pttButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startTalking(pttButton)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopTalking(pttButton)
                    true
                }
                else -> false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PeerRepository.peers.collect { map ->
                    val values = map.values.toList()
                    adapter.items = values
                    emptyText.visibility = if (values.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        // Refresh direction arrows periodically — my own position moves even when
        // nothing else about the peer list changes.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val mesh = (requireActivity() as? MainActivity)?.mesh
                    adapter.myLocation = mesh?.myLocation()
                    kotlinx.coroutines.delay(10_000L)
                }
            }
        }
    }

    private fun startTalking(button: Button) {
        val hasPermission = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Toast.makeText(requireContext(), getString(R.string.permissions_needed), Toast.LENGTH_SHORT).show()
            return
        }
        val mesh = (requireActivity() as? MainActivity)?.mesh
        if (mesh == null || mesh.directPeerCount() == 0) {
            Toast.makeText(requireContext(), R.string.voice_in_range_only, Toast.LENGTH_SHORT).show()
            return
        }
        if (recorder?.start() == true) {
            button.text = "● REC — release to send"
        }
    }

    private fun stopTalking(button: Button) {
        button.setText(R.string.voice_hold_to_talk)
        val bytes = recorder?.stop() ?: return
        val mesh = (requireActivity() as? MainActivity)?.mesh ?: return
        mesh.sendVoiceClip(bytes)
    }

    override fun onDestroyView() {
        recorder?.cancel()
        recorder = null
        super.onDestroyView()
    }
}
