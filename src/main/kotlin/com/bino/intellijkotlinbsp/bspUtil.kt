package com.bino.intellijkotlinbsp

import com.bino.intellijkotlinbsp.settings.BspSettings
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.project.Project

fun bspSettings(project: Project) =
    ExternalSystemApiUtil
        .getSettings(project, BSP.PROJECT_SYSTEM_ID)
    as BspSettings

fun isBspProject(project: Project): Boolean {
    val settings = bspSettings(project).linkedProjectsSettings
    return !settings.isEmpty()
}