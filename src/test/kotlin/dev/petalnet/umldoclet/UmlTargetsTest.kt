package dev.petalnet.umldoclet

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.testFramework.PlatformTestUtil
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.VfsTestUtil
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
    private val settings = UmlOptions()

    private fun resolve(selection: List<VirtualFile>, javadocFor: (Sdk) -> Path? = { realJavadoc }): Resolved =
        UmlTargets.resolve(project, selection, docletJar, settings, javadocFor)

    private fun ok(r: Resolved): Resolved.Ok = r as? Resolved.Ok ?: error("expected Ok, got $r")

    private fun addDemo(): VirtualFile {
        myFixture.addFileToProject("demo/Job.java", "package demo; public class Job {}")
        myFixture.addFileToProject("demo/sub/Deep.java", "package demo.sub; public class Deep {}")
        return myFixture.addFileToProject("demo/Person.java", "package demo; public class Person { private Job job; }").virtualFile
    }

    private fun paths(vararg files: VirtualFile): List<Path> = files.map { Paths.get(it.path) }

    private fun demoFiles(person: VirtualFile): Triple<VirtualFile, VirtualFile, VirtualFile> {
        val job = person.parent.findChild("Job.java")!!
        val deep = person.parent.findChild("sub")!!.findChild("Deep.java")!!
        return Triple(person, job, deep)
    }

    fun testPackageDirectoryDocumentsExactlyTheFilesUnderIt() {
        val (person, job, deep) = demoFiles(addDemo())
        val r = ok(resolve(listOf(person.parent)))
        assertSameElements(r.request.files, paths(person, job, deep))
        assertNull(r.focusClass)
        assertEquals("demo", r.focusPackage)
        assertEquals(realJavadoc, r.request.javadoc)
        assertTrue(r.request.includePrivate)
        assertFalse(r.request.composition)
        assertFalse(r.request.methodDependencies)
        assertEquals(Paths.get(project.basePath!!, "build", "uml").normalize(), r.request.outputDir)
    }

    fun testSubfolderDocumentsOnlyThatFolder() {
        val (_, _, deep) = demoFiles(addDemo())
        val r = ok(resolve(listOf(deep.parent)))
        assertEquals(paths(deep), r.request.files)
        assertEquals("demo.sub", r.focusPackage)
    }

    fun testSingleFileBecomesFileWithFocusClass() {
        val person = addDemo()
        val r = ok(resolve(listOf(person)))
        assertEquals(paths(person), r.request.files)
        assertEquals("Person", r.focusClass)
        assertEquals("demo", r.focusPackage)
    }

    fun testFileInsideSelectedPackageIsDeduped() {
        val (person, job, deep) = demoFiles(addDemo())
        val r = ok(resolve(listOf(person, person.parent, deep)))
        assertSameElements(r.request.files, paths(person, job, deep))
        assertEquals(3, r.request.files.size)
        assertNull(r.focusClass)
        assertEquals("demo", r.focusPackage)
    }

    fun testNestedSelectedFoldersAreDeduped() {
        val (person, job, deep) = demoFiles(addDemo())
        val r = ok(resolve(listOf(deep.parent, person.parent)))
        assertSameElements(r.request.files, paths(person, job, deep))
        assertEquals(3, r.request.files.size)
    }

    fun testTwoFilesHaveNoFocusClass() {
        val (person, job, _) = demoFiles(addDemo())
        val r = ok(resolve(listOf(person, job)))
        assertSameElements(r.request.files, paths(person, job))
        assertNull(r.focusClass)
    }

    fun testSourceRootDocumentsEverythingInThatRoot() {
        val (person, job, deep) = demoFiles(addDemo())
        val top = myFixture.addFileToProject("Top.java", "public class Top {}").virtualFile
        val root = person.parent.parent
        assertEquals(top.parent, root)
        val r = ok(resolve(listOf(root)))
        assertSameElements(r.request.files, paths(person, job, deep, top))
        assertNull(r.focusClass)
        assertEquals("demo", r.focusPackage)
    }

    /** Adds a test source root next to the fixture's `src` root, runs [block], then removes it again. */
    private fun withTestRoot(
        parent: VirtualFile = ModuleRootManager.getInstance(module).sourceRoots.single().parent,
        block: (testRoot: VirtualFile, personTest: VirtualFile) -> Unit,
    ) {
        val testRoot = WriteAction.computeAndWait<VirtualFile, Throwable> {
            parent.createChildDirectory(this, "testsrc")
        }
        try {
            PsiTestUtil.addSourceRoot(module, testRoot, true)
            val personTest = VfsTestUtil.createFile(
                testRoot, "demo/PersonTest.java", "package demo; public class PersonTest { Person p; }"
            )
            VfsTestUtil.createFile(testRoot, "demo/sub/DeepTest.java", "package demo.sub; public class DeepTest {}")
            block(testRoot, personTest)
        } finally {
            PsiTestUtil.removeSourceRoot(module, testRoot)
            WriteAction.runAndWait<Throwable> { testRoot.delete(this) }
        }
    }

    private fun assertNoTestClasses(r: Resolved.Ok) {
        val names = r.request.files.map { it.fileName.toString() }
        assertTrue("test classes leaked into $names", names.none { it.endsWith("Test.java") })
    }

    fun testMainSelectionsNeverIncludeTestSources() {
        val (person, job, deep) = demoFiles(addDemo())
        withTestRoot { testRoot, _ ->
            val index = ProjectFileIndex.getInstance(project)
            assertTrue(index.isInTestSourceContent(testRoot))
            assertFalse(index.isInTestSourceContent(person))

            // Same package name exists in the test root; only the main files may be documented.
            val pkg = ok(resolve(listOf(person.parent)))
            assertSameElements(pkg.request.files, paths(person, job, deep))
            assertNoTestClasses(pkg)

            val file = ok(resolve(listOf(person)))
            assertEquals(paths(person), file.request.files)

            val root = ok(resolve(listOf(person.parent.parent)))
            assertSameElements(root.request.files, paths(person, job, deep))
            assertNoTestClasses(root)

            // The sourcepath for a main selection has no test roots either.
            val mainRoots = UmlTargets.sourceRoots(module, includeTests = false)
            assertFalse("$mainRoots", testRoot in mainRoots)
            assertTrue("$mainRoots", person.parent.parent in mainRoots)
        }
    }

    fun testTestRootNestedInsideAMainFolderStaysOut() {
        val (person, job, deep) = demoFiles(addDemo())
        withTestRoot(parent = person.parent) { testRoot, _ ->
            assertTrue(ProjectFileIndex.getInstance(project).isInTestSourceContent(testRoot))
            val pkg = ok(resolve(listOf(person.parent)))
            assertSameElements(pkg.request.files, paths(person, job, deep))
            assertNoTestClasses(pkg)
        }
    }

    fun testTestSelectionDocumentsOnlyTestSources() {
        addDemo()
        withTestRoot { testRoot, personTest ->
            val pkg = ok(resolve(listOf(personTest.parent)))
            assertSameElements(
                pkg.request.files.map { it.fileName.toString() }, listOf("PersonTest.java", "DeepTest.java")
            )
            val root = ok(resolve(listOf(testRoot)))
            assertSameElements(root.request.files.map { it.fileName.toString() }, listOf("PersonTest.java", "DeepTest.java"))
            val file = ok(resolve(listOf(personTest)))
            assertEquals(paths(personTest), file.request.files)
            assertEquals("PersonTest", file.focusClass)
            // Test selections may resolve against both main and test roots.
            assertTrue(testRoot in UmlTargets.sourceRoots(module, includeTests = true))
        }
    }

    /** Runs the production [UmlTargets.resolveInBackground] (ReadAction.nonBlocking) on a pooled thread. */
    private fun inBackground(
        selection: List<VirtualFile>,
        indicator: ProgressIndicatorBase = ProgressIndicatorBase(),
        beforeAttempt: () -> Unit = {},
    ): Future<Any> = ApplicationManager.getApplication().executeOnPooledThread<Any> {
        try {
            UmlTargets.resolveInBackground(project, selection, docletJar, settings, indicator, { realJavadoc }, beforeAttempt)
        } catch (pce: ProcessCanceledException) {
            pce
        }
    }

    /** Waits for [future] on the EDT without starving the event queue. */
    private fun <T> await(future: Future<T>): T {
        val deadline = System.currentTimeMillis() + 30_000
        while (!future.isDone) {
            check(System.currentTimeMillis() < deadline) { "background resolution did not finish" }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        return future.get()
    }

    fun testBackgroundResolutionResolves() {
        val (person, job, deep) = demoFiles(addDemo())
        val r = await(inBackground(listOf(person.parent)))
        assertSameElements(ok(r as Resolved).request.files, paths(person, job, deep))
    }

    fun testBackgroundResolutionStopsWhenCancelled() {
        val person = addDemo()
        val indicator = ProgressIndicatorBase().apply { start(); cancel() }
        val attempts = AtomicInteger()
        val r = await(inBackground(listOf(person.parent), indicator) { attempts.incrementAndGet() })
        assertTrue("expected ProcessCanceledException, got $r", r is ProcessCanceledException)
        assertEquals("no attempt may run on a cancelled indicator", 0, attempts.get())
    }

    /**
     * The first attempt spins inside the read action until a write action arrives. The non-blocking read action
     * must give way (cancel the attempt), let the write run, and restart; the restarted attempt sees the write.
     */
    private fun runWithCompetingWrite(selection: List<VirtualFile>, write: () -> Unit): Pair<Any, Int> {
        val attempts = AtomicInteger()
        val firstAttemptRunning = CountDownLatch(1)
        val future = inBackground(selection) {
            if (attempts.incrementAndGet() == 1) {
                firstAttemptRunning.countDown()
                val deadline = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < deadline) {
                    ProgressManager.checkCanceled() // the pending write action cancels this attempt
                    Thread.sleep(5)
                }
            }
        }
        assertTrue("first attempt never started", firstAttemptRunning.await(20, TimeUnit.SECONDS))
        WriteAction.runAndWait<Throwable> { write() } // competes with the running read attempt
        return await(future) to attempts.get()
    }

    fun testBackgroundResolutionRestartsAfterWriteAndSeesNewFile() {
        val (person, job, deep) = demoFiles(addDemo())
        lateinit var added: VirtualFile
        val (r, attempts) = runWithCompetingWrite(listOf(person.parent)) {
            added = person.parent.createChildData(this, "Added.java")
            added.setBinaryContent("package demo; public class Added {}".toByteArray())
        }
        assertEquals("the write action must restart the read attempt", 2, attempts)
        assertSameElements(ok(r as Resolved).request.files, paths(person, job, deep, added))
    }

    fun testBackgroundResolutionRevalidatesSelectionAfterWrite() {
        val (person, job, _) = demoFiles(addDemo())
        val (r, attempts) = runWithCompetingWrite(listOf(person, job)) { job.delete(this) }
        assertEquals(2, attempts)
        assertTrue("$r", r is Resolved.Problem)
        assertTrue((r as Resolved.Problem).message, r.message.contains("Job.java no longer exists"))
    }

    fun testSettingsSnapshotIsImmutable() {
        val state = UmlSettings.State()
        val options = UmlSettings.snapshot(state)
        state.includePrivate = false
        state.methodDependencies = true
        state.outputDir = "elsewhere"
        assertEquals(UmlOptions(), options)
        assertEquals(UmlOptions(outputDir = "elsewhere", includePrivate = false, methodDependencies = true), UmlSettings.snapshot(state))
        assertEquals(UmlSettings.DEFAULT_OUTPUT, UmlSettings.snapshot(UmlSettings.State().apply { outputDir = " " }).outputDir)
    }

    fun testSettingsAreApplied() {
        val person = addDemo()
        val custom = UmlOptions(outputDir = "docs/uml", includePrivate = false, composition = true, methodDependencies = true)
        val r = ok(UmlTargets.resolve(project, listOf(person), docletJar, custom) { realJavadoc })
        assertFalse(r.request.includePrivate)
        assertTrue(r.request.composition)
        assertTrue(r.request.methodDependencies)
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
        val r = UmlTargets.resolve(project, listOf(person), Paths.get("/irrelevant.jar"), UmlOptions()) {
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
