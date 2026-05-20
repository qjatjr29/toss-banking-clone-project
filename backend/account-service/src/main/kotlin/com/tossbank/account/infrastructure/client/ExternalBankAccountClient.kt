package com.tossbank.account.infrastructure.client

import AccountNotFoundException
import com.tossbank.account.infrastructure.client.dto.ExternalAccountHolderResponse
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono

private val log = KotlinLogging.logger {}

@Component
class ExternalBankAccountClient(
    @Qualifier("externalBankWebClient") private val webClient: WebClient,
) {
    suspend fun inquireAccountHolder(accountNumber: String): ExternalAccountHolderResponse {
        log.info { "[ExternalBankAccountClient] 타행 계좌 실명 조회 — accountNumber=$accountNumber" }

        return webClient.get()
            .uri { uriBuilder ->
                uriBuilder
                    .path("/api/v1/accounts/holder")
                    .queryParam("accountNumber", accountNumber)
                    .build()
            }
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError) { response ->
                response.bodyToMono(String::class.java)
                    .flatMap { Mono.error(AccountNotFoundException()) }
            }
            .bodyToMono<ExternalAccountHolderResponse>()
            .awaitSingle()
    }
}