package com.bino.intellijkotlinbsp.project.importing.setup

object NoConfigSetup : BspConfigSetup {
    override fun cancel() = Unit

    override fun run(buildReporter: BuildReporter): Result<BuildMessages> =
        Result.success(BuildMessages.empty())
}