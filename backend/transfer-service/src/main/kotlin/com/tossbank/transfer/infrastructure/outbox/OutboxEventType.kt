package com.tossbank.transfer.infrastructure.outbox

import com.tossbank.transfer.infrastructure.kafka.Topics

enum class OutboxEventType {
    WITHDRAW_INQUIRY,    // 출금 여부 재조회
    DEPOSIT_INQUIRY,     // 입금 여부 재조회
    COMPENSATE_WITHDRAW, // 출금 취소 보장 트랜잭션
    INTERBANK_TRANSFER_INQUIRY; // 외부 송금 결과 재조회

    fun toKafkaTopic() = when (this) {
        WITHDRAW_INQUIRY    -> Topics.WITHDRAW_INQUIRY
        DEPOSIT_INQUIRY     -> Topics.DEPOSIT_INQUIRY
        COMPENSATE_WITHDRAW -> Topics.WITHDRAW_CANCEL
        INTERBANK_TRANSFER_INQUIRY  -> Topics.INTERBANK_TRANSFER_INQUIRY
    }
}
