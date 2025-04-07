package com.bino.intellijkotlinbsp

import com.intellij.util.PathUtil
import java.io.File

fun getServerLauncher() = pluginBase().parentFile.resolve("server.jar")

fun getGradleInitScript() = File(pluginBase(), "plugins").resolve("init.gradle")

private fun pluginBase(): File {
    val file = jarWith(BSP::class.java)
    val deep = if (file.name == "classes") 1 else 2
    return parent(file, deep) ?: throw IllegalStateException("Cannot find plugin base")
}

private fun jarWith(clazz: Class<*>): File {
    val file = PathUtil.getJarPathForClass(clazz)
    return File(file)
}

private fun parent(file: File?, level: Int): File? {
    if (level > 0 && file != null) parent(file.parentFile, level - 1)
    return file
}