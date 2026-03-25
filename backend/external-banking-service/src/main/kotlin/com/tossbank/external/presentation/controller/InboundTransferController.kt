package com.tossbank.external.presentation.controller

import com.tossbank.external.application.service.InboundTransferService
import com.tossbank.external.presentation.dto.InboundTransferRequest
import com.tossbank.external.presentation.dto.InboundTransferResponse
import com.tossbank.external.presentation.dto.InboundTransferStatusResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/inbound-transfer")
class InboundTransferController(
    private val inboundTransferService: InboundTransferService
) {

    // 타행으로부터 입금 요청 수신
    @PostMapping
    suspend fun receiveTransfer(
        @RequestBody request: InboundTransferRequest
    ): ResponseEntity<InboundTransferResponse> {
        val result = inboundTransferService.receiveTransfer(request)
        return ResponseEntity.ok(result)
    }

    // 입금 처리 결과 조회
    @GetMapping("/{externalTransactionId}")
    suspend fun getTransferResult(
        @PathVariable externalTransactionId: String
    ): ResponseEntity<InboundTransferStatusResponse> {
        val result = inboundTransferService.getTransferResult(externalTransactionId)
        return ResponseEntity.ok(result)
    }
}