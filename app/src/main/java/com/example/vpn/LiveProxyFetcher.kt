package com.example.vpn

import com.squareup.moshi.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okio.Buffer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

data class LiveProxySyncResult(
    val verifiedServers: List<ServerLocation>,
    val filteredOutErrorCount: Int,
    val testedCandidateCount: Int = 0,
    val sourcesSucceededCount: Int = 0
)

object LiveProxyFetcher {

    /** Ngưỡng RTT tổng (TCP + CONNECT + TLS + HTTPS HEAD tới Xbox) tối đa cho Proxy chất lượng tốt */
    const val MAX_ALLOWED_LIVE_PING_MS = 2500
    const val MIN_VERIFIED_SPEED_MBPS = 260
    private const val TARGET_VERIFIED_PROXY_COUNT = 10
    private const val MAX_CANDIDATES_TO_TEST = 160
    private const val WAVE_BATCH_SIZE = 24

    private val PRIORITY_XBOX_COUNTRIES = setOf("JP", "KR", "US", "AU", "GB", "CA", "DE", "NL", "FR")

    // Các cổng dịch vụ hệ thống bị loại bỏ khi kiểm tra kết nối proxy công cộng
    private val DISALLOWED_PORTS = setOf(
        21, 22, 23, 25, 53, 69, 110, 111, 135, 137, 138, 139,
        143, 161, 389, 445, 512, 513, 514, 1433, 1521, 2049,
        3306, 3389, 5432, 5900, 6379, 11211, 27017
    )

    private val COMMON_PROXY_PORTS = setOf(80, 443, 1080, 3128, 4145, 8080, 8443, 8888, 9050)

    internal data class CandidateNode(
        val countryCode: String, // có thể rỗng nếu nguồn plain-text chưa gắn mã quốc gia -> sẽ tra cứu sau khi vượt qua TLS
        val ip: String,
        val port: Int,
        val protocol: String,
        val sourceLabel: String,
        val preferredName: String? = null,
        val priorityBias: Int = 0
    )

    fun isValidPublicEndpoint(ip: String, port: Int): Boolean {
        if (port !in 1..65535 || port in DISALLOWED_PORTS) return false
        if (GeoIpDetector.isPrivateOrLoopbackIp(ip)) return false
        val trimmed = ip.trim()
        if (trimmed.isBlank()) return false
        // Từ chối các dải IP bogon/multicast/broadcast
        if (trimmed.startsWith("0.") || trimmed.startsWith("224.") ||
            trimmed.startsWith("240.") || trimmed.startsWith("255.")
        ) {
            return false
        }
        return true
    }

    fun computeConnectionPriorityScore(
        port: Int,
        rttMs: Int,
        isCuratedSeed: Boolean,
        isUnstable: Boolean
    ): Int {
        if (isUnstable || rttMs <= 0 || rttMs > MAX_ALLOWED_LIVE_PING_MS) return 0
        var score = if (isCuratedSeed) 91 else 86
        if (port in COMMON_PROXY_PORTS) score += 2
        score += when {
            rttMs <= 450 -> 6
            rttMs <= 850 -> 4
            rttMs <= 1400 -> 2
            else -> 0
        }
        return score.coerceIn(85, 99)
    }

    suspend fun fetchLiveProxies(countryCode: String = "ALL"): List<ServerLocation> {
        return fetchAndCheckPublicProxies(countryCode).verifiedServers
    }

    /**
     * Tự động quét và kiểm tra **Proxy thật chất lượng cao** theo cơ chế nhiều đợt (progressive waves):
     * 1. Thu thập song song từ nhiều nguồn HTTPS đáng tin cậy (ProxyScrape v4 JSON, Proxifly JSON, GeoNode JSON, ProxyScrape v2, Monosans, TheSpeedX) + hạt giống GitHub.
     * 2. Ưu tiên HTTP proxy (WebView ProxyController hỗ trợ HTTP CONNECT ổn định & nhanh nhất), ưu tiên JP/KR/US trước rồi mở rộng sang 30 vùng Xbox hỗ trợ.
     * 3. Quét theo từng đợt 24 ứng viên (tối đa 160 ứng viên, dừng sớm ngay khi đạt 10 proxy đã qua kiểm tra **TLS + HTTPS HEAD tới Xbox** thật sự có mạng và không bị MITM).
     * 4. Chỉ tra cứu GeoIP mạng cho các ứng viên plain-text chưa rõ quốc gia **SAU KHI** đã vượt qua TLS+HTTPS, tránh lãng phí thời gian vào IP chết.
     */
    suspend fun fetchAndCheckPublicProxies(
        countryCode: String = "ALL",
        extraSeeds: List<ServerLocation> = emptyList(),
        remoteSources: List<ProxyFeedSource> = emptyList(),
        blockedServerKeys: Set<String> = emptySet()
    ): LiveProxySyncResult = withContext(Dispatchers.IO) {
        if (VpnManager.isStreamActive()) {
            return@withContext LiveProxySyncResult(emptyList(), 0, 0, 0)
        }
        val normalizedFilter = countryCode.trim().uppercase(Locale.ROOT)
        val filterByCountry = normalizedFilter.isNotEmpty() && normalizedFilter != "ALL" && normalizedFilter != "CUSTOM"
        val targetCountries = if (filterByCountry) {
            listOf(normalizedFilter)
        } else {
            listOf("JP", "KR", "US")
        }

        val candidates = java.util.Collections.synchronizedList(mutableListOf<CandidateNode>())
        val seenIpPort = java.util.Collections.synchronizedSet(HashSet<String>())
        val filteredErrorCounter = AtomicInteger(0)
        val sourcesSucceeded = AtomicInteger(0)

        // 0. Nạp trước các Proxy Seed từ GitHub catalog (nếu có)
        for (seed in extraSeeds) {
            val ip = seed.ip.trim()
            val port = seed.port
            val key = "${ip.lowercase(Locale.ROOT)}:$port"
            if (key in blockedServerKeys || !isValidPublicEndpoint(ip, port)) {
                filteredErrorCounter.incrementAndGet()
                continue
            }
            val cc = seed.countryCode.trim().uppercase(Locale.ROOT)
            if (filterByCountry && cc != normalizedFilter) continue
            if (cc.isNotBlank() && !GeoIpDetector.SUPPORTED_COUNTRIES.contains(cc)) continue
            if (seenIpPort.add(key)) {
                candidates.add(
                    CandidateNode(
                        countryCode = cc,
                        ip = ip,
                        port = port,
                        protocol = if (seed.protocol.equals("socks5", ignoreCase = true)) "socks5" else "http",
                        sourceLabel = "GitHub Proxy",
                        preferredName = seed.name,
                        priorityBias = 3
                    )
                )
            }
        }

        // 1. Thu thập song song từ danh sách nguồn HTTPS
        val activeSources = remoteSources.ifEmpty { RemoteServerCatalog.DEFAULT_PROXY_SOURCES }

        kotlinx.coroutines.coroutineScope {
            val fetchJobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

            for (source in activeSources) {
                if (!source.urlTemplate.startsWith("https://")) continue
                val countriesToQuery = if (source.urlTemplate.contains("{country}")) {
                    targetCountries
                } else {
                    listOf(normalizedFilter.ifBlank { "ALL" })
                }

                for (cc in countriesToQuery) {
                    fetchJobs.add(
                        async {
                            try {
                                val resolvedUrl = source.urlTemplate.replace("{country}", cc)
                                val parsed = when (source.type) {
                                    "proxyscrape_v4_json" -> downloadAndParse(resolvedUrl, 3200) { body ->
                                        parseProxyScrapeV4Json(body)
                                    }
                                    "proxifly_json" -> downloadAndParse(resolvedUrl, 3200) { body ->
                                        parseProxiflyJson(body)
                                    }
                                    "geonode_json" -> downloadAndParse(resolvedUrl, 3200) { body ->
                                        parseGeoNodeJson(body, defaultCc = if (cc == "ALL") "" else cc)
                                    }
                                    else -> downloadAndParse(resolvedUrl, 2800) { body ->
                                        parsePlainIpPortText(
                                            rawText = body,
                                            protocol = if (source.protocol == "socks5") "socks5" else "http",
                                            defaultCc = if (cc == "ALL") "" else cc,
                                            sourceLabel = source.id
                                        )
                                    }
                                }
                                if (parsed != null) {
                                    sourcesSucceeded.incrementAndGet()
                                    var addedForFeed = 0
                                    val feedCap = if (source.type == "plain_ip_port" && !source.urlTemplate.contains("{country}")) 45 else 40
                                    for (node in parsed) {
                                        val key = "${node.ip.lowercase(Locale.ROOT)}:${node.port}"
                                        if (key in blockedServerKeys || !isValidPublicEndpoint(node.ip, node.port)) {
                                            filteredErrorCounter.incrementAndGet()
                                            continue
                                        }
                                        // Kiểm tra nhanh quốc gia (chỉ tra bảng cục bộ 0ms)
                                        val localCc = node.countryCode.ifBlank {
                                            GeoIpDetector.resolveIpCountryCode(node.ip, allowNetworkFallback = false)
                                        }
                                        if (localCc.isNotBlank()) {
                                            if (!GeoIpDetector.SUPPORTED_COUNTRIES.contains(localCc)) {
                                                continue
                                            }
                                            if (filterByCountry && localCc != normalizedFilter) {
                                                continue
                                            }
                                        }
                                        if (seenIpPort.add(key)) {
                                            if (localCc.isNotBlank()) {
                                                GeoIpDetector.cacheResolvedIpCountry(node.ip, localCc)
                                            }
                                            candidates.add(node.copy(countryCode = localCc))
                                            addedForFeed++
                                            if (addedForFeed >= feedCap) break
                                        }
                                    }
                                }
                            } catch (ce: kotlinx.coroutines.CancellationException) {
                                throw ce
                            } catch (_: Exception) {
                            }
                        }
                    )
                }
            }

            fetchJobs.awaitAll()
        }

        if (VpnManager.isStreamActive()) {
            return@withContext LiveProxySyncResult(emptyList(), 0, 0, sourcesSucceeded.get())
        }

        val prioritizedCandidates = synchronized(candidates) {
            candidates
                .sortedWith(
                    compareByDescending<CandidateNode> { it.protocol == "http" }
                        .thenByDescending { it.priorityBias }
                        .thenByDescending { it.countryCode in setOf("JP", "KR", "US") }
                        .thenByDescending { it.countryCode in PRIORITY_XBOX_COUNTRIES }
                        .thenByDescending { it.countryCode.isNotBlank() }
                        .thenByDescending { it.port in COMMON_PROXY_PORTS }
                )
                .take(MAX_CANDIDATES_TO_TEST)
        }

        if (prioritizedCandidates.isEmpty()) {
            return@withContext LiveProxySyncResult(
                verifiedServers = emptyList(),
                filteredOutErrorCount = filteredErrorCounter.get(),
                testedCandidateCount = 0,
                sourcesSucceededCount = sourcesSucceeded.get()
            )
        }

        val currentGen = VpnManager.getCurrentMeasurementGeneration()
        val isEn = try {
            com.example.data.SettingsRepository.config.value.isEnglishUI()
        } catch (_: Exception) {
            false
        }

        val verifiedList = mutableListOf<ServerLocation>()
        val testedCounter = AtomicInteger(0)

        // 2. Quét từng đợt 24 ứng viên (tối đa 8 socket song song), kiểm chứng TLS + HTTPS thật tới Xbox.
        // Dừng sớm ngay khi thu được >= TARGET_VERIFIED_PROXY_COUNT proxy tốt.
        val waves = prioritizedCandidates.chunked(WAVE_BATCH_SIZE)
        for (wave in waves) {
            if (VpnManager.isStreamActive() || verifiedList.size >= TARGET_VERIFIED_PROXY_COUNT) {
                break
            }
            val waveResults = kotlinx.coroutines.coroutineScope {
                wave.map { node ->
                    async {
                        if (VpnManager.isStreamActive()) return@async null
                        testedCounter.incrementAndGet()

                        val handshake = PingTester.verifyRealProxyEndpoint(
                            host = node.ip,
                            port = node.port,
                            protocol = node.protocol,
                            timeoutMs = MAX_ALLOWED_LIVE_PING_MS,
                            verifyTlsAndHttp = true,
                            useFastScanPool = true
                        )

                        if (!handshake.ok || handshake.rttMs !in 1..MAX_ALLOWED_LIVE_PING_MS) {
                            filteredErrorCounter.incrementAndGet()
                            return@async null
                        }

                        // Chỉ sau khi proxy vượt qua TLS + HTTPS thật mới xác nhận quốc gia nếu nguồn chưa có
                        val resolvedCc = node.countryCode.ifBlank {
                            GeoIpDetector.resolveIpCountryCode(
                                ip = node.ip,
                                allowNetworkFallback = true,
                                useFastTimeout = true
                            )
                        }.trim().uppercase(Locale.ROOT)

                        if (resolvedCc.isBlank() || !GeoIpDetector.SUPPORTED_COUNTRIES.contains(resolvedCc)) {
                            filteredErrorCounter.incrementAndGet()
                            return@async null
                        }
                        if (filterByCountry && resolvedCc != normalizedFilter) {
                            filteredErrorCounter.incrementAndGet()
                            return@async null
                        }

                        GeoIpDetector.cacheResolvedIpCountry(node.ip, resolvedCc)
                        val flag = GeoIpDetector.getFlagEmoji(resolvedCc)
                        val countryDisplayName = GeoIpDetector.getCountryDisplayName(resolvedCc, isEn)
                        val azureEndpoint = GeoIpDetector.getAzureEndpointForCountry(resolvedCc)
                        val safeCcId = resolvedCc.lowercase(Locale.ROOT)
                        val protoTag = node.protocol.uppercase(Locale.ROOT)
                        val serverId = "proxy_${safeCcId}_${node.protocol}_${node.ip.replace('.', '_')}_${node.port}"
                        val displayName = node.preferredName?.takeIf { it.isNotBlank() }
                            ?: "$flag $countryDisplayName $protoTag • ${node.ip}"

                        val priorityScore = computeConnectionPriorityScore(
                            port = node.port,
                            rttMs = handshake.rttMs,
                            isCuratedSeed = node.priorityBias > 2,
                            isUnstable = handshake.rttMs >= 1800
                        )

                        val verifiedServer = ServerLocation(
                            id = serverId,
                            name = displayName,
                            flag = flag,
                            ip = node.ip,
                            port = node.port,
                            protocol = node.protocol,
                            azureEndpoint = azureEndpoint,
                            description = "$countryDisplayName • $protoTag TLS • ${handshake.rttMs}ms",
                            speedMbps = 0,
                            countryCode = resolvedCc,
                            isCustom = false,
                            lastPingMs = handshake.rttMs,
                            isVerified = true,
                            trustScore = priorityScore,
                            safetyBadge = "TLS Xbox OK",
                            kind = ServerKind.PROXY
                        )

                        val measurement = EndpointPingMeasurement(
                            rttMs = handshake.rttMs,
                            minRttMs = handshake.rttMs,
                            maxRttMs = handshake.rttMs,
                            configuredPort = node.port,
                            measuredPort = node.port,
                            resolvedIp = node.ip,
                            measurementType = PingMeasurementType.TCP_PRIMARY_PORT,
                            status = ServerPingStatus.MEASURED_PRIMARY,
                            isUnstable = handshake.rttMs >= 1600
                        )
                        VpnManager.recordMeasurementIfCurrentGeneration(currentGen, verifiedServer.id, measurement)
                        verifiedServer
                    }
                }.awaitAll().filterNotNull()
            }
            verifiedList.addAll(waveResults)
        }

        val sortedVerified = verifiedList
            .distinctBy { it.addressPortKey.lowercase(Locale.ROOT) }
            .sortedWith(
                compareBy<ServerLocation> { it.lastPingMs ?: Int.MAX_VALUE }
                    .thenByDescending { it.trustScore }
            )

        LiveProxySyncResult(
            verifiedServers = sortedVerified,
            filteredOutErrorCount = filteredErrorCounter.get(),
            testedCandidateCount = testedCounter.get(),
            sourcesSucceededCount = sourcesSucceeded.get()
        )
    }

    private fun <T> downloadAndParse(urlStr: String, timeoutMs: Int, parser: (String) -> T): T? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs + 800
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json, text/plain;q=0.9")
                setRequestProperty("User-Agent", "Ravno-Android/1.0")
            }
            if (conn.responseCode != 200) return null
            val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                var total = 0
                while (true) {
                    val read = reader.read(buf)
                    if (read <= 0) break
                    total += read
                    if (total > 1024 * 1024) break // Giới hạn an toàn 1MB mỗi feed
                    sb.append(buf, 0, read)
                }
                sb.toString()
            }
            parser(body)
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    internal fun parsePlainIpPortText(
        rawText: String,
        protocol: String = "http",
        defaultCc: String = "",
        sourceLabel: String = "plain"
    ): List<CandidateNode> {
        val out = ArrayList<CandidateNode>()
        val normalizedProto = if (protocol.equals("socks5", ignoreCase = true)) "socks5" else "http"
        val normalizedCc = defaultCc.trim().uppercase(Locale.ROOT)
        for (rawLine in rawText.lineSequence()) {
            var line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#")) continue
            if (line.contains("://")) {
                line = line.substringAfter("://")
            }
            val colonIdx = line.lastIndexOf(':')
            if (colonIdx <= 0) continue
            val ip = line.substring(0, colonIdx).trim()
            val port = line.substring(colonIdx + 1).trim().toIntOrNull() ?: continue
            if (!isValidPublicEndpoint(ip, port)) continue
            out.add(
                CandidateNode(
                    countryCode = normalizedCc,
                    ip = ip,
                    port = port,
                    protocol = normalizedProto,
                    sourceLabel = sourceLabel,
                    priorityBias = if (normalizedCc.isNotBlank()) 1 else 0
                )
            )
        }
        return out
    }

    internal fun parseProxyScrapeV4Json(rawJson: String): List<CandidateNode> {
        if (rawJson.isBlank()) return emptyList()
        val out = ArrayList<CandidateNode>()
        try {
            JsonReader.of(Buffer().writeUtf8(rawJson)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "proxies" && reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
                                reader.skipValue()
                                continue
                            }
                            var ip = ""
                            var port = -1
                            var proto = "http"
                            var country = ""
                            var alive = true
                            var timeoutMs = 9999.0
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "ip" -> ip = readStringOrEmpty(reader).trim()
                                    "port" -> port = readIntOrDefault(reader, -1)
                                    "protocol" -> proto = readStringOrEmpty(reader).trim().lowercase(Locale.ROOT)
                                    "alive" -> alive = readBoolOrDefault(reader, true)
                                    "timeout" -> timeoutMs = readDoubleOrDefault(reader, 9999.0)
                                    "ip_data" -> {
                                        if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                                            reader.beginObject()
                                            while (reader.hasNext()) {
                                                if (reader.nextName() == "countryCode") {
                                                    country = readStringOrEmpty(reader).trim().uppercase(Locale.ROOT)
                                                } else {
                                                    reader.skipValue()
                                                }
                                            }
                                            reader.endObject()
                                        } else {
                                            reader.skipValue()
                                        }
                                    }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            if (!alive || !isValidPublicEndpoint(ip, port)) continue
                            val normalizedProto = when (proto) {
                                "http", "https" -> "http"
                                "socks5" -> "socks5"
                                else -> continue
                            }
                            val bias = if (timeoutMs < 1500.0) 3 else 2
                            out.add(
                                CandidateNode(
                                    countryCode = country,
                                    ip = ip,
                                    port = port,
                                    protocol = normalizedProto,
                                    sourceLabel = "ProxyScrape v4",
                                    priorityBias = bias
                                )
                            )
                        }
                        reader.endArray()
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            }
        } catch (_: Exception) {
        }
        return out
    }

    internal fun parseProxiflyJson(rawJson: String): List<CandidateNode> {
        if (rawJson.isBlank()) return emptyList()
        val out = ArrayList<CandidateNode>()
        try {
            JsonReader.of(Buffer().writeUtf8(rawJson)).use { reader ->
                if (reader.peek() != JsonReader.Token.BEGIN_ARRAY) return emptyList()
                reader.beginArray()
                while (reader.hasNext()) {
                    if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
                        reader.skipValue()
                        continue
                    }
                    var ip = ""
                    var port = -1
                    var proto = "http"
                    var country = ""
                    var httpsSupported = true
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "ip" -> ip = readStringOrEmpty(reader).trim()
                            "port" -> port = readIntOrDefault(reader, -1)
                            "protocol" -> proto = readStringOrEmpty(reader).trim().lowercase(Locale.ROOT)
                            "https" -> httpsSupported = readBoolOrDefault(reader, true)
                            "geolocation" -> {
                                if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                                    reader.beginObject()
                                    while (reader.hasNext()) {
                                        if (reader.nextName() == "country") {
                                            country = readStringOrEmpty(reader).trim().uppercase(Locale.ROOT)
                                        } else {
                                            reader.skipValue()
                                        }
                                    }
                                    reader.endObject()
                                } else {
                                    reader.skipValue()
                                }
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    if (!isValidPublicEndpoint(ip, port)) continue
                    val normalizedProto = when (proto) {
                        "http", "https" -> "http"
                        "socks5" -> "socks5"
                        else -> continue
                    }
                    out.add(
                        CandidateNode(
                            countryCode = country,
                            ip = ip,
                            port = port,
                            protocol = normalizedProto,
                            sourceLabel = "Proxifly",
                            priorityBias = if (httpsSupported) 3 else 1
                        )
                    )
                }
                reader.endArray()
            }
        } catch (_: Exception) {
        }
        return out
    }

    internal fun parseGeoNodeJson(rawJson: String, defaultCc: String = ""): List<CandidateNode> {
        if (rawJson.isBlank()) return emptyList()
        val out = ArrayList<CandidateNode>()
        try {
            JsonReader.of(Buffer().writeUtf8(rawJson)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "data" && reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
                                reader.skipValue()
                                continue
                            }
                            var ip = ""
                            var port = -1
                            var chosenProto: String? = null
                            var country = defaultCc.trim().uppercase(Locale.ROOT)
                            var latencyMs = 9999.0
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "ip" -> ip = readStringOrEmpty(reader).trim()
                                    "port" -> port = readIntOrDefault(reader, -1)
                                    "country" -> {
                                        val c = readStringOrEmpty(reader).trim().uppercase(Locale.ROOT)
                                        if (c.isNotBlank()) country = c
                                    }
                                    "latency" -> latencyMs = readDoubleOrDefault(reader, 9999.0)
                                    "protocols" -> {
                                        if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                                            reader.beginArray()
                                            while (reader.hasNext()) {
                                                val p = readStringOrEmpty(reader).trim().lowercase(Locale.ROOT)
                                                if (p == "http" || p == "https") {
                                                    chosenProto = "http"
                                                } else if (p == "socks5" && chosenProto == null) {
                                                    chosenProto = "socks5"
                                                }
                                            }
                                            reader.endArray()
                                        } else {
                                            reader.skipValue()
                                        }
                                    }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            if (chosenProto == null || !isValidPublicEndpoint(ip, port)) continue
                            out.add(
                                CandidateNode(
                                    countryCode = country,
                                    ip = ip,
                                    port = port,
                                    protocol = chosenProto!!,
                                    sourceLabel = "GeoNode",
                                    priorityBias = if (latencyMs < 1000.0) 2 else 1
                                )
                            )
                        }
                        reader.endArray()
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun readStringOrEmpty(reader: JsonReader): String {
        return when (reader.peek()) {
            JsonReader.Token.STRING, JsonReader.Token.NUMBER -> reader.nextString()
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

    private fun readDoubleOrDefault(reader: JsonReader, defaultValue: Double): Double {
        return when (reader.peek()) {
            JsonReader.Token.NUMBER -> runCatching { reader.nextDouble() }.getOrDefault(defaultValue)
            JsonReader.Token.STRING -> reader.nextString().trim().toDoubleOrNull() ?: defaultValue
            else -> {
                reader.skipValue()
                defaultValue
            }
        }
    }

    private fun readBoolOrDefault(reader: JsonReader, defaultValue: Boolean): Boolean {
        return when (reader.peek()) {
            JsonReader.Token.BOOLEAN -> runCatching { reader.nextBoolean() }.getOrDefault(defaultValue)
            else -> {
                reader.skipValue()
                defaultValue
            }
        }
    }
}
