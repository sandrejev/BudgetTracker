package com.example.budgettracker.receipt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HTTP client for calling an LLM API to generate processor configs.
 * Default: Google Gemini 2.5 Flash free-tier endpoint.
 */
object LlmClient {

    private const val DEFAULT_GEMINI_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent"

    /**
     * Sends [prompt] to the configured LLM and returns the raw text response.
     *
     * @param apiKey  API key. For Gemini this is passed as a query parameter.
     * @param apiUrl  Base URL. Defaults to Gemini Flash.
     */
    suspend fun generate(
        prompt: String,
        apiKey: String,
        apiUrl: String = DEFAULT_GEMINI_URL
    ): String = withContext(Dispatchers.IO) {
        val url = if (apiUrl.contains("generativelanguage.googleapis.com"))
            "$apiUrl?key=$apiKey"
        else apiUrl

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
        }

        // Build request body
        val body = if (url.contains("generativelanguage.googleapis.com")) {
            // Gemini format
            JSONObject().put("contents", org.json.JSONArray().put(
                JSONObject().put("parts", org.json.JSONArray().put(
                    JSONObject().put("text", prompt)
                ))
            )).toString()
        } else {
            // OpenAI-compatible format
            JSONObject()
                .put("model", "gpt-4o-mini")
                .put("messages", org.json.JSONArray().put(
                    JSONObject().put("role", "user").put("content", prompt)
                ))
                .toString()
        }

        OutputStreamWriter(connection.outputStream).use { it.write(body) }

        val responseCode = connection.responseCode
        val responseText = if (responseCode == 200) {
            connection.inputStream.bufferedReader().readText()
        } else {
            val err = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            error("LLM request failed ($responseCode): $err")
        }

        // Extract text from response
        val json = JSONObject(responseText)
        return@withContext when {
            json.has("candidates") -> {
                // Gemini format
                json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
            }
            json.has("choices") -> {
                // OpenAI format
                json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            }
            else -> error("Unrecognised LLM response format")
        }
    }

    /** Extract the first JSON object from an LLM response string. */
    fun extractJsonFromResponse(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }
}
