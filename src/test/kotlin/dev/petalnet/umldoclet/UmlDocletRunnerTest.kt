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

    private fun javaFiles(root: Path): List<Path> =
        Files.walk(root).use { s -> s.filter { it.isRegularFile() && it.extension == "java" }.toList() }.sorted()

    private fun request(
        out: Path,
        sourceRoots: List<Path>,
        files: List<Path>,
        composition: Boolean = false,
        methodDependencies: Boolean = false,
    ) = UmlRequest(
        javadoc = javadoc,
        docletJar = docletJar,
        sourceRoots = sourceRoots,
        classpath = emptyList(),
        files = files,
        outputDir = out,
        includePrivate = true,
        composition = composition,
        methodDependencies = methodDependencies,
    )

    private fun runOk(req: UmlRequest): UmlResult {
        assertTrue("test JVM must be a JDK with $javadoc", Files.isExecutable(javadoc))
        assertTrue("doclet jar must exist: $docletJar", Files.isRegularFile(docletJar))
        val result = UmlDocletRunner.run(req)
        assertEquals("javadoc output:\n" + result.output, 0, result.exitCode)
        assertTrue(result.ok)
        return result
    }

    private fun generate(composition: Boolean): Pair<UmlResult, Path> {
        val out = tmp.newFolder(if (composition) "out-composition" else "out-association").toPath()
        val src = sourceRoot()
        return runOk(request(out, listOf(src), javaFiles(src), composition = composition)) to out
    }

    private fun allFiles(dir: Path): List<Path> = Files.walk(dir).use { s -> s.filter { it.isRegularFile() }.toList() }

    private fun packagePuml(out: Path, pkg: String = "demo"): String {
        val f = out.resolve(pkg).resolve("package.puml")
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
        val src = sourceRoot()
        val result = UmlDocletRunner.run(request(out, listOf(src), listOf(src.resolve("demo").resolve("DoesNotExist.java"))))
        assertFalse(result.ok)
        assertTrue(result.exitCode != 0)
        assertTrue(result.output, result.output.isNotBlank())
        assertEquals(emptyList<Path>(), allFiles(out))
    }

    /** The GameHub example from the forked UMLDoclet: Map fields must become associations to the value type. */
    private fun gameHubRoot(): Path {
        val src = tmp.newFolder("game hub src").toPath() // space: exercises the @argfile quoting
        val pkg = Files.createDirectories(src.resolve("shop"))
        pkg.resolve("GameHub.java").writeText(
            """
            package shop;
            import java.util.HashMap;
            import java.util.Map;
            public class GameHub {
                private HashMap<Integer, Customer> customers;
                private HashMap<Integer, Purchasable> catalog;
                private Map<CustomerId, Customer> byId;
                private Map<Integer, String> names;
                public Customer getCustomerById(int id) { return customers.get(id); }
                public Receipt checkout(Customer customer, Purchasable item) { return null; }
            }
            """.trimIndent()
        )
        pkg.resolve("Customer.java").writeText("package shop;\npublic class Customer { private String name; }\n")
        pkg.resolve("CustomerId.java").writeText("package shop;\npublic class CustomerId { private int value; }\n")
        pkg.resolve("Purchasable.java").writeText("package shop;\npublic interface Purchasable { double price(); }\n")
        pkg.resolve("Receipt.java").writeText("package shop;\npublic class Receipt { private double total; }\n")
        pkg.resolve("Ledger.java").writeText(
            "package shop;\nimport java.util.*;\npublic class Ledger { private Map<String, List<Receipt>> history; }\n"
        )
        return src
    }

    private fun gameHubPuml(name: String, methodDependencies: Boolean): String {
        val out = tmp.newFolder(name).toPath()
        val src = gameHubRoot()
        runOk(request(out, listOf(src), javaFiles(src), methodDependencies = methodDependencies))
        return packagePuml(out, "shop")
    }

    private fun assertLine(puml: String, regex: String) =
        assertTrue("expected /$regex/ in:\n$puml", Regex(regex, RegexOption.MULTILINE).containsMatchIn(puml))

    private fun assertNoLine(puml: String, regex: String) =
        assertFalse("unexpected /$regex/ in:\n$puml", Regex(regex, RegexOption.MULTILINE).containsMatchIn(puml))

    @Test
    fun `GameHub map fields become associations to the value type`() {
        val puml = gameHubPuml("out-gamehub", methodDependencies = false)
        assertLine(puml, """^\s*\S*GameHub --> "\*" \S*Customer: customers""")
        assertLine(puml, """^\s*\S*GameHub --> "\*" \S*Purchasable: catalog""")
        assertLine(puml, """^\s*\S*GameHub --> "\*" \S*CustomerId: byId key""")
        // Map<Integer, String>: neither side is in the package, so the field stays a field.
        assertLine(puml, """-names: Map<Integer, String>""")
        // Nested containers are unwrapped (needs the reviewed fork head, not the first PR commit).
        assertLine(puml, """^\s*\S*Ledger --> "\*" \S*Receipt: history""")
        // Method dependencies are off by default.
        assertNoLine(puml, """\.\.>""")
    }

    @Test
    fun `method dependencies setting adds dashed arrows except where an association exists`() {
        val puml = gameHubPuml("out-gamehub-deps", methodDependencies = true)
        assertLine(puml, """^\s*\S*GameHub \.\.> \S*Receipt\s*$""")
        // Customer and Purchasable already have associations from GameHub.
        assertNoLine(puml, """GameHub \.\.> \S*Customer\b""")
        assertNoLine(puml, """GameHub \.\.> \S*Purchasable\b""")
    }

    @Test
    fun `only the given files are documented, even with more classes of the package on the sourcepath`() {
        val main = tmp.newFolder("scoped-main").toPath()
        val test = tmp.newFolder("scoped-test").toPath()
        Files.createDirectories(main.resolve("demo"))
        Files.createDirectories(test.resolve("demo"))
        main.resolve("demo/Person.java").writeText("package demo;\npublic class Person { private Job job; }\n")
        main.resolve("demo/Job.java").writeText("package demo;\npublic class Job { private String title; }\n")
        main.resolve("demo/Unrelated.java").writeText("package demo;\npublic class Unrelated { private int x; }\n")
        test.resolve("demo/PersonTest.java").writeText("package demo;\npublic class PersonTest { private Person p; }\n")
        val out = tmp.newFolder("out-scoped").toPath()
        runOk(request(out, listOf(main), listOf(main.resolve("demo/Person.java"), main.resolve("demo/Job.java"))))
        val puml = packagePuml(out)
        assertLine(puml, """^\s*\S*Person --> \S*Job: job""")
        assertFalse(puml, puml.contains("Unrelated"))
        assertFalse(puml, puml.contains("PersonTest"))
        assertTrue(allFiles(out).none { it.fileName.toString().startsWith("Unrelated") || it.fileName.toString().startsWith("PersonTest") })
    }

    /**
     * End-to-end check of the argfile escaping with real javadoc: on POSIX a backslash is a legal file-name
     * character, so a folder named like a Windows path segment proves javadoc un-escapes `\\` and spaces.
     */
    @Test
    fun `argfile escaping round-trips through real javadoc`() {
        org.junit.Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val src = tmp.newFolder("C:\\Users\\John Doe src").toPath()
        Files.createDirectories(src.resolve("demo"))
        src.resolve("demo/Person.java").writeText("package demo;\npublic class Person { private Job job; }\n")
        src.resolve("demo/Job.java").writeText("package demo;\npublic class Job { private String title; }\n")
        val out = tmp.newFolder("out-escaping").toPath()
        runOk(request(out, listOf(src), javaFiles(src)))
        assertLine(packagePuml(out), """^\s*\S*Person --> \S*Job: job""")
    }
}
