package com.tossbank.account.integration

import ExternalBankApiException
import ExternalTransferFailedException
import ExternalTransferUnknownException
import com.tossbank.account.application.TransferService
import com.tossbank.account.domain.model.Account
import com.tossbank.account.domain.model.AccountStatus
import com.tossbank.account.domain.model.InterbankTransferStatus
import com.tossbank.account.domain.model.TransactionType
import com.tossbank.account.infrastructure.client.ExternalBankClient
import com.tossbank.account.infrastructure.client.dto.ExternalTransferResponse
import com.tossbank.account.infrastructure.client.dto.ExternalTransferResultStatus
import com.tossbank.account.infrastructure.persistence.AccountRepository
import com.tossbank.account.infrastructure.persistence.InterbankTransferRepository
import com.tossbank.account.infrastructure.persistence.TransactionHistoryRepository
import com.tossbank.account.presentation.dto.TransferRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.util.*

class TransferServiceIntegrationTest : IntegrationTestBase() {

    @Autowired lateinit var transferService: TransferService
    @Autowired lateinit var accountRepository: AccountRepository
    @Autowired lateinit var transactionHistoryRepository: TransactionHistoryRepository
    @Autowired lateinit var interbankTransferRepository: InterbankTransferRepository
    @Autowired lateinit var externalBankClient: ExternalBankClient

    fun createAccount(memberId: Long, balance: BigDecimal, accountNumber: String) =
        accountRepository.save(
            Account(
                memberId      = memberId,
                accountNumber = accountNumber,
                holderName    = "테스트유저$memberId",
                balance       = balance,
                status        = AccountStatus.ACTIVE,
            )
        )

    fun internalRequest(
        fromAccountId: Long,
        toAccountNumber: String,
        amount: BigDecimal,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ) = TransferRequest(
        fromAccountId   = fromAccountId,
        toAccountNumber = toAccountNumber,
        toBankCode      = "092",
        toMemberName    = "수취인",
        amount          = amount,
        idempotencyKey  = idempotencyKey,
    )

    fun interbankRequest(
        fromAccountId: Long,
        amount: BigDecimal,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ) = TransferRequest(
        fromAccountId   = fromAccountId,
        toAccountNumber = "3333-000-000001",
        toBankCode      = "011",
        toMemberName    = "박토스",
        amount          = amount,
        idempotencyKey  = idempotencyKey,
    )

    fun successResponse(txId: String = "ext-tx-001") = ExternalTransferResponse(
        externalTransactionId = txId,
        status                = ExternalTransferResultStatus.SUCCESS,
    )

    init {
        beforeEach { clearAllMocks() }

        afterEach {
            transactionHistoryRepository.deleteAll()
            interbankTransferRepository.deleteAll()
        }
        afterSpec { accountRepository.deleteAll() }
    }

    init {
        // ══════════════════════════════════════════════════════
        // 타행 이체 — transferInterbank() 분기로 진입
        //            memberClient 호출 없음 → mock 불필요
        // ══════════════════════════════════════════════════════

        Given("타행 이체 - 외부 API 200 성공") {

            When("정상 이체를 실행하면") {
                Then("출금 계좌 잔액 차감 + COMPLETED + 거래내역 저장") {
                    coEvery { externalBankClient.transfer(any()) } returns successResponse("ext-tx-001")

                    val from = createAccount(20L, BigDecimal("100000"), "1002-003-000001")
                    val req  = interbankRequest(from.id, BigDecimal("10000"))

                    val result = transferService.transfer(20L, req)

                    result.remainingBalance shouldBeEqualComparingTo BigDecimal("90000")
                    accountRepository.findById(from.id).get().balance shouldBeEqualComparingTo BigDecimal("90000")

                    val transfer = interbankTransferRepository.findByIdempotencyKey(req.idempotencyKey)!!
                    transfer.status                shouldBe InterbankTransferStatus.COMPLETED
                    transfer.externalTransactionId shouldBe "ext-tx-001"

                    // INTERBANK_WITHDRAW 내역 idempotencyKey 보존 확인
                    val history = transactionHistoryRepository.findByIdempotencyKey(req.idempotencyKey)!!
                    history.transactionType shouldBe TransactionType.INTERBANK_WITHDRAW
                }
            }
        }

        Given("타행 이체 - 외부 API 4xx → 보상 트랜잭션 성공") {

            When("이체를 실행하면") {
                Then("ExternalTransferFailedException 발생 + 출금 환불 + COMPENSATED + idempotencyKey 해제") {
                    coEvery { externalBankClient.transfer(any()) } throws
                            ExternalBankApiException(statusCode = 400, message = "Bad Request")

                    val from = createAccount(30L, BigDecimal("100000"), "1002-004-000001")
                    val req  = interbankRequest(from.id, BigDecimal("10000"))

                    shouldThrow<ExternalTransferFailedException> { transferService.transfer(30L, req) }

                    // 보상 완료 → 잔액 복원
                    accountRepository.findById(from.id).get().balance shouldBeEqualComparingTo BigDecimal("100000")

                    // InterbankTransfer COMPENSATED
                    interbankTransferRepository.findAll()
                        .any { it.status == InterbankTransferStatus.COMPENSATED } shouldBe true

                    // INTERBANK_WITHDRAW_CANCEL 내역 저장 확인
                    transactionHistoryRepository.findAll()
                        .any { it.transactionType == TransactionType.INTERBANK_WITHDRAW_CANCEL } shouldBe true

                    // 원본 출금 내역의 idempotencyKey 해제 확인 (재이체 허용을 위해)
                    transactionHistoryRepository.findByIdempotencyKey(req.idempotencyKey)
                        .shouldBeNull()
                }
            }
        }

        Given("타행 이체 - 외부 API 5xx 3회 모두 실패 → UNKNOWN") {

            When("이체를 실행하면") {
                Then("ExternalTransferUnknownException 발생 + 잔액 차감 유지 + UNKNOWN") {
                    coEvery { externalBankClient.transfer(any()) } throws
                            ExternalBankApiException(statusCode = 500, message = "Internal Server Error")

                    val from = createAccount(40L, BigDecimal("100000"), "1002-005-000001")
                    val req  = interbankRequest(from.id, BigDecimal("10000"))

                    shouldThrow<ExternalTransferUnknownException> { transferService.transfer(40L, req) }

                    // ⚠️ UNKNOWN = 입금 여부 불확실 → 보상 금지 → 잔액 차감 유지
                    accountRepository.findById(from.id).get().balance shouldBeEqualComparingTo BigDecimal("90000")

                    val transfer = interbankTransferRepository.findByIdempotencyKey(req.idempotencyKey)!!
                    transfer.status         shouldBe InterbankTransferStatus.UNKNOWN
                    // UNKNOWN은 스케줄러 재조회를 위해 idempotencyKey 유지
                    transfer.idempotencyKey shouldBe req.idempotencyKey
                }
            }
        }

        Given("타행 이체 - UNKNOWN 상태에서 동일 키 재요청") {

            When("5xx 실패 후 동일 키로 재요청하면") {
                Then("외부 API 재호출 없이 UNKNOWN 그대로 반환 + 잔액 1번만 차감") {
                    coEvery { externalBankClient.transfer(any()) } throws
                            ExternalBankApiException(statusCode = 500, message = "Internal Server Error")

                    val from = createAccount(50L, BigDecimal("100000"), "1002-006-000001")
                    val req  = interbankRequest(from.id, BigDecimal("10000"),
                        idempotencyKey = "idem-key-unknown-001")

                    shouldThrow<ExternalTransferUnknownException> { transferService.transfer(50L, req) }
                    shouldThrow<ExternalTransferUnknownException> { transferService.transfer(50L, req) }

                    // 잔액은 단 1번만 차감
                    accountRepository.findById(from.id).get().balance shouldBeEqualComparingTo BigDecimal("90000")

                    // InterbankTransfer 1건만 존재 (중복 생성 없음)
                    interbankTransferRepository.findAll()
                        .filter { it.idempotencyKey == req.idempotencyKey }
                        .size shouldBe 1
                }
            }
        }

        Given("타행 이체 - COMPENSATED 후 동일 키 재이체 허용") {

            When("4xx 실패(보상 완료) 후 동일 키로 재이체하면") {
                Then("신규 이체로 처리 + 출금 재실행 + COMPLETED") {
                    // ── 1차: 4xx → COMPENSATED ──────────────────────────
                    coEvery { externalBankClient.transfer(any()) } throws
                            ExternalBankApiException(statusCode = 400, message = "Bad Request")

                    val from = createAccount(60L, BigDecimal("100000"), "1002-007-000001")
                    val req  = interbankRequest(from.id, BigDecimal("10000"),
                        idempotencyKey = "idem-key-compensated-001")

                    shouldThrow<ExternalTransferFailedException> { transferService.transfer(60L, req) }

                    // 보상 완료 → 잔액 복원 + idempotencyKey 해제
                    accountRepository.findById(from.id).get().balance shouldBeEqualComparingTo BigDecimal("100000")
                    transactionHistoryRepository.findByIdempotencyKey(req.idempotencyKey).shouldBeNull()

                    // ── 2차: 성공 → 신규 이체로 처리 ────────────────────
                    coEvery { externalBankClient.transfer(any()) } returns successResponse("ext-tx-retry-001")

                    val result = transferService.transfer(60L, req)

                    result.remainingBalance shouldBeEqualComparingTo BigDecimal("90000")
                    accountRepository.findById(from.id).get().balance shouldBeEqualComparingTo BigDecimal("90000")

                    // 2차 이체 COMPLETED 확인
                    val completedTransfer = interbankTransferRepository
                        .findByIdempotencyKey(req.idempotencyKey)!!
                    completedTransfer.status                shouldBe InterbankTransferStatus.COMPLETED
                    completedTransfer.externalTransactionId shouldBe "ext-tx-retry-001"

                    // 전체 거래내역 (from 계좌 기준):
                    //   1차 INTERBANK_WITHDRAW        (idempotencyKey = null, 해제됨)
                    //   1차 INTERBANK_WITHDRAW_CANCEL (idempotencyKey = "...-cancel")
                    //   2차 INTERBANK_WITHDRAW        (idempotencyKey = "idem-key-compensated-001")
                    val histories = transactionHistoryRepository.findAll()
                        .filter { it.accountId == from.id }
                    histories.size shouldBe 3
                    histories.count { it.transactionType == TransactionType.INTERBANK_WITHDRAW        } shouldBe 2
                    histories.count { it.transactionType == TransactionType.INTERBANK_WITHDRAW_CANCEL } shouldBe 1
                }
            }
        }

        Given("타행 이체 - 멱등성 (COMPLETED 중복 요청)") {

            When("동일 키로 2번 요청하면") {
                Then("출금 계좌 잔액은 1번만 차감된다") {
                    coEvery { externalBankClient.transfer(any()) } returns successResponse("ext-tx-idem-001")

                    val from = createAccount(70L, BigDecimal("100000"), "1002-008-000001")
                    val req  = interbankRequest(from.id, BigDecimal("10000"),
                        idempotencyKey = "idem-key-interbank-001")

                    transferService.transfer(70L, req)
                    val second = transferService.transfer(70L, req)

                    second.amount           shouldBeEqualComparingTo BigDecimal("10000")
                    second.remainingBalance shouldBeEqualComparingTo BigDecimal("90000")
                    accountRepository.findById(from.id).get().balance shouldBeEqualComparingTo BigDecimal("90000")

                    // 거래내역 1건만 저장 (중복 저장 없음)
                    transactionHistoryRepository.findAll()
                        .filter { it.accountId == from.id }
                        .size shouldBe 1
                }
            }
        }
    }
}
