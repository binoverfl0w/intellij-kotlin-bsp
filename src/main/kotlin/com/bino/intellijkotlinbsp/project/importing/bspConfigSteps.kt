package com.bino.intellijkotlinbsp.project.importing

import ch.epfl.scala.bsp4j.BspConnectionDetails
import com.bino.intellijkotlinbsp.BspBundle
import com.bino.intellijkotlinbsp.addJdkIfNotExists
import com.bino.intellijkotlinbsp.findOrCreateBestJdkForProject
import com.bino.intellijkotlinbsp.project.importing.setup.BspConfigSetup
import com.bino.intellijkotlinbsp.project.importing.setup.GradleConfigSetup
import com.bino.intellijkotlinbsp.project.importing.setup.IndicatorReporter
import com.bino.intellijkotlinbsp.project.importing.setup.NoConfigSetup
import com.bino.intellijkotlinbsp.protocol.BspConnectionConfig
import com.bino.intellijkotlinbsp.settings.BspProjectSettings
import com.intellij.ide.util.projectWizard.ModuleWizardStep
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkTypeId
import com.intellij.openapi.roots.ui.configuration.JdkComboBox
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBList
import com.intellij.uiDesigner.core.GridConstraints
import com.intellij.uiDesigner.core.GridLayoutManager
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UI
import java.nio.file.Path
import javax.swing.JComponent
import org.jetbrains.plugins.gradle.service.project.open.canOpenGradleProject
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListSelectionModel

object BspConfigSteps  {
    sealed class ConfigSetup
    data object NoSetup : ConfigSetup()
    data object GradleSetup : ConfigSetup()

    fun configChoiceName(config: ConfigSetup): String = when(config) {
        NoSetup -> BspBundle.message("bsp.config.steps.choice.no.setup")
        GradleSetup -> BspBundle.message("bsp.config.steps.choice.gradle")
    }

    fun configureBuilder(
        jdk: Sdk?,
        builder: BspProjectImportBuilder,
        workspace: Path,
        configSetup: ConfigSetup,
    ): BspConfigSetup {
        val parameters = getBuilderConfigurationParameters(jdk, workspace, configSetup)
        parameters.preImportConfig?.let { builder.preImportConfig = it }
        parameters.serverConfig?.let { builder.serverConfig = it }
        parameters.externalBspWorkspace?.let { builder.externalBspWorkspace = it }

        return parameters.bspConfigSetup
    }

    data class BuilderConfigurationParameters(
        val bspConfigSetup: BspConfigSetup,
        val preImportConfig: BspProjectSettings.Companion.PreImportConfig?,
        val serverConfig: BspProjectSettings.Companion.BspServerConfig?,
        val externalBspWorkspace: Path?,
    )

    fun getBuilderConfigurationParameters(
        jdk: Sdk?,
        workspace: Path,
        configSetup: ConfigSetup,
    ): BuilderConfigurationParameters {
        val workspaceBspConfigs = BspConnectionConfig.workspaceConfigurationFiles(workspace)

        return if (workspaceBspConfigs.size == 1) {
            BuilderConfigurationParameters(NoConfigSetup, BspProjectSettings.Companion.NoPreImport, BspProjectSettings.Companion.BspConfigFile(workspaceBspConfigs[0]), null)
        } else when(configSetup) {
            NoSetup -> BuilderConfigurationParameters(NoConfigSetup, BspProjectSettings.Companion.AutoPreImport, BspProjectSettings.Companion.AutoConfig, null)
            GradleSetup -> BuilderConfigurationParameters(GradleConfigSetup.apply(workspace, jdk), BspProjectSettings.Companion.NoPreImport, BspProjectSettings.Companion.AutoConfig, null)
        }
    }

    fun configSetupChoices(workspace: Path): List<ConfigSetup> {
        val workspaceConfigs = workspaceSetupChoices(workspace)
        return listOf(workspaceConfigs)
    }

    fun workspaceSetupChoices(workspace: Path): ConfigSetup {
        val vFile = LocalFileSystem.getInstance().findFileByIoFile(workspace.toFile())!!
//        val gradleChoice = if (JavaGradleProjectImportProvider.canOpenProject(vFile)) GradleSetup else null

        return if (canOpenGradleProject(vFile)) {
            GradleSetup
        } else {
            NoSetup
        }
    }
}

class BspSetupConfigStep(
    private val wizardContext: WizardContext,
    private val builder: BspProjectImportBuilder,
    private val setupTaskWorkspace: Path,
): ModuleWizardStep() {

    private var runSetupTask: BspConfigSetup = NoConfigSetup

    private val workspaceBspConfigs = BspConnectionConfig.workspaceConfigurationFiles(setupTaskWorkspace)
    private val workspaceSetupConfigs by lazy { BspConfigSteps.workspaceSetupChoices(setupTaskWorkspace) }
    private val existingJdk = findOrCreateBestJdkForProject(wizardContext.project)

    private val configSetupChoices =
        if (workspaceBspConfigs.size == 1) listOf(BspConfigSteps.NoSetup)
        else listOf(workspaceSetupConfigs)

    private val bspSetupConfigStepUi = BspSetupConfigStepUi(BspBundle.message("bsp.config.steps.setup.config.choose.tool"), configSetupChoices, existingJdk == null)

    override fun getComponent(): JComponent = bspSetupConfigStepUi.mainComponent

    override fun getPreferredFocusedComponent(): JComponent = bspSetupConfigStepUi.chooseBspSetupList

    override fun validate(): Boolean =
        (workspaceBspConfigs.isNotEmpty() ||
                bspSetupConfigStepUi.chooseBspSetupList.selectedIndex >= 0)
                && bspSetupConfigStepUi.isJdkSelectedIfRequired()

    override fun updateStep() {
       bspSetupConfigStepUi.updateChooseBspSetupComponent()
    }

    override fun updateDataModel() {
//        val configIndex = bspSetupConfigStepUi.chooseBspSetupList.selectedIndex
        val configIndex = 0
//        val jdkOpt = if (configIndex >= 0) {
//            existingJdk ?: bspSetupConfigStepUi.getSelectedJdkIfRequired()
//        } else null
//
//        runSetupTask = when(jdkOpt) {
//            null -> NoConfigSetup
//            else -> BspConfigSteps.configureBuilder(jdkOpt, builder, setupTaskWorkspace, configSetupChoices[configIndex])
//        }
        runSetupTask = BspConfigSteps.configureBuilder(findOrCreateBestJdkForProject(wizardContext.project), builder, setupTaskWorkspace, configSetupChoices[configIndex])
    }

    override fun isStepVisible(): Boolean =
        builder.preImportConfig == BspProjectSettings.Companion.AutoPreImport &&
                (configSetupChoices.size > 1 || existingJdk == null) &&
                workspaceBspConfigs.isEmpty()

    override fun onWizardFinished() {
        if (wizardContext.projectBuilder is BspProjectImportBuilder) {
            bspSetupConfigStepUi.getSelectedJdkIfRequired()?.let {
                addJdkIfNotExists(it)
            }
            updateDataModel()
            builder.prepare(wizardContext)
            val task = BspConfigSetupTask(runSetupTask)
            task.queue()
        }
    }

    companion object {
        class BspConfigSetupTask(
            private val setup: BspConfigSetup,
        ) : Task.Modal(null, BspBundle.message("bsp.config.steps.setup.config.task.title"), true) {

            override fun run(indicator: ProgressIndicator) {
                val reporter = IndicatorReporter(indicator)
                setup.run(reporter)
            }

            override fun onCancel() {
                setup.cancel()
            }
        }
    }

}

class BspSetupConfigStepUi(
    title: String,
    private val configSetups: List<BspConfigSteps.ConfigSetup>,
    private val showJdkComboBox: Boolean,
) {
    val mainComponent = run {
        val manager = GridLayoutManager(5, 1)
        manager.isSameSizeHorizontally = false
        JPanel(manager)
    }
    private val chooseBspSetupModel = DefaultListModel<String>()
    val chooseBspSetupList = JBList(chooseBspSetupModel)
    private val model = ProjectSdksModel()

    private val jdkComboBox = run {
        model.reset(null)
        val jdkFilter = { sdk: SdkTypeId -> sdk == JavaSdk.getInstance() }
        JdkComboBox(null, model, jdkFilter, null, jdkFilter, null)
    }

    init {
        var row = 0
        val titleWithTip = withTooltip(TitledSeparator(title), BspBundle.message("bsp.config.steps.setup.config.choose.tool.tooltip"))
        chooseBspSetupList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        row = addTitledComponent(mainComponent, titleWithTip, chooseBspSetupList, row, false)

        if (showJdkComboBox) {
            val panelForComboBox = JPanel(GridBagLayout())
            val label = JLabel("JDK")
            panelForComboBox.add(label, GridBagConstraints(0, 1, 1, 1, 0.0, 0.0, GridBagConstraints.NORTHWEST, GridBagConstraints.NONE, JBUI.insetsTop(8), 0, 0))
            panelForComboBox.add(jdkComboBox, GridBagConstraints(1, 1, 1, 1, 1.0, 0.0, GridBagConstraints.NORTHWEST, GridBagConstraints.HORIZONTAL, JBUI.insets(2, 10, 10, 0), 0, 0))
            val jdkTitleWithTip = withTooltip(TitledSeparator(BspBundle.message("bsp.config.steps.setup.config.choose.jdk")), BspBundle.message("bsp.config.steps.setup.config.choose.jdk.tooltip"))
            addTitledComponent(mainComponent, jdkTitleWithTip, panelForComboBox, row, true)
        } else {
            addSpacer(mainComponent, row)
        }
    }

    fun selectedConfigSetup(): BspConfigSteps.ConfigSetup =
        configSetups[chooseBspSetupList.selectedIndex]

    fun updateChooseBspSetupComponent() {
        val setupChoicesStrings = getConfigSetupChoicesStrings(configSetups)
        chooseBspSetupModel.clear()
        setupChoicesStrings.forEach { chooseBspSetupModel.addElement(it) }
        chooseBspSetupList.selectedIndex = 0
    }

    private fun getConfigSetupChoicesStrings(configSetupChoices: List<BspConfigSteps.ConfigSetup>): List<String> {
        val recommendedSuffix = BspBundle.message("bsp.config.steps.choose.config.recommended.suffix")
        val configChoiceName = configSetupChoices.map { BspConfigSteps.configChoiceName(it) }
        return configChoiceName.mapIndexed { index, choice ->
            if (index == 0) {
                "$choice ($recommendedSuffix)"
            } else {
                choice
            }
        }
    }

    fun getSelectedJdkIfRequired(): Sdk? =
        if (showJdkComboBox) jdkComboBox.selectedJdk else null

    fun isJdkSelectedIfRequired(): Boolean =
        if (showJdkComboBox) jdkComboBox.selectedJdk != null else true

    private fun withTooltip(component: JComponent, tooltip: String) =
        UI.PanelFactory.panel(component).withTooltip(tooltip).createPanel()

    private fun addTitledComponent(
        parent: JComponent,
        title: JComponent,
        component: JComponent,
        row: Int,
        shouldAddSpacer: Boolean,
    ): Int {
        val titleConstraints = GridConstraints()
        titleConstraints.row = row
        titleConstraints.fill = GridConstraints.FILL_HORIZONTAL
        parent.add(title, titleConstraints)

        val listConstraints = GridConstraints()
        listConstraints.row = row + 1
        listConstraints.fill = GridConstraints.FILL_BOTH
        listConstraints.indent = 1
        parent.add(component, listConstraints)
        return if (shouldAddSpacer) {
            addSpacer(parent, row + 2)
        } else {
            row + 2
        }
    }

    private fun addSpacer(parent: JComponent, row: Int): Int {
        val spacer = JPanel()
        val spacerConstraints = GridConstraints()
        spacerConstraints.row = row
        spacerConstraints.vSizePolicy = GridConstraints.SIZEPOLICY_WANT_GROW or GridConstraints.SIZEPOLICY_CAN_SHRINK
        parent.add(spacer, spacerConstraints)
        return row + 1
    }
}

class BspChooseConfigStep(
    context: WizardContext,
    private val builder: BspProjectImportBuilder,
): ModuleWizardStep() {

    private val myComponent = run {
        val manager = GridLayoutManager(5, 1)
        manager.isSameSizeHorizontally = false
        JPanel(manager)
    }
    private val chooseBspSetupModel = DefaultListModel<String>()
    private val chooseBspConfig = run {
        val list = JBList<String>()
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.model = chooseBspSetupModel
        list
    }

    private val bspConfigs = BspConnectionConfig.allConfigs(context.projectDirectory)

    override fun getComponent(): JComponent = myComponent

    override fun validate(): Boolean {
        val alreadySet = builder.serverConfig != BspProjectSettings.Companion.AutoConfig

        val configsExist = !chooseBspConfig.isEmpty
        val configSelected = chooseBspConfig.itemsCount == 1 || chooseBspConfig.selectedIndex >= 0

        return alreadySet || (configsExist && configSelected)
    }

    override fun updateStep() {
        chooseBspSetupModel.clear()
        bspConfigs
            .map { (_, details) -> configName(details) }
            .forEach { chooseBspSetupModel.addElement(it) }
    }

    override fun updateDataModel() {
        val configIndex =
            if (chooseBspConfig.itemsCount == 1) 0
            else chooseBspConfig.selectedIndex

        if (configIndex >= 0) {
            val (file, _) = bspConfigs[configIndex]
            val config = BspProjectSettings.Companion.BspConfigFile(file)
            builder.serverConfig = config
        }
    }

    override fun onWizardFinished() {
       updateStep()
       if (builder.serverConfig == BspProjectSettings.Companion.AutoConfig && chooseBspConfig.itemsCount == 1) {
           val (file, _) = bspConfigs[0]
           val config = BspProjectSettings.Companion.BspConfigFile(file)
           builder.serverConfig = config
       }
    }

    private fun configName(config: BspConnectionDetails) =
        "${config.name} ${config.version}"
}