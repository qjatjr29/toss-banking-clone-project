package com.tossbank.account.infrastructure.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SchedulerConfig {

    @Bean("schedulerScope")
    fun schedulerScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}