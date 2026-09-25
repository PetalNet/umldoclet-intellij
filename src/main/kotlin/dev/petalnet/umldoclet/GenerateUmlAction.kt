package dev.petalnet.umldoclet

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Project view / editor popup: "Generate UML (UMLDoclet)". */
class GenerateUmlAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible =
            project != null && !project.isDefault && selection(e).any { UmlTargets.isCandidate(project, it) }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selection = selection(e)

        // javadoc reads from disk, so unsaved editor changes would be invisible to it.
        FileDocumentManager.getInstance().saveAllDocuments()

        val docletJar = docletJar()
        if (docletJar == null || !Files.isRegularFile(docletJar)) {
            notify(project, "UMLDoclet jar missing", "Expected it at $docletJar. Reinstall the plugin.", NotificationType.ERROR)
            return
        }
        val settings = UmlSettings.getInstance(project).state
        val resolved = ReadAction.compute<Resolved, RuntimeException> {
            UmlTargets.resolve(project, selection, docletJar, settings)
        }
        when (resolved) {
            is Resolved.Problem -> notify(project, "Can't generate UML", resolved.message, NotificationType.ERROR)
            is Resolved.Ok -> GenerateTask(project, resolved).queue()
        }
    }

    private class GenerateTask(project: Project, private val resolved: Resolved.Ok) :
        Task.Backgroundable(project, "Generating UML diagrams (UMLDoclet)", true) {

        private lateinit var result: UmlResult

        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = true
            indicator.text = "Running javadoc with UMLDoclet…"
            result = UmlDocletRunner.run(resolved.request, isCancelled = { indicator.isCanceled })
            if (indicator.isCanceled) throw ProcessCanceledException()
        }

        override fun onSuccess() {
            val project = project ?: return
            val req = resolved.request
            if (!result.ok) {
                LOG.warn("javadoc/UMLDoclet failed (exit ${result.exitCode}). Command: ${req.javadoc}\n${result.output}")
                val why = if (result.exitCode == 0) "javadoc finished but produced no .puml files." else "javadoc exited with ${result.exitCode}."
                notify(
                    project, "UMLDoclet failed",
                    "$why See idea.log for the full output.\n\n${UmlDocletRunner.tail(result.output)}",
                    NotificationType.ERROR,
                )
                return
            }
            if (result.output.isNotBlank()) LOG.info("javadoc/UMLDoclet output:\n${result.output}")

            val outDir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(req.outputDir)
            if (outDir != null) VfsUtil.markDirtyAndRefresh(false, true, true, outDir)
            UmlDocletRunner.primaryDiagram(result.pumlFiles, req.outputDir, resolved.focusClass, resolved.focusPackage)
                ?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
                ?.let { FileEditorManager.getInstance(project).openFile(it, true) }

            val shown = project.basePath?.let { Paths.get(it).relativize(req.outputDir).toString() }
                ?.takeIf { !it.startsWith("..") } ?: req.outputDir.toString()
            val n = result.pumlFiles.size
            notify(project, "UMLDoclet", "Wrote $n ${if (n == 1) "diagram" else "diagrams"} to $shown", NotificationType.INFORMATION)

            if (!plantUmlPluginEnabled()) {
                notify(
                    project, "No PlantUML preview",
                    "The .puml files are ready, but rendering needs a PlantUML plugin. " +
                        "Install \"PlantUML Integration\" (plantuml4idea) from Settings > Plugins to preview them.",
                    NotificationType.INFORMATION,
                )
            }
        }

        override fun onThrowable(error: Throwable) {
            val project = project ?: return
            LOG.warn("UMLDoclet run crashed", error)
            notify(project, "UMLDoclet failed", error.message ?: error.toString(), NotificationType.ERROR)
        }
    }

    companion object {
        private val LOG = logger<GenerateUmlAction>()
        const val PLUGIN_ID = "dev.petalnet.umldoclet"
        const val PLANTUML_PLUGIN_ID = "PlantUML integration"
        const val NOTIFICATION_GROUP = "UMLDoclet"

        fun selection(e: AnActionEvent): List<VirtualFile> =
            e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList()
                ?: listOfNotNull(e.getData(CommonDataKeys.VIRTUAL_FILE))

        /** `<plugin dir>/doclet/umldoclet.jar`, shipped next to the plugin's lib/ (see build.gradle.kts). */
        fun docletJar(): Path? =
            PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath?.resolve("doclet")?.resolve("umldoclet.jar")

        fun plantUmlPluginEnabled(): Boolean =
            PluginManagerCore.getPlugin(PluginId.getId(PLANTUML_PLUGIN_ID))?.isEnabled == true

        fun notify(project: Project, title: String, content: String, type: NotificationType) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(title, content, type)
                .notify(project)
        }
    }
}
