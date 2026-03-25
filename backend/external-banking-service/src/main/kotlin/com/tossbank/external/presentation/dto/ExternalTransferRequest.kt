package com.tossbank.external.presentation.dto

import java.math.BigDecimal

data class ExternalTransferRequest(
    val fromBank: String,
    val toBank: String,
    val toAccountNumber: String,
    val amount: BigDecimal,
    val scenario: MockScenario = MockScenario.SUCCESS
)

enum class MockScenario {
    SUCCESS,        // 정상 처리
    CLIENT_ERROR,   // 4xx - 잘못된 계좌번호 등 클라이언트 오류
    SERVER_ERROR,   // 5xx - 외부 은행 내부 오류
    TIMEOUT         // 응답 지연
}