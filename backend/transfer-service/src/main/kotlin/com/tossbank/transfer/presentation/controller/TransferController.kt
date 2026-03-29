package com.tossbank.transfer.presentation.controller

import com.tossbank.common.response.ApiResponse
import com.tossbank.transfer.application.dto.InternalTransferResult
import com.tossbank.transfer.application.service.InternalTransferFacade
import com.tossbank.transfer.domain.exception.TransferFailedException
import com.tossbank.transfer.presentation.dto.InternalTransferHttpRequest
import com.tossbank.transfer.presentation.dto.InternalTransferHttpResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/transfers")
class InternalTransferController(
    private val transferFacade: InternalTransferFacade,
) {

    @PostMapping("/internal")
    suspend fun transferInternal(
        @RequestHeader("X-User-Id") memberId: Long,
        @RequestBody request: InternalTransferHttpRequest,
    ): ResponseEntity<ApiResponse<InternalTransferHttpResponse>> {
        return when (val result = transferFacade.transfer(memberId, request)) {
            is InternalTransferResult.Completed ->
                ResponseEntity.ok(ApiResponse.success(InternalTransferHttpResponse.from(result)))

            // UNKNOWN 발생 → 202 Accepted + sagaId (클라이언트가 폴링)
            is InternalTransferResult.InProgress ->
                ResponseEntity
                    .accepted()
                    .body(ApiResponse.success(InternalTransferHttpResponse.inProgress(result.sagaId)))

            // 4xx 명확 실패 → 에러 반환
            is InternalTransferResult.Failed ->
                throw TransferFailedException()

            // 보상 진행 중 → 에러 반환 (출금은 취소 예정)
            is InternalTransferResult.Compensating ->
                throw TransferFailedException()
        }
    }

//    @GetMapping("/internal/{sagaId}/status")
//    suspend fun getTransferStatus(
//        @RequestHeader("X-User-Id") memberId: Long,
//        @PathVariable sagaId: Long,
//    ): ApiResponse<InternalTransferStatusResponse> {
//        val saga = sagaRepository.findById(sagaId)
//            .orElseThrow { TransferNotFoundException() }
//
//        if (saga.fromMemberId != memberId) throw UnauthorizedTransferAccessException()
//
//        return ApiResponse.success(InternalTransferStatusResponse.from(saga))
//    }
}