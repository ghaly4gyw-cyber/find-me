package com.ghaly.findme.repo

import com.ghaly.findme.model.ChatMessage
import com.ghaly.findme.model.InactivityAlert
import com.ghaly.findme.model.PeerPosition
import com.ghaly.findme.model.Post
import com.ghaly.findme.model.PostComment
import com.ghaly.findme.model.SosAlert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory, process-wide stores for mesh state. Kept simple on purpose (no DB)
 * for the MVP: state only needs to survive while the app/service is alive.
 */
object PeerRepository {
    private val _peers = MutableStateFlow<Map<String, PeerPosition>>(emptyMap())
    val peers: StateFlow<Map<String, PeerPosition>> = _peers

    fun upsert(position: PeerPosition) {
        _peers.update { it + (position.deviceId to position) }
    }

    fun markDisconnected(deviceId: String) {
        _peers.update { map ->
            val existing = map[deviceId] ?: return@update map
            map + (deviceId to existing.copy(connected = false))
        }
    }
}

object ChatRepository {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    fun add(message: ChatMessage) {
        _messages.update { it + message }
    }
}

object SosRepository {
    private val _alerts = MutableStateFlow<Map<String, SosAlert>>(emptyMap())
    val alerts: StateFlow<Map<String, SosAlert>> = _alerts

    fun upsert(alert: SosAlert) {
        _alerts.update { it + (alert.senderId to alert) }
    }

    fun clear(senderId: String) {
        _alerts.update { map ->
            val existing = map[senderId] ?: return@update map
            map + (senderId to existing.copy(active = false))
        }
    }
}

object InactivityRepository {
    private val _alerts = MutableStateFlow<Map<String, InactivityAlert>>(emptyMap())
    val alerts: StateFlow<Map<String, InactivityAlert>> = _alerts

    fun upsert(alert: InactivityAlert) {
        _alerts.update { it + (alert.senderId to alert) }
    }

    fun clear(senderId: String) {
        _alerts.update { map ->
            val existing = map[senderId] ?: return@update map
            map + (senderId to existing.copy(active = false))
        }
    }
}

object GroupChatRepository {
    private val _messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<ChatMessage>>> = _messages

    private val seenIds = mutableSetOf<String>()

    fun add(groupId: String, message: ChatMessage) {
        if (!seenIds.add(message.id)) return
        _messages.update { map ->
            map + (groupId to (map[groupId].orEmpty() + message))
        }
    }

    fun forGroup(groupId: String): List<ChatMessage> = _messages.value[groupId].orEmpty()
}

object PostRepository {
    private val _posts = MutableStateFlow<List<Post>>(emptyList())
    val posts: StateFlow<List<Post>> = _posts

    private val _comments = MutableStateFlow<Map<String, List<PostComment>>>(emptyMap())
    val comments: StateFlow<Map<String, List<PostComment>>> = _comments

    private val seenPostIds = mutableSetOf<String>()
    private val seenCommentIds = mutableSetOf<String>()

    fun addPost(post: Post) {
        if (!seenPostIds.add(post.id)) return
        _posts.update { it + post }
    }

    fun addComment(comment: PostComment) {
        if (!seenCommentIds.add(comment.id)) return
        _comments.update { map ->
            val list = map[comment.postId].orEmpty() + comment
            map + (comment.postId to list)
        }
    }

    fun commentsFor(postId: String): List<PostComment> = _comments.value[postId].orEmpty()
}
