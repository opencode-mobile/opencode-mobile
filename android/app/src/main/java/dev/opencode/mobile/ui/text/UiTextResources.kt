package dev.opencode.mobile.ui.text

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.domain.text.UiTextLookup
import dev.opencode.mobile.domain.text.resolve

/** [UiTextLookup] backed by Android [Resources]. */
class ResourcesUiTextLookup(
    private val resources: Resources,
) : UiTextLookup {
    override fun string(
        id: Int,
        args: Array<out Any>,
    ): String = if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args)

    override fun plural(
        id: Int,
        count: Int,
        args: Array<out Any>,
    ): String = resources.getQuantityString(id, count, *args)
}

/** Resolves in the current configuration; recomposes when the locale changes, like stringResource. */
@Composable
@ReadOnlyComposable
fun UiText.resolve(): String = resolve(ResourcesUiTextLookup(LocalResources.current))
