plugins { `java-library` }
dependencies {
    implementation(project(":cities:cities-core"))
    compileOnly("org.purpurmc.purpur:purpur-api:${property("purpurVersion")}")
}
