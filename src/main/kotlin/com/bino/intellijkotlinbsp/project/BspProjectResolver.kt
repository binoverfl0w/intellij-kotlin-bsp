package com.bino.intellijkotlinbsp.project

import com.bino.intellijkotlinbsp.BSP
import com.bino.intellijkotlinbsp.protocol.BspConnectionService // Placeholder import
import com.bino.intellijkotlinbsp.protocol.BspDataMapper       // Placeholder import
import com.bino.intellijkotlinbsp.protocol.BspSession           // Placeholder import
import com.bino.intellijkotlinbsp.settings.BspExecutionSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.importing.ProjectResolverPolicy
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.service.project.ExternalSystemProjectResolver
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import java.io.File

class BspProjectResolver : ExternalSystemProjectResolver<BspExecutionSettings> {

    private val log = Logger.getInstance(BspProjectResolver::class.java)

    private val connectionService = BspConnectionService()
    private val dataMapper = BspDataMapper()

    override fun resolveProjectInfo(
        id: ExternalSystemTaskId,
        projectPath: String,
        isPreviewMode: Boolean,
        settings: BspExecutionSettings?,
        listener: ExternalSystemTaskNotificationListener
    ): DataNode<ProjectData>? {
        if (settings == null) {
            // Cannot resolve project without settings
            listener.onFailure(id, RuntimeException("BSP Execution settings not found."))
            return null
        }

        // Use the basePath from settings as the definitive workspace root
        val workspace = File(settings.basePath.toUri())
        if (!workspace.isDirectory) {
            listener.onFailure(id, RuntimeException("Workspace directory not found: ${settings.basePath}"))
            return null
        }

        listener.onTaskOutput(id, "Resolving BSP project at ${workspace.absolutePath}\n", true)

        val projectData = ProjectData(
            BSP.PROJECT_SYSTEM_ID,
            workspace.name,
            workspace.absolutePath,
            workspace.absolutePath
        )
        val rootDataNode = DataNode(ProjectKeys.PROJECT, projectData, null)

        var session: BspSession? = null
        try {
            // 1. Establish connection using the connection service
            listener.onTaskOutput(id, "Establishing BSP connection...\n", true)
            // Pass necessary info: workspace, settings, listener, taskId
            session = connectionService.connect(workspace, settings, listener, id)
            listener.onTaskOutput(id, "BSP Connection established successfully.\n", true)

            // 2. Fetch BSP model data (e.g., build targets, sources) via the session
            listener.onTaskOutput(id, "Fetching BSP project model...\n", true)
            val bspModel = connectionService.fetchBspModel(session, listener, id)
            listener.onTaskOutput(id, "Fetched BSP model successfully.\n", true)

            // 3. Map the fetched BSP model to IntelliJ DataNode structure
            listener.onTaskOutput(id, "Mapping BSP model to IntelliJ project structure...\n", true)
            dataMapper.mapBspModel(bspModel, rootDataNode, listener, id)
            listener.onTaskOutput(id, "Mapping completed.\n", true)

            listener.onTaskOutput(id, "BSP project resolution finished.\n", true)
            return rootDataNode

        } catch (e: Exception) {
            log.warn("Failed to resolve BSP project", e)
            listener.onFailure(id, e)
            return null // Indicate failure
        } finally {
            // 4. Ensure connection is closed via the service
            session?.let { connectionService.disconnect(it) }
            listener.onTaskOutput(id, "BSP connection closed.\n", true)
        }
    }

    override fun cancelTask(taskId: ExternalSystemTaskId, listener: ExternalSystemTaskNotificationListener): Boolean {
        // TODO: Implement cancellation logic (e.g., interrupt BSP connection/requests)
        listener.onTaskOutput(taskId, "Cancel task requested for $taskId\n", true)
        // Delegate cancellation to the connection service
        val cancelled = connectionService.cancelSession(taskId)
        if (!cancelled) {
            listener.onTaskOutput(taskId, "Cancellation for $taskId failed or not supported.\n", true)
        }
        return cancelled
    }
}