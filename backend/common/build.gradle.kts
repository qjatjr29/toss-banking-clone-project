plugins {
    kotlin("plugin.jpa")
}

// bootJar 태스크 비활성화
tasks.withType<org.springframework.boot.gradle.tasks.bundling.BootJar> {
    enabled = false
}

// jar 파일은 생성하도록 설정
tasks.jar {
    enabled = true
}

// bootBuildImage 비활성화
tasks.withType<org.springframework.boot.gradle.tasks.bundling.BootBuildImage> {
    enabled = false
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    compileOnly("org.springframework.boot:spring-boot-starter-webflux")
}
