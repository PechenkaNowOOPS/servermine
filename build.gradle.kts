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
val verifyPackaging = tasks.register("verifyPackaging") {
    group = "verification"
    description = "Verify standalone Economy plugin packaging."
    dependsOn(":economy:economy-plugin:jar")
    doLast {
        val jarFile = layout.projectDirectory.file("economy/economy-plugin/build/libs/ServerMineEconomy-${version}.jar").asFile
        val entries = java.util.zip.ZipFile(jarFile).use { zip ->
            zip.entries().asSequence().map { it.name }.toSet()
        }
        check("plugin.yml" in entries)
        check("ru/servermine/economy/api/EconomyService.class" in entries)
        check("ru/servermine/economy/internal/ServerMineEconomyPlugin.class" in entries)
        check(entries.none { it.startsWith("ru/servermine/cities/") || it.startsWith("ru/servermine/gradostroygui/") })
    }
}
tasks.check { dependsOn(verifyPackaging) }
