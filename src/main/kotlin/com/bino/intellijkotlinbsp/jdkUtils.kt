package com.bino.intellijkotlinbsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.JavaHomeFinder
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.util.lang.JavaVersion
import com.jetbrains.rd.util.string.printToString

fun findOrCreateBestJdkForProject(project: Project?): Sdk? =
    project?.let { ProjectRootManager.getInstance(it).projectSdk }
        ?: mostRecentJdkConfiguredInIde()
        ?: createSdkWithMostRecentFoundJDK()

fun mostRecentJdkConfiguredInIde(): Sdk? =
    findMostRecentJdkConfiguredInIde { true }

private fun findMostRecentJdkConfiguredInIde(condition: (Sdk) -> Boolean): Sdk? {
    val sdkType = JavaSdk.getInstance()
    val jdks = ProjectJdkTable.getInstance()
        .getSdksOfType(sdkType)
        .filter(condition)
    if (jdks.isEmpty()) {
        return null
    }
    return jdks.maxWith(sdkType.versionComparator())
}

private fun createSdkWithMostRecentFoundJDK(): Sdk? {
    val jdkType = JavaSdk.getInstance()

    val detectedJavaHomes = JavaHomeFinder
        .suggestHomePaths(false)
        .filter { jdkType.isValidSdkHome(it) }
        .map { p -> Pair(p, JavaVersion.tryParse(p)) }
        .filter { (_, version) -> version != null }

    val latestJavaHome = detectedJavaHomes
        .maxByOrNull { (_, version) -> version!! }
        ?.let { (home, _) -> home }

    return latestJavaHome?.let { home ->
        ExternalSystemApiUtil.executeOnEdt {
            SdkConfigurationUtil.createAndAddSDK(home, jdkType)
        }
    }
}

fun addJdkIfNotExists(sdk: Sdk) {
    val projectJdkTable = ProjectJdkTable.getInstance()
    if (projectJdkTable.findJdk(sdk.name) == null) {
        inWriteAction {
            projectJdkTable.addJdk(sdk)
        }
    }
}

fun <T> inWriteAction(body: () -> T) {
    ApplicationManager.getApplication().let {
        if (it.isWriteAccessAllowed) {
            body()
        } else {
            it.runWriteAction { body() }
        }
    }
}
