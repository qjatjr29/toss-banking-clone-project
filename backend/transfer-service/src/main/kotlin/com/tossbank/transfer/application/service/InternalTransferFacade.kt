package com.tossbank.transfer.application.service

import com.tossbank.transfer.application.dto.InternalTransferRequest
import com.tossbank.transfer.application.dto.InternalTransferResult
import com.tossbank.transfer.infrastructure.client.MemberClient
import com.tossbank.transfer.presentation.dto.InternalTransferHttpRequest
import org.springframework.stereotype.Service

@Service
class InternalTransferFacade(
    private val orchestrator: InternalTransferSagaOrchestrator,
    private val memberClient: MemberClient,
) {
    suspend fun transfer(
        memberId: Long,
        request: InternalTransferHttpRequest,
    ): InternalTransferResult {
        val fromMemberName = memberClient.getMemberName(memberId)

        return orchestrator.internalTransfer(
            memberId = memberId,
            request  = InternalTransferRequest(
                fromAccountId   = request.fromAccountId,
                toAccountId     = request.toAccountId,
                toAccountNumber = request.toAccountNumber,
                toMemberName    = request.toMemberName,
                fromMemberName  = fromMemberName,
                amount          = request.amount,
                description     = request.description,
                idempotencyKey  = request.idempotencyKey,
            )
        )
    }
}