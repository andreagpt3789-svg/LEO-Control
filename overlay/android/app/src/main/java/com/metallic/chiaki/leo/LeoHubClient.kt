// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.leo

import android.content.Context
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LeoHubClient(private val context: Context) {
    companion object {
        private const val PREFS = "leo_hub_native"
        private const val KEY_HUB_URL = "hub_url"
        private const val KEY_COOKIE = "session_cookie"
        private const val DEFAULT_HUB_URL = "http://192.168.31.75:8765"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    fun hubUrl(): String {
        var v = prefs.getString(KEY_HUB_URL, DEFAULT_HUB_URL)?.trim().orEmpty()
        if (v.isBlank()) v = DEFAULT_HUB_URL
        if (!v.startsWith("http://") && !v.startsWith("https://")) v = "http://$v"
        return v.trimEnd('/')
    }

    fun setHubUrl(value: String) {
        var v = value.trim()
        if (!v.startsWith("http://") && !v.startsWith("https://")) v = "http://$v"
        prefs.edit().putString(KEY_HUB_URL, v.trimEnd('/')).apply()
    }

    fun savedCookie(): String = prefs.getString(KEY_COOKIE, "").orEmpty()

    private fun request(path: String): Request.Builder {
        val b = Request.Builder().url(hubUrl() + path)
        val cookie = savedCookie()
        if (cookie.isNotBlank()) b.header("Cookie", cookie)
        return b
    }

    fun getStatus(callback: (Boolean, Boolean, String) -> Unit) {
        client.newCall(request("/api/status").get().build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                callback(false, false, e.message ?: "Hub non raggiungibile")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    val paired = runCatching { JSONObject(text).optBoolean("paired", false) }.getOrDefault(false)
                    callback(it.isSuccessful, paired, text)
                }
            }
        })
    }

    fun pair(code: String, callback: (Boolean, String) -> Unit) {
        val body = JSONObject().put("code", code).toString().toRequestBody(JSON)
        client.newCall(request("/api/pair").post(body).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                callback(false, e.message ?: "Hub non raggiungibile")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    val setCookie = it.headers("Set-Cookie").firstOrNull { value ->
                        value.startsWith("leo_control_session=")
                    }
                    if (it.isSuccessful && setCookie != null) {
                        val token = setCookie.substringBefore(';')
                        prefs.edit().putString(KEY_COOKIE, token).apply()
                    }
                    val msg = runCatching {
                        JSONObject(text).optString("error").ifBlank {
                            if (it.isSuccessful) "Telefono associato al LEO Hub" else "Associazione non riuscita"
                        }
                    }.getOrDefault(text.ifBlank { "Associazione non riuscita" })
                    callback(it.isSuccessful, msg)
                }
            }
        })
    }

    fun command(deviceId: String, command: String, callback: (Boolean, String, Int) -> Unit) {
        postJson("/api/device/$deviceId/command", JSONObject().put("command", command), callback)
    }

    fun text(deviceId: String, value: String, callback: (Boolean, String, Int) -> Unit) {
        postJson("/api/device/$deviceId/text", JSONObject().put("text", value), callback)
    }

    fun runScene(sceneId: String, callback: (Boolean, JSONObject?, String, Int) -> Unit) {
        val body = "{}".toRequestBody(JSON)
        client.newCall(request("/api/scene/$sceneId/run").post(body).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                callback(false, null, e.message ?: "Hub non raggiungibile", 0)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    val msg = json?.optString("message")?.ifBlank { text } ?: text
                    callback(it.isSuccessful, json, msg, it.code)
                }
            }
        })
    }

    private fun postJson(path: String, json: JSONObject, callback: (Boolean, String, Int) -> Unit) {
        val body = json.toString().toRequestBody(JSON)
        client.newCall(request(path).post(body).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                callback(false, e.message ?: "Hub non raggiungibile", 0)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    val msg = runCatching {
                        JSONObject(text).optString("message").ifBlank {
                            JSONObject(text).optString("error").ifBlank { text }
                        }
                    }.getOrDefault(text)
                    callback(it.isSuccessful, msg, it.code)
                }
            }
        })
    }

    fun webSocket(path: String, listener: WebSocketListener): WebSocket {
        val wsBase = hubUrl()
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val b = Request.Builder().url(wsBase + path)
        val cookie = savedCookie()
        if (cookie.isNotBlank()) b.header("Cookie", cookie)
        return client.newWebSocket(b.build(), listener)
    }
}
