package com.tossbank.account.application

import com.tossbank.account.domain.model.AccountStatus
import com.tossbank.account.infrastructure.persistence.AccountRepository
import com.tossbank.account.presentation.dto.AccountResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AccountQueryService(
    private val accountRepository: AccountRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // =========================================================
    // ⚠️ 이 메서드는 문제를 보여주기 위한 코드입니다. 실제 사용 금지!
    // Dispatchers.IO 없이 직접 JPA 호출
    // Netty 이벤트 루프 스레드가 직접 블로킹 → Thread Starvation 유발
    // =========================================================
    @Transactional(readOnly = true)
    suspend fun getActiveAccountsBlocking(memberId: Long): List<AccountResponse> {
        log.warn("[BLOCKING ⚠️] 스레드 = {}", Thread.currentThread().name)

        // Dispatchers.IO 없이 현재 스레드(Netty)에서 직접 JPA 호출
        // → Netty 이벤트 루프 스레드가 DB 응답 올 때까지 멈춰버림!
        val accounts = accountRepository.findAllByMemberIdAndStatus(memberId, AccountStatus.ACTIVE)

        return accounts.map { AccountResponse.from(it) }
    }

    // Dispatchers.IO로 스레드 풀 격리
    // Netty 이벤트 루프 보호 → Thread Starvation 방어
    @Transactional(readOnly = true)
    suspend fun getActiveAccounts(memberId: Long): List<AccountResponse> {
        return withContext(Dispatchers.IO) {
            accountRepository.findAllByMemberIdAndStatus(memberId, AccountStatus.ACTIVE)
        }.map { AccountResponse.from(it) }
    }
}
