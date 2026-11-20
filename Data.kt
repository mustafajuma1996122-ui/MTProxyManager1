package com.example.mtproxy

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

data class Proxy(
    val server: String,
    val port: Int,
    val secret: String,
    val ping: Int? = null,
    val failed: Boolean = false
) {
    val key: String get() = "$server:$port"
    val link: String get() = "tg://proxy?server=$server&port=$port&secret=$secret"
    val webLink: String get() = "https://t.me/proxy?server=$server&port=$port&secret=$secret"
}

object Net {
    // مصادر عامة — يمكنك تعديل القائمة أو إضافة مصادر أخرى (نص فيه روابط tg://proxy أو JSON)
    val sources = listOf(
        "https://raw.githubusercontent.com/hookzof/socks5_list/master/tg/mtproto.json",
        "https://raw.githubusercontent.com/Grim1313/mtproto-for-telegram/master/all_proxies.txt"
    )

    private val linkRe =
        Regex("""server=([^&\s"'<]+)&(?:amp;)?port=(\d+)&(?:amp;)?secret=([A-Za-z0-9_\-=%]+)""")
    private val plainRe = Regex("""^([^:\s]+):(\d+):(\S+)$""")

    fun parse(text: String): List<Proxy> {
        val out = mutableListOf<Proxy>()
        linkRe.findAll(text).forEach { m ->
            val port = m.groupValues[2].toIntOrNull() ?: return@forEach
            out += Proxy(m.groupValues[1], port, m.groupValues[3])
        }
        if (text.trimStart().startsWith("[")) {
            try {
                val a = JSONArray(text)
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val h = o.optString("host")
                    val p = o.optInt("port")
                    val s = o.optString("secret")
                    if (h.isNotEmpty() && p > 0 && s.isNotEmpty()) out += Proxy(h, p, s)
                }
            } catch (e: Exception) { /* ignore */ }
        }
        return out.distinctBy { it.key }
    }

    fun parseManual(text: String): List<Proxy> {
        val direct = parse(text)
        if (direct.isNotEmpty()) return direct
        return text.lines().mapNotNull { line ->
            plainRe.find(line.trim())?.let {
                val p = it.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                Proxy(it.groupValues[1], p, it.groupValues[3])
            }
        }
    }

    private fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        return try {
            c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    suspend fun fetchAll(): List<Proxy> = withContext(Dispatchers.IO) {
        sources.map { url ->
            async { try { parse(download(url)) } catch (e: Exception) { emptyList() } }
        }.awaitAll().flatten().distinctBy { it.key }
    }

    /** يقيس زمن اتصال TCP بالخادم (ms). */
    suspend fun ping(p: Proxy): Proxy = withContext(Dispatchers.IO) {
        val t = System.nanoTime()
        try {
            Socket().use { it.connect(InetSocketAddress(p.server, p.port), 3000) }
            p.copy(ping = ((System.nanoTime() - t) / 1_000_000).toInt(), failed = false)
        } catch (e: Exception) {
            p.copy(ping = null, failed = true)
        }
    }
}

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("proxies", Context.MODE_PRIVATE)

    fun load(): List<Proxy> = try {
        val a = JSONArray(sp.getString("list", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Proxy(
                o.getString("s"), o.getInt("p"), o.getString("k"),
                if (o.has("ping")) o.getInt("ping") else null,
                o.optBoolean("failed")
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(list: List<Proxy>) {
        val a = JSONArray()
        list.forEach {
            val o = JSONObject().put("s", it.server).put("p", it.port).put("k", it.secret)
            it.ping?.let { v -> o.put("ping", v) }
            o.put("failed", it.failed)
            a.put(o)
        }
        sp.edit().putString("list", a.toString()).apply()
    }
}
