package com.saeed.minikeys.ui

import android.text.InputType
import android.widget.EditText
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.saeed.minikeys.core.settings.KeyboardSettings
import com.saeed.minikeys.core.settings.KeyboardMode
import com.saeed.minikeys.keyboard.EdgeKeyboardView
import com.saeed.minikeys.keyboard.MiniKeyboardView
import kotlin.math.max
import kotlin.math.min

/**
 * Bridge between the previewed keyboard and the test field.
 *
 * It edits the [EditText] buffer directly instead of dispatching key events, so the preview works
 * even while the field is not focused (and without the real IME being enabled).
 */
class TestFieldBuffer {

    var view: EditText? = null

    fun insert(text: String) {
        val field = view ?: return
        val editable = field.editableText ?: return
        val start = selectionStart(field)
        val end = selectionEnd(field)
        editable.replace(start, end, text)
        field.setSelection((start + text.length).coerceAtMost(editable.length))
    }

    fun deleteBackward() {
        val field = view ?: return
        val editable = field.editableText ?: return
        val start = selectionStart(field)
        val end = selectionEnd(field)
        if (end > start) {
            editable.delete(start, end)
            field.setSelection(start)
        } else if (start > 0) {
            editable.delete(start - 1, start)
            field.setSelection(start - 1)
        }
    }

    private fun selectionStart(field: EditText): Int = min(field.selectionStart, field.selectionEnd).coerceAtLeast(0)

    private fun selectionEnd(field: EditText): Int = max(field.selectionStart, field.selectionEnd).coerceAtLeast(0)
}

/**
 * The real [MiniKeyboardView] rendered inside the settings screen — same drawing code as the IME,
 * so theme and size changes are visible here the moment they are saved.
 */
@Composable
fun KeyboardPreview(
    settings: KeyboardSettings,
    buffer: TestFieldBuffer,
    modifier: Modifier = Modifier,
) {
    val vertical = settings.mode == KeyboardMode.VERTICAL
    val previewHeight = if (vertical) 420.dp else settings.keyboardHeightDp.dp

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(previewHeight),
        factory = { context ->
            val listener = object : MiniKeyboardView.Listener {
                override fun onText(text: String) = buffer.insert(text)
                override fun onBackspace() = buffer.deleteBackward()
                override fun onEnter() = buffer.insert("\n")
            }
            if (vertical) {
                EdgeKeyboardView(context).also { view ->
                    view.listener = listener
                    view.applySettings(settings)
                }
            } else {
                MiniKeyboardView(context).also { view ->
                    view.listener = listener
                    view.applySettings(settings)
                }
            }
        },
        update = { view ->
            when (view) {
                is EdgeKeyboardView -> view.applySettings(settings)
                is MiniKeyboardView -> view.applySettings(settings)
            }
        },
    )
}

/**
 * A multi-line test field. Tapping it also summons the real keyboard, which is the end-to-end check
 * that the settings really reach the IME.
 */
@Composable
fun TestField(
    buffer: TestFieldBuffer,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            EditText(context).apply {
                hint = "Test field — type with the preview above, or tap here to use the real keyboard"
                minLines = 3
                maxLines = 6
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
        },
        update = { field -> buffer.view = field },
    )
}