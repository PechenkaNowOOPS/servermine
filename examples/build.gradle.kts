plugins { java }
dependencies {
    compileOnly(project(":economy:economy-api"))
    compileOnly("org.purpurmc.purpur:purpur-api:" + property("purpurVersion"))
}
