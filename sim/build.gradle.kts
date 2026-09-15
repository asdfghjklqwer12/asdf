import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

// :domain 을 쓰는 두 번째 소비자다. Android 없이도 규칙이 돈다는 걸 보여주는 역할도 한다.
// 의존 방향은 :sim → :domain 단방향이다.
dependencies {
    implementation(project(":domain"))
}

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

application {
    mainClass.set("com.studystreak.sim.MainKt")
}

tasks.named<JavaExec>("run") {
    standardOutput = System.out
    // 출력에 한글과 ○ △ ✕ 가 들어간다. 빌드 JVM 로케일이 ASCII 면 전부 물음표로 깨지므로
    // 콘솔 인코딩을 플랫폼에 맡기지 않고 UTF-8 로 못 박는다.
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
