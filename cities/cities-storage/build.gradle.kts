plugins { `java-library` }
dependencies {
    implementation(project(":cities:cities-core"))
    compileOnly("org.xerial:sqlite-jdbc:" + property("sqliteVersion"))
    testRuntimeOnly("org.xerial:sqlite-jdbc:" + property("sqliteVersion"))
}
