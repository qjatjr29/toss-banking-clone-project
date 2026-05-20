package com.tossbank.external.presentation.controller

import com.tossbank.external.application.service.InboundTransferService
import com.tossbank.external.presentation.dto.AccountHolderResponse
import com.tossbank.external.presentation.dto.InboundTransferRequest
import com.tossbank.external.presentation.dto.InboundTransferResponse
import com.tossbank.external.presentation.dto.InboundTransferResultResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
class InboundTransferController(
    private val inboundTransferService: InboundTransferService,
) {

    @PostMapping("/inbound-transfer")
    fun receiveTransfer(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: InboundTransferRequest,
    ): ResponseEntity<InboundTransferResponse> {
        val result = inboundTransferService.receiveTransfer(idempotencyKey, request)
        return ResponseEntity.ok(result)
    }

    @GetMapping("/inbound-transfer/result/{idempotencyKey}")
    fun getTransferResult(
        @PathVariable idempotencyKey: String,
    ): ResponseEntity<InboundTransferResultResponse> {
        val result = inboundTransferService.getTransferResult(idempotencyKey)
        return ResponseEntity.ok(result)
    }

    @GetMapping("/accounts/holder")
    fun inquireAccountHolder(
        @RequestParam accountNumber: String,
    ): ResponseEntity<AccountHolderResponse> {
        val result = inboundTransferService.inquireAccountHolder(accountNumber)
        return ResponseEntity.ok(result)
    }
}