package com.bino.intellijkotlinbsp.project.importing.setup

import com.bino.intellijkotlinbsp.BspBundle
import com.bino.intellijkotlinbsp.getServerLauncher
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.externalSystem.service.ImportCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
//import org.jetbrains.plugins.gradle.util.GradleBundle
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

class GradleConfigSetup(private val runner: GradleRunner, val runInit: (reporter: BuildReporter) -> Result<BuildMessages>): BspConfigSetup {

    override fun cancel() = runner.cancel()
    override fun run(buildReporter: BuildReporter): Result<BuildMessages> = runInit(buildReporter)

    companion object {
        fun apply(baseDir: Path, jdk: Sdk?): GradleConfigSetup {
            invokeAndWaitIfNeeded {
                ProjectJdkTable.getInstance().preconfigure()
            }
            val projectJdk = jdk
                ?: ProjectJdkTable.getInstance().allJdks.firstOrNull { it.sdkType is JavaSdk }
                ?: throw IllegalStateException("No JDK found")
            val jdkType = JavaSdk.getInstance()
            val jdkExe = Path.of(jdkType.getVMExecutablePath(projectJdk))
            val jdkHome = projectJdk.homePath?.let { Path.of(it) }
            val gradleLauncher = getServerLauncher()

            val vmArgs = listOf<String>()

            val runner = GradleRunner()
            val runInit = { reporter: BuildReporter ->
                runner.runGradle(
                    baseDir.toFile(), jdkExe.toFile(), vmArgs,
                    emptyMap(), gradleLauncher, listOf(
                        "-generateBspConnectionFile",
                        "--projectPath=${baseDir.toAbsolutePath()}",
                        "--serverJar=${gradleLauncher.absolutePath}",
                    ),
                    true, null, reporter
                )
            }
            return GradleConfigSetup(runner, runInit)
        }

//        private fun getLauncher(): File {
//            val gradleHome = System.getenv("GRADLE_HOME")
//            val gradleLauncher = File(gradleHome, "lib/gradle-launcher-6.8.3.jar")
//            return gradleLauncher
//        }
    }
}

class GradleRunner {

    private val cancellationFlag = AtomicBoolean(false)

    companion object {
        private const val GRADLE_PROCESS_CHECK_TIMEOUT_MS = 100L
//        private val MAX_IMPORT_DURATION = 60 * 1000
    }

    fun runGradle(
        directory: File,
        vmExecutable: File,
        vmOptions: List<String>,
        environment: Map<String, String>,
        gradleLauncher: File,
        gradleLauncherArgs: List<String>,
        passParentEnvironment: Boolean,
        indicator: ProgressIndicator?,
        reporter: BuildReporter
    ): Result<BuildMessages> {
        val processCommandsRaw = listOf(
            normalizePath(vmExecutable),
            "-jar",
            normalizePath(gradleLauncher),
            *gradleLauncherArgs.toTypedArray()
        )

        val parentEnv = if (passParentEnvironment) GeneralCommandLine.ParentEnvironmentType.CONSOLE else GeneralCommandLine.ParentEnvironmentType.NONE
        val generalCommandLine = GeneralCommandLine(processCommandsRaw)
            .withParentEnvironmentType(parentEnv)
        val processBuilder = generalCommandLine.toProcessBuilder()
        processBuilder.directory(directory)
        processBuilder.environment().putAll(environment)
        // It is required due to #SCL-19498
        processBuilder.environment()["HISTCONTROL"] = "ignorespace"
        val procString = processBuilder.command().joinToString(" ")
        return try {
            processBuilder.start().let { process ->
                OutputStreamWriter(process.outputStream, "UTF-8").use { osw ->
                    BufferedWriter(osw).use { bw ->
                        PrintWriter(bw).use { w ->
                            w.println()
//                            w.println(ignoreInShellHistory())
                            w.println(ignoreInShellHistory("exit"))
                            w.flush()
                            handle(process, reporter, indicator)
                        }
                    }
                }
            }
        } catch (e: ImportCanceledException) {
            Result.success(BuildMessages.empty().status(BuildMessages.Companion.Canceled))
        } catch (e: Throwable) {
            Result.failure(ImportCanceledException(e))
        }
    }

    fun cancel() {
        cancellationFlag.set(true)
    }

    private fun handle(process: Process, reporter: BuildReporter, indicator: ProgressIndicator?): Result<BuildMessages> {
        var messages = BuildMessages.empty()

        val handler = OSProcessHandler(process, "gradle import", Charsets.UTF_8)
//        handler.addProcessListener

        handler.startNotify()

        var processEnded = false
        while (!processEnded && !cancellationFlag.get()) {
            processEnded = handler.waitFor(GRADLE_PROCESS_CHECK_TIMEOUT_MS)

            if (indicator != null && indicator.isCanceled) {
                cancellationFlag.set(true)
            }
        }

        val exitCode = handler.exitCode
        val result = run {
            if (!processEnded)
                Result.failure<BuildMessages>(ImportCanceledException(BspBundle.message("gradle.task.cancelled")))
            else if (exitCode != 0)
                messages = messages.status(BuildMessages.Companion.Error)
            else if (messages.status == BuildMessages.Companion.Indeterminate)
                messages = messages.status(BuildMessages.Companion.OK)
            Result.success(messages)
        }

        if (!handler.isProcessTerminated) {
            handler.setShouldDestroyProcessRecursively(true)
            handler.destroyProcess()
        }

        return result
    }

    private fun normalizePath(file: File): String {
        return file.absolutePath.replace("\\", "/")
    }

    private fun ignoreInShellHistory(command: String): String = " $command"
}