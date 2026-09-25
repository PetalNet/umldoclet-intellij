import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.petalnet"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// UMLDoclet runs inside the project's own `javadoc` process, never in the IDE, so it is
// shipped as a plain file next to the plugin (doclet/umldoclet.jar), not on the plugin classpath.
val doclet: Configuration by configurations.creating { isTransitive = false }

dependencies {
    doclet("nl.talsmasoftware:umldoclet:2.3.2")
    intellijPlatform {
        intellijIdeaCommunity("2024.2.6")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // 2024.2 bundles the Kotlin 1.9 stdlib; don't use newer language features.
        apiVersion.set(KotlinVersion.KOTLIN_1_9)
        languageVersion.set(KotlinVersion.KOTLIN_1_9)
    }
}

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides { recommended() }
    }
}

tasks {
    prepareSandbox {
        // Lands in <sandbox>/plugins/<name>/doclet/umldoclet.jar and therefore in the distribution zip.
        from(doclet) {
            rename { "umldoclet.jar" }
            into(intellijPlatform.projectName.map { "$it/doclet" })
        }
    }
    test {
        // The runner test drives a real javadoc + UMLDoclet, exactly like the plugin does.
        systemProperty("umldoclet.jar", doclet.singleFile.absolutePath)
        // The runner test drops the generated package.puml here so humans can look at it.
        systemProperty("umldoclet.testOut", layout.buildDirectory.dir("uml-samples").get().asFile.absolutePath)
    }
}
