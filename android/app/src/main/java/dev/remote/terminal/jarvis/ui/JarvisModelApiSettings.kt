package dev.remote.terminal.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.remote.terminal.data.LocalStore
import dev.remote.terminal.jarvis.model.ApiChatModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun JarvisModelApiSettings(store: LocalStore, onFormState: (dirty: Boolean, busy: Boolean) -> Unit) {
    var savedUrl by remember(store) { mutableStateOf(store.loadJarvisModelApiUrl()) }
    var savedModel by remember(store) { mutableStateOf(store.loadJarvisModelApiName()) }
    var hasSavedKey by remember(store) { mutableStateOf(store.hasJarvisModelApiKey()) }
    var url by remember(store) { mutableStateOf(savedUrl) }
    var model by remember(store) { mutableStateOf(savedModel) }
    var keyInput by remember(store) { mutableStateOf("") }
    var status by remember(store) { mutableStateOf("") }
    var testResult by remember(store) { mutableStateOf<TestResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val reuseSavedKey = hasSavedKey && runCatching { ApiChatModel.endpoint(url) == ApiChatModel.endpoint(savedUrl) }.getOrDefault(false)
    val dirty = keyInput.isNotEmpty() || url.trim() != savedUrl || model.trim() != savedModel
    SideEffect { onFormState(dirty, busy) }

    fun submit(testOnly: Boolean) {
        if (busy) return
        fun fail(message: String) {
            if (testOnly) testResult = TestResult("连接失败", message) else status = message
        }
        focus.clearFocus()
        keyboard?.hide()
        val draftUrl = url.trim()
        val draftModel = model.trim()
        val draftKey = keyInput.trim()
        val endpoint = runCatching { ApiChatModel.endpoint(draftUrl) }.getOrElse {
            fail("请输入有效的 HTTPS API 地址。")
            return
        }
        if (draftModel.isBlank() || draftKey.any { it.isWhitespace() }) {
            fail("请填写模型名称和有效的 API Key。")
            return
        }
        busy = true
        status = if (testOnly) "" else "正在保存 API 配置…"
        if (testOnly) testResult = TestResult("正在测试连接", "正在检查地址、API Key 和模型响应。", running = true)
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { store.loadJarvisModelApiSettings() }
                val sameEndpoint = runCatching { ApiChatModel.endpoint(saved.url) == endpoint }.getOrDefault(false)
                val key = draftKey.ifBlank { saved.key.takeIf { sameEndpoint }.orEmpty() }
                if (key.isBlank()) {
                    if (sameEndpoint) hasSavedKey = false
                    fail("请为当前 API 地址输入 API Key。")
                } else if (testOnly) {
                    val response = ApiChatModel.complete(
                        draftUrl, draftModel, key,
                        """This is a connection test. Return exactly this JSON object: {"status":"ok"}""",
                        connectionTest = true
                    ).getOrThrow()
                    val valid = runCatching { JSONObject(response).optString("status") == "ok" }.getOrDefault(false)
                    testResult = if (valid) TestResult("连接成功", "API Key 和模型可用，已收到有效的 JSON 响应。")
                    else TestResult("测试未通过", "已连接 API，但模型未返回预期 JSON。请检查模型名称和 JSON 输出支持。")
                } else {
                    withContext(Dispatchers.IO) { store.saveJarvisModelApi(draftUrl, draftModel, key) }
                    savedUrl = draftUrl
                    savedModel = draftModel
                    hasSavedKey = true
                    keyInput = ""
                    status = "API 配置已保存，下次请求生效。"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(if (testOnly) e.message ?: "连接测试失败。" else "无法保存 API Key，请检查设备存储后重试。")
            } finally {
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("API 模式会把本轮请求、召回的记忆和相关 App 状态发送给所配置的模型服务。")
        OutlinedTextField(
            value = url, onValueChange = { url = it; status = "" }, enabled = !busy,
            label = { Text("API Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        OutlinedTextField(
            value = model, onValueChange = { model = it; status = "" }, enabled = !busy,
            label = { Text("模型名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = keyInput, onValueChange = { keyInput = it; status = "" }, enabled = !busy,
            label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { if (reuseSavedKey) Text("••••••••") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit(false) }),
            supportingText = {
                Text(when {
                    reuseSavedKey && keyInput.isBlank() -> "•••••••• · Key 已加密保存。输入新 Key 可替换。"
                    reuseSavedKey -> "点击保存后替换已保存的 Key。"
                    hasSavedKey -> "API 地址已更改，请输入新地址对应的 Key。"
                    else -> "尚未保存 API Key。"
                })
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { submit(false) }, enabled = !busy) { Text("保存 API 配置") }
            TextButton(onClick = { submit(true) }, enabled = !busy) { Text("测试连接") }
        }
        if (status.isNotBlank()) {
            Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall)
        }
        TextButton(
            onClick = {
                store.clearJarvisModelApiKey()
                hasSavedKey = false
                keyInput = ""
                status = "本机已保存的 API Key 已清除。"
            },
            enabled = !busy && hasSavedKey
        ) { Text("清除已保存的 Key") }
    }
    testResult?.let { result ->
        AlertDialog(
            onDismissRequest = { if (!result.running) testResult = null },
            title = { Text(result.title) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (result.running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(result.detail, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { if (!result.running) TextButton(onClick = { testResult = null }) { Text("知道了") } }
        )
    }
}

private data class TestResult(val title: String, val detail: String, val running: Boolean = false)
