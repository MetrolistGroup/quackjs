import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.4.20"
    id("com.android.kotlin.multiplatform.library") version "9.4.1"
    `maven-publish`
}
group = "com.github.MetrolistGroup.quackjs"
version = providers.gradleProperty("VERSION_NAME").orElse("0.1.0").get()
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
    // JitPack drops classifiers from source-variant URLs; publish sources as Maven attachments instead.
    withSourcesJar(publish = false)
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest {
            dependencies {
                implementation("io.github.dokar3:quickjs-kt-jvm:1.0.15")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            }
            if (providers.gradleProperty("oracleOnly").isPresent) {
                kotlin.exclude("**/PlayerParityTest.kt", "**/PlayerBenchmark.kt", "**/Lane32Acceptance*.kt")
            }
        }
    }
}

val releaseSourcesJar = tasks.register<Jar>("releaseSourcesJar") {
    archiveClassifier.set("sources")
    from(kotlin.sourceSets.commonMain.get().kotlin)
    from("LICENSE")
}

publishing.publications.withType<MavenPublication>().configureEach {
    artifact(releaseSourcesJar)
    pom {
        name.set("QuackJS")
        description.set("Kotlin-only YouTube player signature deciphering and n transforms")
        url.set("https://github.com/MetrolistGroup/quackjs")
        licenses {
            license {
                name.set("GNU General Public License, version 3 only")
                url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                distribution.set("repo")
            }
        }
        scm {
            url.set("https://github.com/MetrolistGroup/quackjs")
            connection.set("scm:git:https://github.com/MetrolistGroup/quackjs.git")
        }
    }
}

// Wait for Kotlin's publication components to finish their DSL lifecycle.
gradle.projectsEvaluated {
    publishing.publications.withType<MavenPublication>().configureEach {
        // JitPack drops the metadata classifier from URLs; retain a matching unclassified copy.
        artifacts.filter { it.classifier == "metadata" }.forEach { metadata ->
            artifact(metadata.file) { builtBy(metadata.buildDependencies) }
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

tasks.register<JavaExec>("lane32AcceptanceBenchmark") {
    group = "verification"
    description = "Opt-in per-kind/length warm timings and JVM allocation report with fresh QuickJS parity"
    dependsOn("jvmTestClasses")
    val testCompilation = kotlin.targets.getByName("jvm").compilations.getByName("test")
    classpath(testCompilation.output.allOutputs, testCompilation.runtimeDependencyFiles)
    mainClass.set("meow.nyx.quackjs.verification.Lane32AcceptanceBenchmarkKt")
    systemProperty("quackjs.fixtures", layout.projectDirectory.dir("fixtures").asFile.absolutePath)
    jvmArgs("-Xms512m", "-Xmx2g")
    doFirst {
        check(!providers.gradleProperty("oracleOnly").isPresent) { "lane32AcceptanceBenchmark requires the core, not -PoracleOnly" }
    }
}
