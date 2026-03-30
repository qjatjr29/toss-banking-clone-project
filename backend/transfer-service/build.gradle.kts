plugins {
    kotlin("plugin.jpa")
}

dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // MySQL & JPA
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("com.mysql:mysql-connector-j")

    // Redis (Distributed Lock)
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.redisson:redisson-spring-boot-starter:4.3.0")

    // Kafka (Saga Compensation & Outbox)
    implementation("org.springframework.kafka:spring-kafka")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    // Kotest 검증(Assertion) 라이브러리 (shouldBe 등)
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")
    // Kotest에서 @SpringBootTest 등 스프링 빈을 주입받기 위한 확장 모듈
    testImplementation("io.kotest.extensions:kotest-extensions-spring:1.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mockk:mockk:1.13.10")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers:2.0.3")
    testImplementation("io.kotest.extensions:kotest-extensions-spring:1.1.3")
    testImplementation("org.testcontainers:mysql:1.21.4")
}