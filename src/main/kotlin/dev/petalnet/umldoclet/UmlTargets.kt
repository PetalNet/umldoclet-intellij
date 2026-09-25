package dev.petalnet.umldoclet

import com.intellij.openapi.module.Module
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

/** Turns a Project-view / editor selection into a [UmlRequest]. Call inside a read action. */
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

        // Pass 1: directories become packages (a source root becomes its top-level packages plus the
        // default-package .java files sitting directly in it); files are collected with their package.
        val packages = linkedSetOf<String>()
        val candidateFiles = linkedMapOf<Path, String>()
        for (dir in picked.filter { it.isDirectory }) {
            val pkg = packageIndex.getPackageNameByDirectory(dir) ?: continue
            if (pkg.isNotEmpty()) {
                packages += pkg
            } else {
                for (child in dir.children) {
                    if (child.isDirectory) {
                        packageIndex.getPackageNameByDirectory(child)?.takeIf { it.isNotEmpty() }?.let { packages += it }
                    } else if (child.extension == "java") {
                        candidateFiles[child.nioPath()] = ""
                    }
                }
            }
        }
        val pickedFiles = picked.filter { !it.isDirectory }
        for (vf in pickedFiles) {
            candidateFiles[vf.nioPath()] = vf.parent?.let { packageIndex.getPackageNameByDirectory(it) }.orEmpty()
        }

        // Pass 2: dedup. `-subpackages a` already covers `a.b`, and a file whose package (or a parent
        // package) is selected would otherwise be documented twice.
        val topPackages = packages.filter { p -> packages.none { q -> p.startsWith("$q.") } }
        fun covered(pkg: String) = pkg.isNotEmpty() && topPackages.any { pkg == it || pkg.startsWith("$it.") }
        val files = candidateFiles.filterNot { (_, pkg) -> covered(pkg) }.keys.toList()
        if (topPackages.isEmpty() && files.isEmpty()) return Resolved.Problem("No Java sources in the selection.")

        val soleFile = pickedFiles.singleOrNull()?.takeIf { picked.size == 1 && it.nioPath() in files }
        val focusClass = soleFile?.nameWithoutExtension
        val focusPackage = if (soleFile != null) candidateFiles[soleFile.nioPath()] else topPackages.firstOrNull()

        val deps = OrderEnumerator.orderEntries(module).recursively().withoutSdk()
        val sourceRoots = deps.withoutLibraries().sources().roots
            .mapNotNull { it.fileSystem.getNioPath(it) } // skips non-disk roots (jars, test temp FS)
            .distinct()
        val classpath = OrderEnumerator.orderEntries(module).recursively().withoutSdk().classes().pathsList.pathList
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
                packages = topPackages,
                files = files,
                outputDir = out,
                includePrivate = settings.includePrivate,
                composition = settings.composition,
            ),
            focusClass,
            focusPackage,
        )
    }

    /** Like [VirtualFile.toNioPath] but tolerant of non-local file systems (test fixtures). */
    private fun VirtualFile.nioPath(): Path = fileSystem.getNioPath(this) ?: Paths.get(path)
}
