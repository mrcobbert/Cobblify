import java.security.MessageDigest
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

// Rewind Export (PLAN decision D1): the host OS's encoder helper, built by `bash encoder/build.sh` into encoder/dist/,
// goes into the jar under com/bedwarsqol/rewind/export/helper/ with cobblify-encode.properties (sha256, size,
// protocol, os); HelperStore unpacks it from there. Without the file the jar still builds and loads, and Export
// fails as helper-missing; -PrequireEncodeHelper=true (the release flow) fails the build instead.
val encodeHelperDir = layout.buildDirectory.dir("generated/encodeHelper")
val encodeHelperOs: String? = System.getProperty("os.name").lowercase().let {
    when {
        it.startsWith("mac") -> "mac"
        it.startsWith("windows") -> "windows"
        else -> null
    }
}
val encodeHelperName = if (encodeHelperOs == "windows") "cobblify-encode.exe" else "cobblify-encode"
val encodeHelperFile = rootDir.parentFile.resolve("encoder/dist/$encodeHelperName")
val helperProtocolSource =
    rootDir.parentFile.resolve("common/src/main/java/com/bedwarsqol/rewind/export/HelperProtocol.java")
val generateEncodeHelper by tasks.registering {
    val require = providers.gradleProperty("requireEncodeHelper").map { it.toBoolean() }.orElse(false)
    inputs.property("requireEncodeHelper", require)
    inputs.property("os", encodeHelperOs ?: "none")
    inputs.files(encodeHelperFile, helperProtocolSource)
    outputs.dir(encodeHelperDir)
    doLast {
        val out = encodeHelperDir.get().asFile
        out.deleteRecursively()
        if (encodeHelperOs == null || !encodeHelperFile.isFile) {
            if (require.get()) {
                throw GradleException("requireEncodeHelper: $encodeHelperFile is missing (run bash encoder/build.sh)")
            }
            logger.lifecycle("No encoder helper at $encodeHelperFile: this jar's Rewind Export fails as helper-missing")
            return@doLast
        }
        val protocol = Regex("""PROTOCOL\s*=\s*(\d+)""").find(helperProtocolSource.readText())?.groupValues?.get(1)
            ?: throw GradleException("PROTOCOL not found in $helperProtocolSource")
        val bytes = encodeHelperFile.readBytes()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val dir = out.resolve("com/bedwarsqol/rewind/export/helper")
        dir.mkdirs()
        dir.resolve(encodeHelperName).writeBytes(bytes)
        dir.resolve("cobblify-encode.properties")
            .writeText("sha256=$sha256\nsize=${bytes.size}\nprotocol=$protocol\nos=$encodeHelperOs\n")
    }
}

// fabric.mod.json and com/bedwarsqol/version.properties carry the version (replaces Forge's
// Blossom @VER@ token; BedwarsQol.VERSION reads the properties file).
tasks.processResources {
    dependsOn(generateBackendProperties)
    dependsOn(generateEncodeHelper)
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
        resources.srcDir(encodeHelperDir)
    }
    test {
        java.srcDir(rootDir.parentFile.resolve("common/src/test/java"))
        resources.srcDir(rootDir.parentFile.resolve("common/src/test/resources"))
    }
}
