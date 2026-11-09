package com.ghaly.findme.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ghaly.findme.R
import com.ghaly.findme.model.ChatMessage
import com.ghaly.findme.model.Group
import com.ghaly.findme.model.InactivityAlert
import com.ghaly.findme.model.ListingKind
import com.ghaly.findme.model.MarketListing
import com.ghaly.findme.model.Note
import com.ghaly.findme.model.PeerPosition
import com.ghaly.findme.model.Post
import com.ghaly.findme.model.PostComment
import com.ghaly.findme.model.SosAlert
import java.util.Locale
import java.util.concurrent.TimeUnit

// Kept non-private (not just file-private) so RadarMapFragment's saved-places
// list can reuse the exact same distance/bearing/time formatting as the peer list.
internal fun timeAgo(context: Context, ts: Long): String {
    val diffMs = System.currentTimeMillis() - ts
    val mins = TimeUnit.MILLISECONDS.toMinutes(diffMs)
    return when {
        mins < 1 -> context.getString(R.string.time_just_now)
        mins < 60 -> context.getString(R.string.time_min_ago, mins)
        else -> context.getString(R.string.time_h_ago, TimeUnit.MILLISECONDS.toHours(diffMs))
    }
}

internal fun coords(lat: Double, lon: Double): String =
    String.format(Locale.US, "%.4f, %.4f", lat, lon)

/** 8-point compass arrow for a bearing in degrees (0 = north). */
internal fun bearingArrow(bearingDeg: Float): String {
    val dirs = arrayOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
    val normalized = ((bearingDeg % 360) + 360) % 360
    val index = ((normalized + 22.5f) / 45f).toInt() % 8
    return dirs[index]
}

internal fun distanceLabel(meters: Float): String = when {
    meters < 1000 -> "${meters.toInt()} m"
    else -> String.format(Locale.US, "%.1f km", meters / 1000f)
}

class PeerAdapter(private val onClick: (PeerPosition) -> Unit = {}) : RecyclerView.Adapter<PeerAdapter.VH>() {
    var items: List<PeerPosition> = emptyList()
        set(value) {
            field = value.sortedByDescending { it.ts }
            notifyDataSetChanged()
        }

    /** My own last known position, used to compute direction arrows. Null if unknown. */
    var myLocation: Pair<Double, Double>? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v
        val name: android.widget.TextView = v.findViewById(R.id.peerName)
        val battery: android.widget.TextView = v.findViewById(R.id.peerBattery)
        val direction: android.widget.TextView = v.findViewById(R.id.peerDirection)
        val status: android.widget.TextView = v.findViewById(R.id.peerStatus)
        val coordsView: android.widget.TextView = v.findViewById(R.id.peerCoords)
        val age: android.widget.TextView = v.findViewById(R.id.peerAge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_peer, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        val context = holder.root.context
        holder.name.text = p.name
        holder.status.text = context.getString(
            if (p.connected) R.string.status_in_range else R.string.status_last_known
        )
        holder.status.setTextColor(
            if (p.connected) 0xFF7CCB7C.toInt() else 0xFFD8C3A5.toInt()
        )

        val battery = p.batteryPct
        if (battery != null) {
            holder.battery.visibility = View.VISIBLE
            holder.battery.text = "🔋 $battery%"
            // Flag a critically low battery distinctly from an ordinary one —
            // this is the "may go dark soon, not necessarily in trouble" signal,
            // deliberately a calmer color than the SOS/inactivity red used
            // elsewhere so it doesn't read as an emergency.
            holder.battery.setTextColor(
                if (battery <= 15) 0xFFF2A541.toInt() else 0xFFAFC9C7.toInt()
            )
        } else {
            holder.battery.visibility = View.GONE
        }

        val mine = myLocation
        if (mine != null && (p.lat != 0.0 || p.lon != 0.0)) {
            val results = FloatArray(2)
            android.location.Location.distanceBetween(mine.first, mine.second, p.lat, p.lon, results)
            holder.direction.visibility = View.VISIBLE
            holder.direction.text = bearingArrow(results[1])
            holder.coordsView.text = "${coords(p.lat, p.lon)} · ${distanceLabel(results[0])}"
        } else {
            holder.direction.visibility = View.GONE
            holder.coordsView.text = coords(p.lat, p.lon)
        }

        holder.age.text = context.getString(R.string.last_seen_fmt, timeAgo(context, p.ts))
        holder.root.setOnClickListener { onClick(p) }
    }
}

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {
    var items: List<ChatMessage> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val row: View = v.findViewById(R.id.bubbleRow)
        val sender: android.widget.TextView = v.findViewById(R.id.senderName)
        val text: android.widget.TextView = v.findViewById(R.id.messageText)
        val photo: android.widget.ImageView = v.findViewById(R.id.messagePhoto)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.sender.text = if (m.isMine) holder.row.context.getString(R.string.you_label) else m.senderName
        // A photo can be sent with no caption, so don't leave an empty bubble
        // sitting above the picture in that case.
        if (m.text.isEmpty()) {
            holder.text.visibility = View.GONE
        } else {
            holder.text.visibility = View.VISIBLE
            holder.text.text = m.text
        }
        if (m.photoBase64 != null) {
            try {
                val bytes = android.util.Base64.decode(m.photoBase64, android.util.Base64.NO_WRAP)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                holder.photo.setImageBitmap(bitmap)
                holder.photo.visibility = View.VISIBLE
            } catch (e: Exception) {
                holder.photo.visibility = View.GONE
            }
        } else {
            holder.photo.visibility = View.GONE
        }
        // Bubble side (mine = right, theirs = left) is a pure layout question,
        // so it's set with absolute Gravity on the row — NOT layoutDirection.
        // Forcing layoutDirection here used to also flip how the message text
        // itself gets laid out/aligned inside the bubble, which fought with
        // real right-to-left text (Arabic is one of this app's shipped
        // locales) and could make "their" Arabic messages render left-aligned
        // and look broken. Gravity only moves the bubble; it never touches
        // the text's own direction or alignment.
        (holder.row as android.widget.LinearLayout).gravity =
            if (m.isMine) android.view.Gravity.RIGHT else android.view.Gravity.LEFT
    }
}

class SosAdapter(private val onClick: (SosAlert) -> Unit = {}) : RecyclerView.Adapter<SosAdapter.VH>() {
    var items: List<SosAlert> = emptyList()
        set(value) {
            field = value.filter { it.active }.sortedByDescending { it.ts }
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v
        val name: android.widget.TextView = v.findViewById(R.id.sosName)
        val coordsView: android.widget.TextView = v.findViewById(R.id.sosCoords)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sos_alert, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        val context = holder.name.context
        holder.name.text = context.getString(R.string.sos_needs_help, s.senderName)
        holder.coordsView.text = "${coords(s.lat, s.lon)} · ${timeAgo(context, s.ts)}"
        holder.root.setOnClickListener { onClick(s) }
    }
}

class InactiveAdapter(private val onClick: (InactivityAlert) -> Unit = {}) : RecyclerView.Adapter<InactiveAdapter.VH>() {
    var items: List<InactivityAlert> = emptyList()
        set(value) {
            field = value.filter { it.active }.sortedByDescending { it.ts }
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v
        val name: android.widget.TextView = v.findViewById(R.id.sosName)
        val coordsView: android.widget.TextView = v.findViewById(R.id.sosCoords)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sos_alert, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = items[position]
        val context = holder.name.context
        holder.name.text = context.getString(R.string.inactive_alert, a.senderName)
        holder.coordsView.text = "${coords(a.lat, a.lon)} · ${context.getString(R.string.last_seen_fmt, timeAgo(context, a.ts))}"
        holder.root.setOnClickListener { onClick(a) }
    }
}

class PostAdapter(private val onClick: (Post) -> Unit) : RecyclerView.Adapter<PostAdapter.VH>() {
    var items: List<Post> = emptyList()
        set(value) {
            field = value.sortedByDescending { it.ts }
            notifyDataSetChanged()
        }

    /** postId -> comment count, refreshed by the fragment whenever comments change. */
    var commentCounts: Map<String, Int> = emptyMap()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v
        val author: android.widget.TextView = v.findViewById(R.id.postAuthor)
        val text: android.widget.TextView = v.findViewById(R.id.postText)
        val commentCount: android.widget.TextView = v.findViewById(R.id.postCommentCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_post, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        val context = holder.root.context
        holder.author.text = "${p.authorName} · ${timeAgo(context, p.ts)}"
        holder.text.text = p.text
        val count = commentCounts[p.id] ?: 0
        holder.commentCount.text = if (count == 0) {
            context.getString(R.string.no_replies_yet)
        } else {
            context.resources.getQuantityString(R.plurals.reply_count, count, count)
        }
        holder.root.setOnClickListener { onClick(p) }
    }
}

/** myDeviceId is used only to decide whether the "promote" action should be
 *  offered on long-press — someone can only feature their OWN listing, since
 *  there's no backend/accounts yet to gate that behind anything else. */
class MarketAdapter(private val myDeviceId: String, private val onLongClick: (MarketListing) -> Unit) : RecyclerView.Adapter<MarketAdapter.VH>() {
    var items: List<MarketListing> = emptyList()
        set(value) {
            // Promoted listings always float to the top; within each group, newest first.
            field = value.sortedWith(compareByDescending<MarketListing> { it.promoted }.thenByDescending { it.ts })
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v
        val photo: android.widget.ImageView = v.findViewById(R.id.listingPhoto)
        val promotedBadge: android.widget.TextView = v.findViewById(R.id.listingPromotedBadge)
        val title: android.widget.TextView = v.findViewById(R.id.listingTitle)
        val kindPrice: android.widget.TextView = v.findViewById(R.id.listingKindPrice)
        val details: android.widget.TextView = v.findViewById(R.id.listingDetails)
        val author: android.widget.TextView = v.findViewById(R.id.listingAuthor)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_listing, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val l = items[position]
        val context = holder.root.context
        holder.promotedBadge.visibility = if (l.promoted) View.VISIBLE else View.GONE
        holder.promotedBadge.text = context.getString(R.string.sponsored_badge)
        holder.title.text = l.title
        val kindLabel = context.resources.getStringArray(R.array.listing_kind_labels)[l.kind.ordinal]
        holder.kindPrice.text = if (l.priceText.isNotBlank()) "$kindLabel · ${l.priceText}" else kindLabel
        holder.details.text = l.details
        holder.author.text = "${l.authorName} · ${timeAgo(context, l.ts)}"
        if (l.photoBase64 != null) {
            try {
                val bytes = android.util.Base64.decode(l.photoBase64, android.util.Base64.NO_WRAP)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                holder.photo.setImageBitmap(bitmap)
                holder.photo.visibility = View.VISIBLE
            } catch (e: Exception) {
                holder.photo.visibility = View.GONE
            }
        } else {
            holder.photo.visibility = View.GONE
        }
        holder.root.setOnLongClickListener {
            if (l.authorId == myDeviceId) onLongClick(l)
            true
        }
    }
}

class GroupAdapter(
    private val onOpen: (Group) -> Unit,
    private val onShare: (Group) -> Unit,
    /** Long-press a group to rename it — needed in particular for a group
     *  joined by code, which starts out with a placeholder name built from
     *  the code itself since the code can't carry a name on its own. */
    private val onRename: (Group) -> Unit
) : RecyclerView.Adapter<GroupAdapter.VH>() {
    var items: List<Group> = emptyList()
        set(value) {
            field = value.sortedBy { it.name }
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v
        val name: android.widget.TextView = v.findViewById(R.id.groupName)
        val share: android.widget.TextView = v.findViewById(R.id.groupShareButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val g = items[position]
        holder.name.text = g.name
        holder.root.setOnClickListener { onOpen(g) }
        holder.root.setOnLongClickListener { onRename(g); true }
        holder.share.setOnClickListener { onShare(g) }
    }
}

class CommentAdapter : RecyclerView.Adapter<CommentAdapter.VH>() {
    var items: List<PostComment> = emptyList()
        set(value) {
            field = value.sortedBy { it.ts }
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val sender: android.widget.TextView = v.findViewById(R.id.senderName)
        val text: android.widget.TextView = v.findViewById(R.id.messageText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.sender.text = c.authorName
        holder.text.text = c.text
    }
}

class NoteAdapter(private val onLongClick: (Note) -> Unit = {}) : RecyclerView.Adapter<NoteAdapter.VH>() {
    var items: List<Note> = emptyList()
        set(value) {
            field = value.sortedByDescending { it.ts }
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v
        val text: android.widget.TextView = v.findViewById(R.id.noteText)
        val time: android.widget.TextView = v.findViewById(R.id.noteTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_note, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val n = items[position]
        holder.text.text = n.text
        holder.time.text = timeAgo(holder.time.context, n.ts)
        holder.root.setOnLongClickListener { onLongClick(n); true }
    }
}
