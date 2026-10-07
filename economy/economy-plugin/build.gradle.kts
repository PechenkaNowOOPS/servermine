plugins { java }
dependencies {
    implementation(project(":economy:economy-api"))
    compileOnly("org.purpurmc.purpur:purpur-api:" + property("purpurVersion"))
    testImplementation("org.purpurmc.purpur:purpur-api:" + property("purpurVersion"))
    testImplementation("org.xerial:sqlite-jdbc:" + property("sqliteVersion"))
    testImplementation("org.mockito:mockito-core:5.20.0")
}
val pluginProperties = mapOf("version" to version.toString(), "sqliteVersion" to property("sqliteVersion").toString())
tasks.processResources {
    inputs.properties(pluginProperties)
    filesMatching("plugin.yml") { expand(pluginProperties) }
}
tasks.test { jvmArgs("--enable-native-access=ALL-UNNAMED") }
tasks.jar {
    archiveBaseName.set("ServerMineEconomy")
    from(project(":economy:economy-api").extensions.getByType<SourceSetContainer>()["main"].output)
}
