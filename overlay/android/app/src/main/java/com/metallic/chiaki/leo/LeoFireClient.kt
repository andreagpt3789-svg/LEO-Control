// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.leo

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class LeoFireClient(private val context: Context) {
    companion object {
        private const val PREFS = "leo_direct_devices"
        private const val KEY_FIRE_IP = "fire_ip"
        private const val ADB_PORT = 5555
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var dadb: Dadb? = null
    private var connectedIp: String? = null

    fun savedIp(): String = prefs.getString(KEY_FIRE_IP, "").orEmpty()

    fun setIp(ip: String) {
        prefs.edit().putString(KEY_FIRE_IP, ip.trim()).apply()
        close()
    }

    fun isReachable(): Boolean {
        val ip = savedIp()
        return ip.isNotBlank() && portOpen(ip, ADB_PORT, 350)
    }

    fun connectOrDiscover(): Result<String> {
        val saved = savedIp()
        if (saved.isNotBlank()) {
            val test = connect(saved)
            if (test.isSuccess) return test
        }

        val candidates = discoverPort5555()
        for (ip in candidates) {
            val result = connect(ip)
            if (result.isSuccess) {
                setIp(ip)
                val reconnect = connect(ip)
                if (reconnect.isSuccess) return reconnect
            } else {
                // Save a reachable ADB endpoint even if the Fire TV still needs
                // the one-time RSA confirmation on screen.
                if (portOpen(ip, ADB_PORT, 250)) {
                    prefs.edit().putString(KEY_FIRE_IP, ip).apply()
                    return Result.failure(
                        IllegalStateException(
                            "Fire TV trovata a $ip. Conferma 'Consenti debugging' sulla TV, poi riprova."
                        )
                    )
                }
            }
        }
        return Result.failure(
            IllegalStateException(
                "Fire TV non trovata. Verifica che Debug ADB sia attivo e che telefono e Fire TV siano sulla stessa rete."
            )
        )
    }

    @Synchronized
    private fun connect(ip: String): Result<String> {
        return runCatching {
            if (dadb != null && connectedIp == ip) return@runCatching ip
            close()
            val keyPair = keyPair()
            val client = Dadb.create(
                host = ip,
                port = ADB_PORT,
                keyPair = keyPair,
                connectTimeout = 2500,
                socketTimeout = 4500,
                keepAlive = true
            )
            val probe = client.shell("getprop ro.product.model").allOutput.trim()
            if (probe.isBlank()) {
                client.close()
                error("Endpoint ADB non riconosciuto")
            }
            dadb = client
            connectedIp = ip
            prefs.edit().putString(KEY_FIRE_IP, ip).apply()
            ip
        }
    }

    fun sendKey(keyCode: Int): Result<Unit> =
        withClient { client ->
            val response = client.shell("input keyevent $keyCode")
            if (response.exitCode != 0) error(response.allOutput.ifBlank { "Comando ADB non riuscito" })
        }

    fun sendText(value: String): Result<Unit> =
        withClient { client ->
            val escaped = value
                .replace("%", "\\%")
                .replace(" ", "%s")
                .replace("&", "\\&")
                .replace("|", "\\|")
                .replace("<", "\\<")
                .replace(">", "\\>")
            val response = client.shell("input text '$escaped'")
            if (response.exitCode != 0) error(response.allOutput.ifBlank { "Invio testo non riuscito" })
        }

    fun openPackage(packageName: String): Result<Unit> =
        withClient { client ->
            val response = client.shell("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
            if (response.exitCode != 0) error(response.allOutput.ifBlank { "Avvio app non riuscito" })
        }

    private fun withClient(block: (Dadb) -> Unit): Result<Unit> {
        return runCatching {
            val ip = savedIp()
            if (ip.isBlank()) error("Fire TV non configurata")
            if (dadb == null || connectedIp != ip) {
                connect(ip).getOrThrow()
            }
            val current = dadb ?: error("Connessione Fire TV non disponibile")
            try {
                block(current)
            } catch (first: Throwable) {
                close()
                connect(ip).getOrThrow()
                block(dadb ?: error("Connessione Fire TV non disponibile"))
            }
        }
    }

    private fun keyPair(): AdbKeyPair {
        val dir = File(context.filesDir, "fire_adb")
        val privateKey = File(dir, "adbkey")
        val publicKey = File(dir, "adbkey.pub")
        if (!privateKey.exists() || !publicKey.exists()) {
            AdbKeyPair.generate(privateKey, publicKey)
        }
        return AdbKeyPair.read(privateKey, publicKey)
    }

    private fun discoverPort5555(): List<String> {
        val base = localSubnetBase() ?: return emptyList()
        val pool = Executors.newFixedThreadPool(36)
        return try {
            val tasks = (1..254).map { host ->
                Callable {
                    val ip = "$base.$host"
                    if (portOpen(ip, ADB_PORT, 150)) ip else null
                }
            }
            pool.invokeAll(tasks)
                .mapNotNull { future -> runCatching { future.get() }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun localSubnetBase(): String? {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (address in Collections.list(iface.inetAddresses)) {
                if (address is Inet4Address && address.isSiteLocalAddress) {
                    val parts = address.hostAddress?.split(".") ?: continue
                    if (parts.size == 4) return parts.take(3).joinToString(".")
                }
            }
        }
        return null
    }

    private fun portOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }

    @Synchronized
    fun close() {
        runCatching { dadb?.close() }
        dadb = null
        connectedIp = null
    }
}
