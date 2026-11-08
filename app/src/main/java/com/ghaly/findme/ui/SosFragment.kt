package com.ghaly.findme.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ghaly.findme.R
import com.ghaly.findme.mesh.PhysicalSignal
import com.ghaly.findme.mesh.SmsBridge
import com.ghaly.findme.repo.InactivityRepository
import com.ghaly.findme.repo.SosRepository
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.launch

class SosFragment : Fragment(R.layout.fragment_sos) {

    private val adapter = SosAdapter()
    private val inactiveAdapter = InactiveAdapter()
    private var sosActive = false
    private var pulseAnimator: ValueAnimator? = null
    private var physicalSignal: PhysicalSignal? = null

    /** Whether to actually fire a send once SEND_SMS is granted — true only when
     *  the user tapped "Send status now" and we had to ask for permission first;
     *  false when permission was requested just because they set a contact
     *  number, where sending immediately would be a surprise. */
    private var sendAfterPermission = false

    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                if (sendAfterPermission) attemptSmsSend()
            } else {
                Toast.makeText(requireContext(), R.string.sms_bridge_permission_needed, Toast.LENGTH_SHORT).show()
            }
            sendAfterPermission = false
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<RecyclerView>(R.id.sosList)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        val inactiveList = view.findViewById<RecyclerView>(R.id.inactiveList)
        inactiveList.layoutManager = LinearLayoutManager(requireContext())
        inactiveList.adapter = inactiveAdapter

        val hint = view.findViewById<TextView>(R.id.sosHint)
        val button = view.findViewById<Button>(R.id.sosButton)

        button.setOnClickListener {
            val mesh = (requireActivity() as MainActivity).mesh
            if (mesh == null) {
                Toast.makeText(requireContext(), R.string.mesh_not_ready, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!sosActive) {
                // Only flip the button into its "active/pulsing" state if an SOS
                // actually went out. It used to always flip, even when sendSos()
                // bailed out for missing location permission — which left the
                // button claiming "Cancel SOS" while nothing had been sent at all.
                if (sendSos(mesh)) {
                    sosActive = true
                    button.setText(R.string.cancel_sos)
                    startPulse(button)
                    // Best-effort: if this phone happens to have real signal right now,
                    // get the status out immediately rather than waiting for the next
                    // watchdog tick — SOS is exactly the case worth bypassing the throttle for.
                    SmsBridge.sendNow(requireContext(), bypassThrottle = true)
                }
            } else {
                mesh.clearSos()
                sosActive = false
                button.setText(R.string.sos_button)
                stopPulse(button)
            }
        }

        physicalSignal = PhysicalSignal(requireContext())
        val physicalSignalButton = view.findViewById<Button>(R.id.physicalSignalButton)
        physicalSignalButton.setOnClickListener {
            val signal = physicalSignal ?: return@setOnClickListener
            if (signal.running) {
                signal.stop()
                physicalSignalButton.setText(R.string.physical_signal_start)
            } else {
                signal.start()
                physicalSignalButton.setText(R.string.physical_signal_stop)
            }
        }

        val smsStatus = view.findViewById<TextView>(R.id.smsBridgeStatus)
        refreshSmsStatus(smsStatus)
        view.findViewById<Button>(R.id.smsBridgeSetContact).setOnClickListener {
            showSetContactDialog(smsStatus)
        }
        view.findViewById<Button>(R.id.smsBridgeSendNow).setOnClickListener {
            if (SmsBridge.contact(requireContext()) == null) {
                Toast.makeText(requireContext(), R.string.sms_bridge_no_contact_set, Toast.LENGTH_SHORT).show()
            } else if (!SmsBridge.hasPermission(requireContext())) {
                sendAfterPermission = true
                smsPermissionLauncher.launch(android.Manifest.permission.SEND_SMS)
            } else {
                attemptSmsSend()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SosRepository.alerts.collect { map ->
                    val active = map.values.filter { it.active }
                    adapter.items = active
                    hint.text = if (active.isEmpty()) {
                        getString(R.string.no_peers)
                    } else {
                        getString(R.string.active_sos_count, active.size)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                InactivityRepository.alerts.collect { map ->
                    inactiveAdapter.items = map.values.toList()
                }
            }
        }
    }

    /** Pulses the SOS button gently (alpha breathing) while an alert is active. */
    private fun startPulse(button: Button) {
        stopPulse(button)
        val animator = ValueAnimator.ofFloat(1f, 0.55f).apply {
            duration = 650
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { button.alpha = it.animatedValue as Float }
        }
        animator.start()
        pulseAnimator = animator
    }

    private fun stopPulse(button: Button) {
        pulseAnimator?.cancel()
        pulseAnimator = null
        button.alpha = 1f
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pulseAnimator?.cancel()
        pulseAnimator = null
        physicalSignal?.stop()
        physicalSignal = null
    }

    private fun refreshSmsStatus(statusView: TextView) {
        val contact = SmsBridge.contact(requireContext())
        statusView.text = if (contact != null) {
            getString(R.string.sms_bridge_contact_set, contact)
        } else {
            getString(R.string.sms_bridge_no_contact)
        }
    }

    private fun showSetContactDialog(statusView: TextView) {
        val input = EditText(requireContext())
        input.inputType = android.text.InputType.TYPE_CLASS_PHONE
        input.hint = getString(R.string.sms_bridge_dialog_hint)
        input.setText(SmsBridge.contact(requireContext()).orEmpty())

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.sms_bridge_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val phone = input.text?.toString()?.trim().orEmpty()
                SmsBridge.setContact(requireContext(), phone.ifEmpty { null })
                refreshSmsStatus(statusView)
                if (phone.isNotEmpty() && !SmsBridge.hasPermission(requireContext())) {
                    smsPermissionLauncher.launch(android.Manifest.permission.SEND_SMS)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun attemptSmsSend() {
        val sent = SmsBridge.sendNow(requireContext(), bypassThrottle = true)
        val messageRes = if (sent) R.string.sms_bridge_sent else R.string.sms_bridge_failed
        Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show()
    }

    /** Returns true once an SOS send has actually been kicked off (permission
     *  was there, so the mesh broadcast will go out — synchronously or in the
     *  location callback below); false if nothing was sent at all. */
    @SuppressLint("MissingPermission")
    private fun sendSos(mesh: com.ghaly.findme.mesh.MeshManager): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(
            requireContext(), android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Toast.makeText(requireContext(), getString(R.string.permissions_needed), Toast.LENGTH_LONG).show()
            return false
        }

        LocationServices.getFusedLocationProviderClient(requireContext())
            .lastLocation
            .addOnSuccessListener { loc ->
                val lat = loc?.latitude ?: 0.0
                val lon = loc?.longitude ?: 0.0
                mesh.sendSos(lat, lon)
                Toast.makeText(requireContext(), R.string.sos_sent, Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                mesh.sendSos(0.0, 0.0)
            }
        return true
    }
}
