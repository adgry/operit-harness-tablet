package com.ai.assistance.operit.harness

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.ai.assistance.operit.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 软件内更新：读取 GitHub Release 上的 update.json，下载 APK、校验 SHA256 并拉起系统安装器。
 * 不依赖任何第三方库，与 Harness 运行时的启动流程完全解耦。
 */
data class HarnessUpdate(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String,
)

object HarnessUpdater {
    /** 仓库与发布页；打包时按实际上传位置修改这两处即可。 */
    const val REPOSITORY = "adgry/operit-harness-tablet"
    const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases/latest"
    private const val MANIFEST_URL = "https://github.com/$REPOSITORY/releases/latest/download/update.json"
    private const val MAX_MANIFEST_BYTES = 96 * 1024
    private const val MAX_APK_BYTES = 1024L * 1024L * 1024L

    val currentVersionName: String get() = BuildConfig.VERSION_NAME
    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE

    class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** 返回比本机更新的版本；已是最新时返回 null。网络异常抛出 [UpdateException]。 */
    suspend fun check(): HarnessUpdate? = withContext(Dispatchers.IO) {
        val text = try {
            readText(MANIFEST_URL)
        } catch (error: Exception) {
            throw UpdateException(error.message ?: "无法连接更新服务器", error)
        }
        val json = try {
            JSONObject(text)
        } catch (error: Exception) {
            throw UpdateException("更新清单格式错误", error)
        }
        val versionCode = json.optInt("versionCode", 0)
        val versionName = json.optString("versionName", "").trim()
        val apkUrl = json.optString("apkUrl", "").trim()
        if (versionCode <= 0 || versionName.isEmpty() || !apkUrl.startsWith("https://")) {
            throw UpdateException("更新清单缺少有效版本信息")
        }
        if (versionCode <= currentVersionCode) return@withContext null
        HarnessUpdate(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            sha256 = json.optString("sha256", "").trim().lowercase(),
            notes = json.optString("notes", "").trim(),
        )
    }

    /** 下载并校验；进度回调在 IO 线程触发。 */
    suspend fun download(
        context: Context,
        update: HarnessUpdate,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = (context.getExternalFilesDir("updates") ?: File(context.filesDir, "updates")).apply { mkdirs() }
        val target = File(dir, "operit-harness-${update.versionCode}.apk")
        val partial = File(dir, "operit-harness-${update.versionCode}.apk.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = open(update.apkUrl)
        try {
            val total = connection.contentLengthLong
            if (total > MAX_APK_BYTES) throw UpdateException("安装包大小异常")
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var written = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val percent = (written * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                        if (written > MAX_APK_BYTES) throw UpdateException("安装包大小异常")
                    }
                }
            }
        } catch (error: Exception) {
            partial.delete()
            throw if (error is UpdateException) error else UpdateException(error.message ?: "下载失败", error)
        } finally {
            connection.disconnect()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (update.sha256.length == 64 && actual != update.sha256) {
            partial.delete()
            throw UpdateException("安装包校验失败，请重试")
        }
        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) {
            partial.delete()
            throw UpdateException("无法保存安装包")
        }
        target
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun open(url: String): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 12_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json, application/octet-stream")
            setRequestProperty("User-Agent", "OperitHarness/${BuildConfig.VERSION_NAME}")
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw UpdateException("更新服务器返回 HTTP $code")
        }
        return connection
    }

    private fun readText(url: String): String {
        val connection = open(url)
        try {
            val buffer = ByteArray(8 * 1024)
            val builder = StringBuilder()
            connection.inputStream.use { input ->
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_MANIFEST_BYTES) throw UpdateException("更新清单过大")
                    builder.append(String(buffer, 0, read, Charsets.UTF_8))
                }
            }
            return builder.toString()
        } finally {
            connection.disconnect()
        }
    }
}
