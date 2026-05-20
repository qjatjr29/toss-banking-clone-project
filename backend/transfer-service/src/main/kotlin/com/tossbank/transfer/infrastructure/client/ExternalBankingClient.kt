package com.tossbank.transfer.infrastructure.client

import com.tossbank.transfer.domain.exception.ExternalBankClientException
import com.tossbank.transfer.domain.exception.ExternalBankServerException
import com.tossbank.transfer.domain.exception.ExternalBankTimeoutException
import com.tossbank.transfer.infrastructure.client.dto.ExternalTransferRequest
import com.tossbank.transfer.infrastructure.client.dto.ExternalTransferResponse
import com.tossbank.transfer.infrastructure.client.dto.ExternalTransferResultResponse
import io.netty.handler.timeout.ReadTimeoutException
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono

private val log = KotlinLogging.logger {}

@Component
class ExternalBankingClient(
    private val externalBankingWebClient: WebClient,
) {

    /**
     * 타행 송금 요청
     * idempotencyKey를 헤더로 전달 — 외부 은행이 중복 요청 방어
     *
     * 4xx → ExternalBankClientException (확정 실패 — 보상 트랜잭션)
     * 5xx → ExternalBankServerException (결과 불확실 — TRANSFER_UNKNOWN)
     * timeout → ExternalBankTimeoutException (결과 불확실 — TRANSFER_UNKNOWN)
     */
    suspend fun transfer(
        idempotencyKey: String,
        request: ExternalTransferRequest,
    ): ExternalTransferResponse {
        log.info { "[ExternalBankingClient] 송금 요청 — key=$idempotencyKey to=${request.toAccountNumber}" }

        return execute {
            externalBankingWebClient
                .post()
                .uri("/api/v1/inbound-transfer")
                .header("Idempotency-Key", idempotencyKey)
                .bodyValue(request)
        }
    }

    /**
     * 송금 결과 재조회
     * TRANSFER_UNKNOWN 상태에서 스케줄러가 호출
     * idempotencyKey로 조회 — externalTransactionId를 못 받은 상황에서도 조회 가능
     */
    suspend fun inquireTransferResult(idempotencyKey: String): ExternalTransferResultResponse {
        log.info { "[ExternalBankingClient] 결과 재조회 — key=$idempotencyKey" }

        return externalBankingWebClient
            .get()
            .uri("/api/v1/inbound-transfer/result/{key}", idempotencyKey)
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError) { response ->
                response.bodyToMono(String::class.java)
                    .defaultIfEmpty("4xx error")
                    .flatMap { Mono.error(ExternalBankClientException(it)) }
            }
            .onStatus(HttpStatusCode::is5xxServerError) { response ->
                response.bodyToMono(String::class.java)
                    .defaultIfEmpty("5xx error")
                    .flatMap { Mono.error(ExternalBankServerException(it)) }
            }
            .bodyToMono<ExternalTransferResultResponse>()
            .awaitSingle()
    }

    /**
     * 공통 실행 — 송금 요청에만 사용 (재조회는 예외 처리가 달라서 분리)
     * timeout, 연결 오류를 명시적 예외로 변환
     */
    private suspend fun execute(
        block: () -> WebClient.RequestHeadersSpec<*>,
    ): ExternalTransferResponse {
        return try {
            block()
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError) { response ->
                    response.bodyToMono(String::class.java)
                        .defaultIfEmpty("4xx error")
                        .flatMap { Mono.error(ExternalBankClientException(it)) }
                }
                .onStatus(HttpStatusCode::is5xxServerError) { response ->
                    response.bodyToMono(String::class.java)
                        .defaultIfEmpty("5xx error")
                        .flatMap { Mono.error(ExternalBankServerException(it)) }
                }
                .bodyToMono<ExternalTransferResponse>()
                .awaitSingle()

        } catch (e: ExternalBankClientException) {
            throw e
        } catch (e: ExternalBankServerException) {
            throw e
        } catch (e: ReadTimeoutException) {
            // timeout은 결과 불확실 → TRANSFER_UNKNOWN으로 처리
            log.error { "[ExternalBankingClient] 송금 응답 timeout — key 확인 필요" }
            throw ExternalBankTimeoutException("송금 응답 timeout")
        } catch (e: WebClientRequestException) {
            // 연결 자체 실패 — 결과 불확실
            log.error { "[ExternalBankingClient] 연결 오류: ${e.message}" }
            throw ExternalBankServerException("연결 오류: ${e.message}")
        }
    }
}