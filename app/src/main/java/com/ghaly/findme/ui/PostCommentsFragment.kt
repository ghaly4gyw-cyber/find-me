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
import com.ghaly.findme.repo.PostRepository
import kotlinx.coroutines.launch

class PostCommentsFragment : Fragment(R.layout.fragment_post_comments) {

    companion object {
        private const val ARG_POST_ID = "postId"
        private const val ARG_POST_TEXT = "postText"

        fun newInstance(postId: String, postText: String) = PostCommentsFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_POST_ID, postId)
                putString(ARG_POST_TEXT, postText)
            }
        }
    }

    private val adapter = CommentAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val postId = requireArguments().getString(ARG_POST_ID) ?: return
        val postText = requireArguments().getString(ARG_POST_TEXT).orEmpty()

        view.findViewById<TextView>(R.id.originalPostText).text = postText

        val list = view.findViewById<RecyclerView>(R.id.commentsList)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        val input = view.findViewById<EditText>(R.id.commentInput)
        view.findViewById<Button>(R.id.commentSendButton).setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@setOnClickListener
            (requireActivity() as MainActivity).mesh?.sendComment(postId, text)
            input.setText("")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PostRepository.comments.collect { map ->
                    adapter.items = map[postId].orEmpty()
                }
            }
        }
    }
}
