package dev.opencode.mobile.markdown

import kotlin.test.assertEquals
import kotlin.test.assertTrue

object HighlighterRegressionAssertions {
    fun assertIdentifiersUniformlyCovered(
        code: String,
        spans: List<CodeSpan>,
        identifiers: List<String>,
        label: String = "highlight",
    ) {
        identifiers.forEach { identifier ->
            val start = code.indexOf(identifier)
            assertTrue(start >= 0, "$label missing identifier $identifier")
            val end = start + identifier.length
            val covering = spans.filter { span -> span.end > start && span.start < end }
            val uncovered =
                (start until end).filter { index ->
                    covering.none { span -> span.start <= index && span.end > index }
                }
            assertTrue(
                uncovered.isEmpty(),
                "$label $identifier at $start..$end has uncovered indices $uncovered in spans $covering",
            )
            val roles =
                (start until end).mapNotNull { index ->
                    covering
                        .filter { span -> span.start <= index && span.end > index }
                        .maxByOrNull { span -> span.end - span.start }
                        ?.role
                }
            assertTrue(
                roles.distinct().size <= 1,
                "$label $identifier at $start..$end has fragmented roles $roles in spans $covering",
            )
        }
    }

    fun assertPrivateFieldsUseVariableRole(
        code: String,
        spans: List<CodeSpan>,
        privateFields: List<String>,
        label: String = "highlight",
    ) {
        privateFields.forEach { field ->
            val declarationStart = code.indexOf(field)
            assertTrue(declarationStart >= 0, "$label missing declaration for $field")
            val declarationEnd = declarationStart + field.length
            val declarationSpan =
                spans.singleOrNull { span ->
                    span.start <= declarationStart && span.end >= declarationEnd && span.role != CodeTokenRole.Plain
                }
            assertTrue(
                declarationSpan != null,
                "$label $field declaration at $declarationStart..$declarationEnd missing span in $spans",
            )
            assertEquals(
                CodeTokenRole.Variable,
                declarationSpan.role,
                "$label $field declaration should use Variable role",
            )

            val needle = "this.$field"
            val accessStart = code.indexOf(needle)
            assertTrue(accessStart >= 0, "$label missing member access for $needle")
            val fieldStart = accessStart + "this.".length
            val fieldEnd = fieldStart + field.length
            val accessSpan =
                spans.single { span ->
                    span.start <= fieldStart && span.end >= fieldEnd && span.role != CodeTokenRole.Plain
                }
            assertEquals(
                CodeTokenRole.Variable,
                accessSpan.role,
                "$label $needle should use Variable role",
            )
        }
    }
}
