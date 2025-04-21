package com.bino.intellijkotlinbsp.project.importing.setup

import com.bino.intellijkotlinbsp.BspBundle
import com.intellij.openapi.progress.ProgressIndicator
//import org.jetbrains.plugins.gradle.util.GradleBundle

class IndicatorReporter(private val indicator: ProgressIndicator) : BuildReporter {

    override fun start() {
        indicator.text = BspBundle.message("report.build.running")
    }
}