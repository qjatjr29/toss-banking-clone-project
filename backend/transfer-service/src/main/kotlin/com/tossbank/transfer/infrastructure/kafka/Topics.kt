package com.tossbank.transfer.infrastructure.kafka

object Topics {
    const val WITHDRAW_INQUIRY           = "transfer.withdraw.inquiry"
    const val DEPOSIT_INQUIRY            = "transfer.deposit.inquiry"
    const val WITHDRAW_CANCEL            = "account.withdraw.cancel"
    const val INTERBANK_TRANSFER_INQUIRY = "transfer.interbank.inquiry"
}
