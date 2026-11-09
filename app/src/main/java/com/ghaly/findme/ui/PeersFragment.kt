package com.ghaly.findme.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ghaly.findme.R
import com.ghaly.findme.mesh.DeviceIdentity
import com.ghaly.findme.mesh.VoiceRecorder
import com.ghaly.findme.repo.InactivityRepository
import com.ghaly.findme.repo.PeerRepository
import com.ghaly.findme.repo.RadioLogRepository
import com.ghaly.findme.repo.SosRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PeersFragment : Fragment(R.layout.fragment_peers) {

    private val adapter = PeerAdapter(onClick = { peer ->
        showPeerProfileDialog(peer, (requireActivity() as? MainActivity)?.mesh?.myLocation())
    })
    private var recorder: VoiceRecorder? = null
    private lateinit var radioLogText: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<RecyclerView>(R.id.peersList)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        list.layoutAnimation = android.view.animation.AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_fade_up)
        val emptyText = view.findViewById<View>(R.id.emptyText)

        view.findViewById<TextView>(R.id.exportReportButton).setOnClickListener { shareSafetyReport() }

        radioLogText = view.findViewById(R.id.radioLogText)
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
        // nothing else about the peer list changes. Also drives the radio's
        // "N in range" count, since that's a live property of the direct
        // mesh connections, not something any repository flow tells us about.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val mesh = (requireActivity() as? MainActivity)?.mesh
                    adapter.myLocation = mesh?.myLocation()
                    radioInRangeCount = mesh?.directPeerCount() ?: 0
                    renderRadioStatus()
                    kotlinx.coroutines.delay(10_000L)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RadioLogRepository.log.collect {
                    recentRadioLog = it
                    renderRadioStatus()
                }
            }
        }
    }

    private var radioInRangeCount = 0
    private var recentRadioLog: List<com.ghaly.findme.model.RadioTransmission> = emptyList()

    /** "Radio · N in range" plus the last couple of transmissions, e.g.
     *  "You · just now" / "Ahmed · 2m ago" — enough to see the channel is
     *  alive without needing a full scrolling history for what's meant to be
     *  a quick glance above the talk button. */
    private fun renderRadioStatus() {
        val sb = StringBuilder(getString(R.string.radio_status_fmt, radioInRangeCount))
        val recent = recentRadioLog.takeLast(2)
        if (recent.isEmpty()) {
            sb.append('\n').append(getString(R.string.radio_log_empty))
        } else {
            for (entry in recent.reversed()) {
                val who = if (entry.mine) getString(R.string.radio_you) else entry.senderName
                sb.append('\n').append(getString(R.string.radio_log_entry_fmt, who, timeAgo(requireContext(), entry.ts)))
            }
        }
        radioLogText.text = sb.toString()
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
            button.setText(R.string.recording_release)
        }
    }

    private fun stopTalking(button: Button) {
        button.setText(R.string.voice_hold_to_talk)
        val bytes = recorder?.stop() ?: return
        val mesh = (requireActivity() as? MainActivity)?.mesh ?: return
        val sent = mesh.sendVoiceClip(bytes)
        val message = if (sent) {
            RadioLogRepository.record(DeviceIdentity.name(requireContext()), mine = true)
            getString(R.string.voice_sent, mesh.directPeerCount())
        } else {
            getString(R.string.voice_in_range_only)
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    /** Builds a plain-text snapshot of everyone currently known to this phone —
     *  positions, last-seen times, and any active SOS/inactivity alerts — and
     *  hands it to Android's own share sheet. The mesh itself never reaches a
     *  company's office, but the *person carrying this phone* eventually does:
     *  this is how a foreman briefs their team back in town once they're back
     *  in signal, over WhatsApp, SMS, or email, with a real written record
     *  instead of "everyone seemed fine out there." That record is also the
     *  starting point for anything sold to a mine operator later — a company
     *  dashboard has to ingest something, and this is that something. */
    private fun buildSafetyReport(): String {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine(getString(R.string.report_title))
        sb.appendLine(dateFmt.format(Date()))
        sb.appendLine()

        val activeSos = SosRepository.alerts.value.values.filter { it.active }
        if (activeSos.isNotEmpty()) {
            sb.appendLine(getString(R.string.report_active_sos))
            for (a in activeSos) {
                sb.appendLine("- ${a.senderName}: ${coords(a.lat, a.lon)} (${timeAgo(requireContext(), a.ts)})")
            }
            sb.appendLine()
        }

        val activeInactive = InactivityRepository.alerts.value.values.filter { it.active }
        if (activeInactive.isNotEmpty()) {
            sb.appendLine(getString(R.string.report_inactive))
            for (a in activeInactive) {
                sb.appendLine("- ${a.senderName}: ${coords(a.lat, a.lon)} (${timeAgo(requireContext(), a.ts)})")
            }
            sb.appendLine()
        }

        sb.appendLine(getString(R.string.report_known_miners))
        val peers = PeerRepository.peers.value.values.sortedByDescending { it.ts }
        if (peers.isEmpty()) {
            sb.appendLine(getString(R.string.no_peers))
        } else {
            for (p in peers) {
                val status = getString(if (p.connected) R.string.status_in_range else R.string.status_last_known)
                sb.appendLine("- ${p.name}: ${coords(p.lat, p.lon)} · $status · ${timeAgo(requireContext(), p.ts)}")
            }
        }
        return sb.toString()
    }

    private fun shareSafetyReport() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.report_title))
            putExtra(Intent.EXTRA_TEXT, buildSafetyReport())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.export_report)))
    }

    override fun onDestroyView() {
        recorder?.cancel()
        recorder = null
        super.onDestroyView()
    }
}
