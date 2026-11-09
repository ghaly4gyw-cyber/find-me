package com.ghaly.findme.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ghaly.findme.R
import com.ghaly.findme.repo.NotesRepository
import kotlinx.coroutines.launch

/** Plain personal notes — purely local, never touches the mesh or anyone
 *  else's phone. Reached from the Tools menu, not a bottom tab. */
class NotesFragment : Fragment(R.layout.fragment_notes) {

    private val adapter = NoteAdapter { note -> showDeleteConfirm(note.id) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        NotesRepository.init(requireContext())

        val list = view.findViewById<RecyclerView>(R.id.notesList)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        val emptyText = view.findViewById<TextView>(R.id.notesEmptyText)

        val input = view.findViewById<EditText>(R.id.noteInput)
        view.findViewById<Button>(R.id.noteAddButton).setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@setOnClickListener
            NotesRepository.add(text)
            input.setText("")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                NotesRepository.notes.collect { notes ->
                    adapter.items = notes
                    emptyText.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun showDeleteConfirm(id: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.notes_delete_title)
            .setPositiveButton(R.string.notes_delete_confirm) { _, _ -> NotesRepository.remove(id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
