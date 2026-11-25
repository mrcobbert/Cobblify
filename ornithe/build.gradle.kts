import java.util.Properties

plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.19.4"
    id("ploceus") version "1.19.2"
}

// The single version source is mod_version in the repository root gradle.properties (one level up
// from this build), the same file the Forge build and tools/check-release-version.sh read.
val rootGradleProperties = Properties().apply {
    rootDir.parentFile.resolve("gradle.properties").inputStream().use { load(it) }
}
val modVersion: String = rootGradleProperties.getProperty("mod_version")
    ?: error("mod_version missing from the root gradle.properties")
val modArchivesName: String = rootGradleProperties.getProperty("mod_archives_name")
    ?: error("mod_archives_name missing from the root gradle.properties")

group = "com.bedwarsqol"
version = modVersion

// Release jar: build/libs/Cobblify-1.8.9-ornithe-<mod_version>.jar (mirrors Forge's
// Cobblify-1.8.9-forge-<version>.jar). The unremapped jar carries the "dev" classifier.
base {
    archivesName.set("$modArchivesName-1.8.9-ornithe")
}

ploceus {
    setIntermediaryGeneration(2)
}

val oslVersion = "0.23.1"

dependencies {
    minecraft("com.mojang:minecraft:1.8.9")
    // MCP stable_22: the same names the Forge and Lunar trees compile against.
    mappings(ploceus.mcpMappings("stable", "1.8.9", "22"))
    modImplementation("net.fabricmc:fabric-loader:0.20.1")

    // The OSL modules Cobblify uses, plus the three they depend on (core, executors,
    // text-components) so the loader can resolve the dev runtime.
    for (module in listOf(
        "entrypoints", "lifecycle-events", "keybinds", "resource-loader", "networking",
        "core", "executors", "text-components",
    )) {
        ploceus.dependOslModule(oslVersion, module)
    }

    testImplementation("junit:junit:4.13.2")
}

loom {
    runConfigs.remove(runConfigs["server"])
    // No mixin refmap: Loom 1.19 remaps mixin annotation targets straight into the release jar
    // (tiny-remapper's mixin extension), so mixins.bedwarsqol.json names no refmap.
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

tasks.withType<Test>().configureEach {
    useJUnit()
}

// Bakes the optional owner backend into the jar as the `cobblify-backend.properties` resource read
// by com.bedwarsqol.stats.BackendDefaults. Values come from ~/.gradle/gradle.properties
// (cobblifyBackendUrl / cobblifyBackendToken) only — never the command line — and are never echoed.
// With the properties unset the resource carries empty values and the jar behaves exactly as a
// build without them (self-host / CI case).
val backendPropsDir = layout.buildDirectory.dir("generated/backendProps")
val generateBackendProperties by tasks.registering {
    val url = providers.gradleProperty("cobblifyBackendUrl").orElse("")
    val token = providers.gradleProperty("cobblifyBackendToken").orElse("")
    inputs.property("cobblifyBackendUrl", url)
    inputs.property("cobblifyBackendToken", token)
    outputs.dir(backendPropsDir)
    doLast {
        val file = backendPropsDir.get().file("cobblify-backend.properties").asFile
        file.parentFile.mkdirs()
        val props = Properties()
        props.setProperty("url", url.get())
        props.setProperty("token", token.get())
        file.outputStream().use { props.store(it, null) }
    }
}

// fabric.mod.json and com/bedwarsqol/version.properties carry the version (replaces Forge's
// Blossom @VER@ token; BedwarsQol.VERSION reads the properties file).
tasks.processResources {
    dependsOn(generateBackendProperties)
    inputs.property("version", modVersion)
    filesMatching(listOf("fabric.mod.json", "com/bedwarsqol/version.properties")) {
        expand("version" to modVersion)
    }
}

// Code that is byte-identical between the platform trees and imports nothing platform-specific
// lives once in `common/` (repo root, one level up from this build) and is compiled by every
// build, exactly as lunar/build.gradle.kts does it.
sourceSets {
    main {
        java.srcDir(rootDir.parentFile.resolve("common/src/main/java"))
        resources.srcDir(backendPropsDir)
    }
    test {
        java.srcDir(rootDir.parentFile.resolve("common/src/test/java"))
        resources.srcDir(rootDir.parentFile.resolve("common/src/test/resources"))
    }
}
