package com.tossbank.common.exception

open class CustomException(
    val errorCode: ErrorCode
) : RuntimeException(errorCode.message)