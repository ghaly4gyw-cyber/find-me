package com.ghaly.findme.ui

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.ghaly.findme.repo.InactivityRepository
import com.ghaly.findme.repo.SosRepository
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.launch

class SosFragment : Fragment(R.layout.fragment_sos) {

    private val adapter = SosAdapter()
    private val inactiveAdapter = InactiveAdapter()
    private var sosActive = false

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
                Toast.makeText(requireContext(), "Mesh not ready yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!sosActive) {
                sendSos(mesh)
                sosActive = true
                button.text = "CANCEL SOS"
            } else {
                mesh.clearSos()
                sosActive = false
                button.setText(R.string.sos_button)
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
                        "${active.size} active SOS in range"
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

    @SuppressLint("MissingPermission")
    private fun sendSos(mesh: com.ghaly.findme.mesh.MeshManager) {
        val hasPermission = ContextCompat.checkSelfPermission(
            requireContext(), android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Toast.makeText(requireContext(), getString(R.string.permissions_needed), Toast.LENGTH_LONG).show()
            return
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
    }
}
