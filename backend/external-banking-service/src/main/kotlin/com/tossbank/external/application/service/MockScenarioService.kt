package com.tossbank.external.application.service

import com.tossbank.external.domain.exception.ExternalBankClientException
import com.tossbank.external.domain.exception.ExternalBankErrorCode
import com.tossbank.external.domain.exception.ExternalBankServerException
import com.tossbank.external.domain.model.InboundTransfer
import com.tossbank.external.domain.model.InboundTransferStatus
import com.tossbank.external.infrastructure.persistence.InboundTransferRepository
import com.tossbank.external.presentation.dto.InboundTransferRequest
import com.tossbank.external.presentation.dto.InboundTransferResponse
import kotlinx.coroutines.delay
import mu.KotlinLogging
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

private val log = KotlinLogging.logger {}

@Service
@Profile("!prod")
class MockScenarioService(
    private val inboundTransferRepository: InboundTransferRepository,
) {

    fun simulateClientError() {
        log.warn { "[MockBank] 4xx 시뮬레이션" }
        throw ExternalBankClientException(ExternalBankErrorCode.INVALID_ACCOUNT)
    }

    fun simulateServerError() {
        log.error { "[MockBank] 5xx 시뮬레이션" }
        throw ExternalBankServerException(ExternalBankErrorCode.EXTERNAL_BANK_ERROR)
    }

    // 실제 입금은 완료 후 DB 저장 - 응답만 지연
    // 재조회 시 SUCCESS 반환 → TRANSFER_UNKNOWN → COMPLETED 시나리오
    @Transactional
    suspend fun simulateTimeout(
        idempotencyKey: String,
        request: InboundTransferRequest,
    ): InboundTransferResponse {
        val externalTransactionId = UUID.randomUUID().toString()

        inboundTransferRepository.save(
            InboundTransfer(
                idempotencyKey        = idempotencyKey,
                externalTransactionId = externalTransactionId,
                fromBankCode          = request.fromBankCode,
                fromAccountNumber     = request.fromAccountNumber,
                toAccountNumber       = request.toAccountNumber,
                amount                = request.amount,
                status                = InboundTransferStatus.SUCCESS,
            )
        )

        log.warn { "[MockBank] timeout 시뮬레이션 — 입금 완료, 응답 지연: key=$idempotencyKey" }
        delay(30_000L)

        return InboundTransferResponse(externalTransactionId = externalTransactionId)
    }
}