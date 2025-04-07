package com.bino.intellijkotlinbsp.settings

import com.bino.intellijkotlinbsp.BSP
import com.bino.intellijkotlinbsp.BspBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.externalSystem.model.settings.ExternalSystemExecutionSettings
import com.intellij.openapi.externalSystem.service.settings.AbstractExternalProjectSettingsControl
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemLocalSettings
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemSettings
import com.intellij.openapi.externalSystem.settings.DelegatingExternalSystemSettingsListener
import com.intellij.openapi.externalSystem.settings.ExternalProjectSettings
import com.intellij.openapi.externalSystem.settings.ExternalSystemSettingsListener
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemSettingsControl
import com.intellij.openapi.externalSystem.util.ExternalSystemUiUtil.getFillLineConstraints
import com.intellij.openapi.externalSystem.util.PaintAwarePanel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.toCanonicalPath
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.Converter
import com.intellij.util.xmlb.annotations.OptionTag
import java.beans.BeanProperty
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import javax.swing.JCheckBox

class BspProjectSettings : ExternalProjectSettings() {

    @get:BeanProperty
    var buildOnSave = false

    @get:BeanProperty
    var runPreImportTask = true

    @get:BeanProperty
    var serverConfig: BspServerConfig = AutoConfig

    @get:BeanProperty
    @OptionTag(converter = PreImportConfigConverter::class)
    var preImportConfig: PreImportConfig = AutoPreImport

    override fun setExternalProjectPath(externalProjectPath: String) {
       super.setExternalProjectPath(ExternalSystemApiUtil.toCanonicalPath(externalProjectPath))
    }

    override fun clone(): ExternalProjectSettings {
        val result = BspProjectSettings()
        copyTo(result)
        result.buildOnSave = buildOnSave
        result.runPreImportTask = runPreImportTask
        result.serverConfig = serverConfig
        result.preImportConfig = preImportConfig
        return result
    }

    companion object {
        sealed class BspServerConfig
        data object AutoConfig : BspServerConfig()
        data class BspConfigFile(val path: Path) : BspServerConfig()

        sealed class PreImportConfig
        data object NoPreImport : PreImportConfig()
        data object AutoPreImport : PreImportConfig()


        class PreImportConfigConverter : Converter<PreImportConfig>() {
            override fun fromString(value: String): PreImportConfig? {
                return when (value) {
                    "NoPreImport" -> NoPreImport
                    "AutoPreImport" -> AutoPreImport
                    else -> null
                }
            }

            override fun toString(value: PreImportConfig): String {
                return when(value) {
                    AutoPreImport -> "AutoPreImport"
                    NoPreImport -> "NoPreImport"
                }
            }
        }

        class BspServerConfigConverter : Converter<BspServerConfig>() {
            private val configFile = "BspConfigFile:(?<path>.*)".toRegex()
            override fun fromString(value: String): BspServerConfig? {
                return when (value) {
                    "AutoConfig" -> AutoConfig
                    else -> {
                        val match = configFile.matchEntire(value) ?: return null
                        val path = match.groups["path"]?.value
                            ?: throw IllegalArgumentException("Path group not found in $value")
                        BspConfigFile(Paths.get(path))
                    }
                }
            }

            override fun toString(value: BspServerConfig): String {
                return when(value) {
                    AutoConfig -> "AutoConfig"
                    is BspConfigFile -> "BspConfigFile:${value}"
                }
            }
        }
    }
}

class BspProjectSettingsControl(settings: BspProjectSettings) : AbstractExternalProjectSettingsControl<BspProjectSettings>(null, settings) {

    @get:BeanProperty
    var buildOnSave: Boolean = false

    private val buildOnSaveCheckBox = JCheckBox(BspBundle.message("bsp.protocol.build.automatically.on.file.save"))

    override fun validate(settings: BspProjectSettings): Boolean = true

    override fun fillExtraControls(content: PaintAwarePanel, indentLevel: Int) {
        val fillLineConstraints = getFillLineConstraints(1)
        content.add(buildOnSaveCheckBox, fillLineConstraints)
    }

    override fun isExtraSettingModified(): Boolean {
        val initial = initialSettings
        return buildOnSaveCheckBox.isSelected != initial.buildOnSave
    }

    override fun resetExtraSettings(isDefaultModuleCreation: Boolean) {
        val initial = initialSettings
        buildOnSaveCheckBox.isSelected = initial.buildOnSave
    }

    override fun applyExtraSettings(settings: BspProjectSettings) {
        settings.buildOnSave = buildOnSaveCheckBox.isSelected
    }

    override fun updateInitialExtraSettings() {
        applyExtraSettings(initialSettings)
    }
}

interface BspProjectSettingsListener : ExternalSystemSettingsListener<BspProjectSettings> {
    fun onBuildOnSaveChanged(buildOnSave: Boolean)
}

class BspProjectSettingsListenerAdapter(listener: ExternalSystemSettingsListener<BspProjectSettings>) : DelegatingExternalSystemSettingsListener<BspProjectSettings>(listener), BspProjectSettingsListener {
    override fun onBuildOnSaveChanged(buildOnSave: Boolean) = Unit
}

@State(name = "BspSettings", storages = [Storage("bsp.xml")])
class BspSettings(project: Project): AbstractExternalSystemSettings<BspSettings, BspProjectSettings, BspProjectSettingsListener>(BspTopic, project), PersistentStateComponent<BspSettings.Companion.State>{

    override fun subscribe(listener: ExternalSystemSettingsListener<BspProjectSettings>, parentDisposable: Disposable) =
        doSubscribe(BspProjectSettingsListenerAdapter(listener), parentDisposable)

    override fun copyExtraSettingsFrom(settings: BspSettings) = Unit

    override fun checkSettings(old: BspProjectSettings, current: BspProjectSettings) {
        // TODO: Implement
    }

    override fun getState(): State {
        val state = State()
        fillState(state)
        return state
    }

    override fun loadState(state: State) {
        super.loadState(state)
    }

    fun getSystemSettings(): BspSystemSettings = BspSystemSettings.getInstance()

    companion object {
        val BspTopic = Topic(BspBundle.message("bsp.protocol.specific.settings"), BspProjectSettingsListener::class.java)

        class State : AbstractExternalSystemSettings.State<BspProjectSettings> {
            private val projectSettings = TreeSet<BspProjectSettings>()

            override fun getLinkedExternalProjectsSettings(): MutableSet<BspProjectSettings> = projectSettings
            override fun setLinkedExternalProjectsSettings(settings: MutableSet<BspProjectSettings>) {
//                projectSettings.clear()
                projectSettings.addAll(settings)
            }

        }

        fun getInstance(project: Project): BspSettings {
            return project.getService(BspSettings::class.java)
        }
    }
}

@State(name = "BspSettings", storages = [Storage("bsp.settings.xml")], reportStatistic = true, category = SettingsCategory.TOOLS)
class BspSystemSettings : PersistentStateComponent<BspSystemSettings.Companion.State> {

    @get:BeanProperty
    var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): BspSystemSettings {
            return ApplicationManager.getApplication().getService(BspSystemSettings::class.java)
        }

        class State {
            @get:BeanProperty
            var traceBsp = false
        }
    }
}

@State(name = "BspLocalSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class BspLocalSettings(project: Project) : AbstractExternalSystemLocalSettings<BspLocalSettingsState>(BSP.PROJECT_SYSTEM_ID, project), PersistentStateComponent<BspLocalSettingsState> {
    override fun loadState(state: BspLocalSettingsState) {
        super.loadState(state)
    }

    companion object {
        fun getInstance(project: Project): BspLocalSettings {
            return project.getService(BspLocalSettings::class.java)
        }
    }
}

class BspLocalSettingsState : AbstractExternalSystemLocalSettings.State()

class BspExecutionSettings(
    private val basePath: Path,
    private val traceBsp: Boolean,
    private val runPreImportTask: Boolean,
    private val preImportTask: BspProjectSettings.Companion.PreImportConfig,
    private val config: BspProjectSettings.Companion.BspServerConfig,
) : ExternalSystemExecutionSettings() {

    companion object {
        fun executionSettingsFor(project: Project?, basePath: Path): BspExecutionSettings {
            if (project == null) return executionSettingsFor(basePath)
            val bspSettings = BspSettings.getInstance(project)
            val bspTraceLog = bspSettings.getSystemSettings().state.traceBsp
            val linkedSettings = bspSettings.getLinkedProjectSettings(basePath.toCanonicalPath())
            val runPreImportTask = linkedSettings?.runPreImportTask ?: false
            val preImportConfig = linkedSettings?.preImportConfig ?: BspProjectSettings.Companion.AutoPreImport
            val serverConfig = linkedSettings?.serverConfig ?: BspProjectSettings.Companion.AutoConfig

            return BspExecutionSettings(
                basePath, bspTraceLog, runPreImportTask, preImportConfig, serverConfig
            )
        }

        fun executionSettingsFor(basePath: Path): BspExecutionSettings {
            val systemSettings = BspSystemSettings.getInstance()
            val defaultProjectSettings = BspProjectSettings()
            return BspExecutionSettings(
                basePath, systemSettings.state.traceBsp, defaultProjectSettings.runPreImportTask, BspProjectSettings.Companion.AutoPreImport, BspProjectSettings.Companion.AutoConfig
            )
        }
    }
}

class BspSystemSettingsControl(settings: BspSettings) : ExternalSystemSettingsControl<BspSettings> {

    private val pane = BspSystemSettingsPane()
    private val systemSettings = settings.getSystemSettings()

    override fun fillUi(canvas: PaintAwarePanel, indentLevel: Int) {
        canvas.add(pane.content, getFillLineConstraints(1))
    }

    override fun reset() {
        pane.traceBspCheckbox.isSelected = systemSettings.state.traceBsp
    }

    override fun isModified(): Boolean {
        return pane.traceBspCheckbox.isSelected != systemSettings.state.traceBsp
    }

    override fun disposeUIResources() = Unit

    override fun showUi(show: Boolean) {
        pane.content.isVisible = show
    }

    override fun validate(settings: BspSettings): Boolean = true

    override fun apply(settings: BspSettings) {
        systemSettings.state.traceBsp = pane.traceBspCheckbox.isSelected
    }
}