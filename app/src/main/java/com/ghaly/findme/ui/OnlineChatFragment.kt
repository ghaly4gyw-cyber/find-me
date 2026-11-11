package com.ghaly.findme.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ghaly.findme.R
import com.ghaly.findme.mesh.ProfileStore
import com.ghaly.findme.model.OnlineMessage
import com.ghaly.findme.update.OnlineMessenger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A single online (internet) 1:1 message thread with one [OnlineContact] —
 *  "it can send message for each" when both sides are online, entirely
 *  separate from the offline mesh's own ChatFragment. Polling-based: there's
 *  no push infrastructure, so new messages only arrive while this screen (or
 *  the heartbeat tick) is actually asking fetch-messages.php for them. */
class OnlineChatFragment : Fragment(R.layout.fragment_online_chat) {

    companion object {
        private const val ARG_PHONE = "phone"
        private const val ARG_NAME = "name"

        fun newInstance(phone: String, name: String): OnlineChatFragment {
            return OnlineChatFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PHONE, phone)
                    putString(ARG_NAME, name)
                }
            }
        }
    }

    private val adapter = OnlineMessageAdapter()
    private var messages: List<OnlineMessage> = emptyList()
    private var highestSeenId: Long = 0L

    private fun otherPhone() = requireArguments().getString(ARG_PHONE).orEmpty()
    private fun otherName() = requireArguments().getString(ARG_NAME).orEmpty()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.onlineChatTitle).text = otherName()

        val list = view.findViewById<RecyclerView>(R.id.onlineChatList)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        val input = view.findViewById<EditText>(R.id.onlineChatInput)
        view.findViewById<Button>(R.id.onlineChatSendButton).setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@setOnClickListener
            sendMessage(text)
            input.setText("")
        }

        // Poll every 5s while the thread is open — short enough to feel like
        // a live chat without needing any push/notification plumbing.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    pollMessages()
                    delay(5_000L)
                }
            }
        }
    }

    private fun sendMessage(text: String) {
        val profile = ProfileStore.get(requireContext())
        val phone = profile.phone ?: return
        val mine = OnlineMessage(
            id = -System.currentTimeMillis(), // local-only placeholder id until the next poll
            otherPartyPhone = otherPhone(),
            otherPartyName = otherName(),
            body = text,
            ts = System.currentTimeMillis(),
            mine = true
        )
        messages = messages + mine
        adapter.items = messages
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                OnlineMessenger.sendMessage(requireContext(), phone, profile.deviceId, otherPhone(), text)
            }
        }
    }

    private suspend fun pollMessages() {
        val profile = ProfileStore.get(requireContext())
        val phone = profile.phone ?: return
        val fetched = withContext(Dispatchers.IO) {
            OnlineMessenger.fetchMessages(requireContext(), phone, profile.deviceId, highestSeenId)
        } ?: return
        if (fetched.isEmpty()) return
        // Advance past every fetched message (even ones from other threads),
        // so the next poll's since_id doesn't keep re-fetching them.
        highestSeenId = maxOf(highestSeenId, fetched.maxOf { it.id })
        val fromThisThread = fetched.filter { it.otherPartyPhone == otherPhone() }
        if (fromThisThread.isEmpty()) return
        messages = messages + fromThisThread
        adapter.items = messages
    }
}
