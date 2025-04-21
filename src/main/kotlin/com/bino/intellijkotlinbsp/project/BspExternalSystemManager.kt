package com.bino.intellijkotlinbsp.project

import com.bino.intellijkotlinbsp.BSP
import com.bino.intellijkotlinbsp.isBspProject
import com.bino.intellijkotlinbsp.protocol.BspConnectionConfig
import com.bino.intellijkotlinbsp.settings.*
import com.google.gson.Gson
import com.intellij.execution.configurations.SimpleJavaParameters
import com.intellij.openapi.externalSystem.ExternalSystemAutoImportAware
import com.intellij.openapi.externalSystem.ExternalSystemConfigurableAware
import com.intellij.openapi.externalSystem.ExternalSystemManager
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.project.ExternalSystemProjectResolver
import com.intellij.openapi.externalSystem.task.ExternalSystemTaskManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.Function
import java.io.File
import java.io.FileReader
import java.nio.file.Path

class BspExternalSystemManager :
    ExternalSystemManager<BspProjectSettings, BspProjectSettingsListener, BspSettings, BspLocalSettings, BspExecutionSettings>,
    ExternalSystemConfigurableAware,
    ExternalSystemAutoImportAware {

    override fun enhanceRemoteProcessing(parameters: SimpleJavaParameters) = Unit

    override fun getSystemId(): ProjectSystemId = BSP.PROJECT_SYSTEM_ID

    override fun getSettingsProvider(): Function<Project, BspSettings> = Function { project ->
        BspSettings.getInstance(project)
    }

    override fun getLocalSettingsProvider(): Function<Project, BspLocalSettings> = Function { project ->
        BspLocalSettings.getInstance(project)
    }

    override fun getExecutionSettingsProvider(): Function<Pair<Project, String>, BspExecutionSettings> = Function { pair ->
        BspExecutionSettings.executionSettingsFor(pair.first, Path.of(pair.second))
    }

    override fun getProjectResolverClass(): Class<out ExternalSystemProjectResolver<BspExecutionSettings>> = BspProjectResolver::class.java

    override fun getTaskManagerClass(): Class<out ExternalSystemTaskManager<BspExecutionSettings>> = BspTaskManager::class.java

    override fun getExternalProjectDescriptor(): FileChooserDescriptor = BspOpenProjectDescriptor()

    override fun getConfigurable(project: Project): Configurable = BspExternalSystemConfigurable(project)

    override fun getAffectedExternalProjectPath(changedFileOrDirPath: String, project: Project): String? {
        return null
    }

    private fun detectExternalProjectFiles(project: Project): Boolean {
        return cached(detectExternalProjectFilesKey, project) {
            return@cached if (isBspProject(project) && project.basePath != null) {
                val workspace = File(project.basePath!!)
                val files = BspConnectionConfig.workspaceConfigurationFiles(workspace.toPath())
                files
                    .map { parseAsMap(it.toFile()) }
                    .all {
                        it.isSuccess
                                && it.getOrNull()!!["X-detectExternalProjectFiles"] == true
                    }
            } else true
        }
    }

    private fun parseAsMap(file: File): Result<Map<String, Any>> {
        return try {
            Result.success(Gson().fromJson<Map<String, Any>>(FileReader(file), Map::class.java))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun <A> cached(key: Key<A>, holder: UserDataHolder, compute: () -> A): A {
        return holder.getUserData(key) ?: compute().also { holder.putUserData(key, it) }
    }

    companion object {
        val detectExternalProjectFilesKey: Key<Boolean> = Key.create("BSP.detectExternalProjectFiles")

        fun parseAsMap(file: File): Map<String, Any> {
            val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file)!!
            val content = virtualFile.contentsToByteArray().toString()
            return Gson().fromJson<Map<String, Any>>(content, Map::class.java)
        }
    }
}