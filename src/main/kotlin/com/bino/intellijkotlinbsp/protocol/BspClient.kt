package com.bino.intellijkotlinbsp.protocol

import ch.epfl.scala.bsp4j.BuildClient
import ch.epfl.scala.bsp4j.DidChangeBuildTarget
import ch.epfl.scala.bsp4j.LogMessageParams
import ch.epfl.scala.bsp4j.MessageType
import ch.epfl.scala.bsp4j.PublishDiagnosticsParams
import ch.epfl.scala.bsp4j.ShowMessageParams
import ch.epfl.scala.bsp4j.TaskFinishParams
import ch.epfl.scala.bsp4j.TaskProgressParams
import ch.epfl.scala.bsp4j.TaskStartParams
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener

class BspClient(
    private val listener: ExternalSystemTaskNotificationListener,
    private val taskId: ExternalSystemTaskId
) : BuildClient {

    private val log = Logger.getInstance(BspClient::class.java)

    override fun onBuildShowMessage(params: ShowMessageParams) {
        log.info("[BSP showMessage] ${params.type}: ${params.message}")
        listener.onTaskOutput(taskId, "[BSP] ${params.type}: ${params.message}\n", params.type != MessageType.LOG)
    }

    override fun onBuildLogMessage(params: LogMessageParams) {
        log.info("[BSP logMessage] ${params.type}: ${params.message}")
        listener.onTaskOutput(taskId, "[BSP Log] ${params.message}\n", false)
    }

    override fun onBuildTaskStart(params: TaskStartParams) {
        log.debug("[BSP taskStart] ${params.taskId.id} - ${params.message}")
        params.message?.let { listener.onTaskOutput(taskId, "[BSP Start] ${it}\n", true) }
    }

    override fun onBuildTaskProgress(params: TaskProgressParams) {
        log.debug("[BSP taskProgress] ${params.taskId.id} - ${params.message}")
        params.message?.let { listener.onTaskOutput(taskId, "[BSP Progress] ${it}\n", false) }
    }

    override fun onBuildTaskFinish(params: TaskFinishParams) {
        log.debug("[BSP taskFinish] ${params.taskId.id} - ${params.status}")
        params.message?.let { listener.onTaskOutput(taskId, "[BSP Finish] ${it} (${params.status})\n", true) }
    }

    override fun onBuildPublishDiagnostics(params: PublishDiagnosticsParams) {
        log.debug("[BSP publishDiagnostics] ${params.buildTarget.uri} - ${params.diagnostics.size} issues")
        // TODO: Process diagnostics and potentially show them in the editor
        listener.onTaskOutput(taskId, "[BSP Diagnostics] for ${params.buildTarget.uri}: ${params.diagnostics.size} issues reported.\n", false)
    }

    override fun onBuildTargetDidChange(params: DidChangeBuildTarget) {
        log.info("[BSP buildTargetDidChange] ${params.changes.size} changes")
        // TODO: Handle build target changes (e.g., trigger project reload?)
        listener.onTaskOutput(taskId, "[BSP] Build targets changed. A project reload might be needed.\n", true)
    }
} 