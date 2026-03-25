package com.tossbank.transfer.infrastructure.client.dto

import java.math.BigDecimal

data class ExternalTransferRequest(
    val fromBank: String,
    val toBank: String,
    val toAccountNumber: String,
    val amount: BigDecimal,
    val scenario: String = "SUCCESS"  // 로컬환경: SUCCESS 고정, 테스트: 시나리오 변경
)