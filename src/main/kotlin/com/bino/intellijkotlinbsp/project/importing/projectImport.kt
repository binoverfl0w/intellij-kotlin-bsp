package com.bino.intellijkotlinbsp.project.importing

import com.bino.intellijkotlinbsp.BSP
import com.bino.intellijkotlinbsp.bspSettings
import com.bino.intellijkotlinbsp.findOrCreateBestJdkForProject
import com.bino.intellijkotlinbsp.protocol.BspConnectionConfig
import com.bino.intellijkotlinbsp.settings.*
import com.intellij.ide.impl.runUnderModalProgressIfIsEdt
import com.intellij.ide.util.projectWizard.ModuleWizardStep
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.importing.AbstractOpenProjectProvider
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.internal.InternalExternalProjectInfo
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.service.project.wizard.AbstractExternalProjectImportBuilder
import com.intellij.openapi.externalSystem.service.project.wizard.AbstractExternalProjectImportProvider
import com.intellij.openapi.externalSystem.service.settings.AbstractImportFromExternalSystemControl
import com.intellij.openapi.externalSystem.service.ui.ExternalProjectDataSelectorDialog
import com.intellij.openapi.externalSystem.util.ExternalSystemSettingsControl
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.module.ModifiableModuleModel
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ui.configuration.ModulesProvider
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NotNullFactory
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.packaging.artifacts.ModifiableArtifactModel
import com.intellij.projectImport.ProjectImportBuilder
import com.intellij.projectImport.ProjectImportProvider
import com.intellij.projectImport.ProjectOpenProcessor
//import org.jetbrains.plugins.gradle.service.project.open.GradleProjectOpenProcessor

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.Icon

class BspProjectImportBuilder : AbstractExternalProjectImportBuilder<ImportFromBspSystemControl>(
    ProjectDataManager.getInstance(),
    ImportFromBspSystemControlFactory(),
    BSP.PROJECT_SYSTEM_ID,
) {
    var externalBspWorkspace: Path? = null
    var preImportConfig: BspProjectSettings.Companion.PreImportConfig = BspProjectSettings.Companion.AutoPreImport
    var serverConfig: BspProjectSettings.Companion.BspServerConfig = BspProjectSettings.Companion.AutoConfig

    override fun getName() = BSP.NAME
    override fun getIcon(): Icon = BSP.ICON

    override fun doPrepare(context: WizardContext) = Unit
    override fun beforeCommit(dataNode: DataNode<ProjectData>, project: Project) = Unit
    override fun getExternalProjectConfigToUse(file: File): File = file
    override fun applyExtraSettings(context: WizardContext) = Unit

    override fun commit(project: Project,
                        model: ModifiableModuleModel?,
                        modulesProvider: ModulesProvider?,
                        artificatModel: ModifiableArtifactModel?): List<Module> {
        linkAndRefreshProject(getBspWorkspace().toString(), project)
        applyBspSetupSettings(project)
        return emptyList()
    }

    fun autoConfigure(project: Project?, workspace: Path) {
        val configSetups = BspConfigSteps.configSetupChoices(workspace)
        if (configSetups.size == 1) {
            findOrCreateBestJdkForProject(project)
                ?.let {
                    BspConfigSteps.configureBuilder(it, this, workspace, configSetups.first())
                }
        }
    }

    private fun applyBspSetupSettings(project: Project) {
        val bspSettings = bspSettings(project)
        val projectSettings = bspSettings.getLinkedProjectSettings(getBspWorkspace().toString())
        projectSettings?.let {
            it.preImportConfig = preImportConfig
            it.serverConfig = serverConfig
        }
    }

//    fun setExternalBspWorkspace(externalBspWorkspace: Path) {
//        this.externalBspWorkspace = externalBspWorkspace
//    }

    private fun getBspWorkspace() = externalBspWorkspace ?: Paths.get(fileToImport)

//    fun setPreImportConfig(preImportConfig: BspProjectSettings.Companion.PreImportConfig) {
//        this.preImportConfig = preImportConfig
//    }
//
//    fun setServerConfig(serverConfig: BspProjectSettings.Companion.BspServerConfig) {
//        this.serverConfig = serverConfig
//    }

    override fun setFileToImport(path: String) {
        if (externalBspWorkspace != null) {
            super.setFileToImport(externalBspWorkspace.toString())
        } else {
            LocalFileSystem
                .getInstance()
                .refreshAndFindFileByPath(path)
                ?.let {
                    super.setFileToImport(ProjectImportProvider.getDefaultPath(it))
                }
        }
    }

    private fun linkAndRefreshProject(projectFilePath: String, project: Project) {
        LocalFileSystem
            .getInstance()
            .refreshAndFindFileByPath(projectFilePath)
            ?.let {
                BspOpenProjectProvider.linkToExistingProject(it, project)
            }
            ?: {
                val shortPath = FileUtil.getLocationRelativeToUserHome(FileUtil.toSystemIndependentName(projectFilePath), false)
                throw IllegalArgumentException("project definition file $shortPath not found")
            }
    }

    fun reset() {
        preImportConfig = BspProjectSettings.Companion.AutoPreImport
        serverConfig = BspProjectSettings.Companion.AutoConfig
    }
}

object BspOpenProjectProvider : AbstractOpenProjectProvider() {
    override val systemId: ProjectSystemId = BSP.PROJECT_SYSTEM_ID

    override fun isProjectFile(file: VirtualFile): Boolean = canOpenProject(file)
    override fun canOpenProject(file: VirtualFile): Boolean = canOpenProject(file)

    override fun linkToExistingProject(projectFile: VirtualFile, project: Project) {
        val bspProjectSettings = BspProjectSettings()
        val projectDirectory = getProjectDirectory(projectFile)
        bspProjectSettings.externalProjectPath = projectDirectory.toNioPath().toString()
        attachBspProjectAndRefresh(bspProjectSettings, project)
    }

    private fun attachBspProjectAndRefresh(settings: BspProjectSettings, project: Project) {
        val externalProjectPath = settings.externalProjectPath
        bspSettings(project).linkProject(settings)
        ExternalSystemUtil.refreshProject(
            externalProjectPath,
            ImportSpecBuilder(project, BSP.PROJECT_SYSTEM_ID)
                .usePreviewMode()
                .use(ProgressExecutionMode.MODAL_SYNC)
        )
        ExternalSystemUtil.refreshProject(externalProjectPath,
            ImportSpecBuilder(project, BSP.PROJECT_SYSTEM_ID)
                .callback(FinalImportCallback(project, settings))
        )
    }

    private class FinalImportCallback(
        private val project: Project,
        private val projectSettings: BspProjectSettings
    ) : ExternalProjectRefreshCallback {
        override fun onSuccess(externalProject: DataNode<ProjectData>?) {
            if (externalProject == null) {
                return
            }

            fun selectDataTask() {
                val projectInfo = InternalExternalProjectInfo(BSP.PROJECT_SYSTEM_ID, projectSettings.externalProjectPath, externalProject)
                val dialog = ExternalProjectDataSelectorDialog(project, projectInfo)
                if (dialog.hasMultipleDataToSelect()) {
                    dialog.showAndGet()
                } else {
                    Disposer.dispose(dialog.disposable)
                }
            }

            fun importTask() {
                ProjectDataManager.getInstance().importData(externalProject, project)
            }

            val showSelectiveImportDialog = BspSettings.getInstance(project).showSelectiveImportDialogOnInitialImport()
            val application = ApplicationManager.getApplication()

            if (showSelectiveImportDialog && !application.isHeadlessEnvironment) {
                application.invokeLater {
                    selectDataTask()
                    application.executeOnPooledThread {
                        importTask()
                    }
                }
            } else {
                importTask()
            }
        }
    }
}

class ImportFromBspSystemControl : AbstractImportFromExternalSystemControl<BspProjectSettings, BspProjectSettingsListener, BspSettings>(
    BSP.PROJECT_SYSTEM_ID,
    BspSettings.getInstance(ProjectManager.getInstance().defaultProject),
    BspProjectSettings()
) {
    override fun onLinkedProjectPathChange(path: String) = Unit

    override fun createSystemSettingsControl(settings: BspSettings): ExternalSystemSettingsControl<BspSettings> =
        BspSystemSettingsControl(settings)

    override fun createProjectSettingsControl(settings: BspProjectSettings): ExternalSystemSettingsControl<BspProjectSettings> =
        BspProjectSettingsControl(settings)
}

class ImportFromBspSystemControlFactory : NotNullFactory<ImportFromBspSystemControl> {
    override fun create(): ImportFromBspSystemControl = ImportFromBspSystemControl()
}

//class BspSystemSettings(
//    topic: Topic<BspSystemSettingsListener>,
//    project: Project
//) : AbstractExternalSystemSettings<BspSystemSettings, BspProjectSettings, BspSystemSettingsListener>(topic, project) {
//    override fun copyExtraSettingsFrom(settings: BspSystemSettings) {
//        TODO("Not yet implemented")
//    }
//
//    override fun checkSettings(old: BspProjectSettings, current: BspProjectSettings) {
//        TODO("Not yet implemented")
//    }
//}

class BspProjectImportProvider(private val builder: BspProjectImportBuilder) : AbstractExternalProjectImportProvider(builder, BSP.PROJECT_SYSTEM_ID) {

    constructor() : this(ProjectImportBuilder.EXTENSIONS_POINT_NAME.findExtensionOrFail(BspProjectImportBuilder::class.java))

    override fun canImport(fileOrDirectory: VirtualFile, project: Project?): Boolean =
        canOpenProjectFile(fileOrDirectory) ||
                BspConfigSteps.canOpenGradleProject(fileOrDirectory)

    override fun createSteps(context: WizardContext): Array<ModuleWizardStep> {
        builder.reset()
        builder.autoConfigure(context.project, context.projectDirectory)
        builder.fileToImport = context.projectDirectory.toString()
        return arrayOf(
            BspSetupConfigStep(context, builder, context.projectDirectory),
//            BspChooseConfigStep(context, builder),
        )
    }

    override fun getPathToBeImported(file: VirtualFile?): String {
        return ProjectImportProvider.getDefaultPath(file)
    }
}

class BspProjectOpenProcessor : ProjectOpenProcessor() {

    override val name: String = BSP.NAME
    override val icon: Icon = BSP.ICON

    override fun canOpenProject(file: VirtualFile): Boolean = canOpenProjectFile(file)

    override fun doOpenProject(
        virtualFile: VirtualFile,
        projectToClose: Project?,
        forceOpenInNewFrame: Boolean,
    ): Project = runUnderModalProgressIfIsEdt {
        BspOpenProjectProvider.openProject(virtualFile, projectToClose, forceOpenInNewFrame)!!
    }

}

private fun canOpenProjectFile(workspace: VirtualFile): Boolean {
    val ioWorkspace = workspace.toNioPath()
    val bspConnectionProtocolSupported = BspConnectionConfig.workspaceConfigurationFiles(ioWorkspace).isNotEmpty()
    return bspConnectionProtocolSupported
}