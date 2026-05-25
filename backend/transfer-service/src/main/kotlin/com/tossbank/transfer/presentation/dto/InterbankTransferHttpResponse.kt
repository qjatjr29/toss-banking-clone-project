package com.tossbank.transfer.presentation.dto

data class InterbankTransferHttpResponse(
    val sagaId:           Long,
    val toMemberName:     String? = null,
    val amount:           java.math.BigDecimal? = null,
    val remainingBalance: java.math.BigDecimal? = null,
    val status:           String,
) {
    companion object {
        fun from(result: com.tossbank.transfer.application.dto.InterbankTransferResult.Completed) =
            InterbankTransferHttpResponse(
                sagaId           = result.sagaId,
                toMemberName     = result.toMemberName,
                amount           = result.amount,
                remainingBalance = result.remainingBalance,
                status           = "COMPLETED",
            )

        fun inProgress(sagaId: Long) =
            InterbankTransferHttpResponse(
                sagaId = sagaId,
                status = "IN_PROGRESS",
            )
    }
}