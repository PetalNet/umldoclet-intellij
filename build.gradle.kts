import java.security.MessageDigest
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.petalnet"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    // PetalNet's UMLDoclet fork (Map associations, --uml-method-dependencies), built from a commit by JitPack.
    // Only that group may resolve from JitPack.
    exclusiveContent {
        forRepository { maven { url = uri("https://jitpack.io") } }
        filter { includeGroup("com.github.PetalNet") }
    }
    intellijPlatform { defaultRepositories() }
}

// UMLDoclet runs inside the project's own `javadoc` process, never in the IDE, so it is
// shipped as a plain file next to the plugin (doclet/umldoclet.jar), not on the plugin classpath.
val doclet: Configuration by configurations.creating { isTransitive = false }

// Pinned to an immutable commit of https://github.com/PetalNet/umldoclet main (PR #1 squash-merge).
// The SHA-256 guards against JitPack serving a different jar for the same coordinates.
val docletCommit = "f4ca3e0add29a34ded212891e69903231655b50c"
val docletSha256 = "5c57922f13398ca59b318f674dba3c393fd683bb8011c05748cdd09577b149bb"

dependencies {
    doclet("com.github.PetalNet:umldoclet:$docletCommit")
    intellijPlatform {
        // 2025.3+ ships a single unified IntelliJ IDEA distribution (no separate Community build).
        intellijIdea("2026.2.3")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    // IntelliJ Platform 2026.2 (262) requires Java 25.
    jvmToolchain(25)
    compilerOptions {
        // Match the Kotlin stdlib bundled in 2026.2 (2.4.0).
        apiVersion.set(KotlinVersion.KOTLIN_2_4)
        languageVersion.set(KotlinVersion.KOTLIN_2_4)
    }
}

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides { current() }
    }
}

val verifyDocletJar by tasks.registering {
    val jar: FileCollection = doclet
    val expected = docletSha256
    inputs.files(jar)
    doLast {
        val file = jar.singleFile
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        check(actual == expected) { "UMLDoclet jar ${file.name} has SHA-256 $actual, expected $expected" }
    }
}

tasks {
    prepareSandbox {
        dependsOn(verifyDocletJar)
        // Lands in <sandbox>/plugins/<name>/doclet/umldoclet.jar and therefore in the distribution zip.
        from(doclet) {
            rename { "umldoclet.jar" }
            into(intellijPlatform.projectName.map { "$it/doclet" })
        }
    }
    test {
        dependsOn(verifyDocletJar)
        // IDEA 2026.2.2+ (unified distribution): the bundled Ultimate plugin's post-startup activity shares an
        // obfuscated class name with lib/product-backend.jar, so every fixture project open logs a PluginException
        // and fails the test. Load only what the tests need. See JetBrains/intellij-platform-gradle-plugin#2261.
        systemProperty("idea.load.plugins.id", "dev.petalnet.umldoclet,com.intellij.java")
        // The runner test drives a real javadoc + UMLDoclet, exactly like the plugin does.
        systemProperty("umldoclet.jar", doclet.singleFile.absolutePath)
        // The runner test drops the generated package.puml here so humans can look at it.
        systemProperty("umldoclet.testOut", layout.buildDirectory.dir("uml-samples").get().asFile.absolutePath)
    }
}
