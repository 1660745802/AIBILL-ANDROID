package com.aibill.android.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.aibill.android.data.local.datastore.UserPreferences
import com.aibill.android.data.remote.interceptor.TokenManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExportHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences,
    private val tokenManager: TokenManager,
    private val okHttpClient: OkHttpClient,
) {

    suspend fun exportJson(): Result<Uri> = withContext(Dispatchers.IO) {
        exportFile(
            endpoint = "/api/export/json",
            fileName = "aibill_export_${System.currentTimeMillis()}.json",
            mimeType = "application/json"
        )
    }

    suspend fun exportCsv(): Result<Uri> = withContext(Dispatchers.IO) {
        exportFile(
            endpoint = "/api/export/csv",
            fileName = "aibill_export_${System.currentTimeMillis()}.csv",
            mimeType = "text/csv"
        )
    }

    private suspend fun exportFile(
        endpoint: String,
        fileName: String,
        mimeType: String
    ): Result<Uri> = runCatching {
        val serverUrl = userPreferences.serverUrl.firstOrNull()
            ?: throw IOException("未配置服务器地址")

        val token = tokenManager.getToken()
            ?: throw IOException("未登录，无法导出")

        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}$endpoint")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("导出失败: ${response.code}")
        }

        val body = response.body ?: throw IOException("响应体为空")
        saveToDownloads(fileName, mimeType, body.bytes())
    }

    private fun saveToDownloads(
        fileName: String,
        mimeType: String,
        data: ByteArray
    ): Uri {
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, contentValues)
            ?: throw IOException("无法创建 MediaStore 条目")

        resolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(data)
        } ?: throw IOException("无法打开输出流")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }

        return uri
    }
}
