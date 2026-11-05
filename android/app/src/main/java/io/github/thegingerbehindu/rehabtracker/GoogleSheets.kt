package io.github.thegingerbehindu.rehabtracker

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
private const val API_ROOT = "https://sheets.googleapis.com/v4/spreadsheets"
private const val TEST_TAB = "Android test"
private val SPREADSHEET_URL = Regex("/spreadsheets/d/([A-Za-z0-9_-]+)")
private val SPREADSHEET_ID = Regex("^[A-Za-z0-9_-]{20,}$")

/** Accepts a spreadsheet link or a bare ID, and returns the ID or null. */
fun parseSpreadsheetId(input: String): String? {
    val value = input.trim()
    return SPREADSHEET_URL.find(value)?.groupValues?.get(1) ?: value.takeIf(SPREADSHEET_ID::matches)
}

object GoogleAccess {
    /** If the result has a resolution, the user must be shown Google's consent screen. */
    suspend fun authorize(context: Context): AuthorizationResult {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()
        return Identity.getAuthorizationClient(context).authorize(request).await()
    }
}

class SheetsHttpException(val status: Int, message: String) : IOException("HTTP $status: $message")

class SheetsClient(private val token: String) {
    suspend fun readTitle(spreadsheetId: String): String =
        call("GET", "$API_ROOT/$spreadsheetId?fields=properties.title")
            .getJSONObject("properties").getString("title")

    /** The fallback route: a file the app creates itself is always covered by drive.file. */
    suspend fun createSpreadsheet(title: String): String {
        val body = JSONObject().put("properties", JSONObject().put("title", title))
        return call("POST", API_ROOT, body).getString("spreadsheetId")
    }

    /** Appends one row to a dedicated test tab and reads it back. Returns whether it matched. */
    suspend fun writeAndVerify(spreadsheetId: String): Boolean {
        ensureTestTab(spreadsheetId)
        val stamp = "Android test ${Instant.now()}"
        val range = URLEncoder.encode("'$TEST_TAB'!A:A", "UTF-8")
        val row = JSONObject().put("values", JSONArray().put(JSONArray().put(stamp)))
        call("POST", "$API_ROOT/$spreadsheetId/values/$range:append?valueInputOption=RAW", row)
        val values = call("GET", "$API_ROOT/$spreadsheetId/values/$range").optJSONArray("values") ?: return false
        return values.getJSONArray(values.length() - 1).getString(0) == stamp
    }

    private suspend fun ensureTestTab(spreadsheetId: String) {
        val sheets = call("GET", "$API_ROOT/$spreadsheetId?fields=sheets.properties.title").getJSONArray("sheets")
        for (index in 0 until sheets.length()) {
            if (sheets.getJSONObject(index).getJSONObject("properties").getString("title") == TEST_TAB) return
        }
        val addSheet = JSONObject().put("addSheet", JSONObject().put("properties", JSONObject().put("title", TEST_TAB)))
        call("POST", "$API_ROOT/$spreadsheetId:batchUpdate", JSONObject().put("requests", JSONArray().put(addSheet)))
    }

    private suspend fun call(method: String, url: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val message = runCatching { JSONObject(detail).getJSONObject("error").getString("message") }.getOrDefault("request failed")
                throw SheetsHttpException(status, message)
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}
