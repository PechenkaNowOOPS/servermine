plugins { `java-library` }
dependencies {
    implementation(project(":cities:cities-core"))
    implementation(project(":cities:cities-protection"))
    compileOnly(project(":economy:economy-api"))
}
