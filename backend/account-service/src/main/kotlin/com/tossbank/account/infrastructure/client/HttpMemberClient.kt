package com.tossbank.account.infrastructure.client

import org.springframework.stereotype.Component

@Component
class HttpMemberClient : MemberClient {
    // TODO: 실제 Http 요청을 통해서 멤버 서비스에서 멤버의 이름을 가져옴.
    override fun getMemberName(memberId: Long): String = "회원_$memberId"
}