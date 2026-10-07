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

val repositoryDirectory = layout.projectDirectory.asFile
val releaseVersion = version.toString()
val verifyPackaging = tasks.register("verifyPackaging") {
    group = "verification"
    description = "Checks plugin boundaries and ensures prototype data is excluded from runtime jars."
    dependsOn(":economy:economy-plugin:jar", ":cities:cities-plugin:jar", resourcePack)
    doLast {
        fun entries(relative: String): Set<String> = java.util.zip.ZipFile(repositoryDirectory.resolve(relative)).use { zip ->
            zip.entries().asSequence().map { it.name }.toSet()
        }
        val economy = entries("economy/economy-plugin/build/libs/ServerMineEconomy-$releaseVersion.jar")
        val cities = entries("cities/cities-plugin/build/libs/ServerMineCities-$releaseVersion.jar")
        val pack = entries("build/distributions/ServerMine-ResourcePack.zip")
        check("plugin.yml" in economy && "plugin.yml" in cities)
        check("ru/servermine/economy/api/EconomyService.class" in economy)
        check("ru/servermine/economy/internal/ServerMineEconomyPlugin.class" in economy)
        check("ru/servermine/cities/api/CitiesService.class" in cities)
        check("ru/servermine/cities/plugin/ServerMineCitiesPlugin.class" in cities)
        check("ru/servermine/cities/territory/ClaimRules.class" in cities)
        check(cities.none { it.startsWith("ru/servermine/economy/") }) { "Cities must not bundle Economy classes" }
        check(economy.none { it.startsWith("ru/servermine/cities/") }) { "Economy must not bundle Cities classes" }
        check(cities.none { it.contains("DemoCityRepository") || it.startsWith("ru/servermine/gradostroygui/") || it == "demo-city.yml" })
        check("pack.mcmeta" in pack && "assets/gradostroy/font/gui.json" in pack)
        check(pack.count { it.startsWith("assets/gradostroy/textures/font/gui/") && it.endsWith(".png") } == 8)
    }
}
tasks.check { dependsOn(verifyPackaging) }
