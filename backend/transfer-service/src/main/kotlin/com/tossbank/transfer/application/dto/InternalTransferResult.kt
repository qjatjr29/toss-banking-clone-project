package com.tossbank.transfer.application.dto

import com.tossbank.transfer.domain.model.InternalTransferSaga
import java.math.BigDecimal

sealed class InternalTransferResult {
    data class Completed(
        val sagaId: Long,
        val fromAccountId: Long,
        val toMemberName: String,
        val amount: BigDecimal,
        val remainingBalance: BigDecimal,
    ) : InternalTransferResult()

    data class InProgress(val sagaId: Long) : InternalTransferResult()

    data class Failed(val sagaId: Long, val errorMessage: String?) : InternalTransferResult()

    data class Compensating(val sagaId: Long) : InternalTransferResult()

    companion object {
        fun completed(saga: InternalTransferSaga) = Completed(
            sagaId           = saga.id,
            fromAccountId    = saga.fromAccountId,
            toMemberName     = saga.toMemberName,
            amount           = saga.amount,
            remainingBalance = saga.remainingBalance!!,
        )
        fun inProgress(sagaId: Long) = InProgress(sagaId)
        fun failed(sagaId: Long, msg: String?) = Failed(sagaId, msg)
        fun compensating(sagaId: Long) = Compensating(sagaId)
    }
}