package dev.petalnet.umldoclet

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

/** Settings > Tools > UMLDoclet. */
class UmlSettingsConfigurable(private val project: Project) : BoundConfigurable("UMLDoclet") {

    override fun createPanel(): DialogPanel {
        val state = UmlSettings.getInstance(project).state
        return panel {
            row("Output folder:") {
                textField()
                    .align(AlignX.FILL)
                    .bindText({ state.outputDir ?: UmlSettings.DEFAULT_OUTPUT }, { state.outputDir = it })
                    .comment("Relative to the project root. Only .puml files are written; package folders are kept.")
            }
            row {
                checkBox("Include private members")
                    .bindSelected(state::includePrivate)
                    .comment("Runs javadoc with -private; otherwise -protected (public and protected members only).")
            }
            row {
                checkBox("Draw field associations as composition (*-->)")
                    .bindSelected(state::composition)
                    .comment(
                        "UMLDoclet draws every field reference as a plain association (A --> B), because Java " +
                            "cannot say whether A owns B or merely refers to it. Some courses want fields drawn as " +
                            "composition; this rewrites those arrows to A *--> B. Inheritance, realisation and " +
                            "dependency arrows are left alone."
                    )
            }
            row {
                checkBox("Draw method dependencies (..>)")
                    .bindSelected(state::methodDependencies)
                    .comment(
                        "Adds a dashed dependency A ..> B in package diagrams when a visible method of A takes or " +
                            "returns B (also inside List, Optional, Map, arrays). Skipped when A already has an " +
                            "association to B. Off by default: it can make diagrams busy."
                    )
            }
        }
    }
}
