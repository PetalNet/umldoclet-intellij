package dev.petalnet.umldoclet

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

/** Everything needed for one run, resolved from the IDE by [UmlTargets]. Plain data so it tests without an IDE. */
data class UmlRequest(
    val javadoc: Path,
    val docletJar: Path,
    val sourceRoots: List<Path>,
    val classpath: List<Path>,
    /** Packages to document, including their subpackages. */
    val packages: List<String>,
    /** Individual source files (default package, or files picked one by one). */
    val files: List<Path>,
    val outputDir: Path,
    val includePrivate: Boolean,
    val composition: Boolean,
)

data class UmlResult(val exitCode: Int, val output: String, val pumlFiles: List<Path>) {
    val ok: Boolean get() = exitCode == 0 && pumlFiles.isNotEmpty()
}

/**
 * Runs the project's own `javadoc` with UMLDoclet, then keeps only the `.puml` files.
 *
 * javadoc writes a whole HTML site next to the diagrams, so it runs into a temp directory and only the
 * PlantUML sources are copied to [UmlRequest.outputDir], keeping their package folders.
 */
object UmlDocletRunner {
    const val DOCLET = "nl.talsmasoftware.umldoclet.UMLDoclet"

    fun commandLine(req: UmlRequest, tempOut: Path): List<String> = buildList {
        add(req.javadoc.toString())
        add("-quiet")
        add(if (req.includePrivate) "-private" else "-protected")
        addAll(listOf("-encoding", "UTF-8"))
        add("-Xdoclint:none")
        // Don't let one unresolved import (e.g. an unbuilt module) sink the whole diagram.
        add("--ignore-source-errors")
        addAll(listOf("-d", tempOut.toString()))
        addAll(listOf("-docletpath", req.docletJar.toString(), "-doclet", DOCLET))
        add("--create-puml-files")
        addAll(listOf("--uml-image-format", "none"))
        addAll(listOf("--uml-encoding", "UTF-8"))
        if (req.sourceRoots.isNotEmpty()) addAll(listOf("-sourcepath", req.sourceRoots.joinToString(File.pathSeparator)))
        if (req.classpath.isNotEmpty()) addAll(listOf("-classpath", req.classpath.joinToString(File.pathSeparator)))
        if (req.packages.isNotEmpty()) addAll(listOf("-subpackages", req.packages.joinToString(":")))
        req.files.forEach { add(it.toString()) }
    }

    /**
     * @param isCancelled polled while javadoc runs; returning true kills the process.
     */
    fun run(req: UmlRequest, isCancelled: () -> Boolean = { false }, timeoutSeconds: Long = 600): UmlResult {
        require(req.packages.isNotEmpty() || req.files.isNotEmpty()) { "Nothing selected to diagram" }
        val temp = Files.createTempDirectory("umldoclet-")
        try {
            val log = temp.resolve("javadoc.log").toFile()
            val tempOut = temp.resolve("out")
            val proc = ProcessBuilder(commandLine(req, tempOut))
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (!proc.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (isCancelled() || System.nanoTime() > deadline) {
                    proc.destroyForcibly()
                    proc.waitFor(5, TimeUnit.SECONDS)
                    val why = if (isCancelled()) "Cancelled" else "Timed out after ${timeoutSeconds}s"
                    return UmlResult(-1, why + "\n" + log.readTextOrEmpty(), emptyList())
                }
            }
            val output = log.readTextOrEmpty()
            val copied = if (proc.exitValue() == 0) collectPuml(tempOut, req.outputDir, req.composition) else emptyList()
            return UmlResult(proc.exitValue(), output, copied)
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    private fun File.readTextOrEmpty() = if (exists()) readText() else ""

    private fun collectPuml(from: Path, to: Path, composition: Boolean): List<Path> {
        if (!Files.isDirectory(from)) return emptyList()
        val out = mutableListOf<Path>()
        Files.walk(from).use { stream ->
            stream.filter { it.isRegularFile() && it.extension == "puml" }.forEach { src ->
                val dest = to.resolve(src.relativeTo(from).toString())
                Files.createDirectories(dest.parent)
                val text = src.readText()
                dest.writeText(if (composition) asComposition(text) else text)
                out.add(dest)
            }
        }
        return out.sorted()
    }

    private val association = Regex("""^(\s*)(\S+) --> (.+)$""", RegexOption.MULTILINE)

    /**
     * UMLDoclet draws every field reference as a plain association (`A --> B`); Java can't say whether A
     * owns B. For courses that draw fields as composition, rewrite those arrows as `A *--> B`.
     * Inheritance (`<|--`), realisation (`<|..`) and dependencies (`..>`) are left alone.
     */
    fun asComposition(puml: String): String = association.replace(puml) { m ->
        "${m.groupValues[1]}${m.groupValues[2]} *--> ${m.groupValues[3]}"
    }

    /**
     * The diagram to open afterwards: the picked class, else the picked package's `package.puml`,
     * else the first `package.puml`, else anything.
     */
    fun primaryDiagram(result: List<Path>, outputDir: Path, className: String?, packageName: String?): Path? {
        val pkgDir = packageName.orEmpty().split('.').filter { it.isNotEmpty() }.fold(outputDir) { dir, seg -> dir.resolve(seg) }
        if (className != null) result.firstOrNull { it == pkgDir.resolve("$className.puml") }?.let { return it }
        if (packageName != null) result.firstOrNull { it == pkgDir.resolve("package.puml") }?.let { return it }
        return result.firstOrNull { it.fileName.toString() == "package.puml" } ?: result.firstOrNull()
    }

    /** The last [maxChars] of javadoc's output, for a notification balloon. */
    fun tail(output: String, maxChars: Int = 1500): String =
        if (output.length <= maxChars) output.trim() else "…" + output.takeLast(maxChars).trim()
}
