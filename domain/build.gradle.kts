import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

// Android 의존성 없음 — Compose 도 Android SDK 도 참조하지 않는다.
// Android 모듈에서 그대로 쓸 수 있도록 바이트코드는 17로 맞춘다.
// (툴체인을 17로 못 박으면 JDK 17이 없는 환경에서 빌드가 막혀서 release 플래그를 쓴다)
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
