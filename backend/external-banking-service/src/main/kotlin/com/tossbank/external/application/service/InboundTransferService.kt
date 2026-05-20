package com.tossbank.external.application.service

import com.tossbank.external.domain.exception.ExternalBankClientException
import com.tossbank.external.domain.exception.ExternalBankErrorCode
import com.tossbank.external.domain.model.InboundTransfer
import com.tossbank.external.domain.model.InboundTransferStatus
import com.tossbank.external.infrastructure.config.MockAccountProperties
import com.tossbank.external.infrastructure.persistence.InboundTransferRepository
import com.tossbank.external.presentation.dto.AccountHolderResponse
import com.tossbank.external.presentation.dto.InboundTransferRequest
import com.tossbank.external.presentation.dto.InboundTransferResponse
import com.tossbank.external.presentation.dto.InboundTransferResultResponse
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

private val log = KotlinLogging.logger {}

@Service
class InboundTransferService(
    private val inboundTransferRepository: InboundTransferRepository,
    private val mockAccountProperties: MockAccountProperties,
) {

    // 멱등성 보장: 동일 idempotencyKey 재요청 시 기존 결과 반환
    @Transactional
    fun receiveTransfer(
        idempotencyKey: String,
        request: InboundTransferRequest,
    ): InboundTransferResponse {
        inboundTransferRepository.findByIdempotencyKey(idempotencyKey)
            ?.let { existing ->
                log.warn { "[MockBank] 중복 요청 — 기존 결과 반환: key=$idempotencyKey" }
                return InboundTransferResponse(
                    externalTransactionId = existing.externalTransactionId,
                )
            }

        // 수취 계좌 존재 여부 확인
        mockAccountProperties.findHolderName(request.toAccountNumber)
            ?: throw ExternalBankClientException(ExternalBankErrorCode.INVALID_ACCOUNT)

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

        log.info { "[MockBank] 입금 완료 — key=$idempotencyKey externalId=$externalTransactionId" }
        return InboundTransferResponse(externalTransactionId = externalTransactionId)
    }

    @Transactional(readOnly = true)
    fun getTransferResult(idempotencyKey: String): InboundTransferResultResponse {
        val transfer = inboundTransferRepository.findByIdempotencyKey(idempotencyKey)
            ?: return InboundTransferResultResponse(
                idempotencyKey = idempotencyKey,
                status         = InboundTransferStatus.NOT_FOUND.name,
            )

        return InboundTransferResultResponse(
            idempotencyKey        = idempotencyKey,
            externalTransactionId = transfer.externalTransactionId,
            status                = transfer.status.name,
        )
    }

    fun inquireAccountHolder(accountNumber: String): AccountHolderResponse {
        val holderName = mockAccountProperties.findHolderName(accountNumber)
            ?: throw ExternalBankClientException(ExternalBankErrorCode.INVALID_ACCOUNT)

        return AccountHolderResponse(
            accountNumber = accountNumber,
            holderName    = holderName,
        )
    }
}