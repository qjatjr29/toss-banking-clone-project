package com.tossbank.transfer.application.dto

import com.tossbank.transfer.domain.model.InterbankTransferSaga
import java.math.BigDecimal

sealed class InterbankTransferResult {

    data class Completed(
        val sagaId:           Long,
        val toMemberName:     String,
        val amount:           BigDecimal,
        val remainingBalance: BigDecimal,
    ) : InterbankTransferResult()

    data class InProgress(val sagaId: Long) : InterbankTransferResult()

    data class Failed(val message: String?) : InterbankTransferResult()

    data class Compensating(val sagaId: Long) : InterbankTransferResult()

    companion object {
        fun completed(saga: InterbankTransferSaga) = Completed(
            sagaId           = saga.id,
            toMemberName     = saga.toMemberName,
            amount           = saga.amount,
            remainingBalance = saga.remainingBalance!!,
        )
        fun inProgress(sagaId: Long)   = InProgress(sagaId)
        fun failed(message: String?)   = Failed(message)
        fun compensating(sagaId: Long) = Compensating(sagaId)
    }
}