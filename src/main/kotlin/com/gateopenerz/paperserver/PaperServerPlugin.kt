package com.gateopenerz.paperserver

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.register
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import javax.inject.Inject

@Suppress("unused", "unchecked_cast")
abstract class PaperServerPlugin @Inject constructor(
    private val toolchains: JavaToolchainService
) : Plugin<Project> {

    private val gson = Gson()

    override fun apply(project: Project) {
        with(project) {
            val ext = extensions.create("paperServer", PaperServerExtension::class.java)

            val setup = tasks.register("setupPaperServer") {
                group = "paper"
                description = "Download latest server build, plugins and accept EULA"
                doLast {
                    val serverDirFile = layout.projectDirectory.dir(ext.serverDir.get()).asFile
                    val pluginsDir = File(serverDirFile, "plugins").apply { mkdirs() }

                    downloadServer(project, ext)
                    downloadUrlPlugins(project, ext, pluginsDir)
                    downloadNamedPlugins(project, ext, pluginsDir)

                    if (ServerType.fromString(ext.serverType.get()) == ServerType.ADVANCED_SLIME_PAPER
                        && ext.includeAspPlugin.getOrElse(true)) {
                        downloadAspPlugin(project, ext, pluginsDir)
                    }

                    acceptEula(serverDirFile)
                }
            }

            val runServer = tasks.register<JavaExec>("runPaperServer") {
                group = "paper"
                description = "Start server with configured JVM args"
                dependsOn(setup)
                javaLauncher.set(toolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(21))
                })

                doFirst {
                    val serverDirFile = layout.projectDirectory.dir(ext.serverDir.get()).asFile
                    val serverType = ServerType.fromString(ext.serverType.get())
                    val versionInput = ext.version.get()

                    val (version, buildFromVersion) = parseVersionAndBuild(versionInput)
                    val buildNumber = when {
                        ext.build.isPresent -> ext.build.get()
                        buildFromVersion != null -> buildFromVersion
                        else -> null
                    }

                    val jar = findServerJar(serverDirFile, serverType, version, buildNumber)
                        ?: throw GradleException(
                            "${serverType.name} jar for $version" + (buildNumber?.let { " build $it" } ?: "") + " not found in $serverDirFile – run :setupPaperServer."
                        )

                    val flags = ext.jvmArgs.get().split("\\s+".toRegex()).filter(String::isNotBlank)
                    jvmArgs(flags)
                    workingDir = serverDirFile
                    mainClass.set("-jar")
                    classpath = files()
                    args(jar.absolutePath, "--nogui")

                    logger.lifecycle(">>> Launching ${serverType.name} with JVM args: ${flags.joinToString(" ")}")
                    logger.lifecycle(">>> Using jar: ${jar.name}")
                    if (ext.interactiveConsole.get()) {
                        logger.lifecycle(">>> Server console is interactive. Type 'stop' to shut down.")
                    } else {
                        logger.lifecycle(">>> Server console is non-interactive.")
                    }
                }
            }

            afterEvaluate {
                ext.preLaunchTasks.get().forEach { taskName ->
                    runServer.configure { dependsOn(taskName) }
                }
                if (ext.interactiveConsole.get()) {
                    runServer.configure { standardInput = System.`in` }
                }
            }
        }
    }

    private fun downloadServer(project: Project, ext: PaperServerExtension) {
        val serverType = ServerType.fromString(ext.serverType.get())
        val serverDir = project.layout.projectDirectory.dir(ext.serverDir.get()).asFile

        cleanupDifferentServerTypeJars(serverDir, serverType)

        when (serverType) {
            ServerType.PAPER -> downloadPaperMC(project, ext, "paper")
            ServerType.VELOCITY -> downloadPaperMC(project, ext, "velocity")
            ServerType.FOLIA -> downloadPaperMC(project, ext, "folia")
            ServerType.PURPUR -> downloadPurpur(project, ext)
            ServerType.ADVANCED_SLIME_PAPER -> downloadAdvancedSlimePaper(project, ext)
        }
    }

    private fun cleanupDifferentServerTypeJars(serverDir: File, currentServerType: ServerType) {
        val allServerJarPatterns = mapOf(
            ServerType.PAPER to "paper-",
            ServerType.VELOCITY to "velocity-",
            ServerType.FOLIA to "folia-",
            ServerType.PURPUR to "purpur-",
            ServerType.ADVANCED_SLIME_PAPER to "asp-"
        )

        val currentPattern = allServerJarPatterns[currentServerType]

        serverDir.listFiles { f ->
            f.isFile && f.name.endsWith(".jar") &&
                    allServerJarPatterns.values.any { pattern -> f.name.startsWith(pattern) } &&
                    !f.name.startsWith(currentPattern!!)
        }?.forEach { jar ->
            println("Removing jar from different server type: ${jar.name}")
            jar.delete()
        }
    }

    private fun cleanupOldServerJarsOfSameType(serverDir: File, serverType: ServerType, version: String, buildNumber: String?) {
        val pattern = when (serverType) {
            ServerType.PAPER -> "paper-"
            ServerType.VELOCITY -> "velocity-"
            ServerType.FOLIA -> "folia-"
            ServerType.PURPUR -> "purpur-"
            ServerType.ADVANCED_SLIME_PAPER -> "asp-"
        }

        val targetJarName = when (serverType) {
            ServerType.ADVANCED_SLIME_PAPER -> null
            else -> if (buildNumber != null) {
                "${pattern}${version}-${buildNumber}.jar"
            } else {
                "${pattern}${version}-"
            }
        }

        serverDir.listFiles { f ->
            f.isFile && f.name.endsWith(".jar") && f.name.startsWith(pattern) &&
                    if (targetJarName != null) {
                        if (buildNumber != null) {
                            f.name != targetJarName
                        } else {
                            !f.name.startsWith(targetJarName)
                        }
                    } else {
                        true
                    }
        }?.forEach { jar ->
            println("Removing old server jar: ${jar.name}")
            jar.delete()
        }
    }

    private fun downloadPaperMC(project: Project, ext: PaperServerExtension, projectName: String) {
        val versionInput = ext.version.get()
        val serverDir = project.layout.projectDirectory.dir(ext.serverDir.get()).asFile

        val (version, buildFromVersion) = parseVersionAndBuild(versionInput)

        val requestedBuild = when {
            ext.build.isPresent -> {
                val specifiedBuild = ext.build.get()
                println("Using explicitly specified build: $specifiedBuild")
                specifiedBuild
            }
            buildFromVersion != null -> {
                println("Using build from version string: $buildFromVersion")
                buildFromVersion
            }
            else -> {
                println("Using latest build")
                null
            }
        }

        val build = resolvePaperMCBuild(projectName, version, requestedBuild)
        val jarFile = File(serverDir, build.jarName)

        serverDir.mkdirs()
        if (!jarFile.exists()) {
            cleanupOldServerJarsOfSameType(serverDir, ServerType.fromString(ext.serverType.get()), version, build.number.toString())
            println("Downloading $projectName $version build ${build.number} …")
            downloadFile(build.url, jarFile)
            println("Saved → ${jarFile.relativeTo(project.projectDir)}")
        } else {
            println("$projectName $version build ${build.number} already present.")
        }
    }

    private fun downloadPurpur(project: Project, ext: PaperServerExtension) {
        val versionInput = ext.version.get()
        val serverDir = project.layout.projectDirectory.dir(ext.serverDir.get()).asFile

        serverDir.mkdirs()

        val (version, buildFromVersion) = parseVersionAndBuild(versionInput)

        val buildNumber = when {
            ext.build.isPresent -> {
                val specifiedBuild = ext.build.get()
                println("Using explicitly specified Purpur build: $specifiedBuild")
                specifiedBuild
            }
            buildFromVersion != null -> {
                println("Using Purpur build from version string: $buildFromVersion")
                buildFromVersion
            }
            else -> {
                val buildsUrl = "https://api.purpurmc.org/v2/purpur/$version"
                val buildsConnection = openUrlConnection(buildsUrl)

                if (buildsConnection.responseCode >= 400) {
                    throw GradleException("Failed to get Purpur builds for version $version: HTTP ${buildsConnection.responseCode}")
                }

                val buildsResult = gson.fromJson<Map<String, Any>>(
                    InputStreamReader(buildsConnection.inputStream),
                    object : TypeToken<Map<String, Any>>() {}.type
                )

                val builds = buildsResult["builds"] as? Map<String, Any>
                val latestBuild = builds?.get("latest") as? String ?: throw GradleException("Could not get latest Purpur build")
                println("Using latest Purpur build: $latestBuild")
                latestBuild
            }
        }

        val jarName = "purpur-$version-$buildNumber.jar"
        val jarFile = File(serverDir, jarName)

        if (!jarFile.exists()) {
            cleanupOldServerJarsOfSameType(serverDir, ServerType.PURPUR, version, buildNumber)
            val url = "https://api.purpurmc.org/v2/purpur/$version/$buildNumber/download"
            println("Downloading Purpur $version build $buildNumber …")
            downloadFile(url, jarFile)
            println("Saved → ${jarFile.relativeTo(project.projectDir)}")
        } else {
            println("Purpur $version build $buildNumber already present.")
        }
    }

    private fun downloadAdvancedSlimePaper(project: Project, ext: PaperServerExtension) {
        val version = ext.version.get()
        val branch = ext.aspBranch.get()
        val serverDir = project.layout.projectDirectory.dir(ext.serverDir.get()).asFile
        serverDir.mkdirs()
        try {
            val branchesUrl = "https://api.infernalsuite.com/v1/projects/asp/mcversion/$version/branches"
            val branchesConnection = openUrlConnection(branchesUrl)
            if (branchesConnection.responseCode >= 400) {
                throw GradleException("Failed to get ASP branches for version $version: HTTP ${branchesConnection.responseCode}")
            }
            val branchesResult = gson.fromJson<List<Map<String, Any>>>(
                InputStreamReader(branchesConnection.inputStream),
                object : TypeToken<List<Map<String, Any>>>() {}.type
            )
            val branchBuild = branchesResult.firstOrNull {
                (it["branch"] as? String) == branch
            } ?: throw GradleException("No builds found for ASP branch '$branch' on version $version")

            val buildId = branchBuild["id"] as? String
                ?: throw GradleException("Could not get ASP build ID for branch '$branch'")

            val buildUrl = "https://api.infernalsuite.com/v1/projects/asp/buildId/$buildId"
            val buildConnection = openUrlConnection(buildUrl)
            if (buildConnection.responseCode >= 400) {
                throw GradleException("Failed to get ASP build info: HTTP ${buildConnection.responseCode}")
            }
            val buildResult = gson.fromJson<Map<String, Any>>(
                InputStreamReader(buildConnection.inputStream),
                object : TypeToken<Map<String, Any>>() {}.type
            )
            val files = buildResult["files"] as? List<Map<String, Any>>
                ?: throw GradleException("No files found in ASP build")
            val serverFile = files.firstOrNull { file ->
                val fileName = file["fileName"] as? String ?: ""
                fileName.endsWith(".jar") &&
                        !fileName.contains("plugin", ignoreCase = true) &&
                        !fileName.contains("api", ignoreCase = true)
            } ?: files.filter { file ->
                val fileName = file["fileName"] as? String ?: ""
                fileName.endsWith(".jar")
            }.maxByOrNull { file ->
                (file["size"] as? Number)?.toLong() ?: 0L
            } ?: throw GradleException("No server jar found in ASP build")
            val fileId = serverFile["id"] as? String
                ?: throw GradleException("No file ID found for ASP server jar")
            val fileName = serverFile["fileName"] as? String ?: "asp-$version-$buildId.jar"
            val jarFile = File(serverDir, fileName)
            if (!jarFile.exists()) {
                cleanupOldServerJarsOfSameType(serverDir, ServerType.ADVANCED_SLIME_PAPER, version, null)
                val downloadUrl = "https://api.infernalsuite.com/v1/projects/asp/$buildId/download/$fileId"
                println("Downloading Advanced Slime Paper $version build $buildId from $branch branch: $fileName")
                downloadFile(downloadUrl, jarFile)
                println("Saved → ${jarFile.relativeTo(project.projectDir)}")
            } else {
                println("Advanced Slime Paper $fileName already present.")
            }
        } catch (e: Exception) {
            throw GradleException("Failed to download Advanced Slime Paper: ${e.message}. You can download manually from https://infernalsuite.com/download/asp/", e)
        }
    }

    private fun downloadAspPlugin(project: Project, ext: PaperServerExtension, pluginsDir: File) {
        val version = ext.version.get()
        val branch = ext.aspBranch.get()
        try {
            val branchesUrl = "https://api.infernalsuite.com/v1/projects/asp/mcversion/$version/branches"
            val branchesConnection = openUrlConnection(branchesUrl)
            if (branchesConnection.responseCode >= 400) {
                project.logger.warn("Failed to get ASP plugin branches: HTTP ${branchesConnection.responseCode}")
                return
            }
            val branchesResult = gson.fromJson<List<Map<String, Any>>>(
                InputStreamReader(branchesConnection.inputStream),
                object : TypeToken<List<Map<String, Any>>>() {}.type
            )
            val branchBuild = branchesResult.firstOrNull {
                (it["branch"] as? String) == branch
            }
            if (branchBuild == null) {
                project.logger.warn("No ASP plugin builds found for branch '$branch' on version $version")
                return
            }
            val buildId = branchBuild["id"] as? String ?: return
            val buildUrl = "https://api.infernalsuite.com/v1/projects/asp/buildId/$buildId"
            val buildConnection = openUrlConnection(buildUrl)
            if (buildConnection.responseCode >= 400) {
                project.logger.warn("Failed to get ASP plugin build info: HTTP ${buildConnection.responseCode}")
                return
            }
            val buildResult = gson.fromJson<Map<String, Any>>(
                InputStreamReader(buildConnection.inputStream),
                object : TypeToken<Map<String, Any>>() {}.type
            )
            val files = buildResult["files"] as? List<Map<String, Any>> ?: return
            val pluginFile = files.firstOrNull { file ->
                val fileName = file["fileName"] as? String ?: ""
                fileName.contains("plugin", ignoreCase = true) && fileName.endsWith(".jar")
            }
            if (pluginFile != null) {
                val fileId = pluginFile["id"] as? String
                val fileName = pluginFile["fileName"] as? String ?: "asp-plugin.jar"
                val pluginDestFile = File(pluginsDir, fileName)
                if (!pluginDestFile.exists() && fileId != null) {
                    val downloadUrl = "https://api.infernalsuite.com/v1/projects/asp/$buildId/download/$fileId"
                    println("Downloading ASP Slime World Plugin from $branch branch: $fileName")
                    downloadFile(downloadUrl, pluginDestFile)
                    println("Saved ASP plugin → ${pluginDestFile.relativeTo(project.projectDir)}")
                } else if (pluginDestFile.exists()) {
                    println("ASP Slime World Plugin $fileName already present.")
                }
            } else {
                project.logger.warn("No ASP plugin found in build for version $version on branch $branch")
            }
        } catch (e: Exception) {
            project.logger.warn("Failed to download ASP plugin: ${e.message}")
        }
    }

    private fun findServerJar(serverDir: File, serverType: ServerType, version: String, buildNumber: String? = null): File? {
        return when (serverType) {
            ServerType.PAPER -> {
                if (buildNumber != null) {
                    File(serverDir, "paper-$version-$buildNumber.jar").takeIf { it.exists() }
                } else {
                    serverDir.listFiles { f ->
                        f.isFile && f.name.startsWith("paper-$version-") && f.name.endsWith(".jar")
                    }?.singleOrNull()
                }
            }

            ServerType.PURPUR -> {
                if (buildNumber != null) {
                    File(serverDir, "purpur-$version-$buildNumber.jar").takeIf { it.exists() }
                } else {
                    serverDir.listFiles { f ->
                        f.isFile && f.name.startsWith("purpur-$version-") && f.name.endsWith(".jar")
                    }?.singleOrNull()
                }
            }

            ServerType.VELOCITY -> {
                if (buildNumber != null) {
                    File(serverDir, "velocity-$version-$buildNumber.jar").takeIf { it.exists() }
                } else {
                    serverDir.listFiles { f ->
                        f.isFile && f.name.startsWith("velocity-$version-") && f.name.endsWith(".jar")
                    }?.singleOrNull()
                }
            }

            ServerType.FOLIA -> {
                if (buildNumber != null) {
                    File(serverDir, "folia-$version-$buildNumber.jar").takeIf { it.exists() }
                } else {
                    serverDir.listFiles { f ->
                        f.isFile && f.name.startsWith("folia-$version-") && f.name.endsWith(".jar")
                    }?.singleOrNull()
                }
            }

            ServerType.ADVANCED_SLIME_PAPER -> serverDir.listFiles { f ->
                f.isFile && f.name.startsWith("asp-") && f.name.endsWith(".jar")
            }?.singleOrNull()
        }
    }

    private fun downloadUrlPlugins(project: Project, ext: PaperServerExtension, pluginsDir: File) {
        ext.pluginUrls.get().forEach { pluginUrl ->
            val fileName = pluginUrl.substring(pluginUrl.lastIndexOf('/') + 1)
            val pluginFile = File(pluginsDir, fileName)
            if (!pluginFile.exists()) {
                println("Downloading plugin from URL: $fileName")
                downloadFile(pluginUrl, pluginFile)
                println("Saved plugin → ${pluginFile.relativeTo(project.projectDir)}")
            } else {
                println("Plugin $fileName already present.")
            }
        }
    }

    private fun downloadNamedPlugins(project: Project, ext: PaperServerExtension, pluginsDir: File) {
        val fullMcVersion = ext.version.get()
        ext.plugins.get().forEach { pluginIdentifier ->
            val parts = pluginIdentifier.split(":")
            val source: String?
            val pluginName: String
            val pluginVersion: String?
            when {
                parts.size >= 3 && parts[0] in listOf("hangar", "modrinth") -> {
                    source = parts[0]
                    pluginName = parts[1]
                    pluginVersion = parts.drop(2).joinToString(":")
                }
                parts.size == 2 && parts[0] in listOf("hangar", "modrinth") -> {
                    source = parts[0]
                    pluginName = parts[1]
                    pluginVersion = null
                }
                else -> {
                    source = null
                    pluginName = parts[0]
                    pluginVersion = null
                }
            }
            println("Searching for plugin: $pluginName" + (source?.let { " on $it" } ?: "") + (pluginVersion?.let { " version $it" } ?: ""))
            val url = findPluginUrl(project, pluginName, fullMcVersion, source, pluginVersion)
            if (url != null) {
                val fileName = url.substring(url.lastIndexOf('/') + 1)
                val pluginFile = File(pluginsDir, fileName)
                if (!pluginFile.exists()) {
                    println("Downloading plugin: $fileName from $url")
                    downloadFile(url, pluginFile)
                    println("Saved plugin → ${pluginFile.relativeTo(project.projectDir)}")
                } else {
                    println("Plugin $fileName already present.")
                }
            } else {
                project.logger.warn("Could not find a download for plugin '$pluginName' " + (pluginVersion?.let { "version $it " } ?: "") + "for Minecraft $fullMcVersion" + (source?.let { " on $it" } ?: "."))
            }
        }
    }

    private fun findPluginUrl(project: Project, pluginName: String, mcVersion: String, source: String?, pluginVersion: String?): String? {
        return when (source) {
            "hangar" -> findOnHangar(project, pluginName, mcVersion, pluginVersion)
            "modrinth" -> findOnModrinth(project, pluginName, mcVersion, pluginVersion)
            null -> findOnHangar(project, pluginName, mcVersion, pluginVersion) ?: findOnModrinth(project, pluginName, mcVersion, pluginVersion)
            else -> null
        }
    }

    private fun findOnHangar(project: Project, pluginName: String, mcVersion: String, pluginVersion: String?): String? {
        try {
            val encodedName = URLEncoder.encode(pluginName, "UTF-8")
            val searchUrl = "https://hangar.papermc.io/api/v1/projects?q=$encodedName&limit=1"
            val connection = openUrlConnection(searchUrl)
            if (connection.responseCode >= 400) {
                project.logger.warn("Hangar API returned HTTP ${connection.responseCode} for search: ${connection.responseMessage}")
                return null
            }
            val searchResult = gson.fromJson<Map<String, Any>>(
                InputStreamReader(connection.inputStream),
                object : TypeToken<Map<String, Any>>() {}.type
            )

            val plugin = (searchResult["result"] as? List<Map<String, Any>>)?.firstOrNull() ?: return null
            val namespace = plugin["namespace"] as? Map<String, String>
            val owner = namespace?.get("owner")
            val slug = namespace?.get("slug")
            val finalSlug = slug ?: plugin["slug"] as? String
            if (owner == null || finalSlug == null) {
                project.logger.warn("Could not extract owner/slug from Hangar project response for $pluginName")
                return null
            }
            val versionsUrl = "https://hangar.papermc.io/api/v1/projects/$owner/$finalSlug/versions?limit=100"
            val versionsConnection = openUrlConnection(versionsUrl)
            if (versionsConnection.responseCode >= 400) {
                project.logger.warn("Hangar API returned HTTP ${versionsConnection.responseCode} for versions: ${versionsConnection.responseMessage}")
                return null
            }
            val versionsResult = gson.fromJson<Map<String, Any>>(
                InputStreamReader(versionsConnection.inputStream),
                object : TypeToken<Map<String, Any>>() {}.type
            )

            val allVersions = versionsResult["result"] as? List<Map<String, Any>> ?: emptyList()
            val targetVersion = if (pluginVersion != null) {
                var found = allVersions.firstOrNull { it["name"] as? String == pluginVersion }
                if (found == null && pluginVersion.contains("SNAPSHOT", ignoreCase = true)) {
                    found = allVersions.firstOrNull {
                        (it["name"] as? String)?.startsWith(pluginVersion) == true
                    }
                }
                found
            } else {
                allVersions.firstOrNull {
                    (it["platformDependencies"] as? Map<String, List<String>>)?.get("PAPER")?.contains(mcVersion) == true
                }
            }

            return (targetVersion?.get("downloads") as? Map<String, Map<String, String>>)?.get("PAPER")?.get("downloadUrl")
        } catch (e: Exception) {
            project.logger.warn("Hangar search failed for $pluginName: ${e.message}")
            return null
        }
    }

    private fun findOnModrinth(project: Project, pluginName: String, mcVersion: String, pluginVersion: String?): String? {
        try {
            val encodedName = URLEncoder.encode(pluginName, "UTF-8")
            val facets = URLEncoder.encode("[[\"project_type:plugin\"],[\"categories:paper\"]]", "UTF-8")
            val searchUrl = "https://api.modrinth.com/v2/search?query=$encodedName&facets=$facets&limit=1"
            val connection = openUrlConnection(searchUrl)
            if (connection.responseCode >= 400) {
                project.logger.warn("Modrinth API returned HTTP ${connection.responseCode}: ${connection.responseMessage}")
                return null
            }
            val searchResult = gson.fromJson<Map<String, Any>>(
                InputStreamReader(connection.inputStream),
                object : TypeToken<Map<String, Any>>() {}.type
            )

            val hit = (searchResult["hits"] as? List<Map<String, Any>>)?.firstOrNull() ?: return null
            val projectId = hit["project_id"] as String
            val versionsUrl = "https://api.modrinth.com/v2/project/$projectId/version"
            val versionsConnection = openUrlConnection(versionsUrl)
            if (versionsConnection.responseCode >= 400) {
                project.logger.warn("Modrinth API returned HTTP ${versionsConnection.responseCode}: ${versionsConnection.responseMessage}")
                return null
            }
            val allVersions = gson.fromJson<List<Map<String, Any>>>(
                InputStreamReader(versionsConnection.inputStream),
                object : TypeToken<List<Map<String, Any>>>() {}.type
            )
            val targetVersion = if (pluginVersion != null) {
                allVersions.firstOrNull { it["version_number"] == pluginVersion }
            } else {
                allVersions.firstOrNull {
                    (it["game_versions"] as? List<String>)?.contains(mcVersion) == true &&
                            (it["loaders"] as? List<String>)?.contains("paper") == true
                }
            }

            return (targetVersion?.get("files") as? List<Map<String, Any>>)?.firstOrNull()?.get("url") as? String
        } catch(e: Exception) {
            project.logger.warn("Modrinth search failed for $pluginName: ${e.message}")
            return null
        }
    }

    private fun acceptEula(serverDir: File) {
        val eula = File(serverDir, "eula.txt")
        if (!eula.exists() || !eula.readText().contains("eula=true")) {
            eula.writeText("eula=true")
            println("EULA accepted.")
        }
    }

    private fun downloadFile(url: String, dest: File) {
        try {
            openUrlConnection(url).inputStream.use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            throw GradleException("Failed to download file from $url", e)
        }
    }

    private fun openUrlConnection(url: String): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "paper-setup-plugin/1.1.0 (github.com/GATEOPENERZ/paper-setup-plugin)")
        connection.connectTimeout = 10000
        connection.readTimeout = 30000
        return connection
    }

    private fun parseVersionAndBuild(versionInput: String): Pair<String, String?> {
        val buildRegex = Regex("""^(.+)-(\d+)$""")
        val match = buildRegex.find(versionInput)

        return if (match != null) {
            val version = match.groupValues[1]
            val build = match.groupValues[2]
            println("Parsed version '$versionInput' into version: '$version', build: '$build'")
            Pair(version, build)
        } else {
            Pair(versionInput, null)
        }
    }

    private data class PaperMCBuild(val number: Int, val jarName: String, val url: String)

    private fun resolvePaperMCBuild(project: String, version: String, build: String?): PaperMCBuild =
        openUrlConnection("https://fill.papermc.io/v3/projects/$project/versions/$version/builds/${build ?: "latest"}").inputStream.use {
            val json = gson.fromJson(InputStreamReader(it), Map::class.java)
            val downloads = json["downloads"] as Map<String, Map<String, Any>>
            val app = downloads["server:default"] ?: downloads.values.first()
            PaperMCBuild((json["id"] as Double).toInt(), app["name"] as String, app["url"] as String)
        }
}