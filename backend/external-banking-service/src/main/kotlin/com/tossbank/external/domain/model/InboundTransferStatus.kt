package com.tossbank.external.domain.model

enum class InboundTransferStatus {
    SUCCESS,     // 입금 완료
    PROCESSING,  // 처리 중
    FAILED,      // 입금 실패
    NOT_FOUND,   // 해당 거래 없음
}