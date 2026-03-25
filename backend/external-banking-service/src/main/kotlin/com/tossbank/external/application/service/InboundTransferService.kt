package com.tossbank.external.application.service

import com.tossbank.external.domain.exception.ExternalBankClientException
import com.tossbank.external.domain.exception.ExternalBankErrorCode
import com.tossbank.external.domain.exception.ExternalBankServerException
import com.tossbank.external.presentation.dto.*
import kotlinx.coroutines.delay
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.util.*
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

@Service
class InboundTransferService {

    // 입금 완료된 거래 저장소 (UNKNOWN 재조회 시나리오 지원)
    private val transferStore = ConcurrentHashMap<String, InboundTransferStatus>()

    // 타행으로부터 입금 요청 수신
    suspend fun receiveTransfer(request: InboundTransferRequest): InboundTransferResponse {
        return when (request.scenario) {
            MockScenario.SUCCESS -> {
                val externalId = UUID.randomUUID().toString()
                transferStore[externalId] = InboundTransferStatus.CREDITED
                log.info { "[ExternalBank] 입금 완료 - externalId: $externalId" }
                InboundTransferResponse(
                    externalTransactionId = externalId,
                    status = InboundTransferStatus.CREDITED
                )
            }

            MockScenario.CLIENT_ERROR -> {
                log.warn { "[ExternalBank] 입금 실패 — 존재하지 않는 계좌: ${request.toAccountNumber}" }
                throw ExternalBankClientException(ExternalBankErrorCode.INVALID_ACCOUNT)
            }

            MockScenario.SERVER_ERROR -> {
                log.error { "[ExternalBank] 내부 오류 발생 — 입금 처리 실패" }
                throw ExternalBankServerException(ExternalBankErrorCode.EXTERNAL_BANK_ERROR)
            }

            MockScenario.TIMEOUT -> {
                // 실제로는 입금이 처리됐지만 응답 지연
                // → 송금 은행(토스뱅크) 입장에서 타임아웃 → UNKNOWN 상태 재현
                val externalId = UUID.randomUUID().toString()
                transferStore[externalId] = InboundTransferStatus.CREDITED
                log.warn { "[ExternalBank] 응답 지연 시나리오 - externalId: $externalId (30초 지연, 실제 입금은 완료됨)" }
                delay(30_000L)
                InboundTransferResponse(
                    externalTransactionId = externalId,
                    status = InboundTransferStatus.CREDITED
                )
            }
        }
    }

    // 송금 은행의 재조회 요청
    suspend fun getTransferResult(externalTransactionId: String): InboundTransferStatusResponse {
        val status = transferStore[externalTransactionId]
            ?: return InboundTransferStatusResponse(
                externalTransactionId = externalTransactionId,
                status = InboundTransferStatus.NOT_FOUND
            )

        return InboundTransferStatusResponse(
            externalTransactionId = externalTransactionId,
            status = status
        )
    }
}