package com.bino.intellijkotlinbsp.project

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.StatusCode
import com.bino.intellijkotlinbsp.protocol.BspConnectionService
import com.bino.intellijkotlinbsp.protocol.BspSession
import com.bino.intellijkotlinbsp.settings.BspExecutionSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.task.ExternalSystemTaskManager
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import com.bino.intellijkotlinbsp.BSP // Import BSP object

// Define constants for task prefixes/types
// private const val COMPILE_TASK_PREFIX = "compile:" // Removed
// Future: TEST_TASK_PREFIX, RUN_TASK_PREFIX

class BspTaskManager : ExternalSystemTaskManager<BspExecutionSettings> {

    private val log = Logger.getInstance(BspTaskManager::class.java)

    // TODO: Determine how to get/inject this. Should it be project-specific or application service?
    private val connectionService = BspConnectionService()
    
    // TODO: Mechanism to wait for background task completion notified by BspClient
    private val backgroundTaskFutures = ConcurrentHashMap<String, CompletableFuture<StatusCode>>()

    override fun executeTasks(
        id: ExternalSystemTaskId,
        taskNames: List<String>,
        projectPath: String,
        settings: BspExecutionSettings?,
        jvmParametersSetup: String?, // Ignored for BSP typically
        listener: ExternalSystemTaskNotificationListener
    ) {
        if (settings == null) {
            listener.onFailure(id, RuntimeException("Cannot execute BSP tasks without execution settings."))
            return
        }

        // TODO: Consider reusing connections vs creating one per task execution run
        val workspace = File(settings.basePath.toUri())
        var session: BspSession? = null // Declare session outside try
        try {
            listener.onTaskOutput(id, "Acquiring BSP connection for task execution...\n", true)
            session = connectionService.connect(workspace, settings, listener, id)
            listener.onTaskOutput(id, "BSP connection acquired.\n", true)
            
            val taskFutures = mutableListOf<CompletableFuture<*>>()

            taskNames.forEach { taskName ->
                listener.onTaskOutput(id, "Processing task: $taskName\n", true)
                when {
                    taskName.startsWith(BSP.COMPILE_TASK_PREFIX) -> {
                        val targetUri = taskName.substringAfter(BSP.COMPILE_TASK_PREFIX).trim()
                        if (targetUri.isBlank()) {
                            throw IllegalArgumentException("Compile task specified without target URI: $taskName")
                        }
                        val targetId = BuildTargetIdentifier(targetUri)
                        // Execute and add the future representing completion
                        taskFutures.add(executeCompileTaskAsync(session, targetId, listener, id))
                    }
                    // TODO: Add cases for other task types (test, run)
                    // case taskName.startsWith(TEST_TASK_PREFIX) -> { ... }
                    else -> {
                        log.warn("Unsupported BSP task: $taskName")
                        throw IllegalArgumentException("Unsupported BSP task type: $taskName")
                    }
                }
            }
            
            // Wait for all requested tasks to complete
            listener.onTaskOutput(id, "Waiting for ${taskFutures.size} BSP tasks to complete...\n", true)
            CompletableFuture.allOf(*taskFutures.toTypedArray()).get(30, TimeUnit.MINUTES) // Timeout for all tasks
            
            // Check individual futures for errors? (allOf doesn't propagate exceptions well)
            // taskFutures.forEach { future -> if (future.isCompletedExceptionally) { /* handle */ } }

            listener.onSuccess(id)
            listener.onTaskOutput(id, "All BSP tasks completed successfully.\n", true)

        } catch (e: Exception) {
            log.error("Error executing BSP tasks for project $projectPath", e)
            // Ensure the message clearly indicates failure
            val failureMessage = if (e is TimeoutException) "Timeout waiting for BSP task completion" else "Task execution failed: ${e.message}"
            listener.onFailure(id, Exception(failureMessage, e))
        } finally {
             session?.let { 
                 connectionService.disconnect(it)
                 listener.onTaskOutput(id, "BSP connection for task execution closed.\n", true)
             }
        }
    }

    /**
     * Initiates a compile task and returns a CompletableFuture that completes
     * when the background compilation finishes (signalled by BspClient).
     */
    private fun executeCompileTaskAsync(
        session: BspSession,
        targetId: BuildTargetIdentifier,
        listener: ExternalSystemTaskNotificationListener,
        taskId: ExternalSystemTaskId
    ): CompletableFuture<Void> {
        listener.onTaskOutput(taskId, "Executing compile for target: ${targetId.uri}\n", true)
        val operationOriginId = UUID.randomUUID().toString()
        val compileParams = CompileParams(listOf(targetId)).apply {
            originId = operationOriginId
        }
        
        val completionFuture = CompletableFuture<StatusCode>()
        backgroundTaskFutures[operationOriginId] = completionFuture // Register future for BspClient to complete

        return session.server.buildTargetCompile(compileParams).handleAsync { compileResult, error ->
            if (error != null) {
                log.error("Failed to submit compile request for target ${targetId.uri}", error)
                backgroundTaskFutures.remove(operationOriginId) // Clean up future
                completionFuture.completeExceptionally(RuntimeException("Compile request submission failed: ${error.message}", error))
                throw completionFuture.exceptionNow() // Propagate immediately
            }

            if (compileResult.statusCode == StatusCode.ERROR) {
                log.error("Compile request rejected by server for target ${targetId.uri} (Status: ${compileResult.statusCode}) Origin: ${compileResult.originId}")
                backgroundTaskFutures.remove(operationOriginId) // Clean up future
                completionFuture.completeExceptionally(RuntimeException("Compile request rejected by server (Status: ${compileResult.statusCode})"))
                throw completionFuture.exceptionNow() // Propagate immediately
            }

            listener.onTaskOutput(taskId, "Compile request for ${targetId.uri} acknowledged by server (Origin: $operationOriginId). Waiting for completion...\n", true)
            
            // Now we wait for the backgroundTaskFutures[operationOriginId] to be completed by BspClient
            try {
                val finalStatus = completionFuture.get(15, TimeUnit.MINUTES) // Timeout for actual compile
                if (finalStatus != StatusCode.OK) {
                    throw RuntimeException("Compilation failed for target ${targetId.uri} (Final Status: $finalStatus)")
                }
                listener.onTaskOutput(taskId, "Compilation finished successfully for ${targetId.uri}.\n", true)
            } catch (e: TimeoutException) {
                 log.error("Timeout waiting for compile completion for target ${targetId.uri} (Origin: $operationOriginId)", e)
                 throw RuntimeException("Timeout waiting for compile completion for ${targetId.uri}", e)
            } catch (e: Exception) {
                log.error("Error waiting for compile completion for target ${targetId.uri} (Origin: $operationOriginId)", e)
                // Check if exception came from the future itself
                if (e is java.util.concurrent.ExecutionException) throw e.cause ?: e else throw e 
            }
        }.thenApply { /* Convert to CompletableFuture<Void> */ null }
    }

    // This method would be called by BspClient when onBuildTaskFinish is received
    fun signalTaskCompleted(originId: String, statusCode: StatusCode) {
        backgroundTaskFutures.remove(originId)?.complete(statusCode)
            ?: log.warn("Received completion signal for unknown/already completed originId: $originId")
    }

    override fun cancelTask(id: ExternalSystemTaskId, listener: ExternalSystemTaskNotificationListener): Boolean {
        listener.onTaskOutput(id, "Cancel task requested for $id\n", true)
        // TODO: Implement more granular cancellation via originId/BSP task id if possible
        // This currently cancels the whole session associated with the ExternalSystemTaskId
        val cancelled = connectionService.cancelSession(id)
        if (!cancelled) {
            listener.onTaskOutput(id, "Cancellation for $id failed or not supported.\n", true)
        }
        // Also cancel any pending futures for this task
        backgroundTaskFutures.forEach { (origin, future) ->
            // Heuristic: Check if originId relates to the taskId? Difficult without more context.
            future.cancel(true)
        }
        return cancelled
    }
}