package dev.petalnet.umldoclet

import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdkType
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.PackageIndex
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

sealed interface Resolved {
    data class Ok(val request: UmlRequest, val focusClass: String?, val focusPackage: String?) : Resolved
    data class Problem(val message: String) : Resolved
}

/**
 * Turns a Project-view / editor selection into a [UmlRequest]. Call inside a read action; it walks the selected
 * folders recursively, so use a background (non-blocking) read action, never the EDT.
 */
object UmlTargets {
    fun isCandidate(project: Project, file: VirtualFile): Boolean {
        val index = ProjectFileIndex.getInstance(project)
        return index.isInSourceContent(file) && (file.isDirectory || file.extension == "java")
    }

    /**
     * Where `javadoc` lives for an SDK. Kept as a parameter of [resolve] so tests can point at the
     * test JVM's JDK (light test fixtures use a mock JDK that has no `bin/`).
     */
    fun defaultJavadoc(sdk: Sdk): Path? =
        sdk.homePath?.let { Paths.get(it, "bin", if (SystemInfo.isWindows) "javadoc.exe" else "javadoc") }

    fun resolve(
        project: Project,
        selection: List<VirtualFile>,
        docletJar: Path,
        settings: UmlSettings.State,
        javadocFor: (Sdk) -> Path? = ::defaultJavadoc,
    ): Resolved {
        val index = ProjectFileIndex.getInstance(project)
        val packageIndex = PackageIndex.getInstance(project)
        val picked = selection.filter { isCandidate(project, it) }
        if (picked.isEmpty()) return Resolved.Problem("Select Java files, packages or source folders.")

        val module: Module = index.getModuleForFile(picked.first())
            ?: return Resolved.Problem("${picked.first().name} isn't in a module.")
        // One javadoc run has one SDK, sourcepath and classpath, so it can only serve one module.
        val modules = picked.mapNotNull { index.getModuleForFile(it) }.distinct()
        if (modules.size > 1) {
            return Resolved.Problem(
                "The selection spans several modules (${modules.joinToString { "'${it.name}'" }}). " +
                    "Generate UML for one module at a time."
            )
        }
        val sdk = ModuleRootManager.getInstance(module).sdk ?: ProjectRootManager.getInstance(project).projectSdk
        if (sdk == null || sdk.sdkType !is JavaSdkType) {
            return Resolved.Problem("Module '${module.name}' has no Java SDK. Set one in File > Project Structure.")
        }
        val javadoc = javadocFor(sdk)
        if (javadoc == null || !Files.isExecutable(javadoc)) {
            return Resolved.Problem(
                "No javadoc executable in SDK '${sdk.name}' (${sdk.homePath}). UMLDoclet needs a full JDK 9+, not a JRE."
            )
        }

        // The documented set is exactly the selection: every .java file under a picked directory (recursively,
        // skipping excluded folders) plus every picked file. Files are passed to javadoc by path, never as
        // packages: `-subpackages` would pull in every class of that package from *any* source root on the
        // sourcepath (tests, other modules, generated sources), which is not what was clicked.
        // Test sources are only documented when the picked item itself is test source; a main folder that
        // happens to contain a nested test root keeps its test files out.
        val files = linkedSetOf<Path>()
        val filePackages = linkedMapOf<Path, String>()
        for (item in picked) {
            val pickedIsTest = index.isInTestSourceContent(item)
            fun accept(vf: VirtualFile) {
                if (vf.isDirectory || vf.extension != "java") return
                if (!index.isInSourceContent(vf) || index.isInTestSourceContent(vf) != pickedIsTest) return
                val path = vf.nioPath()
                if (files.add(path)) filePackages[path] = vf.parent?.let { packageIndex.getPackageNameByDirectory(it) }.orEmpty()
            }
            if (item.isDirectory) {
                index.iterateContentUnderDirectory(item) { vf ->
                    ProgressManager.checkCanceled() // runs in a cancellable background read action
                    accept(vf)
                    true
                }
            } else {
                accept(item)
            }
        }
        if (files.isEmpty()) return Resolved.Problem("No Java sources in the selection.")

        val pickedFiles = picked.filter { !it.isDirectory }
        val soleFile = pickedFiles.singleOrNull()?.takeIf { picked.size == 1 }
        val focusClass = soleFile?.nameWithoutExtension
        val focusPackage = picked.first().let { first ->
            val dir = if (first.isDirectory) first else first.parent
            dir?.let { packageIndex.getPackageNameByDirectory(it) }?.takeIf { it.isNotEmpty() || soleFile != null }
        } ?: filePackages.values.filter { it.isNotEmpty() } // a source root: its shallowest package
            .minWithOrNull(compareBy<String>({ p -> p.count { it == '.' } }, { it }))

        // The sourcepath only resolves references; it is not what gets documented. A main-source selection
        // never puts test roots (or test-scoped dependencies) on it.
        val includeTests = picked.any { index.isInTestSourceContent(it) }
        val sourceRoots = sourceRoots(module, includeTests)
            .mapNotNull { it.fileSystem.getNioPath(it) } // skips non-disk roots (jars, test temp FS)
            .distinct()
        val classpath = orderEntries(module, includeTests).classes().pathsList.pathList
            .map { Paths.get(it) }
            .filter { Files.exists(it) }
            .distinct()

        val base = project.basePath?.let { Paths.get(it) } ?: return Resolved.Problem("Project has no base path.")
        val out = base.resolve(settings.outputDir?.takeIf { it.isNotBlank() } ?: UmlSettings.DEFAULT_OUTPUT).normalize()

        return Resolved.Ok(
            UmlRequest(
                javadoc = javadoc,
                docletJar = docletJar,
                sourceRoots = sourceRoots,
                classpath = classpath,
                files = files.toList(),
                outputDir = out,
                includePrivate = settings.includePrivate,
                composition = settings.composition,
                methodDependencies = settings.methodDependencies,
            ),
            focusClass,
            focusPackage,
        )
    }

    private fun orderEntries(module: Module, includeTests: Boolean): OrderEnumerator =
        OrderEnumerator.orderEntries(module).recursively().withoutSdk().let { if (includeTests) it else it.productionOnly() }

    /**
     * Source roots for javadoc's `-sourcepath`: the module's own roots and those of the modules it depends on.
     * Test roots are only included when [includeTests] (the selection itself is test source).
     */
    fun sourceRoots(module: Module, includeTests: Boolean): List<VirtualFile> =
        orderEntries(module, includeTests).withoutLibraries().sources().roots.toList()

    /** Like [VirtualFile.toNioPath] but tolerant of non-local file systems (test fixtures). */
    private fun VirtualFile.nioPath(): Path = fileSystem.getNioPath(this) ?: Paths.get(path)
}
