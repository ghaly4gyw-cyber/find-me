package com.ghaly.findme.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import java.io.ByteArrayOutputStream

class ChatFragment : Fragment(R.layout.fragment_chat) {

    private val adapter = ChatAdapter()

    /** index 0 = "Everyone" (null deviceId), rest = a specific peer to DM. */
    private var targets: List<Pair<String?, String>> = emptyList()
    private var selectedTargetId: String? = null

    private var pendingPhotoBase64: String? = null
    private var photoPreview: ImageView? = null
    private var photoPreviewRow: View? = null

    private val pickChatPhotoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) handlePickedChatPhoto(uri)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<RecyclerView>(R.id.chatList)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        list.layoutAnimation = android.view.animation.AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_fade_up)

        photoPreview = view.findViewById(R.id.chatPhotoPreview)
        photoPreviewRow = view.findViewById(R.id.chatPhotoPreviewRow)
        view.findViewById<Button>(R.id.attachChatPhotoButton).setOnClickListener {
            pickChatPhotoLauncher.launch("image/*")
        }
        view.findViewById<Button>(R.id.clearChatPhotoButton).setOnClickListener {
            clearPendingPhoto()
        }

        targets = listOf(null to getString(R.string.to_everyone))

        val spinner = view.findViewById<Spinner>(R.id.chatTargetSpinner)
        val spinnerAdapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item,
            mutableListOf(getString(R.string.to_everyone))
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
            val photo = pendingPhotoBase64
            // A photo can be sent on its own, with no caption — only block
            // sending when there's genuinely nothing to send.
            if (text.isEmpty() && photo == null) return@setOnClickListener
            val mesh = (requireActivity() as MainActivity).mesh
            if (mesh != null) {
                val mine = mesh.sendChat(text, selectedTargetId, photo)
                ChatRepository.add(mine)
                input.setText("")
                clearPendingPhoto()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PeerRepository.peers.collect { map ->
                    val newTargets = listOf<Pair<String?, String>>(null to getString(R.string.to_everyone)) +
                        map.values.sortedBy { it.name }.map { it.deviceId to it.name }
                    targets = newTargets
                    val previousSelection = selectedTargetId
                    spinnerAdapter.clear()
                    spinnerAdapter.addAll(newTargets.map { it.second })
                    spinnerAdapter.notifyDataSetChanged()
                    val restoredIndex = newTargets.indexOfFirst { it.first == previousSelection }
                    if (restoredIndex >= 0) {
                        spinner.setSelection(restoredIndex)
                    } else if (previousSelection != null) {
                        // the peer we were DMing went out of range and dropped out of
                        // the target list — fall back to "Everyone" instead of leaving
                        // the spinner showing a now-meaningless position.
                        selectedTargetId = null
                        spinner.setSelection(0)
                        refreshList()
                    }
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

    /** Same resize/compress approach as the market-listing photo picker — small
     *  on purpose, since this travels over the mesh, possibly several hops. */
    private fun handlePickedChatPhoto(uri: Uri) {
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
