package com.v2ray.ang.dash

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Один сервер в облачном списке: {"name": "...", "country": "DE", "key": "vless://..."} */
data class DashServer(
    val name: String,
    val country: String,
    val key: String,
) {
    /** Имя в списке профилей: флаг (если country — код из 2 букв) + название. */
    fun displayName(): String {
        val c = country.trim()
        val flag = if (c.length == 2 && c.all { it.isLetter() && it.code < 128 }) {
            c.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
        } else ""
        val base = name.trim().ifEmpty { "Server" }
        return if (flag.isEmpty()) base else "$flag $base"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("country", country)
        .put("key", key)
}

/** Работа с kvdb.io: GET — скачать список, POST — перезаписать. Вызывать только из фонового потока. */
object DashApi {

    @Throws(IOException::class)
    fun fetch(): List<DashServer> {
        val conn = URL(DashConfig.SERVERS_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = DashConfig.TIMEOUT_MS
            conn.readTimeout = DashConfig.TIMEOUT_MS
            conn.setRequestProperty("Cache-Control", "no-cache")
            val code = conn.responseCode
            if (code == 404) return emptyList()
            if (code !in 200..299) throw IOException("kvdb: HTTP $code")

            val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            if (body.isEmpty()) return emptyList()

            val arr = if (body.startsWith("[")) {
                JSONArray(body)
            } else {
                JSONObject(body).optJSONArray("servers") ?: JSONArray()
            }
            val result = ArrayList<DashServer>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val key = o.optString("key").trim()
                if (key.isEmpty()) continue
                result += DashServer(o.optString("name"), o.optString("country"), key)
            }
            return result
        } finally {
            conn.disconnect()
        }
    }

    @Throws(IOException::class)
    fun push(servers: List<DashServer>) {
        val json = JSONArray().also { arr -> servers.forEach { arr.put(it.toJson()) } }.toString()
        val conn = URL(DashConfig.SERVERS_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = DashConfig.TIMEOUT_MS
            conn.readTimeout = DashConfig.TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("kvdb: HTTP $code")
        } finally {
            conn.disconnect()
        }
    }
}
