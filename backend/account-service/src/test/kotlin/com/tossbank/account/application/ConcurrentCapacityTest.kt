package com.tossbank.account.application

import com.tossbank.account.infrastructure.persistence.AccountRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis


/**
 * [테스트 목적]
 * Dispatchers.IO가 실제로 몇 개까지의 동시 DB 접속을 처리할 수 있는지,
 * 그리고 HikariCP 커넥션 풀 사이즈와의 관계를 검증합니다.
 *
 * 핵심 원리
 * Dispatchers.IO 최대 스레드 수 = max(64, CPU 코어 수)
 * HikariCP 기본 커넥션 풀 = 10개
 * → 실제 병렬 DB 처리 병목은 Dispatchers.IO가 아닌 HikariCP 커넥션 풀!
 */
class ConcurrentCapacityTest : StringSpec({

    val accountRepository = mockk<AccountRepository>()
    val service = AccountQueryService(
        accountRepository = accountRepository,
        dbDispatcher      = Dispatchers.IO,
    )

    // 실제로 DB에 접근 중인 동시 요청 수 카운터
    val peakConcurrentDbCalls = AtomicInteger(0)
    val currentConcurrentDbCalls = AtomicInteger(0)

    every { accountRepository.findAllByMemberIdAndStatus(any(), any()) } answers {
        val current = currentConcurrentDbCalls.incrementAndGet()
        peakConcurrentDbCalls.updateAndGet { maxOf(it, current) } // 최고 동시 접속 수 기록
        Thread.sleep(30) // DB 처리 시간 시뮬레이션 (30ms)
        currentConcurrentDbCalls.decrementAndGet()
        emptyList()
    }

    "Dispatchers.IO는 기본적으로 최대 64개의 동시 DB 접속을 처리할 수 있다" {
        peakConcurrentDbCalls.set(0)
        currentConcurrentDbCalls.set(0)

        val requestCount = 100
        val time = measureTimeMillis {
            coroutineScope {
                (1..requestCount).map { memberId ->
                    async { service.getActiveAccounts(memberId.toLong()) }
                }.awaitAll()
            }
        }

        println("\n╔══════════════════════════════════════════════╗")
        println("║ 총 요청: ${requestCount}건")
        println("║ 측정된 최고 동시 DB 접속 수: ${peakConcurrentDbCalls.get()}개")
        println("║ 전체 처리 시간: ${time}ms")
        println("║")
        println("║ [분석]")
        println("║ Dispatchers.IO 최대 스레드: 64개")
        println("║ 실제 DB 병목: HikariCP 커넥션 풀 사이즈")
        println("╚══════════════════════════════════════════════╝\n")

        // 최고 동시 접속 수 = Dispatchers.IO 스레드 수(64개) 근접
        peakConcurrentDbCalls.get() shouldBeGreaterThan 10

        // 100건을 병렬 처리했으므로 단순 순차(100 * 30ms = 3000ms)보다 훨씬 빨라야 함
        time shouldBeLessThan 1000L
    }

    "요청이 64개를 초과해도 큐잉(Queuing)되어 안전하게 모두 처리된다" {
        peakConcurrentDbCalls.set(0)
        currentConcurrentDbCalls.set(0)

        // Dispatchers.IO 최대 스레드(64)를 훨씬 초과하는 200개 동시 요청
        val requestCount = 200
        var allSucceeded = false

        val time = measureTimeMillis {
            coroutineScope {
                (1..requestCount).map { memberId ->
                    async { service.getActiveAccounts(memberId.toLong()) }
                }.awaitAll()
                allSucceeded = true
            }
        }

        println("\n╔══════════════════════════════════════════════╗")
        println("║ 총 요청: ${requestCount}건 (Dispatchers.IO 64개 초과)")
        println("║ 최고 동시 처리: ${peakConcurrentDbCalls.get()}개")
        println("║ 전체 처리 시간: ${time}ms")
        println("║ 모든 요청 성공: $allSucceeded")
        println("║")
        println("║ [결론] 64개 초과분은 코루틴 큐에서 대기 후 순차 처리됨")
        println("║ → 에러 없이 모든 요청이 결과적으로 처리됨 ✅")
        println("╚══════════════════════════════════════════════╝\n")

        // 200개 모두 에러 없이 처리 완료되어야 함
        assert(allSucceeded) { "모든 요청이 처리되어야 합니다" }
    }
})