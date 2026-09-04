import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.hyd"
version = "0.0.1-SNAPSHOT"

repositories {
    maven("https://maven.aliyun.com/repository/public/")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(kotlin("test"))
}

// 声明 toolchain 而不是只写 sourceCompatibility/targetCompatibility：后者只决定编译产物的字节码版本，
// bootRun / test 起子进程用哪个 JVM 由 toolchain 决定；缺省时退化成跑 Gradle 守护进程的那个 JDK。
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        javaParameters.set(true)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
