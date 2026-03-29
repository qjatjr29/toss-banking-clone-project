package com.tossbank.transfer.infrastructure.config

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