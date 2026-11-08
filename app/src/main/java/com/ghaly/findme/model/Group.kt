package com.ghaly.findme.model

data class Group(
    val id: String,
    val name: String,
    val keyBase64: String,
    val adminId: String,
    val createdTs: Long,
    /** The short, sayable join code (e.g. "4K2-9P7") — what actually gets shared
     *  out loud or typed in, as opposed to [id]/[keyBase64] which are derived
     *  from it and never need to be read by a human. */
    val code: String
)
