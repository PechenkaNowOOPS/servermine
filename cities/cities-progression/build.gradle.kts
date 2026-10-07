plugins { `java-library` }
dependencies {
    implementation(project(":cities:cities-core"))
    compileOnly(project(":economy:economy-api"))
}
