package com.tossbank.account.infrastructure.client

interface MemberClient {
    fun getMemberName(memberId: Long): String
}