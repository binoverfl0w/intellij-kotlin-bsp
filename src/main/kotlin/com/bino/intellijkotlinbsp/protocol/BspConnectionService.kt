package com.bino.intellijkotlinbsp.protocol

import ch.epfl.scala.bsp4j.*
import com.bino.intellijkotlinbsp.settings.BspExecutionSettings
import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import org.eclipse.lsp4j.jsonrpc.Launcher
import java.io.File
import java.io.PrintWriter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class BspModel(
    val initializeResult: InitializeBuildResult,
    val buildTargets: List<BuildTarget>,
    val sources: Map<BuildTarget, SourcesItem>,
    val dependencySources: Map<BuildTarget, DependencySourcesItem>
)

data class BspSession(
    val sessionId: String,
    val connectionDetails: BspConnectionDetails,
    val server: BuildServer,
    val client: BuildClient,
    val initializeResult: InitializeBuildResult,
    private val connector: BspConnector
) {
    private val log = Logger.getInstance(BspSession::class.java)

    fun close() {
        log.info("Closing BSP session $sessionId")
        try {
            server.buildShutdown().get(5, TimeUnit.SECONDS)
            server.onBuildExit()
            log.info("Sent buildShutdown & onBuildExit to server.")
        } catch (e: Exception) {
            log.warn("Error during BSP shutdown sequence for session $sessionId", e)
        } finally {
            connector.disconnect()
            log.info("BSP connector streams closed and process destroyed for session $sessionId.")
        }
    }
}

class BspConnectionService {

    private val log = Logger.getInstance(BspConnectionService::class.java)

    // TODO: Need a way to manage multiple active sessions, perhaps keyed by taskId or projectPath
    private val activeSessions = mutableMapOf<ExternalSystemTaskId, BspSession>()
    private val executor = Executors.newCachedThreadPool() // For launcher

    fun connect(
        workspace: File,
        settings: BspExecutionSettings,
        listener: ExternalSystemTaskNotificationListener,
        id: ExternalSystemTaskId
    ): BspSession {
        log.info("Connecting to BSP server for workspace: ${workspace.path}, Task ID: $id")
        listener.onTaskOutput(id, "BSPConnectionService: Finding connection details...\n", true)

        val connectionDetails = findBspConnectionDetails(workspace, settings)
            ?: throw RuntimeException("BSP Connection details not found in ${workspace.absolutePath}")
        listener.onTaskOutput(id, "Found connection details: ${connectionDetails.name}\n", true)

        val connector = BspConnector(workspace, settings.traceBsp)
        var session: BspSession? = null
        try {
            listener.onTaskOutput(id, "BSPConnectionService: Launching server process...\n", true)
            connector.connect(connectionDetails)

            val client = BspClient(listener, id)
            val serverInputStream = connector.inputStream ?: throw RuntimeException("Server process input stream is null")
            val serverOutputStream = connector.outputStream ?: throw RuntimeException("Server process output stream is null")

            val traceOutput: PrintWriter? = null

            val launcher = Launcher.Builder<BuildServer>()
                .setRemoteInterface(BuildServer::class.java)
                .setLocalService(client)
                .setInput(serverInputStream)
                .setOutput(serverOutputStream)
                .setExecutorService(executor)
                .create()

            val server = launcher.remoteProxy
            val listening = launcher.startListening()
            log.info("BSP JSON-RPC Launcher started.")
            listener.onTaskOutput(id, "BSPConnectionService: Initializing connection...\n", true)

            val initializeParams = createInitializeParams(workspace)
            val initResultFuture: CompletableFuture<InitializeBuildResult> = server.buildInitialize(initializeParams)

            val initializeResult = try {
                 initResultFuture.get(60, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                log.error("BSP Initialize timed out", e)
                throw RuntimeException("BSP server initialization timed out after 60 seconds.", e)
            } catch (e: Exception) {
                log.error("BSP Initialize failed", e)
                throw RuntimeException("Failed during BSP initialization: ${e.message}", e)
            }
            
            server.onBuildInitialized()
            log.info("BSP Server Initialized: ${initializeResult.displayName} v${initializeResult.version}")
            listener.onTaskOutput(id, "BSP Server Initialized: ${initializeResult.displayName} v${initializeResult.version}\n", true)

            val sessionId = "${id}-${System.currentTimeMillis()}"
            session = BspSession(
                sessionId,
                connectionDetails,
                server,
                client,
                initializeResult,
                connector
            )

            activeSessions[id] = session

            log.info("BSP connection established: Session $sessionId for Task $id")
            listener.onTaskOutput(id, "BSPConnectionService: Connection successful (Session: $sessionId).\n", true)
            return session

        } catch (e: Exception) {
            log.error("BSP Connection failed for workspace ${workspace.path}", e)
            connector.disconnect()
            session?.let { activeSessions.remove(id) }
            throw RuntimeException("Failed to establish BSP connection: ${e.message}", e)
        }
    }

    fun fetchBspModel(
        session: BspSession,
        listener: ExternalSystemTaskNotificationListener,
        id: ExternalSystemTaskId
    ): BspModel {
        listener.onTaskOutput(id, "BSPConnectionService: Fetching model for session ${session.sessionId}...\n", true)
        val server = session.server
        val timeoutSeconds = 60L

        try {
            listener.onTaskOutput(id, "Fetching workspace build targets...\n", true)
            val targetsResult = server.workspaceBuildTargets()
                .get(timeoutSeconds, TimeUnit.SECONDS)
            val buildTargets = targetsResult.targets
            listener.onTaskOutput(id, "Found ${buildTargets.size} build targets.\n", true)

            if (buildTargets.isEmpty()) {
                log.warn("No build targets found for session ${session.sessionId}")
                return BspModel(session.initializeResult, emptyList(), emptyMap(), emptyMap())
            }

            val targetIds = buildTargets.map { it.id }

            listener.onTaskOutput(id, "Fetching sources for ${targetIds.size} targets...\n", true)
            val sourcesParams = SourcesParams(targetIds)
            val sourcesResult = server.buildTargetSources(sourcesParams)
                .get(timeoutSeconds * targetIds.size, TimeUnit.SECONDS)
            val sourcesMap = buildTargets.zip(sourcesResult.items).associate { (target, item) -> target to item }
            listener.onTaskOutput(id, "Fetched sources.\n", true)


            listener.onTaskOutput(id, "Fetching dependency sources for ${targetIds.size} targets...\n", true)
            val depSourcesParams = DependencySourcesParams(targetIds)
            val depSourcesResult = server.buildTargetDependencySources(depSourcesParams)
                 .get(timeoutSeconds * targetIds.size, TimeUnit.SECONDS)
            val depSourcesMap = buildTargets.zip(depSourcesResult.items).associate { (target, item) -> target to item }
            listener.onTaskOutput(id, "Fetched dependency sources.\n", true)

            val model = BspModel(
                initializeResult = session.initializeResult,
                buildTargets = buildTargets,
                sources = sourcesMap,
                dependencySources = depSourcesMap
            )

            listener.onTaskOutput(id, "BSPConnectionService: Model fetched successfully.\n", true)
            return model

        } catch (e: TimeoutException) {
            log.error("BSP request timed out during model fetching for session ${session.sessionId}", e)
            listener.onFailure(id, RuntimeException("BSP request timed out while fetching project model.", e))
            throw RuntimeException("BSP request timed out.", e)
        } catch (e: Exception) {
            log.error("Failed to fetch BSP model data for session ${session.sessionId}", e)
            listener.onFailure(id, RuntimeException("Failed to retrieve data from BSP server: ${e.message}", e))
            throw RuntimeException("Failed to retrieve BSP data.", e)
        }
    }

    fun disconnect(session: BspSession) {
        log.info("Disconnecting BSP session ${session.sessionId}")
        session.close()
    }

    fun cancelSession(taskId: ExternalSystemTaskId): Boolean {
        log.info("Attempting to cancel BSP session for task $taskId")
        val session = activeSessions.remove(taskId)
        return if (session != null) {
            log.warn("Cancellation logic for BSP session ${session.sessionId} not fully implemented.")
            session.close()
            true
        } else {
            log.warn("No active BSP session found for task $taskId to cancel.")
            false
        }
    }

    private fun findBspConnectionDetails(workspace: File, settings: BspExecutionSettings): BspConnectionDetails? {
        log.info("Searching for BSP connection files in ${workspace.absolutePath}/.bsp")
        val bspDir = File(workspace, ".bsp")
        if (!bspDir.isDirectory) {
            log.warn("No .bsp directory found in ${workspace.absolutePath}")
            return null
        }

        val connectionFiles = bspDir.listFiles { _, name -> name.endsWith(".json") } ?: return null
        if (connectionFiles.isEmpty()) {
             log.warn("No .json files found in ${bspDir.absolutePath}")
            return null
        }

        val chosenFile = connectionFiles.first()
        log.info("Using BSP connection file: ${chosenFile.absolutePath}")
        return try {
            Gson().fromJson(chosenFile.reader(Charsets.UTF_8), BspConnectionDetails::class.java)
        } catch (e: Exception) {
            log.warn("Failed to parse BSP connection file: ${chosenFile.path}", e)
            null
        }
    }
    
    private fun createInitializeParams(workspace: File): InitializeBuildParams {
        val clientCapabilities = BuildClientCapabilities(listOf("kotlin"))
        return InitializeBuildParams(
            "IntelliJ-BSP-Plugin",
            "0.1.0",
            "2.1.0-M4",
            workspace.toURI().toString(),
            clientCapabilities
        )
    }
}

data class BspConnectionDetails(
    val name: String,
    val version: String,
    val bspVersion: String,
    val argv: List<String>,
    val languages: List<String>
) 