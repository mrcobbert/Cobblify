@file:Suppress("UnstableApiUsage", "PropertyName")

import org.polyfrost.gradle.util.noServerRunConfigs
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.Properties

// Adds support for kotlin, and adds the Polyfrost Gradle Toolkit
// which we use to prepare the environment.
plugins {
    kotlin("jvm")
    id("org.polyfrost.multi-version")
    id("org.polyfrost.defaults.repo")
    id("org.polyfrost.defaults.java")
    id("org.polyfrost.defaults.loom")
    id("com.github.johnrengelman.shadow")
    id("net.kyori.blossom") version "1.3.2"
    id("signing")
    java
}

// Gets the mod name, version and id from the `gradle.properties` file.
val mod_name: String by project
val mod_version: String by project
val mod_id: String by project
val mod_archives_name: String by project

// Replaces the variables in `ExampleMod.java` to the ones specified in `gradle.properties`.
blossom {
    replaceToken("@VER@", mod_version)
    replaceToken("@NAME@", mod_name)
    replaceToken("@ID@", mod_id)
}

// Sets the mod version to the one specified in `gradle.properties`. Make sure to change this following semver!
version = mod_version
// Sets the group, make sure to change this to your own. It can be a website you own backwards or your GitHub username.
// e.g. com.github.<your username> or com.<your domain>
group = "com.bedwarsqol"

// Sets the name of the output jar (the one you put in your mods folder and send to other people)
// It outputs all versions of the mod into the `versions/{mcVersion}/build` directory.
base {
    archivesName.set("$mod_archives_name-$platform")
}

// Configures Polyfrost Loom, our plugin fork to easily set up the programming environment.
loom {
    // Removes the server configs from IntelliJ IDEA, leaving only client runs.
    noServerRunConfigs()

    // Configures the client run for legacy Forge.
    if (project.platform.isLegacyForge) {
        runConfigs {
            "client" {
                property("mixin.debug.export", "true") // Outputs all mixin changes to `versions/{mcVersion}/run/.mixin.out/class`
            }
        }
    }
    // Configures the mixins if we are building for forge
    if (project.platform.isForge) {
        forge {
            mixinConfig("mixins.${mod_id}.json")
        }
    }
    // Configures the name of the mixin "refmap"
    mixin.defaultRefmapName.set("mixins.${mod_id}.refmap.json")
}

// Creates the shade/shadow configuration, so we can include libraries inside our mod, rather than having to add them separately.
val shade: Configuration by configurations.creating {
    configurations.implementation.get().extendsFrom(this)
}
val modShade: Configuration by configurations.creating {
    configurations.modImplementation.get().extendsFrom(this)
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

// Configures the output directory for when building from the `src/resources` directory.
sourceSets {
    main {
        output.setResourcesDir(java.classesDirectory)
        resources.srcDir(backendPropsDir)
        // Code that is byte-identical between the Forge and Lunar trees and imports nothing
        // platform-specific lives once in `common/` and is compiled by both builds. Added, not
        // replaced: the multi-version plugin sets this source set's srcDirs during plugin
        // application, so this runs after it. See tools/check-tree-drift.sh for what keeps the
        // still-mirrored files honest.
        java.srcDir(rootProject.file("common/src/main/java"))
    }
    test {
        java.srcDir(rootProject.file("common/src/test/java"))
        resources.srcDir(rootProject.file("common/src/test/resources"))
    }
}

// Adds the Polyfrost maven repository so that we can get the libraries necessary to develop the mod.
repositories {
    maven("https://repo.polyfrost.org/releases")
}

// Configures the libraries/dependencies for your mod.
dependencies {
    // JUnit 4 for the pure, Minecraft-free ChatHeadGrammar tests (see src/test). Java 8 compatible.
    testImplementation("junit:junit:4.13.2")

    // Adds DevAuth, which we can use to log in to Minecraft in development.
    modRuntimeOnly("me.djtheredstoner:DevAuth-${if (platform.isFabric) "fabric" else if (platform.isLegacyForge) "forge-legacy" else "forge-latest"}:1.2.0")

    // If we are building for legacy forge, bundle Mixin 0.7.11 into the jar so the mod
    // bootstraps Mixin itself (see the TweakClass manifest entry below) and works on a bare
    // Forge install with no separate Mixin loader. No transitives — Forge already ships
    // asm/guava/gson/log4j, and pulling them in would clash with the ones in the game.
    if (platform.isLegacyForge) {
        shade("org.spongepowered:mixin:0.7.11-SNAPSHOT") { isTransitive = false }
    }
}

tasks {
    // Processes the `src/resources/mcmod.info`, `fabric.mod.json`, or `mixins.${mod_id}.json` and replaces
    // the mod id, name and version with the ones in `gradle.properties`
    processResources {
        dependsOn(generateBackendProperties)
        inputs.property("id", mod_id)
        inputs.property("name", mod_name)
        val java = if (project.platform.mcMinor >= 18) {
            17 // If we are playing on version 1.18, set the java version to 17
        } else {
            // Else if we are playing on version 1.17, use java 16.
            if (project.platform.mcMinor == 17)
                16
            else
                8 // For all previous versions, we **need** java 8 (for Forge support).
        }
        val compatLevel = "JAVA_${java}"
        inputs.property("java", java)
        inputs.property("java_level", compatLevel)
        inputs.property("version", mod_version)
        inputs.property("mcVersionStr", project.platform.mcVersionStr)
        filesMatching(listOf("mcmod.info", "mixins.${mod_id}.json", "mods.toml")) {
            expand(
                mapOf(
                    "id" to mod_id,
                    "name" to mod_name,
                    "java" to java,
                    "java_level" to compatLevel,
                    "version" to mod_version,
                    "mcVersionStr" to project.platform.mcVersionStr
                )
            )
        }
        filesMatching("fabric.mod.json") {
            expand(
                mapOf(
                    "id" to mod_id,
                    "name" to mod_name,
                    "java" to java,
                    "java_level" to compatLevel,
                    "version" to mod_version,
                    "mcVersionStr" to project.platform.mcVersionStr.substringBeforeLast(".") + ".x"
                )
            )
        }
    }

    // Configures the resources to include if we are building for forge or fabric.
    withType(Jar::class.java) {
        if (project.platform.isFabric) {
            exclude("mcmod.info", "mods.toml")
        } else {
            exclude("fabric.mod.json")
            if (project.platform.isLegacyForge) {
                exclude("mods.toml")
            } else {
                exclude("mcmod.info")
            }
        }
    }

    // Configures our shadow/shade configuration, so we can
    // include some dependencies within our mod jar file.
    named<ShadowJar>("shadowJar") {
        archiveClassifier.set("dev")
        configurations = listOf(shade, modShade)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    remapJar {
        inputFile.set(shadowJar.get().archiveFile)
        archiveClassifier.set("")
    }

    jar {
        // Sets the jar manifest attributes.
        if (platform.isLegacyForge) {
            manifest.attributes += mapOf(
                "ModSide" to "CLIENT", // We aren't developing a server-side mod
                "ForceLoadAsMod" to true, // We want to load this jar as a mod, so we force Forge to do so.
                // Bootstraps the bundled SpongePowered Mixin without a separate loader mod.
                // ForceLoadAsMod above makes Forge still load us as a normal mod despite the tweaker.
                "TweakClass" to "org.spongepowered.asm.launch.MixinTweaker",
                "MixinConfigs" to "mixins.${mod_id}.json" // We want to use our mixin configuration, so we specify it here.
            )
        }
        dependsOn(shadowJar)
        archiveClassifier.set("")
        enabled = false
    }
}
