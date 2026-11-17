package com.ghaly.findme.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
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
import com.ghaly.findme.update.SupplierActivatedUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A supplier's own in-app panel — "the مندوب should have in the app, his
 * own panel, with fully options like the one he was have in the web": own
 * offline code (+ the ability to change it), how many people they've
 * personally activated (by code vs by approval), and the same shared
 * pending-activations queue the admin screen shows, so a supplier never
 * has to fall back to the web's supplier-panel.php for any of it.
 *
 * Only reachable at all from a top-bar button MainActivity shows when
 * ProfileStore.isSupplier is true, but every action here is also
 * re-checked server-side (isSupplierPhone/canManageActivations in
 * schema.php), so this screen is never the only thing standing between a
 * random phone and someone else's activation or someone else's code.
 */
class SupplierPanelFragment : Fragment(R.layout.fragment_supplier_panel) {

    private val pendingAdapter = PendingActivationAdapter(
        onApprove = { request -> decide(request, approve = true) },
        onReject = { request -> decide(request, approve = false) }
    )
    private var pendingAction = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val pendingList = view.findViewById<RecyclerView>(R.id.supplierPendingList)
        pendingList.layoutManager = LinearLayoutManager(requireContext())
        pendingList.adapter = pendingAdapter
        val pendingEmptyText = view.findViewById<TextView>(R.id.supplierPendingEmptyText)

        val changeCodeToggle = view.findViewById<TextView>(R.id.supplierChangeCodeToggle)
        val changeCodeRow = view.findViewById<LinearLayout>(R.id.supplierChangeCodeRow)
        changeCodeToggle.setOnClickListener {
            changeCodeRow.visibility = if (changeCodeRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        view.findViewById<View>(R.id.supplierSaveCodeButton).setOnClickListener {
            saveNewCode(view)
        }

        // Show the last-known code immediately (cached on this device since
        // the last sync) so the screen isn't blank while the fuller fetch
        // (stats, activated list, pending queue) is still in flight.
        ProfileStore.supplierCode(requireContext())?.let {
            view.findViewById<TextView>(R.id.supplierCodeText).text = it
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (!pendingAction) {
                        refreshSupplierInfo(view)
                        refreshPendingQueue(pendingList, pendingEmptyText)
                    }
                    delay(15_000L)
                }
            }
        }
    }

    private suspend fun refreshSupplierInfo(view: View) {
        val profile = ProfileStore.get(requireContext())
        val phone = profile.phone ?: return
        val info = withContext(Dispatchers.IO) {
            ActivationMessenger.fetchSupplierInfo(requireContext(), phone, profile.deviceId)
        }
        if (info == null) {
            if (view.findViewById<TextView>(R.id.supplierCodeText).text.isNullOrEmpty()) {
                Toast.makeText(requireContext(), R.string.supplier_panel_load_failed, Toast.LENGTH_SHORT).show()
            }
            return
        }

        info.offlineCode?.let { view.findViewById<TextView>(R.id.supplierCodeText).text = it }
        view.findViewById<TextView>(R.id.supplierTotalCount).text = info.totalActivated.toString()
        view.findViewById<TextView>(R.id.supplierApprovalCount).text = info.activatedByApproval.toString()
        view.findViewById<TextView>(R.id.supplierCodeCount).text = info.activatedByCode.toString()

        renderActivatedList(view, info.activatedList)
    }

    private fun renderActivatedList(view: View, list: List<SupplierActivatedUser>) {
        val container = view.findViewById<LinearLayout>(R.id.supplierActivatedList)
        val emptyText = view.findViewById<TextView>(R.id.supplierActivatedEmptyText)
        emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        container.removeAllViews()
        val context = requireContext()
        for (u in list) {
            val row = TextView(context).apply {
                val via = if (u.via == "code") getString(R.string.supplier_activated_via_code)
                else getString(R.string.supplier_activated_via_approval)
                text = "${u.name} — ${u.phone} ($via)"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(0, 12, 0, 12)
            }
            container.addView(row)
        }
    }

    private suspend fun refreshPendingQueue(list: RecyclerView, emptyText: TextView) {
        val profile = ProfileStore.get(requireContext())
        val phone = profile.phone ?: return
        val requests = withContext(Dispatchers.IO) {
            ActivationMessenger.fetchPendingRequests(requireContext(), phone, profile.deviceId)
        } ?: return
        pendingAdapter.items = requests
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
                pendingAdapter.items = pendingAdapter.items.filter { it.id != request.id }
                Toast.makeText(
                    requireContext(),
                    getString(if (approve) R.string.activation_approved_toast else R.string.activation_rejected_toast, request.name),
                    Toast.LENGTH_SHORT
                ).show()
                // An approval here changes this supplier's own stats too —
                // refresh them right away instead of waiting on the next tick.
                view?.let { viewLifecycleOwner.lifecycleScope.launch { refreshSupplierInfo(it) } }
            } else {
                Toast.makeText(requireContext(), R.string.online_network_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveNewCode(view: View) {
        val input = view.findViewById<EditText>(R.id.supplierNewCodeInput)
        val newCode = input.text?.toString()?.trim()?.uppercase().orEmpty()
        if (!Regex("^[A-Z0-9]{4,16}$").matches(newCode)) {
            Toast.makeText(requireContext(), R.string.supplier_code_invalid_toast, Toast.LENGTH_SHORT).show()
            return
        }
        val profile = ProfileStore.get(requireContext())
        val phone = profile.phone ?: return
        view.findViewById<View>(R.id.supplierSaveCodeButton).isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ActivationMessenger.changeSupplierCode(requireContext(), phone, profile.deviceId, newCode)
            }
            view.findViewById<View>(R.id.supplierSaveCodeButton).isEnabled = true
            when (result) {
                is ActivationMessenger.ChangeCodeResult.Success -> {
                    view.findViewById<TextView>(R.id.supplierCodeText).text = result.newCode
                    ProfileStore.applyActivationStatus(
                        requireContext(),
                        activated = ProfileStore.isActivated(requireContext()),
                        isAdmin = ProfileStore.isAdmin(requireContext()),
                        isSupplier = true,
                        supplierCode = result.newCode
                    )
                    input.text?.clear()
                    view.findViewById<LinearLayout>(R.id.supplierChangeCodeRow).visibility = View.GONE
                    Toast.makeText(requireContext(), R.string.supplier_code_saved_toast, Toast.LENGTH_SHORT).show()
                }
                is ActivationMessenger.ChangeCodeResult.AlreadyInUse ->
                    Toast.makeText(requireContext(), R.string.supplier_code_taken_toast, Toast.LENGTH_SHORT).show()
                is ActivationMessenger.ChangeCodeResult.Failed ->
                    Toast.makeText(requireContext(), R.string.supplier_code_save_failed_toast, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
