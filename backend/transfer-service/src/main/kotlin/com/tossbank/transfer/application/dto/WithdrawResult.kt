package com.tossbank.transfer.application.dto

import java.math.BigDecimal

sealed class WithdrawResult {
    data class Success(val remainingBalance: BigDecimal) : WithdrawResult()
    data class Failed(val message: String?) : WithdrawResult()
    data object Unknown : WithdrawResult()

    fun toTransferResult(sagaId: Long): InternalTransferResult = when (this) {
        is Success -> throw IllegalStateException("Success는 toTransferResult 호출 불가")
        is Failed  -> InternalTransferResult.failed(sagaId, message)
        is Unknown -> InternalTransferResult.inProgress(sagaId)
    }
}