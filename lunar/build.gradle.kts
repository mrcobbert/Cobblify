import java.util.Properties

plugins {
    id("net.weavemc.gradle") version "1.3.3"
}

group = "com.bedwarsqol"
version = "0.8.0"

weave {
    configure {
        name = "Cobblify"
        modId = "bedwarsqol"
        entryPoints = listOf("com.bedwarsqol.BedwarsQol")
        mixinConfigs = listOf("mixins.bedwarsqol.json")
        mcpMappings()
    }
    version("1.8.9")
}

repositories {
    maven("https://repo.spongepowered.org/maven/")
    // Weave packages: https://gitlab.com/weave-mc/weave/-/packages/
    maven("https://gitlab.com/api/v4/projects/80566527/packages/maven")
}

dependencies {
    implementation("net.weavemc:loader:1.3.3")
    implementation("net.weavemc:internals:1.3.3")
    implementation("net.weavemc.api:api:1.3.3")
    implementation("net.weavemc.api:api-v1_8:1.3.3") // 1.8 events
    compileOnly("org.spongepowered:mixin:0.8.5")

    // Provided by Lunar/Minecraft at runtime — compileOnly so they aren't bundled into the mod jar.
    // Versions match what 1.8.9 ships.
    compileOnly("org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209")
    compileOnly("com.google.code.gson:gson:2.2.4")
    compileOnly("com.mojang:authlib:1.5.21")

    // Pure-logic unit tests (JUnit 4, Java-8 compatible). gson is compileOnly above, so it must be on
    // the test classpath explicitly for ScraperBackendClient's parse tests to run.
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.code.gson:gson:2.2.4")
}

tasks.withType<Test>().configureEach {
    useJUnit()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
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

tasks.processResources {
    dependsOn(generateBackendProperties)
}

// Code that is byte-identical between the Forge and Lunar trees and imports nothing
// platform-specific lives once in `common/` (repo root, one level up from this build) and is
// compiled by both. See tools/check-tree-drift.sh for what keeps the still-mirrored files honest.
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
