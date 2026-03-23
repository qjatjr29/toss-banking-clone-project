package com.tossbank.account.application

import AccountNotFoundException
import TransferSameAccountException
import com.tossbank.account.infrastructure.client.MemberClient
import com.tossbank.account.infrastructure.lock.RedissonLockManager
import com.tossbank.account.infrastructure.persistence.AccountRepository
import com.tossbank.account.presentation.dto.TransferRequest
import com.tossbank.account.presentation.dto.TransferResponse
import com.tossbank.common.domain.BankConstants
import kotlinx.coroutines.CoroutineDispatcher
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

        // TODO: 외부 은행 API 호출
        //  ExternalBankClient.transfer() + retryWithBackoff
        //  성공  → response 반환
        //  4xx  → 보상 트랜잭션 → 예외 throw
        //  5xx  → UNKNOWN 저장 → 예외 throw

        return response
    }
}