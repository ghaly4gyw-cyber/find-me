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
import com.ghaly.findme.model.PeerPosition
import com.ghaly.findme.model.Post
import com.ghaly.findme.model.PostComment
import com.ghaly.findme.model.SosAlert
import java.util.Locale
import java.util.concurrent.TimeUnit

private fun timeAgo(context: Context, ts: Long): String {
    val diffMs = System.currentTimeMillis() - ts
    val mins = TimeUnit.MILLISECONDS.toMinutes(diffMs)
    return when {
        mins < 1 -> context.getString(R.string.time_just_now)
        mins < 60 -> context.getString(R.string.time_min_ago, mins)
        else -> context.getString(R.string.time_h_ago, TimeUnit.MILLISECONDS.toHours(diffMs))
    }
}

private fun coords(lat: Double, lon: Double): String =
    String.format(Locale.US, "%.4f, %.4f", lat, lon)

/** 8-point compass arrow for a bearing in degrees (0 = north). */
private fun bearingArrow(bearingDeg: Float): String {
    val dirs = arrayOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
    val normalized = ((bearingDeg % 360) + 360) % 360
    val index = ((normalized + 22.5f) / 45f).toInt() % 8
    return dirs[index]
}

private fun distanceLabel(meters: Float): String = when {
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
        holder.text.text = m.text
        holder.row.layoutDirection =
            if (m.isMine) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    }
}

class SosAdapter : RecyclerView.Adapter<SosAdapter.VH>() {
    var items: List<SosAlert> = emptyList()
        set(value) {
            field = value.filter { it.active }.sortedByDescending { it.ts }
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
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
    }
}

class InactiveAdapter : RecyclerView.Adapter<InactiveAdapter.VH>() {
    var items: List<InactivityAlert> = emptyList()
        set(value) {
            field = value.filter { it.active }.sortedByDescending { it.ts }
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
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

class GroupAdapter(
    private val onOpen: (Group) -> Unit,
    private val onShare: (Group) -> Unit
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
