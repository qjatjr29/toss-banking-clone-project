plugins {
    kotlin("plugin.jpa")
}

dependencies {
    implementation(project(":common"))
//    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // MySQL & JPA
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("com.mysql:mysql-connector-j")

    // Redis (Distributed Lock)
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.redisson:redisson-spring-boot-starter:4.3.0")

    // Kafka (Saga Compensation & Outbox)
    implementation("org.springframework.kafka:spring-kafka")
}