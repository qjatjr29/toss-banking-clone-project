package com.tossbank.account.application

import AccountNotFoundException
import com.tossbank.account.domain.model.AccountStatus
import com.tossbank.account.infrastructure.persistence.AccountRepository
import com.tossbank.account.presentation.dto.AccountHolderResponse
import com.tossbank.account.presentation.dto.AccountResponse
import com.tossbank.common.domain.BankConstants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val log = KotlinLogging.logger {}

@Service
@Transactional(readOnly = true)
class AccountQueryService(
    private val accountRepository: AccountRepository,
    @Qualifier("dbDispatcher") private val dbDispatcher: CoroutineDispatcher,
) {
    // =========================================================
    // ⚠️ 이 메서드는 문제를 보여주기 위한 코드입니다. 실제 사용 금지!
    // Dispatchers.IO 없이 직접 JPA 호출
    // Netty 이벤트 루프 스레드가 직접 블로킹 → Thread Starvation 유발
    // =========================================================
    suspend fun getActiveAccountsBlocking(memberId: Long): List<AccountResponse> {
        log.warn("[BLOCKING ⚠️] 스레드 = {}", Thread.currentThread().name)

        // Dispatchers.IO 없이 현재 스레드(Netty)에서 직접 JPA 호출
        // → Netty 이벤트 루프 스레드가 DB 응답 올 때까지 멈춰버림!
        val accounts = accountRepository.findAllByMemberIdAndStatus(memberId, AccountStatus.ACTIVE)

        return accounts.map { AccountResponse.from(it) }
    }

    // Netty 이벤트 루프 보호 → Thread Starvation 방지
    suspend fun getActiveAccounts(memberId: Long): List<AccountResponse> {
        return withContext(dbDispatcher) {
            accountRepository
                .findAllByMemberIdAndStatus(memberId, AccountStatus.ACTIVE)
                .map { AccountResponse.from(it) }
        }
    }

    suspend fun getAccount(memberId: Long, accountId: Long): AccountResponse {
        return withContext(dbDispatcher) {
            val account = accountRepository.findById(accountId)
                .orElseThrow { AccountNotFoundException() }
            account.verifyOwner(memberId)
            AccountResponse.from(account)
        }
    }

    /**
     * 계좌 실명조회 — 이체 전 수취인 이름 확인용
     *
     * 당행: 내부 DB 직접 조회
     * 타행: 금융결제원 오픈뱅킹 계좌실명조회 API 호출
     *   POST https://openapi.kftc.or.kr/v2.0/inquiry/real_name
     *   TODO: external-banking-service 구현 시 연동
     */
    suspend fun inquireAccountHolder(
        accountNumber: String,
        bankCode: String,
    ): AccountHolderResponse = withContext(dbDispatcher) {

        // 타행 계좌는 외부 API 호출 (추후 구현)
        if (bankCode != BankConstants.TOSS_BANK_CODE) {
            return@withContext inquireExternalBank(accountNumber, bankCode)
        }

        // 당행 계좌 — 내부 DB 조회
        val account = accountRepository.findByAccountNumberAndStatus(
            accountNumber = accountNumber,
            status        = AccountStatus.ACTIVE,
        ) ?: throw AccountNotFoundException()

        AccountHolderResponse(
            accountNumber = accountNumber,
            holderName    = account.holderName,
            bankCode      = bankCode,
        )
    }

    private fun inquireExternalBank(
        accountNumber: String,
        bankCode: String,
    ): AccountHolderResponse {
        // Mock 응답
        return AccountHolderResponse(
            accountNumber = accountNumber,
            holderName    = "홍길동",  // 실제로는 외부 API 응답
            bankCode      = bankCode,
        )
    }

}
