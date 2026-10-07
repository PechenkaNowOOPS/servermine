plugins { java }
dependencies {
    implementation(project(":economy:economy-api"))
    compileOnly("org.purpurmc.purpur:purpur-api:" + property("purpurVersion"))
    testImplementation("org.purpurmc.purpur:purpur-api:" + property("purpurVersion"))
    testImplementation("org.xerial:sqlite-jdbc:" + property("sqliteVersion"))
    testImplementation("org.mockito:mockito-core:5.20.0")
}
tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("sqliteVersion", project.property("sqliteVersion"))
    filesMatching("plugin.yml") { expand(mapOf("version" to project.version, "sqliteVersion" to project.property("sqliteVersion"))) }
}
tasks.jar {
    archiveBaseName.set("ServerMineEconomy")
    from(project(":economy:economy-api").extensions.getByType<SourceSetContainer>()["main"].output)
}
