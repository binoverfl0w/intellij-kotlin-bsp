package com.bino.intellijkotlinbsp.project

import com.bino.intellijkotlinbsp.settings.BspExecutionSettings
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.service.project.ExternalSystemProjectResolver
import java.io.File

class BspProjectResolver : ExternalSystemProjectResolver<BspExecutionSettings> {

    override fun resolveProjectInfo(
        id: ExternalSystemTaskId,
        projectPath: String,
        isPreviewMode: Boolean,
        settings: BspExecutionSettings?,
        listener: ExternalSystemTaskNotificationListener
    ): DataNode<ProjectData>? {
        val workspaceCreationFile = File(projectPath)
        val workspace =
            if (workspaceCreationFile.isDirectory || !workspaceCreationFile.exists()) workspaceCreationFile
            else workspaceCreationFile.parentFile

        TODO()
    }

    override fun cancelTask(taskId: ExternalSystemTaskId, listener: ExternalSystemTaskNotificationListener): Boolean {
        TODO("Not yet implemented")
    }
}