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
import com.ghaly.findme.repo.PostRepository
import kotlinx.coroutines.launch

class PostsFragment : Fragment(R.layout.fragment_posts) {

    private lateinit var adapter: PostAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Harmless no-op if the mesh service already initialized this — same
        // belt-and-suspenders pattern as SavedPlaceRepository in the map tab.
        PostRepository.init(requireContext())

        adapter = PostAdapter { post ->
            parentFragmentManager.beginTransaction()
                .replace(R.id.contentContainer, PostCommentsFragment.newInstance(post.id, post.text))
                .addToBackStack(null)
                .commit()
        }

        val list = view.findViewById<RecyclerView>(R.id.postsList)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        list.layoutAnimation = android.view.animation.AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_fade_up)

        val emptyText = view.findViewById<View>(R.id.noPostsText)
        val input = view.findViewById<EditText>(R.id.postInput)
        view.findViewById<Button>(R.id.postButton).setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@setOnClickListener
            (requireActivity() as MainActivity).mesh?.sendPost(text)
            input.setText("")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PostRepository.posts.collect { posts ->
                    adapter.items = posts
                    emptyText.visibility = if (posts.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PostRepository.comments.collect { map ->
                    adapter.commentCounts = map.mapValues { it.value.size }
                }
            }
        }
    }
}
