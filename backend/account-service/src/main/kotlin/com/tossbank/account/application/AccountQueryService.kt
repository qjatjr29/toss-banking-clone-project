package com.tossbank.account.application

import AccountNotFoundException
import com.tossbank.account.domain.model.AccountStatus
import com.tossbank.account.infrastructure.client.ExternalBankAccountClient
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
    private val externalBankClient: ExternalBankAccountClient,
    @Qualifier("dbDispatcher") private val dbDispatcher: CoroutineDispatcher,
) {

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

        if (bankCode != BankConstants.TOSS_BANK_CODE) {
            log.info { "타행 계좌 실명 조회 — bankCode=$bankCode accountNumber=$accountNumber" }
            val response = externalBankClient.inquireAccountHolder(accountNumber)
            return@withContext AccountHolderResponse(
                accountNumber = response.accountNumber,
                holderName    = response.holderName,
                bankCode      = bankCode,
            )
        }

        // 당행 → 내부 DB 조회
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
}
