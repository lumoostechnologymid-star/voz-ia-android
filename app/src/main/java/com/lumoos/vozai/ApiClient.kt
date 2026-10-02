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

    fun createVoice(name: String, sample: File, consent: Boolean): String {
        val boundary = "----VozIA${UUID.randomUUID()}"
        val conn = (URL("${baseUrl.trimEnd('/')}/voice-profile").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 30_000; readTimeout = 120_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
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
        val body = readBody(conn); requireOk(conn, body)
        return JSONObject(body).getString("voice_id")
    }

    fun chat(message: String, history: List<Message>): String {
        val arr = JSONArray()
        history.takeLast(12).forEach { arr.put(JSONObject().put("role", it.role).put("text", it.text)) }
        val payload = JSONObject().put("message", message).put("history", arr)
        val conn = jsonConnection("${baseUrl.trimEnd('/')}/chat")
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        val body = readBody(conn); requireOk(conn, body)
        return JSONObject(body).getString("reply")
    }

    fun speak(text: String, voiceId: String, target: File): File {
        val payload = JSONObject().put("text", text).put("voice_id", voiceId)
        val conn = jsonConnection("${baseUrl.trimEnd('/')}/speak", 120_000)
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        if (conn.responseCode !in 200..299) throw IllegalStateException(readBody(conn))
        BufferedInputStream(conn.inputStream).use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    private fun jsonConnection(url: String, timeout: Int = 60_000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = timeout
            setRequestProperty("Content-Type", "application/json")
        }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.readText().orEmpty()
    }
    private fun requireOk(conn: HttpURLConnection, body: String) {
        if (conn.responseCode !in 200..299) throw IllegalStateException("Error ${conn.responseCode}: $body")
    }
}
