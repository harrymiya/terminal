package dev.remote.terminal.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
internal fun JarvisModelApiSettings(store: LocalStore) {
    var url by remember(store) { mutableStateOf(store.loadJarvisModelApiUrl()) }
    var model by remember(store) { mutableStateOf(store.loadJarvisModelApiName()) }
    var keyInput by remember(store) { mutableStateOf("") }
    var status by remember(store) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit(testOnly: Boolean) {
        val draftUrl = url.trim()
        val draftModel = model.trim()
        val draftKey = keyInput.trim()
        val endpoint = runCatching { ApiChatModel.endpoint(draftUrl) }.getOrElse {
            status = "请输入有效的 HTTPS API 地址。"
            return
        }
        if (draftModel.isBlank() || draftKey.any { it.isWhitespace() }) {
            status = "请填写模型名称和有效的 API Key。"
            return
        }
        busy = true
        status = if (testOnly) "正在测试连接…" else ""
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { store.loadJarvisModelApiSettings() }
                val sameEndpoint = runCatching { ApiChatModel.endpoint(saved.url) == endpoint }.getOrDefault(false)
                val key = draftKey.ifBlank { saved.key.takeIf { sameEndpoint }.orEmpty() }
                if (key.isBlank()) {
                    status = "请为当前 API 地址输入 API Key。"
                } else if (testOnly) {
                    val response = ApiChatModel.complete(
                        draftUrl, draftModel, key,
                        """This is a connection test. Return exactly this JSON object: {"status":"ok"}"""
                    ).getOrThrow()
                    val valid = runCatching { JSONObject(response).optString("status") == "ok" }.getOrDefault(false)
                    status = if (valid) "连接成功，模型支持 JSON 响应。" else "已连接，模型未返回预期的 JSON。"
                } else {
                    withContext(Dispatchers.IO) { store.saveJarvisModelApi(draftUrl, draftModel, key) }
                    keyInput = ""
                    status = "API 配置已保存，下次请求生效。"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = if (testOnly) e.message ?: "连接测试失败。" else "无法保存 API Key，请检查设备存储后重试。"
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
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = { Text("地址不变时，留空沿用本机已保存的 Key。") }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { submit(false) }, enabled = !busy) { Text("保存") }
            TextButton(onClick = { submit(true) }, enabled = !busy) { Text("测试连接") }
        }
        TextButton(
            onClick = {
                store.clearJarvisModelApiKey()
                keyInput = ""
                status = "本机已保存的 API Key 已清除。"
            },
            enabled = !busy
        ) { Text("清除已保存的 Key") }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
