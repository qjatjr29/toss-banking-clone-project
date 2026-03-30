package com.tossbank.transfer.infrastructure.config

import kotlinx.coroutines.*
import mu.KotlinLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private val log = KotlinLogging.logger {}

@Configuration
class CoroutineScopeConfig {

    @Bean("schedulerScope")
    fun schedulerScope(): CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                    Dispatchers.IO +
                    CoroutineName("scheduler") +
                    CoroutineExceptionHandler { _, e ->
                        log.error("미처리 코루틴 예외", e)
                    }
        )
}