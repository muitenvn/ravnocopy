package com.example.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.squareup.moshi.JsonReader
import okio.Buffer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class ProxyFeedSource(
    val id: String,
    val type: String, // "plain_ip_port" | "geonode_json"
    val protocol: String, // "http" | "socks5" | "auto"
    val urlTemplate: String
)

data class RemoteServerCatalogData(
    val schemaVersion: Int,
    val updatedAt: String,
    val bypassServers: List<ServerLocation>,
    val proxyServers: List<ServerLocation>,
    val proxySources: List<ProxyFeedSource>,
    val sourceLabel: String
)

/**
 * Tải & lưu đệm danh sách máy chủ tách biệt (Bypass riêng, Proxy riêng + nguồn quét Proxy)
 * từ file dữ liệu JSON tĩnh trên GitHub Pages / GitHub Raw của dự án Ravno.
 * Chỉ đọc dữ liệu cấu hình JSON thuần, tuyệt đối không tải hay thực thi mã.
 */
object RemoteServerCatalog {

    const val GITHUB_PAGES_CATALOG_URL = "https://muitenvn.github.io/Ravno-App/servers.json"
    const val GITHUB_RAW_CATALOG_URL = "https://raw.githubusercontent.com/muitenvn/Ravno-App/main/docs/servers.json"

    private const val PREFS_NAME = "cloudplay_servers"
    private const val KEY_REMOTE_CATALOG_JSON = "remote_server_catalog_json_v1"
    private const val KEY_REMOTE_CATALOG_SOURCE = "remote_server_catalog_source_v1"
    private const val KEY_REMOTE_CATALOG_SYNCED_AT = "remote_server_catalog_synced_at_v1"

    const val AUTO_SYNC_CATALOG_INTERVAL_MS = 6L * 60L * 60L * 1000L // 6 giờ

    val DEFAULT_PROXY_SOURCES: List<ProxyFeedSource> = listOf(
        ProxyFeedSource(
            id = "proxyscrape_http",
            type = "plain_ip_port",
            protocol = "http",
            urlTemplate = "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=2500&country={country}&ssl=yes&anonymity=all"
        ),
        ProxyFeedSource(
            id = "proxyscrape_socks5",
            type = "plain_ip_port",
            protocol = "socks5",
            urlTemplate = "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=socks5&timeout=2500&country={country}&ssl=all&anonymity=all"
        ),
        ProxyFeedSource(
            id = "geonode_json",
            type = "geonode_json",
            protocol = "auto",
            urlTemplate = "https://proxylist.geonode.com/api/proxy-list?limit=30&page=1&sort_by=lastChecked&sort_type=desc&country={country}&protocols=http%2Csocks5"
        )
    )

    suspend fun fetchFromGitHub(timeoutMs: Int = 3500): Pair<String, RemoteServerCatalogData>? =
        withContext(Dispatchers.IO) {
            val urls = listOf(
                "GitHub Pages" to GITHUB_PAGES_CATALOG_URL,
                "GitHub Raw" to GITHUB_RAW_CATALOG_URL
            )
            for ((label, urlStr) in urls) {
                try {
                    val raw = downloadText(urlStr, timeoutMs) ?: continue
                    val parsed = parseCatalogJson(raw, sourceLabel = label) ?: continue
                    if (parsed.bypassServers.isNotEmpty() || parsed.proxyServers.isNotEmpty() || parsed.proxySources.isNotEmpty()) {
                        return@withContext raw to parsed
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (_: Exception) {
                }
            }
            null
        }

    private fun downloadText(urlStr: String, timeoutMs: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = (timeoutMs + 800).coerceAtMost(5000)
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Ravno-Android/1.0")
            }
            if (conn.responseCode != 200) return null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(4096)
                var total = 0
                while (true) {
                    val read = reader.read(buf)
                    if (read <= 0) break
                    total += read
                    if (total > 512 * 1024) return null // Giới hạn an toàn 512KB
                    sb.append(buf, 0, read)
                }
                sb.toString()
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    fun parseCatalogJson(rawJson: String, sourceLabel: String = "GitHub"): RemoteServerCatalogData? {
        if (rawJson.isBlank()) return null
        return try {
            var schemaVersion = 1
            var updatedAt = ""
            var bypassServers = emptyList<ServerLocation>()
            var proxyServers = emptyList<ServerLocation>()
            var proxySources = emptyList<ProxyFeedSource>()

            JsonReader.of(Buffer().writeUtf8(rawJson)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "schemaVersion" -> {
                            if (reader.peek() == JsonReader.Token.NUMBER) {
                                schemaVersion = reader.nextInt()
                            } else {
                                reader.skipValue()
                            }
                        }
                        "updatedAt" -> {
                            updatedAt = if (reader.peek() == JsonReader.Token.STRING) reader.nextString() else {
                                reader.skipValue(); ""
                            }
                        }
                        "bypassServers" -> {
                            bypassServers = if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                                parseServerArray(reader, ServerKind.BYPASS)
                            } else {
                                reader.skipValue(); emptyList()
                            }
                        }
                        "proxyServers" -> {
                            proxyServers = if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                                parseServerArray(reader, ServerKind.PROXY)
                            } else {
                                reader.skipValue(); emptyList()
                            }
                        }
                        "proxySources" -> {
                            proxySources = if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                                parseProxySources(reader)
                            } else {
                                reader.skipValue(); emptyList()
                            }
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }

            val bypassKeySet = bypassServers.map { it.addressPortKey.lowercase(Locale.ROOT) }.toSet()
            val isolatedProxyServers = proxyServers.filterNot {
                it.addressPortKey.lowercase(Locale.ROOT) in bypassKeySet
            }

            RemoteServerCatalogData(
                schemaVersion = schemaVersion,
                updatedAt = updatedAt,
                bypassServers = bypassServers,
                proxyServers = isolatedProxyServers,
                proxySources = proxySources.ifEmpty { DEFAULT_PROXY_SOURCES },
                sourceLabel = sourceLabel
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun readStringOrEmpty(reader: JsonReader): String {
        return when (reader.peek()) {
            JsonReader.Token.STRING -> reader.nextString()
            JsonReader.Token.NUMBER -> reader.nextString()
            else -> {
                reader.skipValue()
                ""
            }
        }
    }

    private fun readIntOrDefault(reader: JsonReader, defaultValue: Int): Int {
        return when (reader.peek()) {
            JsonReader.Token.NUMBER -> runCatching { reader.nextInt() }.getOrDefault(defaultValue)
            JsonReader.Token.STRING -> reader.nextString().trim().toIntOrNull() ?: defaultValue
            else -> {
                reader.skipValue()
                defaultValue
            }
        }
    }

    private fun parseServerArray(reader: JsonReader, forcedKind: ServerKind): List<ServerLocation> {
        val result = ArrayList<ServerLocation>()
        val seenKeys = HashSet<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            var rawId = ""
            var rawName = ""
            var rawFlag = ""
            var rawIp = ""
            var port = if (forcedKind == ServerKind.BYPASS) 443 else 8080
            var rawProto = "http"
            var rawCountry = ""
            var rawAzure = ""
            var rawDesc = ""

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "id" -> rawId = readStringOrEmpty(reader)
                    "name" -> rawName = readStringOrEmpty(reader)
                    "flag" -> rawFlag = readStringOrEmpty(reader)
                    "ip" -> rawIp = readStringOrEmpty(reader)
                    "port" -> port = readIntOrDefault(reader, port)
                    "protocol" -> rawProto = readStringOrEmpty(reader)
                    "countryCode" -> rawCountry = readStringOrEmpty(reader)
                    "azureEndpoint" -> rawAzure = readStringOrEmpty(reader)
                    "description" -> rawDesc = readStringOrEmpty(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            val ip = rawIp.trim()
            if (!LiveProxyFetcher.isValidPublicEndpoint(ip, port)) continue

            val cc = rawCountry.trim().uppercase(Locale.ROOT)
            if (cc.isBlank() || !GeoIpDetector.SUPPORTED_COUNTRIES.contains(cc)) continue

            val key = "${ip.lowercase(Locale.ROOT)}:$port"
            if (!seenKeys.add(key)) continue

            val normalizedProto = if (rawProto.trim().lowercase(Locale.ROOT) == "socks5") "socks5" else "http"
            val idPrefix = if (forcedKind == ServerKind.PROXY) "gh_proxy_" else "gh_bypass_"
            val id = rawId.trim().ifBlank {
                "${idPrefix}${cc.lowercase(Locale.ROOT)}_${ip.replace('.', '_')}_$port"
            }
            val flag = rawFlag.trim().ifBlank { GeoIpDetector.getFlagEmoji(cc) }
            val name = rawName.trim().ifBlank { "$flag $cc • $ip" }
            val azureEndpoint = rawAzure.trim().ifBlank {
                GeoIpDetector.getAzureEndpointForCountry(cc)
            }
            val description = rawDesc.trim().ifBlank {
                if (forcedKind == ServerKind.PROXY) "Proxy • ${normalizedProto.uppercase(Locale.ROOT)}" else cc
            }

            result.add(
                ServerLocation(
                    id = id,
                    name = name,
                    flag = flag,
                    ip = ip,
                    port = port,
                    protocol = normalizedProto,
                    azureEndpoint = azureEndpoint,
                    description = description,
                    speedMbps = 0,
                    countryCode = cc,
                    isCustom = false,
                    isVerified = forcedKind == ServerKind.BYPASS,
                    kind = forcedKind,
                    safetyBadge = if (forcedKind == ServerKind.PROXY) "GitHub Proxy" else "Bypass"
                )
            )
        }
        reader.endArray()
        return result
    }

    private fun parseProxySources(reader: JsonReader): List<ProxyFeedSource> {
        val list = mutableListOf<ProxyFeedSource>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            var id = ""
            var type = "plain_ip_port"
            var proto = "http"
            var urlTemplate = ""
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "id" -> id = readStringOrEmpty(reader).trim()
                    "type" -> type = readStringOrEmpty(reader).trim().lowercase(Locale.ROOT)
                    "protocol" -> proto = readStringOrEmpty(reader).trim().lowercase(Locale.ROOT)
                    "urlTemplate" -> urlTemplate = readStringOrEmpty(reader).trim()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            // Chỉ chấp nhận nguồn HTTPS hợp lệ
            if (id.isBlank() || !urlTemplate.startsWith("https://")) continue
            if (type != "plain_ip_port" && type != "geonode_json") continue
            list.add(ProxyFeedSource(id = id, type = type, protocol = proto, urlTemplate = urlTemplate))
        }
        reader.endArray()
        return list
    }

    fun loadCachedCatalog(context: Context): Pair<RemoteServerCatalogData, Long>? {
        return try {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = sp.getString(KEY_REMOTE_CATALOG_JSON, null) ?: return null
            val source = sp.getString(KEY_REMOTE_CATALOG_SOURCE, "Cache") ?: "Cache"
            val syncedAt = sp.getLong(KEY_REMOTE_CATALOG_SYNCED_AT, 0L)
            val parsed = parseCatalogJson(raw, sourceLabel = source) ?: return null
            parsed to syncedAt
        } catch (_: Exception) {
            null
        }
    }

    fun saveCachedCatalog(context: Context, rawJson: String, sourceLabel: String) {
        try {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sp.edit()
                .putString(KEY_REMOTE_CATALOG_JSON, rawJson)
                .putString(KEY_REMOTE_CATALOG_SOURCE, sourceLabel)
                .putLong(KEY_REMOTE_CATALOG_SYNCED_AT, System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {
        }
    }
}
