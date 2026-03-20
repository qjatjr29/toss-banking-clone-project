package com.tossbank.account.application

import AccountNotFoundException
import ExternalTransferNotSupportedException
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

    /**
     * 당행 이체
     *
     * 동시성 제어 레이어:
     *   1. withContext(dbDispatcher)     — Netty 이벤트 루프 블로킹 방지
     *   2. Redisson MultiLock (분산 락)  — 앱 서버 인스턴스 간 요청 직렬화
     *   3. @Transactional                — HikariCP 커넥션 획득 (락 획득 후 → 점유 최소화)
     *   4. PESSIMISTIC_WRITE             — DB 레벨 최후 방어선
     *
     *   - fromMemberName: 트랜잭션 밖에서 MemberClient로 조회 (HTTP 호출의 DB 커넥션 점유 방지)
     *   - toMemberName: 클라이언트가 계좌실명조회 후 request에 포함해서 전달
     */
    suspend fun transfer(memberId: Long, request: TransferRequest): TransferResponse =
        withContext(dbDispatcher) {
            if (request.toBankCode != BankConstants.TOSS_BANK_CODE) {
                // 타행 이체 — external-banking-service 위임 (추후 구현)
                log.info { "타행 이체 요청: toBankCode=${request.toBankCode}" }
                throw ExternalTransferNotSupportedException()
            } else transferInternal(memberId, request);
        }

    // 당행 이체
    private fun transferInternal(memberId: Long, request: TransferRequest): TransferResponse {

        val toAccount = accountRepository.findByAccountNumber(request.toAccountNumber)
            ?: throw AccountNotFoundException()
        val toAccountId = toAccount.id

        if (request.fromAccountId == toAccountId) {
            throw TransferSameAccountException()
        }

        // 송금인 이름 조회 — @Transactional 밖에서 실행
        // - HTTP 호출 시 DB 커넥션을 점유하지 않도록 락 획득 전에 처리
        val fromMemberName = memberClient.getMemberName(memberId)

        log.info {
            "이체 요청: memberId=$memberId from=${request.fromAccountId} " +
                    "to=$toAccountId amount=${request.amount}"
        }

        // 분산 락 → 트랜잭션 → DB 락
        return lockManager.withTransferLocks(
            accountId1 = request.fromAccountId,
            accountId2 = toAccountId,
        ) {
            transferTransactionExecutor.execute(
                memberId = memberId,
                request = request,
                toAccountId = toAccountId,
                fromMemberName = fromMemberName,
            )
        }
    }
}