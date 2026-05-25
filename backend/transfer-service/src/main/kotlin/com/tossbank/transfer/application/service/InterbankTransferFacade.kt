package com.tossbank.transfer.application.service

import com.tossbank.transfer.application.dto.InterbankTransferRequest
import com.tossbank.transfer.application.dto.InterbankTransferResult
import com.tossbank.transfer.infrastructure.client.MemberClient
import org.springframework.stereotype.Service

@Service
class InterbankTransferFacade(
    private val orchestrator: InterbankTransferSagaOrchestrator,
    private val memberClient: MemberClient,
) {
    suspend fun transfer(
        memberId: Long,
        request:  InterbankTransferHttpRequest,
    ): InterbankTransferResult {
        val fromMemberName = memberClient.getMemberName(memberId)

        return orchestrator.interbankTransfer(
            memberId = memberId,
            request  = InterbankTransferRequest(
                fromAccountId     = request.fromAccountId,
                fromAccountNumber = request.fromAccountNumber,
                toAccountNumber   = request.toAccountNumber,
                toBankCode        = request.toBankCode,
                toMemberName      = request.toMemberName,
                fromMemberName    = fromMemberName,
                amount            = request.amount,
                description       = request.description,
                idempotencyKey    = request.idempotencyKey,
            )
        )
    }
}