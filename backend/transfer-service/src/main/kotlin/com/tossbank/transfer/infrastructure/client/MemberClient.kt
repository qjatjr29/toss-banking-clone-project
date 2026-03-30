package com.tossbank.transfer.infrastructure.client

interface MemberClient {
    fun getMemberName(memberId: Long): String
}