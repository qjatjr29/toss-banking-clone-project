package com.tossbank.account.presentation.dto

data class SliceResponse<T>(
    val content: List<T>,
    val hasNext: Boolean
)