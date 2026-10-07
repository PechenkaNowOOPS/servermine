plugins { `java-library` }
dependencies {
    compileOnly("org.purpurmc.purpur:purpur-api:${property("purpurVersion")}")
    implementation(project(":cities:cities-api"))
    implementation(project(":cities:cities-core"))
    implementation(project(":cities:cities-storage"))
    implementation(project(":cities:cities-creation"))
    implementation(project(":cities:cities-territory"))
    implementation(project(":cities:cities-protection"))
    implementation(project(":cities:cities-members"))
    implementation(project(":cities:cities-roles"))
    implementation(project(":cities:cities-treasury"))
    implementation(project(":cities:cities-progression"))
    implementation(project(":cities:cities-upgrades"))
    implementation(project(":cities:cities-market"))
    implementation(project(":cities:cities-diplomacy"))
    implementation(project(":cities:cities-gui"))
    implementation(project(":cities:cities-admin"))
    compileOnly(project(":economy:economy-api"))
}
val pluginVersion = version.toString()
val pluginProperties = mapOf("version" to pluginVersion, "sqliteVersion" to property("sqliteVersion").toString())
tasks.processResources {
    inputs.properties(pluginProperties)
    filesMatching("plugin.yml") { expand(pluginProperties) }
}
tasks.jar {
    archiveBaseName.set("ServerMineCities")
    val modules = listOf("api", "core", "storage", "creation", "territory", "protection", "members", "roles", "treasury", "progression", "upgrades", "market", "diplomacy", "gui", "admin")
    modules.forEach { module ->
        from(project(":cities:cities-$module").extensions.getByType<SourceSetContainer>()["main"].output)
    }
    duplicatesStrategy = DuplicatesStrategy.FAIL
}
