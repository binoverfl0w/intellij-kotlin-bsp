package com.bino.intellijkotlinbsp.project

import com.bino.intellijkotlinbsp.settings.BspExecutionSettings
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.task.ExternalSystemTaskManager

class BspTaskManager : ExternalSystemTaskManager<BspExecutionSettings> {
    override fun cancelTask(id: ExternalSystemTaskId, listener: ExternalSystemTaskNotificationListener): Boolean {
        return false
    }
}