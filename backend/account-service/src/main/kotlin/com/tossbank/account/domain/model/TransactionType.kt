package com.tossbank.account.domain.model

enum class TransactionType {
    DEPOSIT,                   // 입금
    WITHDRAWAL,                // 출금
    TRANSFER,                  // 당행 이체 (출금)
    TRANSFER_IN,               // 당행 이체 (입금)
    TRANSFER_CANCEL,
    INTERBANK_WITHDRAW,        // 타행 이체 출금
    INTERBANK_WITHDRAW_CANCEL, // 타행 이체 출금 취소
}