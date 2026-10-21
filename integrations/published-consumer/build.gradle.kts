plugins { kotlin("jvm") version "2.4.20" }
kotlin { jvmToolchain(21) }
dependencies {
    implementation("com.github.MetrolistGroup.innertubex:innertubex:v0.10.0-quackjs-SNAPSHOT")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-mock:3.6.0")
}
tasks.test {
    systemProperty("quackjs.fixtures", layout.projectDirectory.dir("../../fixtures").asFile.absolutePath)
    maxHeapSize = "2g"
}
