package com.bino.intellijkotlinbsp.project

import com.bino.intellijkotlinbsp.BSP
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.ui.ExternalSystemTextProvider

class BspTextProvider : ExternalSystemTextProvider {
    override val systemId: ProjectSystemId = BSP.PROJECT_SYSTEM_ID
}