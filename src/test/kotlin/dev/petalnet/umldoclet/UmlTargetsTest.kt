package dev.petalnet.umldoclet

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Selection resolution against a light Java fixture.
 *
 * The light fixture's SDK is a mock JDK without a `bin/` directory, so the javadoc location is injected
 * through [UmlTargets.resolve]'s `javadocFor` seam and points at the test JVM's own JDK.
 *
 * A plain [DefaultLightProjectDescriptor] is used because the default `JAVA_LATEST` descriptor pulls
 * `org.jetbrains:annotations` from a Maven repository, which is not available in a plugin build.
 */
class UmlTargetsTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = MOCK_JDK

    private val docletJar: Path = Paths.get("/irrelevant/umldoclet.jar")
    private val realJavadoc: Path = Paths.get(System.getProperty("java.home"), "bin", "javadoc")
    private val settings = UmlSettings.State()

    private fun resolve(selection: List<VirtualFile>, javadocFor: (Sdk) -> Path? = { realJavadoc }): Resolved =
        UmlTargets.resolve(project, selection, docletJar, settings, javadocFor)

    private fun ok(r: Resolved): Resolved.Ok = r as? Resolved.Ok ?: error("expected Ok, got $r")

    private fun addDemo(): VirtualFile {
        myFixture.addFileToProject("demo/Job.java", "package demo; public class Job {}")
        myFixture.addFileToProject("demo/sub/Deep.java", "package demo.sub; public class Deep {}")
        return myFixture.addFileToProject("demo/Person.java", "package demo; public class Person { private Job job; }").virtualFile
    }

    fun testPackageDirectoryBecomesPackage() {
        val person = addDemo()
        val r = ok(resolve(listOf(person.parent)))
        assertEquals(listOf("demo"), r.request.packages)
        assertEmpty(r.request.files)
        assertNull(r.focusClass)
        assertEquals("demo", r.focusPackage)
        assertEquals(realJavadoc, r.request.javadoc)
        assertTrue(r.request.includePrivate)
        assertFalse(r.request.composition)
        assertEquals(Paths.get(project.basePath!!, "build", "uml").normalize(), r.request.outputDir)
    }

    fun testSingleFileBecomesFileWithFocusClass() {
        val person = addDemo()
        val r = ok(resolve(listOf(person)))
        assertEmpty(r.request.packages)
        assertEquals(listOf(Paths.get(person.path)), r.request.files)
        assertEquals("Person", r.focusClass)
        assertEquals("demo", r.focusPackage)
    }

    fun testFileInsideSelectedPackageIsDeduped() {
        val person = addDemo()
        val deep = person.parent.findChild("sub")!!.findChild("Deep.java")!!
        val r = ok(resolve(listOf(person, person.parent, deep)))
        assertEquals(listOf("demo"), r.request.packages)
        assertEmpty(r.request.files)
        assertNull(r.focusClass)
        assertEquals("demo", r.focusPackage)
    }

    fun testNestedSelectedPackagesCollapse() {
        val person = addDemo()
        val sub = person.parent.findChild("sub")!!
        val r = ok(resolve(listOf(sub, person.parent)))
        assertEquals(listOf("demo"), r.request.packages)
    }

    fun testTwoFilesHaveNoFocusClass() {
        val person = addDemo()
        val job = person.parent.findChild("Job.java")!!
        val r = ok(resolve(listOf(person, job)))
        assertEquals(2, r.request.files.size)
        assertNull(r.focusClass)
    }

    fun testSourceRootBecomesTopLevelPackagesPlusDefaultPackageFiles() {
        val person = addDemo()
        val top = myFixture.addFileToProject("Top.java", "public class Top {}").virtualFile
        val root = person.parent.parent
        assertEquals(top.parent, root)
        val r = ok(resolve(listOf(root)))
        assertEquals(listOf("demo"), r.request.packages)
        assertEquals(listOf(Paths.get(top.path)), r.request.files)
        assertNull(r.focusClass)
        assertEquals("demo", r.focusPackage)
    }

    fun testSettingsAreApplied() {
        val person = addDemo()
        val custom = UmlSettings.State().apply { outputDir = "docs/uml"; includePrivate = false; composition = true }
        val r = ok(UmlTargets.resolve(project, listOf(person), docletJar, custom) { realJavadoc })
        assertFalse(r.request.includePrivate)
        assertTrue(r.request.composition)
        assertEquals(Paths.get(project.basePath!!, "docs", "uml").normalize(), r.request.outputDir)
    }

    fun testJreWithoutJavadocIsProblem() {
        val person = addDemo()
        val r = resolve(listOf(person)) { sdk -> Paths.get(sdk.homePath ?: "/nowhere", "bin", "javadoc") }
        assertTrue("$r", r is Resolved.Problem)
        assertTrue((r as Resolved.Problem).message, r.message.contains("not a JRE"))
    }

    fun testEmptySelectionIsProblem() {
        assertTrue(resolve(emptyList()) is Resolved.Problem)
    }

    fun testNonJavaFileIsNotACandidate() {
        val txt = myFixture.addFileToProject("notes.txt", "hi").virtualFile
        assertFalse(UmlTargets.isCandidate(project, txt))
        assertTrue(resolve(listOf(txt)) is Resolved.Problem)
    }

    companion object {
        private val MOCK_JDK = DefaultLightProjectDescriptor()
    }
}

/** Separate class: light projects are keyed by descriptor, so this one gets a project without any SDK. */
class UmlTargetsNoSdkTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = NO_SDK

    fun testNoSdkIsProblem() {
        val person = myFixture.addFileToProject("demo/Person.java", "package demo; public class Person {}").virtualFile
        val r = UmlTargets.resolve(project, listOf(person), Paths.get("/irrelevant.jar"), UmlSettings.State()) {
            Paths.get(System.getProperty("java.home"), "bin", "javadoc")
        }
        assertTrue("$r", r is Resolved.Problem)
        assertTrue((r as Resolved.Problem).message, r.message.contains("no Java SDK"))
    }

    companion object {
        private val NO_SDK = object : DefaultLightProjectDescriptor() {
            override fun getSdk(): Sdk? = null
        }
    }
}
