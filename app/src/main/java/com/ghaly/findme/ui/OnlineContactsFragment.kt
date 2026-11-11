package com.ghaly.findme.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ghaly.findme.R
import com.ghaly.findme.mesh.ProfileStore
import com.ghaly.findme.model.OnlineContact
import com.ghaly.findme.repo.OnlineContactsRepository
import com.ghaly.findme.update.OnlineMessenger
import com.ghaly.findme.update.RegistrationUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Online (internet, not mesh) contacts added by code — "when they are
 *  online, they can do more stuff". Reached from a top-bar icon, not a
 *  bottom tab, same as Notes/Calculator/Accounts. Tapping a contact opens a
 *  1:1 [OnlineChatFragment] thread with them. */
class OnlineContactsFragment : Fragment(R.layout.fragment_online_contacts) {

    private val adapter = OnlineContactAdapter(
        onClick = { contact -> openChat(contact) },
        onLongClick = { contact -> showRemoveConfirm(contact) }
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        OnlineContactsRepository.init(requireContext())

        val list = view.findViewById<RecyclerView>(R.id.onlineContactsList)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        val emptyText = view.findViewById<TextView>(R.id.onlineContactsEmptyText)

        val myCodeText = view.findViewById<TextView>(R.id.onlineMyCodeText)
        val code = RegistrationUploader.getContactCode(requireContext())
        myCodeText.text = if (code != null) {
            getString(R.string.online_my_code_fmt, code)
        } else {
            getString(R.string.online_my_code_pending)
        }

        view.findViewById<Button>(R.id.addOnlineContactButton).setOnClickListener {
            showAddContactDialog()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                OnlineContactsRepository.contacts.collect { contacts ->
                    adapter.items = contacts
                    emptyText.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        // Refresh online/offline dots every 20s while this screen is visible —
        // "just show who's online right now", nothing fancier than a poll.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refreshStatuses()
                    delay(20_000L)
                }
            }
        }
    }

    private suspend fun refreshStatuses() {
        val profile = ProfileStore.get(requireContext())
        val phone = profile.phone ?: return
        val phones = OnlineContactsRepository.contacts.value.map { it.phone }
        if (phones.isEmpty()) return
        val statuses = withContext(Dispatchers.IO) {
            OnlineMessenger.fetchContactsStatus(requireContext(), phone, profile.deviceId, phones)
        }
        if (statuses != null) OnlineContactsRepository.applyStatus(statuses)
    }

    private fun showAddContactDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.online_add_contact_hint)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.online_add_contact)
            .setView(input)
            .setPositiveButton(R.string.online_add_contact) { _, _ ->
                val code = input.text?.toString()?.trim().orEmpty()
                if (code.isNotEmpty()) addContactByCode(code)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addContactByCode(code: String) {
        val profile = ProfileStore.get(requireContext())
        val phone = profile.phone
        if (phone == null) {
            Toast.makeText(requireContext(), R.string.online_network_error, Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                OnlineMessenger.addContact(requireContext(), phone, profile.deviceId, code)
            }
            when (result) {
                is OnlineMessenger.AddContactResult.Success -> {
                    OnlineContactsRepository.add(result.phone, result.name)
                    Toast.makeText(requireContext(), getString(R.string.online_add_contact_success, result.name), Toast.LENGTH_SHORT).show()
                }
                is OnlineMessenger.AddContactResult.NotFound ->
                    Toast.makeText(requireContext(), R.string.online_add_contact_not_found, Toast.LENGTH_SHORT).show()
                is OnlineMessenger.AddContactResult.CannotAddSelf ->
                    Toast.makeText(requireContext(), R.string.online_add_contact_self, Toast.LENGTH_SHORT).show()
                is OnlineMessenger.AddContactResult.NetworkError ->
                    Toast.makeText(requireContext(), R.string.online_network_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openChat(contact: OnlineContact) {
        (requireActivity() as MainActivity).openOnlineChat(contact.phone, contact.name)
    }

    private fun showRemoveConfirm(contact: OnlineContact) {
        AlertDialog.Builder(requireContext())
            .setTitle(contact.name)
            .setMessage(R.string.online_remove_contact_confirm)
            .setPositiveButton(R.string.online_remove_contact) { _, _ -> OnlineContactsRepository.remove(contact.phone) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
