package com.bino.intellijkotlinbsp.protocol

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class BspConnector(private val workspace: File, private val traceMessages: Boolean = false) {

    private val log = Logger.getInstance(BspConnector::class.java)
    private var process: Process? = null

    val inputStream: InputStream? get() = process?.inputStream
    val outputStream: OutputStream? get() = process?.outputStream
    val errorStream: InputStream? get() = process?.errorStream

    fun connect(connectionDetails: BspConnectionDetails) {
        if (process != null) {
            log.warn("BSP server process already started for ${connectionDetails.name}")
            return
        }

        log.info("Starting BSP server process: ${connectionDetails.argv.joinToString(" ")}")
        log.info("Working directory: ${workspace.absolutePath}")

        try {
            val processBuilder = ProcessBuilder(connectionDetails.argv)
            processBuilder.directory(workspace)

            process = processBuilder.start()
            log.info("BSP server process started successfully (PID: ${process?.pid()})")

            startErrorStreamLogger()

        } catch (e: IOException) {
            log.error("Failed to start BSP server process: ${connectionDetails.argv}", e)
            process = null
            throw e
        }
    }

    fun disconnect() {
        process?.let {
            log.info("Stopping BSP server process (PID: ${it.pid()})")
            try {
                // TODO: Consider more graceful shutdown (sending shutdown/exit) before destroying
                if (it.isAlive) {
                    it.destroyForcibly()
                    it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                    log.info("BSP server process destroyed.")
                }
            } catch (e: Exception) {
                log.warn("Error stopping BSP server process", e)
            }
        }
        process = null
    }

    private fun startErrorStreamLogger() {
        val errStream = errorStream ?: return
        Thread { 
            try {
                errStream.bufferedReader().forEachLine { line ->
                    log.warn("[BSP Server STDERR] $line")
                }
            } catch (e: IOException) {
                log.debug("BSP server stderr stream closed.")
            }
        }.apply {
            name = "BSP Server STDERR Reader"
            isDaemon = true
            start()
        }
    }
} 