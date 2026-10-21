pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement {
    repositories {
        mavenLocal {
            content {
                includeGroup("meow.nyx")
                includeGroup("com.github.MetrolistGroup.innertubex")
            }
        }
        mavenCentral()
        google()
    }
}
rootProject.name = "quackjs-published-consumer"
