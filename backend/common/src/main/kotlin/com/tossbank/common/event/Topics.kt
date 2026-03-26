package com.tossbank.common.event

object Topics {
    // 당행 이체
    const val INTERNAL_TRANSFER         = "internal-transfer"
    const val INTERNAL_WITHDRAW_CANCEL  = "internal-withdraw-cancel"

    // 타행 이체
    const val INTERBANK_TRANSFER        = "interbank-transfer"
    const val TRANSFER_RESULT           = "transfer-result"
}