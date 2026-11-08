package com.ghaly.findme.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ghaly.findme.R
import com.ghaly.findme.mesh.GroupRepository
import com.ghaly.findme.model.Group
import kotlinx.coroutines.launch

class GroupsFragment : Fragment(R.layout.fragment_groups) {

    private lateinit var adapter: GroupAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = GroupAdapter(
            onOpen = { group ->
                parentFragmentManager.beginTransaction()
                    .replace(R.id.contentContainer, GroupChatFragment.newInstance(group.id, group.name))
                    .addToBackStack(null)
                    .commit()
            },
            onShare = { group -> showShareDialog(group) }
        )

        val list = view.findViewById<RecyclerView>(R.id.groupsList)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        val emptyText = view.findViewById<View>(R.id.noGroupsText)

        view.findViewById<Button>(R.id.createGroupButton).setOnClickListener { showCreateDialog() }
        view.findViewById<Button>(R.id.joinGroupButton).setOnClickListener { showJoinDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                GroupRepository.groups.collect { groups ->
                    adapter.items = groups
                    emptyText.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        // trigger initial load from disk
        GroupRepository.loadAndGet(requireContext())
    }

    private fun showCreateDialog() {
        val input = EditText(requireContext())
        input.hint = getString(R.string.group_name_hint)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.create_group)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    val group = GroupRepository.create(requireContext(), name)
                    showShareDialog(group)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showJoinDialog() {
        val input = EditText(requireContext())
        input.hint = getString(R.string.join_code_hint)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.join_group)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val code = input.text?.toString()?.trim().orEmpty()
                if (code.isNotEmpty()) {
                    val group = GroupRepository.joinFromCode(requireContext(), code)
                    if (group == null) {
                        Toast.makeText(requireContext(), R.string.invalid_group_code, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showShareDialog(group: Group) {
        val code = GroupRepository.codeFor(group)
        AlertDialog.Builder(requireContext())
            .setTitle(group.name)
            .setMessage(getString(R.string.share_this_code) + "\n\n" + code)
            .setPositiveButton(R.string.copy_code) { _, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Find Me group code", code))
                Toast.makeText(requireContext(), R.string.code_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }
}
