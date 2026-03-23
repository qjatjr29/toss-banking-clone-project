package com.tossbank.account.infrastructure.client

import ExternalBankApiException
import com.tossbank.account.infrastructure.client.dto.ExternalTransferRequest
import com.tossbank.account.infrastructure.client.dto.ExternalTransferResponse
import io.netty.handler.timeout.ReadTimeoutException
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import reactor.core.publisher.Mono

private val log = KotlinLogging.logger {}

@Component
class HttpExternalBankClient(
    @Qualifier("externalBankWebClient") private val webClient: WebClient,
): ExternalBankClient {
    override suspend fun transfer(request: ExternalTransferRequest): ExternalTransferResponse =
        execute {
            webClient.post()
                .uri("/transfers")
                .header("Idempotency-Key", request.idempotencyKey)
                .bodyValue(request)
        }

    override suspend fun inquireTransferResult(idempotencyKey: String): ExternalTransferResponse =
        execute {
            webClient.get()
                .uri("/transfers/{idempotencyKey}", idempotencyKey)
        }

    private suspend fun execute(
        block: () -> WebClient.RequestHeadersSpec<*>,
    ): ExternalTransferResponse {
        return try {
            block()
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError) { response ->
                    response.bodyToMono(String::class.java)
                        .defaultIfEmpty("4xx error")
                        .flatMap { body ->
                            Mono.error(ExternalBankApiException(response.statusCode().value(), body))
                        }
                }
                .onStatus(HttpStatusCode::is5xxServerError) { response ->
                    response.bodyToMono(String::class.java)
                        .defaultIfEmpty("5xx error")
                        .flatMap { body ->
                            Mono.error(ExternalBankApiException(response.statusCode().value(), body))
                        }
                }
                .bodyToMono(ExternalTransferResponse::class.java)
                .awaitSingle()

        } catch (e: ExternalBankApiException) {
            throw e  // 이미 변환된 예외 — 그대로 전파

        } catch (e: ReadTimeoutException) {
            log.error { "외부 은행 API 읽기 타임아웃" }
            throw ExternalBankApiException(504, "Read timeout")

        } catch (e: WebClientRequestException) {
            // ConnectTimeoutException 포함 — 연결 자체 실패
            log.error { "외부 은행 API 연결 오류: ${e.message}" }
            throw ExternalBankApiException(503, "Connection error: ${e.message}")
        }
    }
}