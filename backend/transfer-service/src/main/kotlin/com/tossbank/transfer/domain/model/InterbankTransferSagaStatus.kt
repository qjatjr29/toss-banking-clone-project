package com.tossbank.transfer.domain.model

enum class InterbankTransferSagaStatus {
    PENDING,               // 초기 상태
    WITHDRAW_COMPLETED,    // 출금 완료 — 외부 송금 시도 전
    TRANSFER_UNKNOWN,      // 외부 송금 결과 불확실 (5xx/timeout): 보상 금지
    COMPENSATING,          // 보상 진행 중 (출금 취소 Kafka 발행)
    COMPENSATED,           // 보상 완료
    COMPLETED,             // 이체 성공
    FAILED,                // 이체 실패 (출금 4xx)
    MANUAL_REQUIRED,       // 자동 복구 한계 초과: 수동 처리 필요
}