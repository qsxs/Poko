import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile

plugins {
    kotlin("multiplatform")
    `maven-publish`
}
fun readMap(file: File) = (JsonSlurper().parse(file) as Map<*, *>).entries.associate { it.key.toString() to it.value }
fun asMap(value: Any?) = (value as Map<*, *>).entries.associate { it.key.toString() to requireNotNull(it.value) }
fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
fun leaves(file: File): List<File> = if (file.isDirectory) file.walkTopDown().filter(File::isFile).toList() else if (file.isFile) listOf(file) else emptyList()
fun records(files: Iterable<File>) = files.flatMap(::leaves).distinct().sortedBy { it.path }.map {
    mapOf("path" to it.absolutePath, "size" to it.length(), "sha256" to sha(it))
}
val config = readMap(file("port-config.json"))
val sources = readMap(file("source-inputs.json"))
val tools = file(providers.gradleProperty("pokoTooling").get()).resolve("toolchains/kotlin-native/2.4.20-0.1.0-08")
val metadata = readMap(tools.resolve("metadata-toolchain.json"))
val commonizer = readMap(tools.resolve("commonizer-toolchain.json"))
val host = readMap(tools.resolve("native-compiler-toolchain.json"))
val commonizerPins = asMap(metadata.getValue("launcherPins")) + asMap(commonizer.getValue("pins"))
val commonizerArtifacts = asMap(metadata.getValue("launcherArtifacts")) + asMap(commonizer.getValue("artifacts"))
val hostPins = asMap(host.getValue("pins"))
val hostArtifacts = asMap(host.getValue("artifacts"))
val work = file(providers.gradleProperty("pokoWork").get()).canonicalFile
val fingerprint = providers.gradleProperty("pokoFingerprint").get()
val sourceRoot = file(sources.getValue("sourceRoot") as String).canonicalFile
val expectedSources = (sources.getValue("sourceMembers") as List<*>).map(::asMap).associate {
    sourceRoot.resolve(it.getValue("member").toString().removePrefix("commonMain/")).canonicalFile to it.getValue("sha256").toString()
}
check(expectedSources.size == 3 && fingerprint.matches(Regex("[0-9a-f]{64}")))
check(metadata["compilerVersion"] == config["kotlin"] && commonizer["compilerVersion"] == config["kotlin"] && host["compilerVersion"] == config["kotlin"])
group = config.getValue("group").toString()
version = providers.gradleProperty("pokoVersion").get()
layout.buildDirectory.set(work.resolve("build"))
kotlin {
    explicitApi()
    ohosX64()
    ohosArm64()
    sourceSets {
        val commonMain by getting { kotlin.setSrcDirs(listOf(sourceRoot)) }
        matching { it.name.endsWith("Test") }.configureEach { kotlin.setSrcDirs(emptyList<File>()) }
    }
}
configurations.configureEach {
    if (name == host["configuration"] || name == commonizer["configuration"]) {
        val fixedPins = if (name == host["configuration"]) hostPins else commonizerPins
        resolutionStrategy.eachDependency {
            val ga = "${requested.group}:${requested.name}"
            val fixed = fixedPins[ga]?.toString() ?: error("Unpinned compiler host dependency: $ga")
            if (requested.version != fixed) useVersion(fixed)
        }
    }
    val metadataConfiguration = name.endsWith("ResolvableDependenciesMetadata") || name == "allSourceSetsCompileDependenciesMetadata"
    val library = metadataConfiguration || name.endsWith("CompileKlibraries") || name.endsWith("CompilationDependenciesMetadata")
    if (library && !name.contains("Test", ignoreCase = true)) resolutionStrategy.eachDependency {
        val ga = "${requested.group}:${requested.name}"
        check(ga == "org.jetbrains.kotlin:kotlin-stdlib") { "Unexpected Poko library dependency: $requested" }
        if (requested.version != config["stdlib"]) useVersion(config.getValue("stdlib").toString())
    }
}
fun evidence(name: String, data: Any) {
    val target = work.resolve("evidence/$name.json")
    target.parentFile.mkdirs()
    target.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(data)) + "\n")
}
fun edges(configuration: Configuration) = configuration.incoming.resolutionResult.allDependencies.map { edge ->
    if (edge !is ResolvedDependencyResult) throw GradleException("Unresolved Poko dependency: $edge", (edge as? UnresolvedDependencyResult)?.failure)
    mapOf("from" to edge.from.id.displayName, "requested" to edge.requested.displayName, "selected" to edge.selected.id.displayName)
}
fun checkedTools(name: String, fixed: Map<String, Any>, expected: Map<String, Any>): Map<String, Any> {
    val configuration = configurations.getByName(name)
    val artifacts = configuration.resolvedConfiguration.resolvedArtifacts.map { artifact ->
        val id = artifact.moduleVersion.id
        val ga = "${id.group}:${id.name}"
        check(fixed[ga] == id.version) { "Host tool version differs: $id" }
        val item = asMap(expected.getValue(ga))
        check(sha(artifact.file) == item["sha256"] && artifact.file.length() == (item.getValue("size") as Number).toLong()) { "Host tool bytes differ: $id" }
        mapOf("gav" to id.toString(), "files" to records(listOf(artifact.file)))
    }
    check(artifacts.map { it.getValue("gav") }.toSet() == fixed.map { "${it.key}:${it.value}" }.toSet()) { "Host tool set differs: $name" }
    return mapOf("configuration" to name, "edges" to edges(configuration), "artifacts" to artifacts)
}
fun toolRecords() = mapOf("commonizer" to checkedTools(commonizer.getValue("configuration").toString(), commonizerPins, commonizerArtifacts),
    "nativeCompiler" to checkedTools(host.getValue("configuration").toString(), hostPins, hostArtifacts))
fun resolution() = listOf("commonMainResolvableDependenciesMetadata", "ohosX64CompileKlibraries", "ohosArm64CompileKlibraries").map { name ->
    val configuration = configurations.getByName(name)
    val artifacts = configuration.incoming.artifacts.artifacts.map { artifact ->
        val id = artifact.id.componentIdentifier
        check(id is ModuleComponentIdentifier && id.group == "org.jetbrains.kotlin" && id.module == "kotlin-stdlib" && id.version == config["stdlib"]) {
            "Unexpected Poko library artifact: $id"
        }
        mapOf("component" to id.displayName, "attributes" to artifact.variant.attributes.keySet().associate {
            it.name to artifact.variant.attributes.getAttribute(it).toString()
        }, "files" to records(listOf(artifact.file)))
    }
    mapOf("configuration" to name, "edges" to edges(configuration), "artifacts" to artifacts)
}
val verifyHost = tasks.register("verifyPokoHost") {
    outputs.upToDateWhen { false }
    doLast { evidence("host", mapOf("fingerprint" to fingerprint, "tools" to toolRecords())) }
}
tasks.withType<KotlinNativeCompile>().configureEach {
    if (name in listOf("compileCommonMainKotlinMetadata", "compileKotlinOhosX64", "compileKotlinOhosArm64")) dependsOn(verifyHost)
}
tasks.register("resolvePoko") {
    doLast {
        check(gradle.gradleVersion == config["gradle"])
        evidence("resolve", mapOf("fingerprint" to fingerprint, "tools" to toolRecords(), "configurations" to resolution()))
    }
}
fun recordCompilation(name: String, compilerName: String, stage: String) {
    tasks.register(name) {
        dependsOn(compilerName)
        if (stage == "metadata") dependsOn("allMetadataJar")
        outputs.upToDateWhen { false }
        doLast {
            val compiler = tasks.getByName(compilerName) as KotlinNativeCompile
            val actualSources = compiler.sources.files.filter { it.isFile && it.extension == "kt" }.map { it.canonicalFile }.toSet()
            check(actualSources == expectedSources.keys && expectedSources.all { (path, hash) -> sha(path) == hash }) { "Original Poko source set differs" }
            check(compiler.enabled && compiler.state.executed && !compiler.state.noSource &&
                compiler.state.skipMessage in listOf(null, "UP-TO-DATE", "FROM-CACHE")) { "Standard KGP compiler did not execute" }
            val outputs = records(compiler.outputs.files.files)
            check(outputs.any { (it.getValue("size") as Long) > 0 }) { "Compiler output is empty" }
            val metadataJar = if (stage == "metadata") tasks.getByName("allMetadataJar") else null
            if (metadataJar != null) check(metadataJar.state.executed && !metadataJar.state.noSource)
            evidence(stage, mapOf("fingerprint" to fingerprint, "stage" to stage, "task" to compiler.path,
                "sourceCount" to actualSources.size, "sources" to records(actualSources), "outputs" to outputs,
                "metadataJarOutputs" to if (metadataJar == null) emptyList<Any>() else records(metadataJar.outputs.files.files),
                "plugins" to records(compiler.compilerPluginClasspath?.files.orEmpty()), "pluginOptions" to compiler.compilerPluginCommandLine,
                "libraries" to compiler.libraries.files.sortedBy { it.path }.map { mapOf("path" to it.absolutePath, "files" to records(listOf(it))) },
                "runtime" to "NOT_RUN"))
        }
    }
}
recordCompilation("recordPokoMetadata", "compileCommonMainKotlinMetadata", "metadata")
recordCompilation("recordPokoX64", "compileKotlinOhosX64", "native-x64")
recordCompilation("recordPokoArm64", "compileKotlinOhosArm64", "native-arm64")
publishing {
    repositories { maven { name = "Port"; url = uri(providers.gradleProperty("pokoRepository").get()) } }
    publications.withType<MavenPublication>().configureEach {
        artifact(file("legal/LICENSE")) { classifier = "license"; extension = "txt" }
        if (name == "kotlinMultiplatform") {
            listOf("sources" to ("upstream-sources" to "jar"), "pom" to ("upstream-pom" to "xml"),
                "module" to ("upstream-module" to "json")).forEach { (key, format) ->
                artifact(file(asMap(sources.getValue(key)).getValue("path"))) { classifier = format.first; extension = format.second }
            }
        }
        pom {
            name.set("Poko Annotations")
            description.set("Unmodified Poko 0.23.1 annotations for CPF OHOS")
            licenses { license { name.set("The Apache Software License, Version 2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0.txt") } }
            scm { url.set("https://github.com/drewhamilton/Poko") }
        }
    }
}
tasks.withType<AbstractPublishToMaven>().configureEach {
    doFirst {
        listOf("sources", "pom", "module", "license").forEach { key ->
            val item = asMap(sources.getValue(key))
            check(sha(file(item.getValue("path"))) == item["sha256"]) { "Upstream provenance differs: $key" }
        }
        listOf("metadata", "native-x64", "native-arm64").forEach {
            check(readMap(work.resolve("evidence/$it.json"))["fingerprint"] == fingerprint) { "Compilation receipt differs: $it" }
        }
    }
}
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(file("legal/LICENSE")) { into("META-INF/licenses/poko-annotations") }
}
tasks.register("publishPoko") { dependsOn("publishAllPublicationsToPortRepository") }
