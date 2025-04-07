package com.bino.intellijkotlinbsp.protocol

import ch.epfl.scala.bsp4j.BspConnectionDetails
import com.bino.intellijkotlinbsp.BspBundle
import com.bino.intellijkotlinbsp.BspConnectionError
import com.google.gson.Gson
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil.defaultIfEmpty
import com.intellij.util.SystemProperties
import java.nio.file.Files
import java.nio.file.Path

object BspConnectionConfig {

    private const val BSP_WORKSPACE_CONFIG_DIR_NAME = ".bsp"
    private const val BSP_SYSTEM_CONFIG_DIR_NAME = "bsp"

    fun workspaceConfigurationFiles(workspace: Path): List<Path> {
        val workspaceConfigDir = workspace.resolve(BSP_WORKSPACE_CONFIG_DIR_NAME).toFile()
        return if (workspaceConfigDir.isDirectory) {
            workspaceConfigDir
                .listFiles { _, name -> name.endsWith(".json") }
                ?.map { it.toPath() }
                ?.toList()
                ?: emptyList()
        } else {
            emptyList()
        }
    }

    fun workspaceBspConfigs(workspace: Path): List<Pair<Path, BspConnectionDetails>> {
        val files = workspaceConfigurationFiles(workspace)
        return tryReadingConnectionFiles(files)
            .filter { it.isSuccess }
            .map { it.getOrThrow() }
            .toList()
    }

    fun allConfigs(workspace: Path): List<Pair<Path, BspConnectionDetails>> {
        val workspaceConfigs = workspaceConfigurationFiles(workspace)
        val systemConfigs = systemDependentConnectionFiles()
        val potentialConfigs = tryReadingConnectionFiles(workspaceConfigs + systemConfigs)

        return potentialConfigs
            .filter { it.isSuccess }
            .map { it.getOrThrow() }
            .toList()
    }

    private fun systemDependentConnectionFiles(): List<Path> {
        val basePaths =
            when {
                SystemInfo.isWindows -> windowsBspFiles()
                SystemInfo.isUnix -> unixBspFiles()
                SystemInfo.isMac -> macBspFiles()
                else -> emptyList()
            }
        return listFiles(bspDirs(basePaths))
    }

    private fun tryReadingConnectionFiles(files: List<Path>): List<Result<Pair<Path, BspConnectionDetails>>> {
        val gson = Gson()
        return files
            .map { f ->
                readConnectionFile(f, gson)
                    .map { Pair(f, it) }
            }
            .toList()
    }

    private fun readConnectionFile(path: Path, gson: Gson): Result<BspConnectionDetails> {
        if (Files.isReadable(path)) {
            Files.newBufferedReader(path).use { reader ->
                return Result.success(gson.fromJson(reader, BspConnectionDetails::class.java))
            }
        } else {
            return Result.failure(BspConnectionError(BspBundle.message("bsp.protocol.file.not.readable", path)))
        }
    }

    private fun windowsBspFiles(): List<String> {
        val localAppData = System.getenv("LOCALAPPDATA")
        val programData = System.getenv("PROGRAMDATA")
        return listOf(localAppData, programData)
    }

    private fun unixBspFiles(): List<String> {
        val xdgDataHome = System.getenv("XDG_DATA_HOME")
        val xdgDataDirs = System.getenv("XDG_DATA_DIRS")
        val dataHome = defaultIfEmpty(xdgDataHome, "${SystemProperties.getUserHome()}/.local/share")
        val dataDirs = defaultIfEmpty(xdgDataDirs, "/usr/local/share:/usr/share").split(":").toList()
        return listOf(dataHome) + dataDirs
    }

    private fun macBspFiles(): List<String> {
        val userHome = SystemProperties.getUserHome()
        val userData = "$userHome/Library/Application Support"
        val systemData = "/Library/Application Support"
        return listOf(userData, systemData)
    }

    private fun bspDirs(basePaths: List<String>): List<Path> =
        basePaths.map { Path.of(it, BSP_SYSTEM_CONFIG_DIR_NAME) }

    private fun listFiles(dirs: List<Path>): List<Path> =
        dirs
            .flatMap { path ->
                val f = path.toFile()
                if (f.isDirectory) {
                    f.listFiles()?.map { it.toPath() } ?: emptyList()
                } else {
                    emptyList()
                }
            }
}