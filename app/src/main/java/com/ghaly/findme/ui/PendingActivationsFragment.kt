package com.ghaly.findme.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ghaly.findme.R
import com.ghaly.findme.mesh.ProfileStore
import com.ghaly.findme.update.ActivationMessenger
import com.ghaly.findme.update.PendingActivation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Admin-only (Osama/Ghaly) — "he will be has a list there of the requests.
 *  He approve it, accept it, or the otherwise". Only reachable at all from a
 *  top-bar button MainActivity only shows when ProfileStore.isAdmin is true,
 *  but every action here is also re-checked server-side (isAdminPhone), so
 *  this screen is never the only thing standing between a random phone and
 *  someone else's activation. */
class PendingActivationsFragment : Fragment(R.layout.fragment_pending_activations) {

    private val adapter = PendingActivationAdapter(
        onApprove = { request -> decide(request, approve = true) },
        onReject = { request -> decide(request, approve = false) }
    )
    private var pendingAction = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<RecyclerView>(R.id.pendingActivationsList)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        val emptyText = view.findViewById<TextView>(R.id.pendingActivationsEmptyText)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (!pendingAction) refresh(emptyText)
                    delay(15_000L)
                }
            }
        }
    }

    private suspend fun refresh(emptyText: TextView) {
        val profile = ProfileStore.get(requireContext())
        val phone = profile.phone ?: return
        val requests = withContext(Dispatchers.IO) {
            ActivationMessenger.fetchPendingRequests(requireContext(), phone, profile.deviceId)
        } ?: return
        adapter.items = requests
        emptyText.visibility = if (requests.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun decide(request: PendingActivation, approve: Boolean) {
        val profile = ProfileStore.get(requireContext())
        val phone = profile.phone ?: return
        pendingAction = true
        viewLifecycleOwner.lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                ActivationMessenger.decideRequest(requireContext(), phone, profile.deviceId, request.id, approve)
            }
            pendingAction = false
            if (success) {
                adapter.items = adapter.items.filter { it.id != request.id }
                Toast.makeText(
                    requireContext(),
                    getString(if (approve) R.string.activation_approved_toast else R.string.activation_rejected_toast, request.name),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(requireContext(), R.string.online_network_error, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
