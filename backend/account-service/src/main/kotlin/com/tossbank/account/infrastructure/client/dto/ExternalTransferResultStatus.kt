package com.tossbank.account.infrastructure.client.dto

enum class ExternalTransferResultStatus {
    SUCCESS,     // 입금 완료
    FAILED,      // 입금 실패 (4xx)
    PROCESSING,  // 처리 중
}
