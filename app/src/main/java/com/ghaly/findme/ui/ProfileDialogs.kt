package com.ghaly.findme.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.location.Location
import android.net.Uri
import android.util.Base64
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.ghaly.findme.R
import com.ghaly.findme.model.PeerPosition
import com.ghaly.findme.repo.ProfileRepository
import java.util.concurrent.TimeUnit

/** Shows a peer's profile (photo, name, phone, description) with Call/WhatsApp
 *  actions, used from both the radar map and the peer list. */
fun Fragment.showPeerProfileDialog(peer: PeerPosition, myLocation: Pair<Double, Double>?) {
    val context = requireContext()
    val view = layoutInflater.inflate(R.layout.dialog_view_profile, null)
    val photo = view.findViewById<ImageView>(R.id.viewProfilePhoto)
    val name = view.findViewById<TextView>(R.id.viewProfileName)
    val distance = view.findViewById<TextView>(R.id.viewProfileDistance)
    val description = view.findViewById<TextView>(R.id.viewProfileDescription)
    val phoneRow = view.findViewById<View>(R.id.viewProfilePhoneRow)
    val noPhone = view.findViewById<View>(R.id.viewProfileNoPhone)
    val callButton = view.findViewById<android.widget.Button>(R.id.callButton)
    val whatsappButton = view.findViewById<android.widget.Button>(R.id.whatsappButton)

    val profile = ProfileRepository.get(peer.deviceId)

    name.text = peer.name

    val mins = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - peer.ts)
    val lastSeen = if (mins < 1) {
        context.getString(R.string.time_just_now)
    } else {
        context.getString(R.string.time_min_ago, mins)
    }
    val lastSeenFull = context.getString(R.string.last_seen_fmt, lastSeen)
    if (myLocation != null && (peer.lat != 0.0 || peer.lon != 0.0)) {
        val results = FloatArray(2)
        Location.distanceBetween(myLocation.first, myLocation.second, peer.lat, peer.lon, results)
        val meters = results[0]
        val distText = if (meters < 1000) {
            context.getString(R.string.distance_m_away, meters.toInt())
        } else {
            context.getString(R.string.distance_km_away, meters / 1000f)
        }
        distance.text = "$distText • $lastSeenFull"
    } else {
        distance.text = lastSeenFull
    }

    if (profile?.description.isNullOrBlank()) {
        description.visibility = View.GONE
    } else {
        description.visibility = View.VISIBLE
        description.text = profile?.description
    }

    if (profile?.photoBase64 != null) {
        try {
            val bytes = Base64.decode(profile.photoBase64, Base64.NO_WRAP)
            photo.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        } catch (e: Exception) {
            // keep placeholder
        }
    }

    val phone = profile?.phone
    if (phone.isNullOrBlank()) {
        phoneRow.visibility = View.GONE
        noPhone.visibility = View.VISIBLE
    } else {
        phoneRow.visibility = View.VISIBLE
        noPhone.visibility = View.GONE
        callButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
        }
        whatsappButton.setOnClickListener {
            val digits = phone.filter { it.isDigit() || it == '+' }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")))
            } catch (e: Exception) {
                // WhatsApp / a browser isn't available — nothing more we can do offline
            }
        }
    }

    AlertDialog.Builder(context)
        .setView(view)
        .setPositiveButton(android.R.string.ok, null)
        .show()
}
