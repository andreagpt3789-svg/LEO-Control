// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.leo

import android.content.Context
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class LeoVidaaClient(private val context: Context) {
    companion object {
        private const val PREFS = "leo_direct_devices"
        private const val KEY_TV_IP = "tv_ip"
        private const val KEY_UUID = "vidaa_uuid"
        private const val KEY_CLIENT_ID = "vidaa_client_id"
        private const val KEY_USERNAME = "vidaa_username"
        private const val KEY_ACCESS = "vidaa_access"
        private const val KEY_REFRESH = "vidaa_refresh"
        private const val DEFAULT_TV_IP = "192.168.31.170"
        private const val PORT = 36669

        private const val OFFICIAL_VIDAA_PACKAGE = "com.universal.remote.multi"
        private const val P12_PASSWORD = "186e990688070325a1c4b0ce275d2388"

        private const val PATTERN = "38D65DC30F45109A369A86FCE866A85B"
        private const val SUFFIX_MODERN = "h!i@s#$v%i^d&a*a"
        private const val SUFFIX_LEGACY = "h*i&s%e!r^v0i1c9"
        private const val XOR_CONST = 0x569814772b03a968L
    }

    enum class AuthMode { MODERN, MIDDLE, LEGACY, STATIC }

    data class PairingResult(val ok: Boolean, val message: String)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var client: MqttClient? = null
    private var currentClientId: String = ""
    private var currentUsername: String = ""
    private var pendingPairCallback: ((PairingResult) -> Unit)? = null

    fun savedIp(): String = prefs.getString(KEY_TV_IP, DEFAULT_TV_IP).orEmpty()

    fun setIp(ip: String) {
        prefs.edit().putString(KEY_TV_IP, ip.trim()).apply()
        disconnect()
    }

    fun isPaired(): Boolean =
        prefs.getString(KEY_ACCESS, "").orEmpty().isNotBlank() &&
            prefs.getString(KEY_CLIENT_ID, "").orEmpty().isNotBlank() &&
            prefs.getString(KEY_USERNAME, "").orEmpty().isNotBlank()

    fun isReachable(): Boolean {
        val ip = savedIp()
        return ip.isNotBlank() && runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, PORT), 450)
                true
            }
        }.getOrDefault(false)
    }

    fun hasOfficialVidaaCertificateSource(): Boolean =
        locateOfficialP12Bytes() != null || File(context.filesDir, "vidaa/client.p12").exists()

    fun sendKey(key: String): Result<Unit> = runCatching {
        ensureConnected()
        publish("/remoteapp/tv/remote_service/" + currentClientId + "/actions/sendkey", key)
    }

    fun setSource(sourceId: String): Result<Unit> = runCatching {
        ensureConnected()
        publish(
            "/remoteapp/tv/ui_service/" + currentClientId + "/actions/changesource",
            JSONObject().put("sourceid", sourceId).toString()
        )
    }

    fun launchApp(name: String): Result<Unit> = runCatching {
        ensureConnected()
        val payload = when (name.lowercase(Locale.ROOT)) {
            "netflix" -> JSONObject()
                .put("name", "Netflix").put("urlType", 37).put("storeType", 0).put("url", "netflix")
            "youtube" -> JSONObject()
                .put("name", "YouTube").put("urlType", 37).put("storeType", 0).put("url", "youtube")
            "prime", "amazon" -> JSONObject()
                .put("name", "Amazon").put("urlType", 37).put("storeType", 0).put("url", "amazon")
            "disney", "disney+" -> JSONObject()
                .put("name", "Disney+").put("urlType", 37).put("storeType", 0).put("url", "disneyplus")
            else -> error("App VIDAA non supportata")
        }
        publish("/remoteapp/tv/ui_service/" + currentClientId + "/actions/launchapp", payload.toString())
    }

    fun startPairing(callback: (PairingResult) -> Unit) {
        Thread {
            val result = runCatching {
                disconnect()
                val connected = connectForPairing()
                if (!connected) {
                    error(
                        if (hasOfficialVidaaCertificateSource()) {
                            "Connessione VIDAA non riuscita. Verifica che TV e telefono siano sulla stessa rete."
                        } else {
                            "Per il collegamento diretto installa una volta l'app ufficiale VIDAA Smart TV. LEO userà localmente il certificato dell'app, senza passare dal PC."
                        }
                    )
                }
                subscribePairingTopics()
                pendingPairCallback = callback
                val payload = JSONObject()
                    .put("app_version", 2)
                    .put("connect_result", 0)
                    .put("device_type", "Mobile App")
                    .toString()
                publish(
                    "/remoteapp/tv/ui_service/" + currentClientId + "/actions/vidaa_app_connect",
                    payload
                )
                PairingResult(true, "Guarda la TV: inserisci in LEO il PIN mostrato sullo schermo.")
            }.getOrElse { PairingResult(false, it.message ?: "Pairing VIDAA non riuscito") }
            callback(result)
        }.start()
    }

    fun submitPin(pin: String, callback: (PairingResult) -> Unit) {
        Thread {
            val result = runCatching {
                val c = client
                if (c == null || !c.isConnected) error("Connessione VIDAA scaduta. Riavvia il pairing.")
                pendingPairCallback = callback
                val value = pin.trim().toIntOrNull() ?: error("PIN non valido")
                val payload = JSONObject().put("authNum", value).toString()
                publish(
                    "/remoteapp/tv/ui_service/" + currentClientId + "/actions/authenticationcode",
                    payload
                )
                PairingResult(true, "PIN inviato. Attendo conferma dalla TV…")
            }.getOrElse { PairingResult(false, it.message ?: "PIN non inviato") }
            callback(result)
        }.start()
    }

    private fun ensureConnected() {
        val existing = client
        if (existing != null && existing.isConnected) return

        val savedClientId = prefs.getString(KEY_CLIENT_ID, "").orEmpty()
        val savedUsername = prefs.getString(KEY_USERNAME, "").orEmpty()
        val access = prefs.getString(KEY_ACCESS, "").orEmpty()
        if (savedClientId.isBlank() || savedUsername.isBlank() || access.isBlank()) {
            error("Hisense non ancora associata direttamente a LEO")
        }

        connect(savedClientId, savedUsername, access).getOrThrow()
    }

    private fun connectForPairing(): Boolean {
        val uuid = controllerUuid()
        val modes = listOf(AuthMode.MODERN, AuthMode.MIDDLE, AuthMode.LEGACY, AuthMode.STATIC)
        for (mode in modes) {
            val creds = credentials(uuid, mode)
            val attempt = connect(creds.first, creds.second, creds.third)
            if (attempt.isSuccess) return true
        }
        return false
    }

    private fun connect(clientId: String, username: String, passwordValue: String): Result<Unit> =
        runCatching {
            disconnect()
            val mqtt = MqttClient(
                "ssl://" + savedIp() + ":" + PORT,
                clientId,
                MemoryPersistence()
            )
            mqtt.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {}

                override fun messageArrived(topic: String, message: MqttMessage) {
                    handleMessage(topic, message.payload.toString(Charsets.UTF_8))
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            val options = MqttConnectOptions().apply {
                userName = username
                password = passwordValue.toCharArray()
                isCleanSession = true
                connectionTimeout = 5
                keepAliveInterval = 35
                socketFactory = vidaaSslSocketFactory()
                isAutomaticReconnect = false
            }
            mqtt.connect(options)
            client = mqtt
            currentClientId = clientId
            currentUsername = username
            subscribePairingTopics()
        }

    private fun subscribePairingTopics() {
        val c = client ?: return
        if (!c.isConnected || currentClientId.isBlank()) return
        val topics = arrayOf(
            "/remoteapp/mobile/" + currentClientId + "/ui_service/data/authentication",
            "/remoteapp/mobile/" + currentClientId + "/ui_service/data/authenticationcode",
            "/remoteapp/mobile/" + currentClientId + "/platform_service/data/tokenissuance"
        )
        topics.forEach { runCatching { c.subscribe(it, 0) } }
    }

    private fun handleMessage(topic: String, body: String) {
        val json = runCatching { JSONObject(body) }.getOrNull()

        if (topic.contains("authenticationcode") || topic.endsWith("/authentication")) {
            val accepted = json?.optInt("result", 0) == 1
            if (accepted) {
                runCatching {
                    publish(
                        "/remoteapp/tv/platform_service/" + currentClientId + "/data/gettoken",
                        JSONObject().put("refreshtoken", "").toString()
                    )
                    publish(
                        "/remoteapp/tv/ui_service/" + currentClientId + "/actions/authenticationcodeclose",
                        ""
                    )
                }
            }
        }

        if (topic.contains("tokenissuance") && json != null) {
            val access = json.optString("accesstoken")
            val refresh = json.optString("refreshtoken")
            if (access.isNotBlank()) {
                prefs.edit()
                    .putString(KEY_CLIENT_ID, currentClientId)
                    .putString(KEY_USERNAME, currentUsername)
                    .putString(KEY_ACCESS, access)
                    .putString(KEY_REFRESH, refresh)
                    .apply()
                pendingPairCallback?.invoke(
                    PairingResult(true, "Hisense associata direttamente a LEO Control.")
                )
                pendingPairCallback = null
            }
        }
    }

    private fun publish(topic: String, value: String) {
        val c = client ?: error("VIDAA non connessa")
        if (!c.isConnected) error("VIDAA non connessa")
        c.publish(topic, MqttMessage(value.toByteArray(Charsets.UTF_8)).apply {
            qos = 0
            isRetained = false
        })
    }

    private fun credentials(uuid: String, mode: AuthMode): Triple<String, String, String> {
        if (mode == AuthMode.STATIC) {
            val flat = uuid.replace(":", "").replace("-", "").uppercase(Locale.ROOT)
            return Triple(flat + "$" + "vidaa_common", "hisenseservice", "multimqttservice")
        }

        val now = System.currentTimeMillis() / 1000L
        val race = md5(PATTERN + "$" + uuid).take(6)
        val clientId = uuid + "$" + "his" + "$" + race + "_vidaacommon_001"
        val username = when (mode) {
            AuthMode.LEGACY -> "his" + "$" + now
            else -> "his" + "$" + (now xor XOR_CONST)
        }
        val suffix = if (mode == AuthMode.MODERN) SUFFIX_MODERN else SUFFIX_LEGACY
        val remainder = now.toString().filter { it.isDigit() }.sumOf { it.digitToInt() } % 10
        val valueHash = md5("his" + remainder + suffix).take(6)
        val password = md5(now.toString() + "$" + valueHash)
        return Triple(clientId, username, password)
    }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }

    private fun controllerUuid(): String {
        val existing = prefs.getString(KEY_UUID, "").orEmpty()
        if (existing.isNotBlank()) return existing

        val raw = UUID.randomUUID().toString().replace("-", "").take(12)
        val bytes = raw.chunked(2).toMutableList()
        val first = bytes.first().toInt(16)
        bytes[0] = "%02x".format((first or 0x02) and 0xFE)
        val value = bytes.joinToString(":")
        prefs.edit().putString(KEY_UUID, value).apply()
        return value
    }

    private fun vidaaSslSocketFactory(): SSLSocketFactory {
        val keyManagers = loadClientKeyManagers()
        val trustAll = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
        )
        return SSLContext.getInstance("TLS").apply {
            init(keyManagers, trustAll, SecureRandom())
        }.socketFactory
    }

    private fun loadClientKeyManagers(): Array<javax.net.ssl.KeyManager>? {
        val p12 = persistedP12Bytes() ?: locateOfficialP12Bytes()?.also { persistP12(it) } ?: return null
        return runCatching {
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(ByteArrayInputStream(p12), P12_PASSWORD.toCharArray())
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, P12_PASSWORD.toCharArray())
            kmf.keyManagers
        }.getOrNull()
    }

    private fun persistedP12Bytes(): ByteArray? {
        val file = File(context.filesDir, "vidaa/client.p12")
        return if (file.exists()) runCatching { file.readBytes() }.getOrNull() else null
    }

    private fun persistP12(bytes: ByteArray) {
        runCatching {
            val file = File(context.filesDir, "vidaa/client.p12")
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
    }

    private fun locateOfficialP12Bytes(): ByteArray? {
        return runCatching {
            val info = context.packageManager.getApplicationInfo(OFFICIAL_VIDAA_PACKAGE, 0)
            ZipFile(info.sourceDir).use { zip ->
                val entry = zip.entries().asSequence().firstOrNull {
                    val n = it.name.lowercase(Locale.ROOT)
                    n.endsWith(".p12") && (
                        n.contains("client_mobile_android") ||
                            n.endsWith("/el.p12") ||
                            n.endsWith("/3r.p12") ||
                            n.contains("rcamobile")
                        )
                } ?: return@use null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        }.getOrNull()
    }

    fun disconnect() {
        runCatching {
            client?.let {
                if (it.isConnected) it.disconnect()
                it.close()
            }
        }
        client = null
        currentClientId = ""
        currentUsername = ""
    }
}
