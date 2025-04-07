package com.bino.intellijkotlinbsp.project.importing.setup

import com.intellij.build.events.Failure
import com.intellij.build.events.Warning

data class BuildMessages(
    val warnings: List<Warning>,
    val errors: List<Failure>,
    val exceptions: List<Exception>,
    val messages: List<String>,
    val status: BuildStatus,
){

    fun status(buildStatus: BuildStatus): BuildMessages = copy(status = buildStatus)

    companion object {
        sealed class BuildStatus

        data object Indeterminate : BuildStatus()
        data object Canceled : BuildStatus()
        data object Error : BuildStatus()
        data object OK : BuildStatus()

        fun empty(): BuildMessages = BuildMessages(emptyList(), emptyList(), emptyList(), emptyList(), Indeterminate)
    }
}
