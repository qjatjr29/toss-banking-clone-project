package com.tossbank.transfer.presentation.dto

import com.tossbank.transfer.application.dto.InternalTransferResult
import com.tossbank.transfer.domain.model.InternalTransferSaga
import java.math.BigDecimal

data class InternalTransferHttpRequest(
    val fromAccountId: Long,
    val toAccountId: Long,
    val toAccountNumber: String,
    val toMemberName: String,
    val amount: BigDecimal,
    val description: String? = null,
    val idempotencyKey: String,
)

data class InternalTransferHttpResponse(
    val sagaId: Long,
    val status: String,              // "COMPLETED" | "IN_PROGRESS" | "FAILED" | "COMPENSATING"
    val fromAccountId: Long? = null,
    val toMemberName: String? = null,
    val amount: BigDecimal? = null,
    val remainingBalance: BigDecimal? = null,
) {
    companion object {
        fun from(result: InternalTransferResult.Completed) = InternalTransferHttpResponse(
            sagaId           = result.sagaId,
            status           = "COMPLETED",
            fromAccountId    = result.fromAccountId,
            toMemberName     = result.toMemberName,
            amount           = result.amount,
            remainingBalance = result.remainingBalance,
        )
        fun inProgress(sagaId: Long) = InternalTransferHttpResponse(sagaId = sagaId, status = "IN_PROGRESS")
    }
}

data class InternalTransferStatusResponse(
    val sagaId: Long,
    val status: String,
    val amount: BigDecimal,
    val remainingBalance: BigDecimal?,
) {
    companion object {
        fun from(saga: InternalTransferSaga) = InternalTransferStatusResponse(
            sagaId           = saga.id,
            status           = saga.status.name,
            amount           = saga.amount,
            remainingBalance = saga.remainingBalance,
        )
    }
}