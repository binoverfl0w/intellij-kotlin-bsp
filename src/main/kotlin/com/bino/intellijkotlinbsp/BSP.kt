package com.bino.intellijkotlinbsp

import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.util.IconLoader

object BSP {
    const val NAME = "BSP"
    val ICON = IconLoader.getIcon("/com/bino/plugins/kotlin/bsp/images/buildServerProtocol.svg", BSP::class.java)
    val PROJECT_SYSTEM_ID = ProjectSystemId("BSP", NAME)
    val NOTIFICATION_GROUP: NotificationGroup by lazy {  NotificationGroupManager.getInstance().getNotificationGroup(NAME) }
    
    // Shared constants
    const val COMPILE_TASK_PREFIX = "compile:"
    // Add other prefixes like TEST_TASK_PREFIX here later
}