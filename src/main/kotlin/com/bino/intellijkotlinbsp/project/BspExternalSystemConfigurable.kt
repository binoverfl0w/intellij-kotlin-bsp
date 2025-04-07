package com.bino.intellijkotlinbsp.project

import com.bino.intellijkotlinbsp.BSP
import com.bino.intellijkotlinbsp.settings.*
import com.intellij.openapi.externalSystem.service.settings.AbstractExternalSystemConfigurable
import com.intellij.openapi.externalSystem.util.ExternalSystemSettingsControl
import com.intellij.openapi.project.Project

class BspExternalSystemConfigurable(
    project: Project
): AbstractExternalSystemConfigurable<BspProjectSettings, BspProjectSettingsListener, BspSettings>(project, BSP.PROJECT_SYSTEM_ID) {
    override fun getId(): String = "bsp.project.settings.configurable"

    override fun newProjectSettings(): BspProjectSettings = BspProjectSettings()

    override fun createSystemSettingsControl(settings: BspSettings): ExternalSystemSettingsControl<BspSettings> = BspSystemSettingsControl(settings)

    override fun createProjectSettingsControl(settings: BspProjectSettings): ExternalSystemSettingsControl<BspProjectSettings> = BspProjectSettingsControl(settings)

}