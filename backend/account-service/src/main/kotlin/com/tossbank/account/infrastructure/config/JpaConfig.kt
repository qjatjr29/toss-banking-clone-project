package com.tossbank.account.infrastructure.config

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@Configuration
@EnableJpaAuditing
class JpaConfig {
    /**
     * DB 전용 고정 스레드 풀
     *
     * Netty 이벤트 루프를 블로킹하지 않기 위해
     * JPA 호출은 반드시 withContext(dbDispatcher) { } 로 격리
     *
     * 풀 크기 = HikariCP 커넥션 수(10) + CPU 코어 수
     * 이유: 커넥션 반납 후에도 결과 매핑, TX 커밋 등 CPU 작업이 남음
     */
    @Bean("dbDispatcher")
    fun dbDispatcher(): CoroutineDispatcher {
        val poolSize = 10 + Runtime.getRuntime().availableProcessors()
        val count = AtomicInteger(0)
        return Executors.newFixedThreadPool(poolSize) { r ->
            Thread(r, "db-dispatcher-${count.incrementAndGet()}").apply {
                isDaemon = true
            }
        }.asCoroutineDispatcher()
    }
}