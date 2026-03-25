package com.tossbank.transfer.infrastructure.client

import com.tossbank.transfer.domain.exception.ExternalBankClientException
import com.tossbank.transfer.domain.exception.ExternalBankServerException
import com.tossbank.transfer.infrastructure.client.dto.ExternalBankErrorResponse
import com.tossbank.transfer.infrastructure.client.dto.ExternalTransferRequest
import com.tossbank.transfer.infrastructure.client.dto.ExternalTransferResponse
import com.tossbank.transfer.infrastructure.client.dto.ExternalTransferStatusResponse
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

private val log = KotlinLogging.logger {}

@Component
class ExternalBankingClient(
    private val externalBankingWebClient: WebClient
) {

    // 이체 요청
    suspend fun transfer(request: ExternalTransferRequest): ExternalTransferResponse {
        log.info { "[ExternalBankingClient] 이체 요청 - toAccount: ${request.toAccountNumber}, amount: ${request.amount}" }

        return externalBankingWebClient
            .post()
            .uri("/api/v1/inbound-transfer")
            .bodyValue(request)
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError) { response ->
                response.bodyToMono<ExternalBankErrorResponse>()
                    .map { ExternalBankClientException(it.message) }
            }
            .onStatus(HttpStatusCode::is5xxServerError) { response ->
                response.bodyToMono<ExternalBankErrorResponse>()
                    .map { ExternalBankServerException(it.message) }
            }
            .bodyToMono<ExternalTransferResponse>()
            .awaitSingle()
    }

    // 거래 결과 조회
    suspend fun getTransferStatus(externalTransactionId: String): ExternalTransferStatusResponse {
        log.info { "[ExternalBankingClient] 거래 조회 - externalId: $externalTransactionId" }

        return externalBankingWebClient
            .get()
            .uri("/api/v1/inbound-transfer/$externalTransactionId")
            .retrieve()
            .bodyToMono<ExternalTransferStatusResponse>()
            .awaitSingle()
    }
}