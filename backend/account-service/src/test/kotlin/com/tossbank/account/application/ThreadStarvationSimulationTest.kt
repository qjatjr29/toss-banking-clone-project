package com.tossbank.account.application

import com.tossbank.account.infrastructure.persistence.AccountRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis

/**
 * [테스트 목적]
 * CPU 코어 수만큼 제한된 스레드 풀(Netty 이벤트 루프 흉내)에서
 * Blocking 방식과 Non-Blocking 방식의 처리 시간 차이를 극적으로 보여줍니다.
 *
 * 나온 결과
 * - BLOCKING:     707ms (4개 스레드가 50번을 순차 처리)
 * - NON-BLOCKING: 62ms  (IO 워커들이 50번을 병렬 처리)
 */
class ThreadStarvationSimulationTest : BehaviorSpec({

    val accountRepository = mockk<AccountRepository>()
    val service = AccountQueryService(accountRepository)

    // Netty 이벤트 루프를 흉내내는 4개 스레드 풀 (CPU 코어 4개 가정)
    val nettySimulator = Executors.newFixedThreadPool(4).asCoroutineDispatcher()

    // DB 응답 시간 흉내 (50ms 블로킹)
    every { accountRepository.findAllByMemberIdAndStatus(any(), any()) } answers {
        Thread.sleep(50) // JPA Blocking I/O 흉내
        emptyList()
    }

    afterSpec { nettySimulator.close() }

    Given("4개의 스레드만 가진 Netty 이벤트 루프 환경에서") {
        val concurrentUsers = 50 // 50명의 동시 요청

        When("Dispatchers.IO 없이 Blocking 방식으로 50명의 요청을 처리하면") {
            val blockingTime = measureTimeMillis {
                runBlocking(nettySimulator) {
                    (1..concurrentUsers).map { memberId ->
                        launch {
                            // 4개 스레드가 50ms 블로킹을 번갈아 처리
                            // → 4개 스레드가 멈춰있는 동안 다른 요청 전혀 처리 불가
                            service.getActiveAccountsBlocking(memberId.toLong())
                        }
                    }.joinAll()
                }
            }

            Then("처리 시간이 매우 길어진다 (Thread Starvation 발생)") {
                println("\n╔══════════════════════════════════════╗")
                println("║ [❌ BLOCKING] 처리 시간: ${blockingTime}ms")
                println("║ 4개 스레드가 50번 순차 처리 → 병목 발생")
                println("╚══════════════════════════════════════╝\n")

                // 4개 스레드가 50번의 50ms 작업을 순서대로 처리
                // 최소 예상 시간: 50 / 4 * 50ms = 625ms
                blockingTime shouldBeGreaterThan 600L
            }
        }

        When("Dispatchers.IO로 격리하여 50명의 요청을 처리하면") {
            val nonBlockingTime = measureTimeMillis {
                runBlocking(nettySimulator) {
                    (1..concurrentUsers).map { memberId ->
                        launch {
                            // Netty 스레드는 즉시 IO 워커에 위임하고 다음 요청 처리
                            // → IO 워커 64개가 50개 요청을 거의 동시에 처리
                            service.getActiveAccounts(memberId.toLong())
                        }
                    }.joinAll()
                }
            }

            Then("처리 시간이 획기적으로 단축된다 (Thread Starvation 해소)") {
                println("\n╔══════════════════════════════════════╗")
                println("║ [✅ NON-BLOCKING] 처리 시간: ${nonBlockingTime}ms")
                println("║ IO 워커들이 50개 요청 병렬 처리")
                println("╚══════════════════════════════════════╝\n")

                // IO 워커 64개가 50개를 거의 동시에 → 50ms + 오버헤드 정도
                nonBlockingTime shouldBeLessThan 300L
            }
        }
    }
})