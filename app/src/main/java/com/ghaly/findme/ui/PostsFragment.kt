package com.ghaly.findme.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ghaly.findme.R
import com.ghaly.findme.mesh.DeviceIdentity
import com.ghaly.findme.model.ListingKind
import com.ghaly.findme.model.MarketListing
import com.ghaly.findme.repo.MarketRepository
import com.ghaly.findme.repo.PostRepository
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class PostsFragment : Fragment(R.layout.fragment_posts) {

    private lateinit var adapter: PostAdapter
    private lateinit var marketAdapter: MarketAdapter
    private var showingMarket = false
    private var pendingListingPhotoBase64: String? = null
    private var listingPhotoPreview: ImageView? = null

    private val pickListingPhotoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) handlePickedListingPhoto(uri)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Harmless no-op if the mesh service already initialized this — same
        // belt-and-suspenders pattern as SavedPlaceRepository in the map tab.
        PostRepository.init(requireContext())
        MarketRepository.init(requireContext())

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
                    if (!showingMarket) emptyText.visibility = if (posts.isEmpty()) View.VISIBLE else View.GONE
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

        setUpMarket(view)
        setUpFeedToggle(view)
    }

    private fun setUpFeedToggle(view: View) {
        val tabCommunity = view.findViewById<Button>(R.id.tabFeedCommunity)
        val tabMarket = view.findViewById<Button>(R.id.tabFeedMarket)
        val communityComposer = view.findViewById<View>(R.id.communityComposer)
        val marketComposer = view.findViewById<View>(R.id.marketComposer)
        val postsList = view.findViewById<View>(R.id.postsList)
        val marketList = view.findViewById<View>(R.id.marketList)
        val noPostsText = view.findViewById<View>(R.id.noPostsText)
        val noListingsText = view.findViewById<View>(R.id.noListingsText)

        fun render() {
            communityComposer.visibility = if (showingMarket) View.GONE else View.VISIBLE
            marketComposer.visibility = if (showingMarket) View.VISIBLE else View.GONE
            postsList.visibility = if (showingMarket) View.GONE else View.VISIBLE
            marketList.visibility = if (showingMarket) View.VISIBLE else View.GONE
            noPostsText.visibility = if (!showingMarket && adapter.itemCount == 0) View.VISIBLE else View.GONE
            noListingsText.visibility = if (showingMarket && marketAdapter.itemCount == 0) View.VISIBLE else View.GONE
            tabCommunity.setTextColor(resources.getColor(if (showingMarket) R.color.sand_300 else R.color.white, null))
            tabMarket.setTextColor(resources.getColor(if (showingMarket) R.color.white else R.color.sand_300, null))
        }
        tabCommunity.setOnClickListener { showingMarket = false; render() }
        tabMarket.setOnClickListener { showingMarket = true; render() }
        render()
    }

    private fun setUpMarket(view: View) {
        val myId = DeviceIdentity.id(requireContext())
        marketAdapter = MarketAdapter(myId) { listing -> showListingActionMenu(listing) }

        val marketList = view.findViewById<RecyclerView>(R.id.marketList)
        marketList.layoutManager = LinearLayoutManager(requireContext())
        marketList.adapter = marketAdapter

        val noListingsText = view.findViewById<View>(R.id.noListingsText)

        val kindSpinner = view.findViewById<Spinner>(R.id.listingKindSpinner)
        kindSpinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item,
            resources.getStringArray(R.array.listing_kind_labels)
        )

        val titleInput = view.findViewById<EditText>(R.id.listingTitleInput)
        val priceInput = view.findViewById<EditText>(R.id.listingPriceInput)
        val detailsInput = view.findViewById<EditText>(R.id.listingDetailsInput)

        listingPhotoPreview = view.findViewById(R.id.listingPhotoPreview)
        view.findViewById<Button>(R.id.addListingPhotoButton).setOnClickListener {
            pickListingPhotoLauncher.launch("image/*")
        }

        view.findViewById<Button>(R.id.postListingButton).setOnClickListener {
            val title = titleInput.text?.toString()?.trim().orEmpty()
            if (title.isEmpty()) {
                Toast.makeText(requireContext(), R.string.listing_title_hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val kind = ListingKind.values()[kindSpinner.selectedItemPosition]
            val price = priceInput.text?.toString()?.trim().orEmpty()
            val details = detailsInput.text?.toString()?.trim().orEmpty()
            (requireActivity() as MainActivity).mesh?.sendListing(kind, title, details, price, photoBase64 = pendingListingPhotoBase64)
            titleInput.setText("")
            priceInput.setText("")
            detailsInput.setText("")
            pendingListingPhotoBase64 = null
            listingPhotoPreview?.setImageDrawable(null)
            listingPhotoPreview?.visibility = View.GONE
            Toast.makeText(requireContext(), R.string.listing_posted, Toast.LENGTH_SHORT).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                MarketRepository.listings.collect { listings ->
                    marketAdapter.items = listings
                    if (showingMarket) noListingsText.visibility = if (listings.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    /** Same idea as the profile-photo picker on the main screen, but a bit
     *  bigger (people need to actually recognize the item for sale) and not
     *  force-cropped to a square. Still deliberately small — this travels
     *  over the Bluetooth/Wi-Fi mesh, possibly several relay hops, so every
     *  extra KB is a real cost, not just a nicety. */
    private fun handlePickedListingPhoto(uri: Uri) {
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
            val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            pendingListingPhotoBase64 = base64
            listingPhotoPreview?.let {
                it.setImageBitmap(resized)
                it.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.photo_load_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showListingActionMenu(listing: MarketListing) {
        val options = mutableListOf(
            getString(if (listing.promoted) R.string.listing_action_unpromote else R.string.listing_action_promote),
            getString(R.string.listing_action_delete)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(listing.title)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> (requireActivity() as MainActivity).mesh?.setListingPromoted(listing, !listing.promoted)
                    1 -> MarketRepository.remove(listing.id)
                }
            }
            .show()
    }
}
