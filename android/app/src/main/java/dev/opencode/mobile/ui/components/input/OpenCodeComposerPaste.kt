package dev.opencode.mobile.ui.components

import android.content.ClipDescription
import android.content.ClipboardManager
import android.net.Uri
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat

/**
 * An image or file pasted into the composer, from the text menu's Paste, Ctrl+V, or the keyboard's
 * image insertion (#870). [release] drops the temporary read grant once the content is copied.
 */
class OpenCodeComposerPastedContent(
    val uris: List<Uri>,
    private val onRelease: () -> Unit = {},
) {
    fun release() = onRelease()
}

/**
 * Paste hooks for the prompt field. It keeps the `TextFieldValue` field (only the `TextFieldState`
 * field reads `contentReceiver`, and that one cannot colour mentions), so a clipboard holding an
 * image or file is caught at the text menu and at the hardware Paste keys instead. Text-only
 * clipboards paste as text, untouched.
 */
@Stable
internal class ComposerAttachmentPaste(
    private val clipboard: ClipboardManager?,
) {
    /** Reads the clip's types only, so Android shows no "pasted from" notice. */
    fun hasAttachableClip(): Boolean = clipboard?.primaryClipDescription?.hasAttachableTypes() == true

    fun attachableClip(): OpenCodeComposerPastedContent? {
        val clip = runCatching { clipboard?.primaryClip }.getOrNull() ?: return null
        if (!clip.description.hasAttachableTypes()) return null
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        return uris.takeIf { it.isNotEmpty() }?.let { OpenCodeComposerPastedContent(it) }
    }

    /** Ctrl+V or Shift+Insert with an image or file on the clipboard; true when it was handled. */
    fun handleKey(
        event: KeyEvent,
        onPaste: (OpenCodeComposerPastedContent) -> Unit,
    ): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val paste =
            (event.isCtrlPressed && !event.isShiftPressed && event.key == Key.V) ||
                (event.isShiftPressed && !event.isCtrlPressed && event.key == Key.Insert)
        if (!paste || !hasAttachableClip()) return false
        val content = attachableClip() ?: return false
        onPaste(content)
        return true
    }
}

@Composable
internal fun rememberComposerAttachmentPaste(): ComposerAttachmentPaste {
    val context = LocalContext.current
    return remember(context) { ComposerAttachmentPaste(context.getSystemService(ClipboardManager::class.java)) }
}

/** The text menu offers Paste for an image or file clip, in place of the text Paste it would hide. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.composerAttachmentPasteMenu(
    paste: ComposerAttachmentPaste,
    enabled: Boolean,
    onPaste: (OpenCodeComposerPastedContent) -> Unit,
): Modifier {
    if (!enabled) return this
    return filterTextContextMenuComponents { component ->
        component.key != TextContextMenuKeys.PasteKey || !paste.hasAttachableClip()
    }.appendTextContextMenuComponents {
        if (paste.hasAttachableClip()) {
            item(key = ComposerPasteAttachmentKey, label = "Paste") {
                close()
                paste.attachableClip()?.let(onPaste)
            }
        }
    }
}

/**
 * Lets the keyboard insert images (Gboard's copied-image chip, stickers) through `commitContent`:
 * the composer's input connection advertises image types and hands inserted content to [onPaste].
 * Always wraps [content] so toggling [enabled] never recreates the text field.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ComposerKeyboardContentInsertion(
    enabled: Boolean,
    onPaste: (OpenCodeComposerPastedContent) -> Unit,
    content: @Composable () -> Unit,
) {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnPaste by rememberUpdatedState(onPaste)
    val interceptor =
        remember {
            object : PlatformTextInputInterceptor {
                override suspend fun interceptStartInputMethod(
                    request: PlatformTextInputMethodRequest,
                    nextHandler: PlatformTextInputSession,
                ): Nothing {
                    val wrapped =
                        object : PlatformTextInputMethodRequest {
                            override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                                val connection = request.createInputConnection(outAttributes)
                                if (!currentEnabled) return connection
                                EditorInfoCompat.setContentMimeTypes(outAttributes, KeyboardContentMimeTypes)
                                return InputConnectionCompat.createWrapper(connection, outAttributes) { info, flags, _ ->
                                    if (!currentEnabled) return@createWrapper false
                                    val grantRead =
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
                                            flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0
                                    if (grantRead && runCatching { info.requestPermission() }.isFailure) {
                                        return@createWrapper false
                                    }
                                    currentOnPaste(
                                        OpenCodeComposerPastedContent(listOf(info.contentUri)) {
                                            if (grantRead) runCatching { info.releasePermission() }
                                        },
                                    )
                                    true
                                }
                            }
                        }
                    nextHandler.startInputMethod(wrapped)
                }
            }
        }
    InterceptPlatformTextInput(interceptor = interceptor, content = content)
}

internal fun ClipDescription.hasAttachableTypes(): Boolean = composerClipTypesAreAttachable((0 until mimeTypeCount).map(::getMimeType))

/**
 * An image, or only non-text types (a copied file). A clip that also names a text type, such as rich
 * text with an app-specific type, stays a text paste: the built-in Paste is never hidden for a clip
 * whose items carry no file.
 */
internal fun composerClipTypesAreAttachable(mimeTypes: List<String>): Boolean =
    mimeTypes.any { it.startsWith("image/") } || (mimeTypes.isNotEmpty() && mimeTypes.none { it.startsWith("text/") })

private val ComposerPasteAttachmentKey = Any()
private val KeyboardContentMimeTypes = arrayOf("image/png", "image/jpeg", "image/gif", "image/webp")
