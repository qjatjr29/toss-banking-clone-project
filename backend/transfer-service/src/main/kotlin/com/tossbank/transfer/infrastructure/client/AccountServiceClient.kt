package com.tossbank.transfer.infrastructure.client

import com.tossbank.transfer.infrastructure.client.dto.*
import com.tossbank.transfer.infrastructure.client.exception.AccountServiceException
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

private val log = KotlinLogging.logger {}

@Component
class AccountServiceClient(
    private val webClient: WebClient,
) {

    suspend fun withdraw(request: AccountWithdrawRequest): AccountWithdrawResponse =
        webClient.post()
            .uri("/api/v1/internal/accounts/withdraw")
            .bodyValue(request)
            .retrieve()
            .handleErrorStatus()
            .awaitBody<AccountWithdrawResponse>()

    suspend fun deposit(request: AccountDepositRequest): AccountDepositResponse =
        webClient.post()
            .uri("/api/v1/internal/accounts/deposit")
            .bodyValue(request)
            .retrieve()
            .handleErrorStatus()
            .awaitBody<AccountDepositResponse>()

    suspend fun inquireTransaction(idempotencyKey: String): TransactionInquiryResponse =
        webClient.get()
            .uri("/api/v1/internal/accounts/transactions/{key}", idempotencyKey)
            .retrieve()
            .handleErrorStatus()
            .awaitBody<TransactionInquiryResponse>()

    private fun WebClient.ResponseSpec.handleErrorStatus() =
        this
            .onStatus({ it.is4xxClientError }) { res ->
                res.bodyToMono(String::class.java).map {
                    AccountServiceException(
                        isClientError = true,
                        message       = it,
                        statusCode    = res.statusCode().value(),
                    )
                }
            }
            .onStatus({ it.is5xxServerError }) { res ->
                res.bodyToMono(String::class.java).map {
                    AccountServiceException(
                        isClientError = false,
                        message       = it,
                        statusCode    = res.statusCode().value(),
                    )
                }
            }
}