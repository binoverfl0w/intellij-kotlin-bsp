package com.bino.intellijkotlinbsp.project

import com.bino.intellijkotlinbsp.BSP
import com.bino.intellijkotlinbsp.project.importing.BspConfigSteps
import com.bino.intellijkotlinbsp.project.importing.BspOpenProjectProvider
import com.bino.intellijkotlinbsp.settings.BspProjectSettings
import com.bino.intellijkotlinbsp.settings.BspSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.externalSystem.autolink.ExternalSystemProjectLinkListener
import com.intellij.openapi.externalSystem.autolink.ExternalSystemUnlinkedProjectAware
import com.intellij.openapi.externalSystem.settings.ExternalSystemSettingsListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class BspUnlinkedProjectAware : ExternalSystemUnlinkedProjectAware {
    override val systemId = BSP.PROJECT_SYSTEM_ID

    override fun isBuildFile(project: Project, buildFile: VirtualFile): Boolean {
        return BspConfigSteps.canOpenGradleProject(buildFile)
    }

    override fun isLinkedProject(project: Project, externalProjectPath: String): Boolean {
        return BspSettings.getInstance(project).linkedProjectsSettings.isNotEmpty()
    }

    override fun subscribe(
        project: Project,
        listener: ExternalSystemProjectLinkListener,
        parentDisposable: Disposable,
    ) {
        val settings = BspSettings.getInstance(project)
        settings.subscribe(object : ExternalSystemSettingsListener<BspProjectSettings> {
            override fun onProjectsLinked(settings: MutableCollection<BspProjectSettings>) {
               settings.forEach { listener.onProjectLinked(it.externalProjectPath) }
            }

            override fun onProjectsUnlinked(linkedProjectPaths: MutableSet<String>) {
                linkedProjectPaths.forEach { listener.onProjectUnlinked(it) }
            }
        }, parentDisposable)
    }

    override fun linkAndLoadProject(project: Project, externalProjectPath: String) {
        BspOpenProjectProvider.linkToExistingProject(externalProjectPath, project)
    }
}