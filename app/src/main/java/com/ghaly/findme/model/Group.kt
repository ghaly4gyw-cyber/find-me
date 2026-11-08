package com.ghaly.findme.model

data class Group(
    val id: String,
    val name: String,
    val keyBase64: String,
    val adminId: String,
    val createdTs: Long
)
