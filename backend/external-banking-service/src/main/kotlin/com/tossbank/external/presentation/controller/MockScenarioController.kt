package com.tossbank.external.presentation.controller

import com.tossbank.external.presentation.dto.InboundTransferRequest
import com.tossbank.external.presentation.dto.InboundTransferResponse
import kotlinx.coroutines.runBlocking
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/test")
@Profile("!prod")
class MockScenarioController(
    private val mockScenarioService: MockScenarioService,
) {

    // 4xx 시뮬레이션 — 확정 실패 (보상 트랜잭션 트리거)
    @PostMapping("/inbound-transfer/client-error")
    fun simulateClientError(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: InboundTransferRequest,
    ): ResponseEntity<Unit> {
        mockScenarioService.simulateClientError()
        return ResponseEntity.ok().build()
    }

    // 5xx 시뮬레이션 — 결과 불확실 (TRANSFER_UNKNOWN 트리거)
    @PostMapping("/inbound-transfer/server-error")
    fun simulateServerError(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: InboundTransferRequest,
    ): ResponseEntity<Unit> {
        mockScenarioService.simulateServerError()
        return ResponseEntity.ok().build()
    }

    // timeout 시뮬레이션 — 실제 입금은 완료, 응답만 지연
    // TRANSFER_UNKNOWN → 재조회 → COMPLETED 시나리오 지원
    @PostMapping("/inbound-transfer/timeout")
    fun simulateTimeout(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: InboundTransferRequest,
    ): ResponseEntity<InboundTransferResponse> = runBlocking {
        val result = mockScenarioService.simulateTimeout(idempotencyKey, request)
        ResponseEntity.ok(result)
    }
}