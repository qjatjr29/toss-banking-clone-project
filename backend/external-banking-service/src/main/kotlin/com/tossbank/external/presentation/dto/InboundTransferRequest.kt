package com.tossbank.external.presentation.dto

import java.math.BigDecimal

data class InboundTransferRequest(
    val fromBank: String,              // 송금 은행 (토스뱅크)
    val toAccountNumber: String,       // 입금 계좌번호 (우리 은행 고객)
    val amount: BigDecimal,
    val scenario: MockScenario = MockScenario.SUCCESS
)

enum class InboundTransferStatus {
    CREDITED,    // 입금 완료
    NOT_FOUND    // 거래 없음
}

enum class MockScenario {
    SUCCESS,        // 정상 입금 처리
    CLIENT_ERROR,   // 4xx — 존재하지 않는 계좌 등
    SERVER_ERROR,   // 5xx — 외부 은행 내부 오류
    TIMEOUT         // 응답 지연
}