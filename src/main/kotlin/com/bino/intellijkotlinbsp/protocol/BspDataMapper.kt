package com.bino.intellijkotlinbsp.protocol

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.DependencySourcesItem
import ch.epfl.scala.bsp4j.SourceItem
import ch.epfl.scala.bsp4j.SourceItemKind
import ch.epfl.scala.bsp4j.SourcesItem
import com.bino.intellijkotlinbsp.BSP
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.*
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.util.io.FileUtil
import java.io.File
import java.net.URI
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.toPath

class BspDataMapper {

    private val log = Logger.getInstance(BspDataMapper::class.java)

    fun mapBspModel(
        bspModel: BspModel,
        projectNode: DataNode<ProjectData>,
        listener: ExternalSystemTaskNotificationListener,
        taskId: ExternalSystemTaskId
    ) {
        log.info("Starting mapping of BSP model to IntelliJ project structure.")
        listener.onTaskOutput(taskId, "Mapping BSP model...\n", true)

        val projectData = projectNode.data

        val libraryRegistry = mutableMapOf<String, DataNode<LibraryData>>()
        val moduleRegistry = mutableMapOf<String, DataNode<ModuleData>>()

        bspModel.buildTargets.forEach { target ->
            val moduleNode = createModuleDataNode(projectNode, projectData, target)
            moduleRegistry[target.id.uri] = moduleNode // Register module node
            listener.onTaskOutput(taskId, "Mapped module: ${moduleNode.data.internalName}\n", false)
        }

        bspModel.buildTargets.forEach { target ->
            val moduleNode = moduleRegistry[target.id.uri]
            if (moduleNode == null) {
                log.debug("Skipping sources/dependencies for target ${target.id.uri} as it might be part of a grouped module.")
                return@forEach
            }

            val sourcesItem = bspModel.sources[target]
            if (sourcesItem != null) {
                mapSources(moduleNode, sourcesItem, target)
            } else {
                log.warn("Missing SourcesItem for target: ${target.id.uri}")
            }

            val dependencySourcesItem = bspModel.dependencySources[target]
            val targetDependencies = target.dependencies

            if (dependencySourcesItem != null) {
                mapLibraryDependencies(projectNode, moduleNode, dependencySourcesItem, libraryRegistry)
            } else {
                 log.warn("Missing DependencySourcesItem for target: ${target.id.uri}")
            }

            mapModuleDependencies(moduleNode, targetDependencies, moduleRegistry)
        }

        log.info("Finished mapping BSP model.")
        listener.onTaskOutput(taskId, "Mapping complete.\n", true)
    }

    private fun createModuleDataNode(
        projectNode: DataNode<ProjectData>,
        projectData: ProjectData,
        target: BuildTarget
    ): DataNode<ModuleData> {
        val moduleId = target.id.uri
        val moduleName = target.displayName ?: File(URI(target.id.uri)).name
        val moduleExternalName = moduleName
        val moduleInternalName = moduleName

        val moduleFileDirPath = projectData.linkedExternalProjectPath
        val moduleConfigPath = projectData.linkedExternalProjectPath

        val moduleTypeId = if (target.languageIds.contains("kotlin")) {
             "KOTLIN_MODULE"
        } else {
             "JAVA_MODULE"
        }

        val moduleData = ModuleData(moduleId, BSP.PROJECT_SYSTEM_ID, moduleTypeId,
            moduleName, moduleFileDirPath, moduleConfigPath)

        moduleData.setProperty("bspTargetTags", target.tags.joinToString(","))
        target.baseDirectory?.let { moduleData.setProperty("bspBaseDirectory", it) }


        return projectNode.createChild(ProjectKeys.MODULE, moduleData)
    }

    private fun mapSources(
        moduleNode: DataNode<ModuleData>,
        sourcesItem: SourcesItem,
        target: BuildTarget
    ) {
        val isTestTarget = target.tags?.contains("test") == true
        val moduleBasePath = moduleNode.data.getProperty("bspBaseDirectory")
            ?.let { FileUtil.toSystemIndependentName(URI(it).path) }
            ?: moduleNode.data.linkedExternalProjectPath

        val contentRootData = ContentRootData(BSP.PROJECT_SYSTEM_ID, moduleBasePath)
        var hasContent = false

        sourcesItem.sources.forEach { sourceItem ->
            val path = FileUtil.toSystemIndependentName(URI(sourceItem.uri).path)

            val sourceType = when {
                 isTestTarget && sourceItem.generated -> ExternalSystemSourceType.TEST_GENERATED
                 isTestTarget -> ExternalSystemSourceType.TEST
                 sourceItem.generated -> ExternalSystemSourceType.SOURCE_GENERATED
                 else -> ExternalSystemSourceType.SOURCE
            }

            contentRootData.storePath(sourceType, path)
            hasContent = true
        }

         sourcesItem.roots?.forEach { rootUri ->
            val path = FileUtil.toSystemIndependentName(URI(rootUri).path)
            if (path.startsWith(moduleBasePath)) {
                 contentRootData.storePath(if (isTestTarget) ExternalSystemSourceType.TEST else ExternalSystemSourceType.SOURCE, path)
                 hasContent = true
             } else {
                 log.warn("Source root $path is outside module base path $moduleBasePath for target ${target.id.uri}. Skipping.")
             }
         }

        if (hasContent) {
             moduleNode.createChild(ProjectKeys.CONTENT_ROOT, contentRootData)
        }
    }

    private fun mapLibraryDependencies(
        projectNode: DataNode<ProjectData>,
        moduleNode: DataNode<ModuleData>,
        dependencySourcesItem: DependencySourcesItem,
        libraryRegistry: MutableMap<String, DataNode<LibraryData>>
    ) {

        dependencySourcesItem.sources.forEach { depSourceUri ->
            val path = FileUtil.toSystemIndependentName(URI(depSourceUri).path)
            val libraryId = File(path).name

            val libraryNode = libraryRegistry.computeIfAbsent(libraryId) { id ->
                val libraryData = LibraryData(BSP.PROJECT_SYSTEM_ID, id, true)
                libraryData.addPath(LibraryPathType.BINARY, path)
                projectNode.createChild(ProjectKeys.LIBRARY, libraryData)
            }

            val libraryDependencyData = LibraryDependencyData(
                moduleNode.data,
                libraryNode.data,
                LibraryLevel.PROJECT
            )
            val isTestModule = moduleNode.data.getProperty("bspTargetTags")?.contains("test") == true
            libraryDependencyData.scope = if (isTestModule) DependencyScope.TEST else DependencyScope.COMPILE
            moduleNode.createChild(ProjectKeys.LIBRARY_DEPENDENCY, libraryDependencyData)
        }
    }

     private fun mapModuleDependencies(
         moduleNode: DataNode<ModuleData>,
         dependencyTargetIds: List<ch.epfl.scala.bsp4j.BuildTargetIdentifier>,
         moduleRegistry: Map<String, DataNode<ModuleData>>
     ) {
         val isTestModule = moduleNode.data.getProperty("bspTargetTags")?.contains("test") == true

         dependencyTargetIds.forEach { depTargetId ->
             val dependencyModuleNode = moduleRegistry[depTargetId.uri]
             if (dependencyModuleNode != null) {
                 val moduleDependencyData = ModuleDependencyData(
                     moduleNode.data,
                     dependencyModuleNode.data
                 )
                 val isDepTestModule = dependencyModuleNode.data.getProperty("bspTargetTags")?.contains("test") == true

                 moduleDependencyData.scope = when {
                      isTestModule -> DependencyScope.TEST
                      isDepTestModule -> DependencyScope.TEST
                      else -> DependencyScope.COMPILE
                 }
                 moduleDependencyData.isExported = false
                 moduleDependencyData.isProductionOnTestDependency = isDepTestModule && !isTestModule

                 moduleNode.createChild(ProjectKeys.MODULE_DEPENDENCY, moduleDependencyData)
             } else {
                 log.warn("Could not resolve dependency target to a project module: ${depTargetId.uri}. Treating as unresolved external dependency.")
             }
         }
     }
} 