rootProject.name = "servermine"
include(":economy:economy-api", ":economy:economy-plugin")
listOf("api", "core", "storage", "creation", "territory", "protection", "members", "roles", "treasury",
    "progression", "upgrades", "market", "diplomacy", "gui", "admin", "plugin").forEach {
    include(":cities:cities-$it")
}
include(":examples")
