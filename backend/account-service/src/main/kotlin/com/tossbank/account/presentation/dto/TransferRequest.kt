package com.tossbank.account.presentation.dto

import java.math.BigDecimal

data class TransferRequest(
    // 출금 정보
    val fromAccountId: Long,

    // 수취인 정보 — 계좌실명조회에서 받은 그대로
    val toAccountNumber: String,
    val toBankCode: String,
    val toMemberName: String,

    // 이체 정보
    val amount: BigDecimal,
    val description: String? = null,

    // 중복 요청 방지
    val idempotencyKey: String,
)
