package com.ai.assistance.operit.harness

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import com.ai.assistance.operit.core.config.SystemToolPrompts
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.ui.permissions.ToolPermissionSystem
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Loopback-only, authenticated, stateless Streamable HTTP MCP transport. */
class NativeMcpServer(context: Context, token: String) : HarnessMcpTransport(token) {
    private val handler = AIToolHandler.getInstance(context)
    private val permissions = ToolPermissionSystem.getInstance(context)
    // ToolPermissionSystem has one pending callback, so permission and execution are one critical section.
    private val execution = Mutex()
    private val catalog by lazy {
        runBlocking { HarnessNativeInitialization.initialize(context) }
        handler.registerDefaultTools()
        val registered = handler.getAllToolNames().toSet()
        SystemToolPrompts.getAllCategoriesEn().flatMap { it.tools }.distinctBy { it.name }
            .filter { HarnessSecurity.allowedTool(it.name) && it.name in registered }
            .associateBy { it.name }
    }
    private val rpc = HarnessRpc(object : HarnessRpc.NativeTools {
        override fun catalog(): JSONArray = JSONArray().apply {
            catalog.values.sortedBy { it.name }.forEach { tool ->
                put(JSONObject().put("name", tool.name).put("description", tool.description)
                    .put("parameters", tool.parameters).put("details", tool.details).put("notes", tool.notes)
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject().apply {
                        tool.parametersStructured.orEmpty().forEach { p ->
                            put(p.name, JSONObject().put("type", p.type).put("description", p.description).apply {
                                p.default?.let { put("default", it) }
                            })
                        }
                    }).put("required", JSONArray(tool.parametersStructured.orEmpty().filter { it.required }.map { it.name }))))
            }
        }

        override fun execute(name: String, parameters: JSONObject): JSONObject = runBlocking {
            execution.withLock {
                if (!HarnessSecurity.allowedTool(name) || name !in catalog) return@withLock HarnessRpc.text("Operation unavailable", true)
                val schema = catalog.getValue(name)
                val supplied = parameters.keys().asSequence().toList()
                if (supplied.any { it.startsWith("__") }) return@withLock HarnessRpc.text("System parameters are forbidden", true)
                schema.parametersStructured?.let { fields ->
                    val allowed = fields.map { it.name }.toSet()
                    if (supplied.any { it !in allowed } || fields.any { it.required && !parameters.has(it.name) }) {
                        return@withLock HarnessRpc.text("Parameters do not match the operation catalog", true)
                    }
                }
                val tool = AITool(name, supplied.map { key -> ToolParameter(key, parameters.get(key).toString()) })
                val permission = permissions.checkToolPermission(tool)
                if (!permission.isGranted) return@withLock HarnessRpc.text("Native permission: ${permission.name}", true)
                val result = handler.executeTool(tool)
                if (!result.success) return@withLock HarnessRpc.text(result.error ?: result.result.toString(), true)
                if (name == "capture_screenshot") {
                    val file = File(result.result.toString())
                    if (!file.isFile || file.length() > 16 * 1024 * 1024 || file.length() == 0L) {
                        return@withLock HarnessRpc.text("Screenshot bytes unavailable or too large", true)
                    }
                    val bytes = file.readBytes()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outMimeType !in setOf("image/png", "image/jpeg", "image/webp")) {
                        return@withLock HarnessRpc.text("Screenshot is not a supported image", true)
                    }
                    JSONObject().put("content", JSONArray().put(JSONObject().put("type", "image")
                        .put("mimeType", bounds.outMimeType).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))))
                        .put("isError", false)
                } else HarnessRpc.text(result.result.toString(), false)
            }
        }
    })

    override fun dispatch(request: JSONObject): JSONObject? = rpc.dispatch(request)
}
