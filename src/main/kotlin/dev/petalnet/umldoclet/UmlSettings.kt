package dev.petalnet.umldoclet

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Per-project settings, stored in `.idea/umldoclet.xml`. */
@Service(Service.Level.PROJECT)
@State(name = "UmlDocletSettings", storages = [Storage("umldoclet.xml")])
class UmlSettings : SimplePersistentStateComponent<UmlSettings.State>(State()) {

    class State : BaseState() {
        /** Where the `.puml` files go, relative to the project base directory (absolute paths work too). */
        var outputDir by string(DEFAULT_OUTPUT)

        /** `javadoc -private` when true, `-protected` otherwise. */
        var includePrivate by property(true)

        /** Rewrite field associations `A --> B` to composition `A *--> B`. */
        var composition by property(false)

        /** Pass `--uml-method-dependencies`: dashed `A ..> B` arrows for types used in method signatures. */
        var methodDependencies by property(false)
    }

    companion object {
        const val DEFAULT_OUTPUT = "build/uml"

        /** Immutable copy of the settings, taken on the EDT before work is queued to a background thread. */
        fun snapshot(state: State) = UmlOptions(
            outputDir = state.outputDir?.takeIf { it.isNotBlank() } ?: DEFAULT_OUTPUT,
            includePrivate = state.includePrivate,
            composition = state.composition,
            methodDependencies = state.methodDependencies,
        )
        fun getInstance(project: Project): UmlSettings = project.service()
    }
}

/** Immutable snapshot of [UmlSettings.State]; safe to hand to background threads. */
data class UmlOptions(
    val outputDir: String = UmlSettings.DEFAULT_OUTPUT,
    val includePrivate: Boolean = true,
    val composition: Boolean = false,
    val methodDependencies: Boolean = false,
)
