plugins {
    kotlin("multiplatform") version "2.4.20"
    `maven-publish`
}
group = "meow.nyx"
version = "0.1.0-SNAPSHOT"
// Any core source enables API compilation, so incomplete or broken declarations still fail after integration.
val hasCoreSources = fileTree("src/commonMain/kotlin") { include("**/*.kt") }.files.isNotEmpty()
if (!hasCoreSources) {
    logger.lifecycle("Core sources are absent: JVM verification is oracle-only; Kotlin player parity is not checked.")
}
kotlin {
    jvm()
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
            // Keep default JVM tests buildable before the separate core lane is integrated.
            if (!hasCoreSources || providers.gradleProperty("oracleOnly").isPresent) {
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
        check(hasCoreSources) { "playerBenchmark requires the pending commonMain core sources to be integrated" }
        check(!providers.gradleProperty("oracleOnly").isPresent) { "playerBenchmark requires the core, not -PoracleOnly" }
    }
}
