package com.tossbank.transfer.presentation.controller

import com.tossbank.common.response.ApiResponse
import com.tossbank.transfer.application.dto.InterbankTransferResult
import com.tossbank.transfer.application.service.InterbankTransferFacade
import com.tossbank.transfer.domain.exception.TransferFailedException
import com.tossbank.transfer.presentation.dto.InterbankTransferHttpRequest
import com.tossbank.transfer.presentation.dto.InterbankTransferHttpResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/transfers")
class InterbankTransferController(
    private val facade: InterbankTransferFacade,
) {

    @PostMapping("/interbank")
    suspend fun transferInterbank(
        @RequestHeader("X-User-Id") memberId: Long,
        @RequestBody request: InterbankTransferHttpRequest,
    ): ResponseEntity<ApiResponse<InterbankTransferHttpResponse>> {
        return when (val result = facade.transfer(memberId, request)) {
            is InterbankTransferResult.Completed ->
                ResponseEntity.ok(
                    ApiResponse.success(InterbankTransferHttpResponse.from(result))
                )

            // TRANSFER_UNKNOWN → 202 Accepted (클라이언트 폴링)
            is InterbankTransferResult.InProgress ->
                ResponseEntity
                    .accepted()
                    .body(ApiResponse.success(InterbankTransferHttpResponse.inProgress(result.sagaId)))

            // 확정 실패
            is InterbankTransferResult.Failed ->
                throw TransferFailedException()

            // 보상 진행 중
            is InterbankTransferResult.Compensating ->
                throw TransferFailedException()
        }
    }
}