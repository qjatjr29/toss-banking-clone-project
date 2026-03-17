//plugins {
//    kotlin("jvm")
//}
//
//group = "com"
//version = "1.0.0"
//
//repositories {
//    mavenCentral()
//}
//
//dependencies {
//    testImplementation(kotlin("test"))
//}
//
//tasks.test {
//    useJUnitPlatform()
//}

dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
}