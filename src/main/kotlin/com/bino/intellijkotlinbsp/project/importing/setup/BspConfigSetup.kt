package com.bino.intellijkotlinbsp.project.importing.setup

interface BspConfigSetup {
    fun cancel()
    fun run(buildReporter: BuildReporter): Result<BuildMessages>
}