package dev.petalnet.umldoclet

import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.VfsTestUtil
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Selections spanning two modules. Light fixtures can't add modules, so this is a heavy (on-disk) fixture.
 * One javadoc run has one SDK, sourcepath and classpath, so a mixed-module selection is rejected.
 */
class UmlTargetsMultiModuleTest : JavaCodeInsightFixtureTestCase() {

    private val javadoc = Paths.get(System.getProperty("java.home"), "bin", "javadoc")

    private fun resolve(selection: List<com.intellij.openapi.vfs.VirtualFile>): Resolved =
        UmlTargets.resolve(project, selection, Paths.get("/irrelevant/umldoclet.jar"), UmlOptions()) { javadoc }

    fun testSelectionSpanningTwoModulesIsRejected() {
        val person = myFixture.addFileToProject("demo/Person.java", "package demo; public class Person {}").virtualFile
        val otherDir = Files.createDirectories(Paths.get(myFixture.tempDirPath).resolveSibling(name + "-other"))
        try {
            twoModules(person, otherDir)
        } finally {
            otherDir.toFile().deleteRecursively()
        }
    }

    private fun twoModules(person: com.intellij.openapi.vfs.VirtualFile, otherDir: java.nio.file.Path) {
        val otherRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(otherDir)!!
        val other = PsiTestUtil.addModule(project, JavaModuleType.getModuleType(), "other", otherRoot)
        val index = ProjectFileIndex.getInstance(project)
        if (!index.isInSourceContent(otherRoot)) PsiTestUtil.addSourceContentToRoots(other, otherRoot)
        val thing = VfsTestUtil.createFile(otherRoot, "elsewhere/Thing.java", "package elsewhere; public class Thing {}")
        assertEquals(other, index.getModuleForFile(thing))
        assertFalse(index.getModuleForFile(person) == other)

        val r = resolve(listOf(person, thing))
        assertTrue("$r", r is Resolved.Problem)
        val message = (r as Resolved.Problem).message
        assertTrue(message, message.contains("several modules"))
        assertTrue(message, message.contains("'other'"))
        assertTrue(message, message.contains("'${module.name}'"))

        // Folders of two modules are rejected too.
        assertTrue(resolve(listOf(person.parent, otherRoot)) is Resolved.Problem)
    }
}
