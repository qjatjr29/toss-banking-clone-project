package com.tossbank.transfer.infrastructure.client.exception

class AccountServiceException(
    val isClientError: Boolean,
    message: String?,
    val statusCode: Int = 0,
) : RuntimeException(message) {
    val isServerError: Boolean get() = !isClientError
}