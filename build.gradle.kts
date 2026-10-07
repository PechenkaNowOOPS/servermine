import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test

plugins { base }
allprojects {
    group = "ru.servermine"
    version = "0.1.0-SNAPSHOT"
    repositories {
        mavenCentral()
        maven("https://repo.purpurmc.org/snapshots")
    }
}
subprojects {
    // Grouping projects carry no code or artifacts.
    if (path.count { it == ':' } >= 2 || name == "examples") {
        apply(plugin = "java-library")
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(25))
            withSourcesJar()
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(25)
        }
        dependencies {
            add("testImplementation", platform("org.junit:junit-bom:5.13.4"))
            add("testImplementation", "org.junit.jupiter:junit-jupiter")
            add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
        }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }
        tasks.withType<ProcessResources>().configureEach { filteringCharset = "UTF-8" }
    }
}
val resourcePack = tasks.register<Zip>("resourcePack") {
    from("resource-pack") { exclude("README.md") }
    archiveFileName.set("ServerMine-ResourcePack.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.assemble { dependsOn(resourcePack) }
