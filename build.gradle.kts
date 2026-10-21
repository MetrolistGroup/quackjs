import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.4.20"
    id("com.android.kotlin.multiplatform.library") version "9.4.1"
    `maven-publish`
}
group = "meow.nyx"
version = "0.1.0-SNAPSHOT"
kotlin {
    android {
        namespace = "meow.nyx.quackjs"
        compileSdk = 37
        minSdk = 23
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        withHostTest {}
    }
    jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    linuxX64()
    iosArm64()
    iosSimulatorArm64()
    jvmToolchain(21)
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest {
            dependencies {
                implementation("io.github.dokar3:quickjs-kt-jvm:1.0.15")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            }
            if (providers.gradleProperty("oracleOnly").isPresent) {
                kotlin.exclude("**/PlayerParityTest.kt", "**/PlayerBenchmark.kt")
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("quackjs.fixtures", layout.projectDirectory.dir("fixtures").asFile.absolutePath)
    maxHeapSize = "2g"
}

tasks.register<JavaExec>("playerBenchmark") {
    group = "verification"
    description = "Opt-in real-player Kotlin vs full-player QuickJS benchmark (requires parity)"
    dependsOn("jvmTestClasses")
    val testCompilation = kotlin.targets.getByName("jvm").compilations.getByName("test")
    classpath(testCompilation.output.allOutputs, testCompilation.runtimeDependencyFiles)
    mainClass.set("meow.nyx.quackjs.verification.PlayerBenchmarkKt")
    systemProperty("quackjs.fixtures", layout.projectDirectory.dir("fixtures").asFile.absolutePath)
    jvmArgs("-Xms512m", "-Xmx2g")
    doFirst {
        check(!providers.gradleProperty("oracleOnly").isPresent) { "playerBenchmark requires the core, not -PoracleOnly" }
    }
}
