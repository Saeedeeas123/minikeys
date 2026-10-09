package com.saeed.minikeys.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.saeed.minikeys.core.settings.KeyboardSettings
import com.saeed.minikeys.core.settings.KeyboardTheme
import com.saeed.minikeys.core.llm.LlmProviderPreset
import com.saeed.minikeys.core.mediapipe.LocalModelFormats
import com.saeed.minikeys.core.llama.LlamaModelFormats
import kotlin.math.roundToInt

private const val HEIGHT_STEP_DP = 10

/**
 * The one settings screen of the keyboard module:
 *  - enable/select the IME,
 *  - choose a theme and a size (written to disk on every change),
 *  - see those saved values immediately, both in a live preview and as raw stored values.
 */
@Composable
fun MiniKeysSettingsScreen(state: SettingsScreenState) {
    val context = LocalContext.current
    val buffer = remember { TestFieldBuffer() }
    val aiState = remember { AiSettingsState(context) }
    val localState = remember { LocalLlmState(context) }
    val llamaState = remember { LlamaCppState(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "MiniKeys",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Keyboard module v0.1.0 — every change below is saved and applied to the keyboard " +
                "immediately, no restart needed.",
            style = MaterialTheme.typography.bodyMedium,
        )

        StatusCard(state)
        ThemeCard(state)
        SizeCard(state)
        AiCard(aiState)
        LocalModelCard(localState)
        LlamaGgufCard(llamaState)
        PreviewCard(state, buffer)
        SavedSettingsCard(state)
    }
}

@Composable
private fun StatusCard(state: SettingsScreenState) {
    SectionCard(title = "Keyboard status") {
        StatusRow(label = "Enabled in system settings", value = state.imeStatus.enabled)
        StatusRow(label = "Selected as the current keyboard", value = state.imeStatus.selected)

        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { state.openSystemKeyboardSettings() }) { Text("Enable") }
            OutlinedButton(onClick = { state.showKeyboardPicker() }) { Text("Choose keyboard") }
        }

        Text(
            text = "Enable → tick MiniKeys, then Choose keyboard → pick MiniKeys. The status above " +
                "refreshes every time this screen comes back to the front.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun StatusRow(label: String, value: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = if (value) "YES" else "NO",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (value) Color(0xFF2E7D32) else Color(0xFFB3261E),
        )
    }
}

@Composable
private fun ThemeCard(state: SettingsScreenState) {
    SectionCard(title = "Theme") {
        KeyboardTheme.ALL.forEach { theme ->
            ThemeRow(
                theme = theme,
                selected = theme.id == state.settings.themeId,
                onSelect = { state.selectTheme(theme.id) },
            )
        }
    }
}

@Composable
private fun ThemeRow(theme: KeyboardTheme, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(theme.keyBackground))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(12.dp))
        Text(text = theme.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = if (selected) "Selected" else "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SizeCard(state: SettingsScreenState) {
    SectionCard(title = "Size") {
        SliderRow(
            label = "Keyboard height",
            valueText = "${state.settings.keyboardHeightDp} dp",
            value = state.settings.keyboardHeightDp.toFloat(),
            range = KeyboardSettings.MIN_HEIGHT_DP.toFloat()..KeyboardSettings.MAX_HEIGHT_DP.toFloat(),
            steps = ((KeyboardSettings.MAX_HEIGHT_DP - KeyboardSettings.MIN_HEIGHT_DP) / HEIGHT_STEP_DP) - 1,
            onChange = { state.setKeyboardHeightDp(it.roundToInt()) },
        )
        SliderRow(
            label = "Key label size",
            valueText = "${state.settings.keyTextSizeSp} sp",
            value = state.settings.keyTextSizeSp.toFloat(),
            range = KeyboardSettings.MIN_TEXT_SIZE_SP.toFloat()..KeyboardSettings.MAX_TEXT_SIZE_SP.toFloat(),
            steps = (KeyboardSettings.MAX_TEXT_SIZE_SP - KeyboardSettings.MIN_TEXT_SIZE_SP) - 1,
            onChange = { state.setKeyTextSizeSp(it.roundToInt()) },
        )
        OutlinedButton(
            onClick = { state.resetToDefaults() },
            modifier = Modifier.padding(top = 6.dp),
        ) {
            Text("Reset to defaults")
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalModelCard(localState: LocalLlmState) {
    SectionCard(title = "Local model (offline)") {
        Text(
            text = "Runs fully on the phone through MediaPipe — no network needed for inference. " +
                "Supported files: .task / .litertlm / .bin. Import from device storage or download " +
                "from Hugging Face; a token is needed only for gated/private repositories.",
            style = MaterialTheme.typography.bodySmall,
        )

        Text(
            text = "Selected: ${localState.selectedModelPath.ifBlank { "none" }}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 6.dp),
        )

        val pickModel = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let { localState.copyPickedDocument(it) } }

        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { pickModel.launch(arrayOf("*/*")) }) { Text("Pick from device") }
            OutlinedButton(onClick = { localState.refresh() }) { Text("Refresh list") }
        }

        localState.modelFiles.forEach { file ->
            ModelFileRow(
                file = file,
                supported = LocalModelFormats.isSupported(file.name),
                selected = file.absolutePath == localState.selectedModelPath,
                onSelect = { localState.select(file.absolutePath) },
            )
        }

        LabeledField(
            label = "Hugging Face repository (user/model)",
            value = localState.hfRepo,
            onValue = { localState.hfRepo = it },
        )
        LabeledField(
            label = "File name in the repository",
            value = localState.hfFileName,
            onValue = { localState.hfFileName = it },
        )
        LabeledField(
            label = "Hugging Face token (gated repos only)",
            value = localState.hfToken,
            onValue = { localState.hfToken = it },
            hideValue = true,
        )

        Button(
            onClick = { localState.downloadFromHuggingFace() },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("Download from Hugging Face")
        }

        if (localState.downloadProgress.isNotBlank()) {
            Text(
                text = localState.downloadProgress,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }

        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { localState.testLocal() },
                enabled = !localState.testing,
            ) {
                Text(if (localState.testing) "Loading…" else "Test offline model")
            }
            OutlinedButton(onClick = { localState.unloadModel() }) { Text("Unload from memory") }
        }

        Text(
            text = localState.operationStatus,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ModelFileRow(
    file: java.io.File,
    supported: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = file.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = when {
                !supported -> "unsupported"
                else -> humanFileSize(file.length())
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (supported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

private fun humanFileSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "%.1f KB".format(bytes / 1024.0)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiCard(aiState: AiSettingsState) {
    SectionCard(title = "AI provider (OpenAI-compatible)") {
        Text(
            text = "Any endpoint that speaks the OpenAI chat schema works — cloud providers or a " +
                "local server (llama.cpp / Ollama / LM Studio on your LAN). The API key is stored " +
                "encrypted with the Android Keystore, never as plain text.",
            style = MaterialTheme.typography.bodySmall,
        )

        LabeledField(
            label = "Base URL",
            value = aiState.baseUrl,
            onValue = { aiState.baseUrl = it },
        )
        LabeledField(
            label = "Model",
            value = aiState.model,
            onValue = { aiState.model = it },
        )
        LabeledField(
            label = "API key",
            value = aiState.apiKey,
            onValue = { aiState.apiKey = it },
            hideValue = true,
        )

        Text(
            text = "Quick presets — they fill the URL and an example model; edit both freely:",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 10.dp),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LlmProviderPreset.ALL.forEach { preset ->
                OutlinedButton(
                    onClick = { aiState.applyPreset(preset) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 10.dp, vertical = 4.dp,
                    ),
                ) {
                    Text(preset.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Button(
            onClick = {
                aiState.saveConfig()
                aiState.runTest()
            },
            enabled = !aiState.testing,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(if (aiState.testing) "Testing…" else "Save & Test")
        }

        if (aiState.testStatus.isNotBlank()) {
            Text(
                text = aiState.testStatus,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    hideValue: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (hideValue) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    )
}

@Composable
private fun PreviewCard(state: SettingsScreenState, buffer: TestFieldBuffer) {
    SectionCard(title = "Saved settings on the keyboard") {
        KeyboardPreview(
            settings = state.settings,
            buffer = buffer,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)),
        )
        Text(
            text = "This preview is the real keyboard view, drawn from the saved settings. Tap its " +
                "keys to type into the test field below.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        TestField(
            buffer = buffer,
            modifier = Modifier
                .padding(top = 8.dp)
                .height(96.dp),
        )
    }
}

@Composable
private fun SavedSettingsCard(state: SettingsScreenState) {
    val snapshot = state.savedSnapshot

    SectionCard(title = "Persisted values (read back from disk)") {
        KeyValueRow("Theme", snapshot.settings.themeId)
        KeyValueRow("Keyboard height", "${snapshot.settings.keyboardHeightDp} dp")
        KeyValueRow("Key label size", "${snapshot.settings.keyTextSizeSp} sp")
        KeyValueRow("Last write", state.savedAtText())

        Text(
            text = if (snapshot.fileExists) {
                "Preferences file: ${snapshot.filePath}"
            } else {
                "Preferences file not written yet: ${snapshot.filePath}"
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(10.dp))
        Text(
            text = "Raw entries",
            style = MaterialTheme.typography.labelLarge,
        )
        if (snapshot.rawEntries.isEmpty()) {
            Text(
                text = "(empty — the keyboard is running on defaults)",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        } else {
            snapshot.rawEntries.forEach { (key, value) ->
                KeyValueRow(key, value, monospace = true)
            }
        }

        OutlinedButton(
            onClick = { state.refreshFromDisk() },
            modifier = Modifier.padding(top = 10.dp),
        ) {
            Text("Reload from disk")
        }
    }
}

@Composable
private fun KeyValueRow(key: String, value: String, monospace: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = key,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LlamaGgufCard(llamaState: LlamaCppState) {
    SectionCard(title = "GGUF model (llama.cpp, offline)") {
        Text(
            text = "Runs fully on the phone through llama.cpp — no network needed for inference. " +
                "Supported files: .gguf. The same models folder serves both offline engines.",
            style = MaterialTheme.typography.bodySmall,
        )

        Text(
            text = "Selected: ${llamaState.selectedModelPath.ifBlank { "none" }}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 6.dp),
        )

        val pickModel = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let { llamaState.copyPickedDocument(it) } }

        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { pickModel.launch(arrayOf("*/*")) }) { Text("Pick from device") }
            OutlinedButton(onClick = { llamaState.refresh() }) { Text("Refresh list") }
        }

        llamaState.modelFiles.forEach { file ->
            ModelFileRow(
                file = file,
                supported = LlamaModelFormats.isSupported(file.name),
                selected = file.absolutePath == llamaState.selectedModelPath,
                onSelect = { llamaState.select(file.absolutePath) },
            )
        }

        LabeledField(
            label = "Hugging Face repository (user/model)",
            value = llamaState.hfRepo,
            onValue = { llamaState.hfRepo = it },
        )
        LabeledField(
            label = "File name in the repository",
            value = llamaState.hfFileName,
            onValue = { llamaState.hfFileName = it },
        )
        LabeledField(
            label = "Hugging Face token (gated repos only)",
            value = llamaState.hfToken,
            onValue = { llamaState.hfToken = it },
            hideValue = true,
        )

        Button(
            onClick = { llamaState.downloadFromHuggingFace() },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("Download from Hugging Face")
        }

        if (llamaState.downloadProgress.isNotBlank()) {
            Text(
                text = llamaState.downloadProgress,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }

        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { llamaState.testModel() },
                enabled = !llamaState.testing,
            ) {
                Text(if (llamaState.testing) "Loading…" else "Test GGUF model")
            }
            OutlinedButton(onClick = { llamaState.unloadModel() }) { Text("Unload from memory") }
        }

        Text(
            text = llamaState.operationStatus,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
