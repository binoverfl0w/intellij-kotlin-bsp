package com.bino.intellijkotlinbsp.ui

import ch.epfl.scala.bsp4j.BuildTarget // Import BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetCapabilities
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import com.bino.intellijkotlinbsp.BSP
import com.bino.intellijkotlinbsp.BspBundle
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.*
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListenerAdapter
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.task.TaskCallbackAdapter
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil

class BspToolWindowFactory : ToolWindowFactory, DumbAware {

    companion object {
        const val ID = "BSP" // Tool window ID
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val bspToolWindowPanel = BspToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(bspToolWindowPanel.content, "", false)
        toolWindow.contentManager.addContent(content)
        // Trigger initial refresh when window is created
        bspToolWindowPanel.refreshTargets()
    }

    override fun shouldBeAvailable(project: Project): Boolean {
        // Only show the tool window if the project is recognized as a BSP project
        // TODO: Use a more reliable check, e.g., check linked projects in BspSettings
        // return BspSettings.getInstance(project).linkedProjectsSettings.isNotEmpty()
        // For now, let's assume it's always available if plugin is active
        return true
    }
}

/**
 * Encapsulates the UI and logic for the BSP Tool Window panel.
 */
class BspToolWindowPanel(private val project: Project) {

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(BspToolWindowPanel::class.java)
    private val targetListModel = DefaultListModel<BuildTarget>()
    private val targetList = JBList(targetListModel)
    val content: JComponent = createPanel()

    private fun createPanel(): JComponent {
        val panel = JPanel(BorderLayout())

        // Configure List
        targetList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        targetList.cellRenderer = BuildTargetRenderer() // Custom renderer for better display

        // Buttons
        val refreshButton = JButton(BspBundle.message("bsp.toolwindow.button.refresh"))
        val compileButton = JButton(BspBundle.message("bsp.toolwindow.button.compileSelected"))
        compileButton.isEnabled = false // Disable initially

        val buttonPanel = JPanel()
        buttonPanel.layout = BoxLayout(buttonPanel, BoxLayout.X_AXIS)
        buttonPanel.add(refreshButton)
        buttonPanel.add(Box.createHorizontalStrut(5))
        buttonPanel.add(compileButton)

        // Layout
        panel.add(JBScrollPane(targetList), BorderLayout.CENTER)
        panel.add(buttonPanel, BorderLayout.NORTH)

        // --- Actions --- 
        refreshButton.addActionListener { refreshTargets() }
        compileButton.addActionListener { compileSelectedTarget() }
        targetList.addListSelectionListener { 
            compileButton.isEnabled = !targetList.isSelectionEmpty 
        }

        return panel
    }

    fun refreshTargets() {
        val title = BspBundle.message("bsp.toolwindow.refresh.title")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = BspBundle.message("bsp.toolwindow.refresh.indicator.text")

                // Find the linked BSP project path
                // NOTE: Assumes only one BSP project linked. Needs refinement for multi-root projects.
                val settings = com.bino.intellijkotlinbsp.settings.BspSettings.getInstance(project)
                val linkedProjectPath = settings.linkedProjectsSettings.firstOrNull()?.externalProjectPath

                if (linkedProjectPath == null) {
                    log.warn("Cannot refresh BSP targets: No linked BSP project found.")
                    // Update UI on EDT to show error/clear list
                    SwingUtilities.invokeLater { 
                        targetListModel.clear()
                        // TODO: Show a status message in the UI
                    }
                    return
                }
                
                 indicator.text2 = BspBundle.message("bsp.toolwindow.refresh.indicator.text2", linkedProjectPath)

                // Trigger External System refresh
                com.intellij.openapi.externalSystem.util.ExternalSystemUtil.refreshProject(
                    project,
                    BSP.PROJECT_SYSTEM_ID,
                    linkedProjectPath,
                    false, // Not preview mode
                    com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode.IN_BACKGROUND_ASYNC
                )
                
                 // Refresh MAY NOT update the data immediately. We need to wait or find a callback.
                 // For simplicity now, we'll try reading the data after triggering refresh.
                 // A better approach involves ProjectDataService or listeners.
                
                 // Fetch data from ProjectDataManager cache AFTER refresh (may not be immediate!)
                 val projectDataManager = ProjectDataManager.getInstance()
                 val projectInfo = projectDataManager.getExternalProjectData(project, BSP.PROJECT_SYSTEM_ID, linkedProjectPath)
                 val targets = mutableListOf<BuildTarget>()
                 if (projectInfo != null) {
                     val projectStructure = projectInfo.externalProjectStructure
                     if (projectStructure != null) {
                         // Find ModuleData nodes which should correspond to BuildTargets
                         val moduleNodes = com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil.findAll(projectStructure, ProjectKeys.MODULE)
                         moduleNodes.forEach { moduleNode ->
                             // TODO: Need a reliable way to get the original BuildTarget from ModuleData
                             // This might require storing the BuildTarget data *within* the ModuleData 
                             // during the BspDataMapper phase, or re-querying based on module ID.
                             // Placeholder: Create dummy BuildTarget from ModuleData for UI
                             val moduleData = moduleNode.data
                             targets.add(BuildTarget(BuildTargetIdentifier(moduleData.id), emptyList(), emptyList(), emptyList(), BuildTargetCapabilities()))
                         }
                     }
                 }

                // Update UI on the Event Dispatch Thread
                SwingUtilities.invokeLater { 
                    targetListModel.clear()
                    if (targets.isEmpty()) {
                        log.warn("No targets found in cached data after refresh for $linkedProjectPath")
                         // TODO: Show status message
                    } else {
                        targets.forEach { targetListModel.addElement(it) }
                    }
                }
            }
        })
    }

    private fun compileSelectedTarget() {
        val selectedTarget = targetList.selectedValue ?: return
        val targetId = selectedTarget.id
        val targetUri = targetId.uri
        val taskName = "${BSP.COMPILE_TASK_PREFIX}$targetUri"

        val title = BspBundle.message("bsp.toolwindow.compile.title", targetUri)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = BspBundle.message("bsp.toolwindow.compile.indicator.text", targetUri)

                try {
                    val settings = com.bino.intellijkotlinbsp.settings.BspSettings.getInstance(project)
                    val linkedProjectPath = settings.linkedProjectsSettings.firstOrNull()?.externalProjectPath
                        ?: throw IllegalStateException("Cannot compile: No linked BSP project found.")

                    val taskSettings = ExternalSystemTaskExecutionSettings()
                    taskSettings.externalProjectPath = linkedProjectPath
                    taskSettings.taskNames = listOf(taskName)
                    taskSettings.externalSystemIdString = BSP.PROJECT_SYSTEM_ID.id

                    // Define the TaskCallback
                    val taskCallback = object : TaskCallbackAdapter() {
                        override fun onSuccess() {
                            log.info("BSP Compile task reported success via TaskCallback.")
                            // Update UI on EDT
                            SwingUtilities.invokeLater { 
                                indicator.text2 = "BSP compile finished successfully."
                                // TODO: Show non-modal success notification?
                            }
                        }

                        override fun onFailure() {
                            log.error("BSP Compile task reported failure via TaskCallback.")
                            // Update UI on EDT
                            SwingUtilities.invokeLater { 
                                javax.swing.JOptionPane.showMessageDialog(content, "Compile failed.", "BSP Compile Error", javax.swing.JOptionPane.ERROR_MESSAGE)
                                indicator.text2 = "BSP compile failed."
                            }
                        }
                    }

                    indicator.text2 = "Executing BSP compile task via ExternalSystemUtil..."
                    ExternalSystemUtil.runTask(
                        taskSettings,
                        DefaultRunExecutor.EXECUTOR_ID,
                        project,
                        BSP.PROJECT_SYSTEM_ID,
                        taskCallback, // Pass the TaskCallback here
                        ProgressExecutionMode.IN_BACKGROUND_ASYNC,
                        false,
                        null
                    )
                    
                    log.info("BSP Compile task for $targetUri submitted successfully via ExternalSystemUtil.runTask.")
                    // No immediate status update here - wait for callback

                } catch (e: Exception) {
                     log.error("Failed to submit BSP compile task for $targetUri", e)
                     SwingUtilities.invokeLater { 
                         javax.swing.JOptionPane.showMessageDialog(content, "Failed to submit compile task: ${e.message}", "BSP Compile Error", javax.swing.JOptionPane.ERROR_MESSAGE)
                     }
                }
            }
        })
    }

    // Custom renderer to display BuildTarget nicely in the list
    private class BuildTargetRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): java.awt.Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is BuildTarget) {
                text = value.displayName ?: value.id.uri // Show display name or URI
                // Optionally set icon based on tags (library, application, test)
            }
            return component
        }
    }
} 