dependencies {
    implementation(project(":common")) {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-data-jpa")
        exclude(group = "org.hibernate.orm", module = "hibernate-core")
    }
    implementation("org.springframework.boot:spring-boot-starter-webflux")
}