package com.lumoos.vozai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class ApiClient(private val baseUrl: String) {
    data class Message(val role: String, val text: String)
    data class Health(val ok: Boolean, val chatConfigured: Boolean, val voiceConfigured: Boolean)

    companion object {
        // Clave anon/pública de Supabase. No es una clave secreta.
        private const val SUPABASE_ANON_KEY =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InBvd2h5anNwZGZzdnZodGZ0dHRyIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTAxMjA0ODUsImV4cCI6MjEwNTY5NjQ4NX0.dh7xIVRXeaXyAgUHtsknlzSGodeieN43CuhG3v4HMao"
    }

    fun health(): Health {
        val conn = (URL("${baseUrl.trimEnd('/')}/health").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            applyAuth(this)
        }
        val body = readBody(conn)
        requireOk(conn, body)
        val json = JSONObject(body)
        return Health(
            ok = json.optBoolean("ok", false),
            chatConfigured = json.optBoolean("chat_configured", false),
            voiceConfigured = json.optBoolean("voice_configured", false)
        )
    }

    fun createVoice(name: String, sample: File, consent: Boolean): String {
        val boundary = "----VozIA${UUID.randomUUID()}"
        val conn = (URL("${baseUrl.trimEnd('/')}/voice-profile").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            applyAuth(this)
        }
        BufferedOutputStream(conn.outputStream).use { out ->
            fun textPart(key: String, value: String) {
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"$key\"\r\n\r\n".toByteArray())
                out.write("$value\r\n".toByteArray())
            }
            textPart("name", name)
            textPart("consent", consent.toString())
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"file\"; filename=\"${sample.name}\"\r\n".toByteArray())
            out.write("Content-Type: audio/mp4\r\n\r\n".toByteArray())
            sample.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val body = readBody(conn)
        requireOk(conn, body)
        return JSONObject(body).getString("voice_id")
    }

    fun chat(message: String, history: List<Message>): String {
        val arr = JSONArray()
        history.takeLast(12).forEach { arr.put(JSONObject().put("role", it.role).put("text", it.text)) }
        val payload = JSONObject().put("message", message).put("history", arr)
        val conn = jsonConnection("${baseUrl.trimEnd('/')}/chat")
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        val body = readBody(conn)
        requireOk(conn, body)
        return JSONObject(body).getString("reply")
    }

    fun speak(text: String, voiceId: String, target: File): File {
        val payload = JSONObject().put("text", text).put("voice_id", voiceId)
        val conn = jsonConnection("${baseUrl.trimEnd('/')}/speak", 120_000)
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        val bodyCode = conn.responseCode
        if (bodyCode !in 200..299) throw IllegalStateException(readBody(conn))
        BufferedInputStream(conn.inputStream).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }

    private fun jsonConnection(url: String, timeout: Int = 60_000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = timeout
            setRequestProperty("Content-Type", "application/json")
            applyAuth(this)
        }

    private fun applyAuth(conn: HttpURLConnection) {
        conn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
        conn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.readText().orEmpty()
    }

    private fun requireOk(conn: HttpURLConnection, body: String) {
        if (conn.responseCode !in 200..299) {
            val detail = try { JSONObject(body).optString("detail", body) } catch (_: Exception) { body }
            throw IllegalStateException("Error ${conn.responseCode}: $detail")
        }
    }
}
