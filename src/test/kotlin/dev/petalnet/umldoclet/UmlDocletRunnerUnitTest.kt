package dev.petalnet.umldoclet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

class UmlDocletRunnerUnitTest {

    @Test
    fun `asComposition rewrites associations and leaves other arrows alone`() {
        val input = """
            @startuml
            demo::Person --> demo::Job: job
            demo::Person --> "*" demo::Hobby: hobbies
              demo::Indented --> demo::Other
            demo::Person <|-- demo::Manager
            demo::Job <|.. demo::Contract
            demo::Person ..> demo::Util
            note "an --> arrow inside text" as N1
            @enduml
        """.trimIndent()
        val expected = """
            @startuml
            demo::Person *--> demo::Job: job
            demo::Person *--> "*" demo::Hobby: hobbies
              demo::Indented *--> demo::Other
            demo::Person <|-- demo::Manager
            demo::Job <|.. demo::Contract
            demo::Person ..> demo::Util
            note "an --> arrow inside text" as N1
            @enduml
        """.trimIndent()
        assertEquals(expected, UmlDocletRunner.asComposition(input))
    }

    @Test
    fun `asComposition is idempotent on already-composed input`() {
        val s = "a::A *--> b::B\n"
        assertEquals(s, UmlDocletRunner.asComposition(s))
    }

    @Test
    fun `primaryDiagram prefers class, then its package, then any package, then anything`() {
        val out = Paths.get("/p/build/uml")
        val person = out.resolve("demo").resolve("Person.puml")
        val demoPkg = out.resolve("demo").resolve("package.puml")
        val otherPkg = out.resolve("other").resolve("package.puml")
        val other = out.resolve("other").resolve("Thing.puml")
        val all = listOf(other, otherPkg, person, demoPkg)

        assertEquals(person, UmlDocletRunner.primaryDiagram(all, out, "Person", "demo"))
        assertEquals(demoPkg, UmlDocletRunner.primaryDiagram(all, out, "Missing", "demo"))
        assertEquals(demoPkg, UmlDocletRunner.primaryDiagram(all, out, null, "demo"))
        assertEquals(otherPkg, UmlDocletRunner.primaryDiagram(all, out, null, "nope"))
        assertEquals(otherPkg, UmlDocletRunner.primaryDiagram(all, out, null, null))
        assertEquals(other, UmlDocletRunner.primaryDiagram(listOf(other), out, null, null))
        assertNull(UmlDocletRunner.primaryDiagram(emptyList(), out, "Person", "demo"))

        // Nested and default packages.
        val deep = out.resolve("a").resolve("b").resolve("C.puml")
        assertEquals(deep, UmlDocletRunner.primaryDiagram(listOf(deep), out, "C", "a.b"))
        val top = out.resolve("Top.puml")
        assertEquals(top, UmlDocletRunner.primaryDiagram(listOf(deep, top), out, "Top", ""))
    }

    @Test
    fun `tail keeps the end of long output`() {
        val long = (1..2000).joinToString("") { "x" } + "END"
        val t = UmlDocletRunner.tail(long, 100)
        assertEquals(101, t.length)
        assertEquals("END", t.takeLast(3))
        assertEquals("short", UmlDocletRunner.tail("short\n", 100))
    }

    private fun req(files: List<java.nio.file.Path>, methodDependencies: Boolean) = UmlRequest(
        javadoc = Paths.get("/jdk/bin/javadoc"), docletJar = Paths.get("/doclet.jar"),
        sourceRoots = listOf(Paths.get("/p/src/main/java")), classpath = emptyList(),
        files = files, outputDir = Paths.get("/p/build/uml"),
        includePrivate = true, composition = false, methodDependencies = methodDependencies,
    )

    @Test
    fun `commandLine passes files, never -subpackages, and the method dependencies flag only when set`() {
        val file = Paths.get("/p/src/main/java/demo/Person.java")
        val off = UmlDocletRunner.commandLine(req(listOf(file), false), Paths.get("/tmp/out"))
        assertFalse(off.toString(), off.contains("-subpackages"))
        assertFalse(off.toString(), off.contains("--uml-method-dependencies"))
        assertEquals(file.toString(), off.last())
        val on = UmlDocletRunner.commandLine(req(listOf(file), true), Paths.get("/tmp/out"), listOf("@/tmp/sources.txt"))
        assertTrue(on.toString(), on.contains("--uml-method-dependencies"))
        assertEquals("@/tmp/sources.txt", on.last())
    }

    @Test
    fun `argFile quotes each path and escapes backslashes and quotes`() {
        val text = UmlDocletRunner.argFile(listOf(Paths.get("/a b/C.java"), Paths.get("/x/We\\ird\"Name.java")))
        assertEquals("\"/a b/C.java\"\n\"/x/We\\\\ird\\\"Name.java\"\n", text)
    }

    @Test
    fun `argFile escapes a Windows drive-letter path with spaces`() {
        // String-level: on Linux this is a single file name, but toString() keeps the backslashes as on Windows.
        val windows = Paths.get("C:\\Users\\John Doe\\My Project\\src\\main\\java\\demo\\Person.java")
        assertEquals(
            "\"C:\\\\Users\\\\John Doe\\\\My Project\\\\src\\\\main\\\\java\\\\demo\\\\Person.java\"\n",
            UmlDocletRunner.argFile(listOf(windows)),
        )
    }
}
