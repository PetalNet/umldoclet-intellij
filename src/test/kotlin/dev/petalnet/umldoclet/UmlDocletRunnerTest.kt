package dev.petalnet.umldoclet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

/**
 * Drives a real `javadoc` (the test JVM's JDK) with the real UMLDoclet jar, like the plugin does.
 * No IDE involved.
 */
class UmlDocletRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val javadoc: Path = Paths.get(
        System.getProperty("java.home"), "bin",
        if (System.getProperty("os.name").startsWith("Windows")) "javadoc.exe" else "javadoc",
    )
    private val docletJar: Path = Paths.get(
        System.getProperty("umldoclet.jar") ?: error("Gradle passes -Dumldoclet.jar (see build.gradle.kts)")
    )

    private fun sourceRoot(): Path {
        val src = tmp.newFolder("src").toPath()
        val pkg = Files.createDirectories(src.resolve("demo"))
        pkg.resolve("Person.java").writeText(
            """
            package demo;
            import java.util.List;
            public class Person {
                private Job job;
                private List<Hobby> hobbies;
                private String name;
            }
            """.trimIndent()
        )
        pkg.resolve("Manager.java").writeText("package demo;\npublic class Manager extends Person {}\n")
        pkg.resolve("Job.java").writeText("package demo;\npublic class Job { private String title; }\n")
        pkg.resolve("Hobby.java").writeText("package demo;\npublic class Hobby { private String name; }\n")
        return src
    }

    private fun generate(composition: Boolean): Pair<UmlResult, Path> {
        assertTrue("test JVM must be a JDK with $javadoc", Files.isExecutable(javadoc))
        assertTrue("doclet jar must exist: $docletJar", Files.isRegularFile(docletJar))
        val out = tmp.newFolder(if (composition) "out-composition" else "out-association").toPath()
        val req = UmlRequest(
            javadoc = javadoc,
            docletJar = docletJar,
            sourceRoots = listOf(sourceRoot()),
            classpath = emptyList(),
            packages = listOf("demo"),
            files = emptyList(),
            outputDir = out,
            includePrivate = true,
            composition = composition,
        )
        val result = UmlDocletRunner.run(req)
        assertEquals("javadoc output:\n" + result.output, 0, result.exitCode)
        assertTrue(result.ok)
        return result to out
    }

    private fun allFiles(dir: Path): List<Path> = Files.walk(dir).use { s -> s.filter { it.isRegularFile() }.toList() }

    private fun packagePuml(out: Path): String {
        val f = out.resolve("demo").resolve("package.puml")
        assertTrue("expected $f", Files.isRegularFile(f))
        val text = f.readText()
        System.getProperty("umldoclet.testOut")?.let { sample ->
            val dest = Paths.get(sample).resolve(out.fileName.toString() + ".package.puml")
            Files.createDirectories(dest.parent)
            dest.writeText(text)
        }
        return text
    }

    @Test
    fun `writes only puml files, package layout kept`() {
        val (result, out) = generate(composition = false)
        val files = allFiles(out)
        assertTrue(files.isNotEmpty())
        assertEquals("only .puml files may be copied, got $files", emptyList<Path>(), files.filter { it.extension != "puml" })
        assertTrue(files.none { it.fileName.toString().endsWith(".html") })
        assertEquals(files.sorted(), result.pumlFiles.sorted())
        assertTrue(files.any { it.endsWith(Paths.get("demo", "Person.puml")) })
        assertTrue(files.any { it.endsWith(Paths.get("demo", "package.puml")) })
    }

    @Test
    fun `package diagram has field associations`() {
        val (_, out) = generate(composition = false)
        val puml = packagePuml(out)
        assertTrue(puml, Regex("""^\s*\S*Person --> \S*Job\b""", RegexOption.MULTILINE).containsMatchIn(puml))
        assertTrue(puml, Regex("""^\s*\S*Person --> "\*" \S*Hobby\b""", RegexOption.MULTILINE).containsMatchIn(puml))
        assertTrue(puml, Regex("""^\s*\S*Person <\|-- \S*Manager\b""", RegexOption.MULTILINE).containsMatchIn(puml))
        assertFalse(puml, puml.contains("*-->"))
    }

    @Test
    fun `composition mode rewrites field associations only`() {
        val (_, out) = generate(composition = true)
        val puml = packagePuml(out)
        assertTrue(puml, Regex("""^\s*\S*Person \*--> \S*Job\b""", RegexOption.MULTILINE).containsMatchIn(puml))
        assertTrue(puml, Regex("""^\s*\S*Person \*--> "\*" \S*Hobby\b""", RegexOption.MULTILINE).containsMatchIn(puml))
        assertTrue(puml, Regex("""^\s*\S*Person <\|-- \S*Manager\b""", RegexOption.MULTILINE).containsMatchIn(puml))
        assertFalse(puml, Regex("""^\s*\S+ --> """, RegexOption.MULTILINE).containsMatchIn(puml))
    }

    @Test
    fun `failure yields non-zero exit and no files`() {
        val out = tmp.newFolder("out-fail").toPath()
        val req = UmlRequest(
            javadoc = javadoc, docletJar = docletJar,
            sourceRoots = listOf(sourceRoot()), classpath = emptyList(),
            packages = listOf("does.not.exist"), files = emptyList(),
            outputDir = out, includePrivate = true, composition = false,
        )
        val result = UmlDocletRunner.run(req)
        assertFalse(result.ok)
        assertTrue(result.exitCode != 0)
        assertTrue(result.output, result.output.isNotBlank())
        assertEquals(emptyList<Path>(), allFiles(out))
    }
}
