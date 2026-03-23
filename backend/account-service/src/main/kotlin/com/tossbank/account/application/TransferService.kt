package com.tossbank.account.application

import AccountNotFoundException
import CompensationFailedException
import ExternalBankApiException
import ExternalTransferFailedException
import ExternalTransferUnknownException
import TransferSameAccountException
import com.tossbank.account.application.dto.InterbankTransferContext
import com.tossbank.account.infrastructure.client.ExternalBankClient
import com.tossbank.account.infrastructure.client.MemberClient
import com.tossbank.account.infrastructure.client.dto.ExternalTransferRequest
import com.tossbank.account.infrastructure.lock.RedissonLockManager
import com.tossbank.account.infrastructure.persistence.AccountRepository
import com.tossbank.account.presentation.dto.TransferRequest
import com.tossbank.account.presentation.dto.TransferResponse
import com.tossbank.common.domain.BankConstants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

private val log = KotlinLogging.logger {}

@Service
class TransferService(
    private val accountRepository: AccountRepository,
    private val lockManager: RedissonLockManager,
    private val transferTransactionExecutor: TransferTransactionExecutor,
    private val memberClient: MemberClient,
    private val externalBankClient: ExternalBankClient,
    @Qualifier("dbDispatcher") private val dbDispatcher: CoroutineDispatcher,
) {

    suspend fun transfer(memberId: Long, request: TransferRequest): TransferResponse {
        if (request.toBankCode != BankConstants.TOSS_BANK_CODE) {
            return transferInterbank(memberId, request)
        }
        val fromMemberName = memberClient.getMemberName(memberId)
        return transferInternal(memberId, request, fromMemberName)
    }


    /**
     * 당행 이체
     *
     * 동시성 제어
     *  withContext(dbDispatcher)  — Netty 이벤트 루프 블로킹 방지
     *  Redisson MultiLock         — 앱 서버 인스턴스 간 요청 직렬화
     *  @Transactional             — DB 커넥션 획득 (락 획득 후 → 점유 최소화)
     *  PESSIMISTIC_WRITE
     */
    private suspend fun transferInternal(
        memberId: Long,
        request: TransferRequest,
        fromMemberName: String,
    ): TransferResponse = withContext(dbDispatcher) {
        val toAccount = accountRepository.findByAccountNumber(request.toAccountNumber)
            ?: throw AccountNotFoundException()
        val toAccountId = toAccount.id

        if (request.fromAccountId == toAccountId) throw TransferSameAccountException()

        lockManager.withTransferLocks(
            accountId1 = request.fromAccountId,
            accountId2 = toAccountId,
        ) {
            transferTransactionExecutor.executeInternalTransfer(
                memberId       = memberId,
                request        = request,
                toAccountId    = toAccountId,
                fromMemberName = fromMemberName,
            )
        }
    }

    /**
     *  멱등성 체크
     *  T1: 출금 (분산 락 → @Transactional → SELECT FOR UPDATE)
     *  T2: 외부 은행 API 호출 (트랜잭션 밖 — 이슈 3에서 구현)
     *  T2 성공(200)       → COMPLETED
     *  T2 실패(4xx) → 보상 트랜잭션 → COMPENSATED
     *  T2 불확실(5xx/TO)  → UNKNOWN (보상 트랜잭션 금지) → 입금 여부 확인 및 재시도
     */
    private suspend fun transferInterbank(memberId: Long, request: TransferRequest): TransferResponse {

        //  출금, 이후 외부 API 호출 구간에서는 DB 커넥션 미점유
        val response = withContext(dbDispatcher) {
            lockManager.withSingleLock(request.fromAccountId) {
                transferTransactionExecutor.executeInterbankWithdraw(
                    memberId        = memberId,
                    fromAccountId   = request.fromAccountId,
                    toAccountNumber = request.toAccountNumber,
                    toBankCode      = request.toBankCode,
                    toMemberName    = request.toMemberName,
                    amount          = request.amount,
                    description     = request.description,
                    idempotencyKey  = request.idempotencyKey,
                )
            }
        }
        return executeExternalTransfer(response, request)
    }
    /**
     * 외부 은행 API 호출 + 상태 전이
     * ExternalBankApiException 외에 예상치 못한 예외(네트워크 단절 등)도 결과를 알 수 없으므로 UNKNOWN으로 처리
     */
    private suspend fun executeExternalTransfer(
        withdrawResult : InterbankTransferContext,
        request        : TransferRequest,
    ): TransferResponse {
        val interbankId = withdrawResult.interbankTransferId

        return try {
            val externalResponse = retryWithBackoff(
                maxAttempts = 3,
                retryOn     = { it is ExternalBankApiException && it.isServerError },
            ) {
                externalBankClient.transfer(
                    ExternalTransferRequest(
                        fromBankCode      = BankConstants.TOSS_BANK_CODE,
                        fromAccountNumber = withdrawResult.fromAccountNumber,
                        toAccountNumber   = request.toAccountNumber,
                        toBankCode        = request.toBankCode,
                        toMemberName      = request.toMemberName,
                        amount            = request.amount,
                        idempotencyKey    = request.idempotencyKey,
                    )
                )
            }

            withContext(dbDispatcher) {
                transferTransactionExecutor.markInterbankCompleted(
                    interbankTransferId = interbankId,
                    externalTxId        = externalResponse.externalTransactionId,
                )
            }
            log.info { "타행 이체 완료: id=$interbankId externalTxId=${externalResponse.externalTransactionId}" }
            withdrawResult.transferResponse

        } catch (e: ExternalBankApiException) {
            when {
                e.isClientError -> {
                    // 4xx 확정 실패 → 보상 트랜잭션
                    log.warn { "타행 이체 4xx(${e.statusCode}) → 보상 트랜잭션: id=$interbankId" }
                    withContext(dbDispatcher) {
                        transferTransactionExecutor.compensateInterbank(interbankId)
                    }
                    throw ExternalTransferFailedException()
                }
                else -> {
                    // 5xx/timeout 3회 재시도 모두 실패 → UNKNOWN (보상 트랜잭션 금지)
                    log.error { "타행 이체 5xx(${e.statusCode}) → UNKNOWN: id=$interbankId" }
                    withContext(dbDispatcher) {
                        transferTransactionExecutor.markInterbankUnknown(interbankId,
                            "statusCode=${e.statusCode} message=${e.message}")
                    }
                    throw ExternalTransferUnknownException()
                }
            }
        } catch (e: ExternalTransferFailedException) {
            throw e  // 보상 트랜잭션 후 이미 throw된 예외 - 그대로 전파

        } catch (e: CompensationFailedException) {
            // 보상 트랜잭션 실패
            log.error(e.cause) { "보상 트랜잭션 실패 → COMPENSATION_PENDING 스케줄러 재시도 예정: id=$interbankId" }
            throw ExternalTransferFailedException()  // 사용자에게 "이체 실패" 응답

        }catch (e: Exception) {
            // 네트워크 단절 등 예상치 못한 예외 → 결과 불확실 → UNKNOWN
            log.error(e) { "타행 이체 예상치 못한 예외 → UNKNOWN: id=$interbankId" }
            withContext(dbDispatcher) {
                transferTransactionExecutor.markInterbankUnknown(
                    interbankTransferId = interbankId,
                    errorMessage        = e.message ?: "unexpected error",
                )
            }
            throw ExternalTransferUnknownException()
        }
    }

    /**
     * 지수 백오프 재시도
     *
     * retryOn(e) = false → 즉시 throw
     * retryOn(e) = true  → delay 후 재시도 (5xx/timeout)
     *
     * 500ms → 1000ms → 마지막 시도 (delay 없음)
     */
    private suspend fun <T> retryWithBackoff(
        maxAttempts  : Int                    = 3,
        initialDelay : Long                   = 500L,
        maxDelay     : Long                   = 5_000L,
        factor       : Double                 = 2.0,
        retryOn      : (Exception) -> Boolean = { true },
        block        : suspend () -> T,
    ): T {
        var currentDelay = initialDelay
        repeat(maxAttempts - 1) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                if (!retryOn(e)) throw e
                log.warn { "외부 은행 API 재시도 (${attempt + 1}/${maxAttempts - 1}) — ${currentDelay}ms 후" }
                delay(currentDelay)
                currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
            }
        }
        return block()  // 마지막 시도 — 실패 시 예외 그대로 전파
    }
}