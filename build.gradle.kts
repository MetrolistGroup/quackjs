plugins {
    kotlin("multiplatform") version "2.4.20"
    `maven-publish`
}
group = "meow.nyx"
version = "0.1.0-SNAPSHOT"
kotlin {
    jvm()
    linuxX64()
    iosArm64()
    iosSimulatorArm64()
    jvmToolchain(21)
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
