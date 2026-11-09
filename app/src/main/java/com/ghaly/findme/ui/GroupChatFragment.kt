package com.ghaly.findme.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ghaly.findme.R
import com.ghaly.findme.repo.GroupChatRepository
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

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

    private var pendingPhotoBase64: String? = null
    private var photoPreview: ImageView? = null
    private var photoPreviewRow: View? = null

    private val pickGroupChatPhotoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) handlePickedGroupChatPhoto(uri)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val groupId = requireArguments().getString(ARG_GROUP_ID) ?: return
        requireActivity().title = requireArguments().getString(ARG_GROUP_NAME)

        val list = view.findViewById<RecyclerView>(R.id.groupChatList)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        photoPreview = view.findViewById(R.id.groupChatPhotoPreview)
        photoPreviewRow = view.findViewById(R.id.groupChatPhotoPreviewRow)
        view.findViewById<Button>(R.id.attachGroupChatPhotoButton).setOnClickListener {
            pickGroupChatPhotoLauncher.launch("image/*")
        }
        view.findViewById<Button>(R.id.clearGroupChatPhotoButton).setOnClickListener {
            clearPendingPhoto()
        }

        val input = view.findViewById<EditText>(R.id.groupChatInput)
        view.findViewById<Button>(R.id.groupChatSendButton).setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            val photo = pendingPhotoBase64
            if (text.isEmpty() && photo == null) return@setOnClickListener
            (requireActivity() as MainActivity).mesh?.sendGroupMessage(groupId, text, photo)
            input.setText("")
            clearPendingPhoto()
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

    override fun onDestroyView() {
        super.onDestroyView()
        // We swapped the activity's title to the group name on the way in — put
        // it back, or every other screen keeps showing the group's name in the
        // app bar after you leave this chat.
        requireActivity().title = getString(R.string.app_name)
    }

    /** Same resize/compress approach used everywhere else photos get sent over
     *  the mesh — small on purpose, since this may hop through several relays. */
    private fun handlePickedGroupChatPhoto(uri: Uri) {
        try {
            val input = requireContext().contentResolver.openInputStream(uri) ?: return
            val original = BitmapFactory.decodeStream(input)
            input.close()
            val maxDim = 320
            val scale = maxDim.toFloat() / maxOf(original.width, original.height)
            val resized = if (scale < 1f) {
                Bitmap.createScaledBitmap(original, (original.width * scale).toInt().coerceAtLeast(1), (original.height * scale).toInt().coerceAtLeast(1), true)
            } else original
            val out = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 60, out)
            pendingPhotoBase64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            photoPreview?.setImageBitmap(resized)
            photoPreviewRow?.visibility = View.VISIBLE
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.photo_load_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearPendingPhoto() {
        pendingPhotoBase64 = null
        photoPreview?.setImageDrawable(null)
        photoPreviewRow?.visibility = View.GONE
    }
}
