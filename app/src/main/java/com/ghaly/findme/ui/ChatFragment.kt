package com.ghaly.findme.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ghaly.findme.R
import com.ghaly.findme.model.ChatMessage
import com.ghaly.findme.repo.ChatRepository
import com.ghaly.findme.repo.PeerRepository
import kotlinx.coroutines.launch

class ChatFragment : Fragment(R.layout.fragment_chat) {

    private val adapter = ChatAdapter()

    /** index 0 = "Everyone" (null deviceId), rest = a specific peer to DM. */
    private var targets: List<Pair<String?, String>> = listOf(null to "Everyone")
    private var selectedTargetId: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<RecyclerView>(R.id.chatList)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        val spinner = view.findViewById<Spinner>(R.id.chatTargetSpinner)
        val spinnerAdapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, mutableListOf("Everyone")
        )
        spinner.adapter = spinnerAdapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedTargetId = targets.getOrNull(pos)?.first
                refreshList()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val input = view.findViewById<EditText>(R.id.chatInput)
        view.findViewById<Button>(R.id.sendButton).setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@setOnClickListener
            val mesh = (requireActivity() as MainActivity).mesh
            if (mesh != null) {
                val mine = mesh.sendChat(text, selectedTargetId)
                ChatRepository.add(mine)
                input.setText("")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PeerRepository.peers.collect { map ->
                    val newTargets = listOf<Pair<String?, String>>(null to "Everyone") +
                        map.values.sortedBy { it.name }.map { it.deviceId to it.name }
                    targets = newTargets
                    val previousSelection = selectedTargetId
                    spinnerAdapter.clear()
                    spinnerAdapter.addAll(newTargets.map { it.second })
                    spinnerAdapter.notifyDataSetChanged()
                    val restoredIndex = newTargets.indexOfFirst { it.first == previousSelection }
                    if (restoredIndex >= 0) spinner.setSelection(restoredIndex)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ChatRepository.messages.collect { refreshList(it) }
            }
        }
    }

    private var lastMessages: List<ChatMessage> = emptyList()

    private fun refreshList(messages: List<ChatMessage>? = null) {
        val source = messages ?: lastMessages
        lastMessages = source
        val target = selectedTargetId
        val filtered = if (target == null) {
            source.filter { it.targetId == null }
        } else {
            source.filter {
                (it.isMine && it.targetId == target) || (!it.isMine && it.senderId == target)
            }
        }
        adapter.items = filtered
    }
}
