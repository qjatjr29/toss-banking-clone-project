package com.tossbank

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

@SpringBootApplication
@EnableJpaAuditing
class TossBankApplication

fun main(args: Array<String>) {
	runApplication<TossBankApplication>(*args)
}
