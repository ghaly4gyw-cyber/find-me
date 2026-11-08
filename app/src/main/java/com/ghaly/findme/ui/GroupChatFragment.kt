package com.ghaly.findme.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ghaly.findme.R
import com.ghaly.findme.repo.GroupChatRepository
import kotlinx.coroutines.launch

class GroupChatFragment : Fragment(R.layout.fragment_group_chat) {

    companion object {
        private const val ARG_GROUP_ID = "groupId"
        private const val ARG_GROUP_NAME = "groupName"

        fun newInstance(groupId: String, groupName: String) = GroupChatFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_GROUP_ID, groupId)
                putString(ARG_GROUP_NAME, groupName)
            }
        }
    }

    private val adapter = ChatAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val groupId = requireArguments().getString(ARG_GROUP_ID) ?: return
        requireActivity().title = requireArguments().getString(ARG_GROUP_NAME)

        val list = view.findViewById<RecyclerView>(R.id.groupChatList)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        val input = view.findViewById<EditText>(R.id.groupChatInput)
        view.findViewById<Button>(R.id.groupChatSendButton).setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@setOnClickListener
            (requireActivity() as MainActivity).mesh?.sendGroupMessage(groupId, text)
            input.setText("")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                GroupChatRepository.messages.collect { map ->
                    val messages = map[groupId].orEmpty()
                    adapter.items = messages
                    if (messages.isNotEmpty()) list.scrollToPosition(messages.size - 1)
                }
            }
        }
    }
}
