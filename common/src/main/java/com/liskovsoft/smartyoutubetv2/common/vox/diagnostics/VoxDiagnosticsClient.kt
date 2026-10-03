package com.liskovsoft.smartyoutubetv2.common.vox.diagnostics

import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.smartyoutubetv2.common.utils.Utils
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Изолированный HTTPS-клиент для безопасной анонимной отправки диагностических отчётов разработчикам.
 * Не отправляет никаких пользовательских учётных данных или заголовков авторизации.
 */
class VoxDiagnosticsClient(
    private val endpointUrl: String = DEFAULT_ENDPOINT_URL
) {
    companion object {
        private const val TAG = "VoxDiagnosticsClient"
        const val DEFAULT_ENDPOINT_URL = "https://diagnostics.smarttube.app/v1/report"
        private const val TIMEOUT_MS = 10_000
        private val EXECUTOR = Executors.newSingleThreadExecutor()

        @Volatile
        private var sInstance: VoxDiagnosticsClient? = null

        @JvmStatic
        fun instance(): VoxDiagnosticsClient {
            return sInstance ?: synchronized(this) {
                sInstance ?: VoxDiagnosticsClient().also { sInstance = it }
            }
        }
    }

    sealed class Result {
        data class Success(val reportId: String) : Result()
        data class Error(val message: String, val cause: Throwable? = null) : Result()
    }

    interface Callback {
        fun onSuccess(reportId: String)
        fun onError(errorMessage: String)
    }

    fun submitReportAsync(report: VoxDiagnosticReport, callback: Callback) {
        EXECUTOR.execute {
            val result = submitReport(report)
            Utils.post {
                when (result) {
                    is Result.Success -> callback.onSuccess(result.reportId)
                    is Result.Error -> callback.onError(result.message)
                }
            }
        }
    }

    fun submitReport(report: VoxDiagnosticReport): Result {
        return try {
            val rawJson = report.toJson()
            val sanitizedJson = VoxDiagnosticSanitizer.sanitizeJson(rawJson)

            if (!VoxDiagnosticSanitizer.isSafePayload(sanitizedJson)) {
                return Result.Error("Diagnostic report failed privacy security check")
            }

            val url = URL(endpointUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "SmartTube-VOX-Diagnostics/1.0")
            }

            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(sanitizedJson)
                writer.flush()
            }

            val responseCode = connection.responseCode
            val responseStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

            val responseBody = BufferedReader(InputStreamReader(responseStream, StandardCharsets.UTF_8)).use { reader ->
                reader.readText()
            }

            if (responseCode in 200..299) {
                val respJson = JSONObject(responseBody)
                val reportId = respJson.optString("reportId", "VOX-${System.currentTimeMillis() % 1000000}")
                Log.i(TAG, "Diagnostic report submitted successfully: $reportId")
                Result.Success(reportId)
            } else {
                Log.e(TAG, "Diagnostics service returned error $responseCode: $responseBody")
                Result.Error("HTTP error $responseCode: $responseBody")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to submit diagnostic report", e)
            Result.Error(e.message ?: "Connection error", e)
        }
    }
}
