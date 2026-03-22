package com.tossbank.account.domain.model

enum class InterbankTransferStatus {
    PENDING,               // 이체 시작 - 초기 상태
    WITHDRAW_COMPLETED,    // 출금 커밋 완료
    COMPLETED,             // 타행 입금 성공 - 최종 성공
    FAILED,                // 명확한 실패 (4xx)
    COMPENSATED,           // 보상 트랜잭션 완료 - 출금 취소 완료
    COMPENSATION_PENDING,  // 실패 — 스케줄러 재시도 대기 (DLQ)
    UNKNOWN,               // 결과 불명 (5xx/Timeout) - 재조회 필요, 보상 금지
    MANUAL_REQUIRED,       // 자동 복구 한계 초과 - 수동 처리 필요
}