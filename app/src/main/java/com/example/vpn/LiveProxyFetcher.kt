package com.example.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

data class LiveProxySyncResult(
    val verifiedServers: List<ServerLocation>,
    val filteredOutErrorCount: Int
)

object LiveProxyFetcher {

    const val MAX_ALLOWED_LIVE_PING_MS = 2200
    const val MIN_VERIFIED_SPEED_MBPS = 260

    // Các cổng dịch vụ hệ thống bị loại bỏ khi kiểm tra kết nối proxy công cộng
    private val DISALLOWED_PORTS = setOf(
        21, 22, 23, 25, 53, 69, 110, 111, 135, 137, 138, 139,
        143, 161, 389, 445, 512, 513, 514, 1433, 1521, 2049,
        3306, 3389, 5432, 5900, 6379, 11211, 27017
    )

    private val COMMON_PROXY_PORTS = setOf(80, 443, 1080, 3128, 4145, 8080, 8443, 8888, 9050)

    private data class CandidateNode(
        val countryCode: String,
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
        var score = if (isCuratedSeed) 91 else 85
        if (port in COMMON_PROXY_PORTS) score += 3
        score += when {
            rttMs <= 220 -> 5
            rttMs <= 450 -> 3
            rttMs <= 900 -> 1
            else -> 0
        }
        return score.coerceIn(85, 99)
    }

    suspend fun fetchLiveProxies(countryCode: String = "ALL"): List<ServerLocation> {
        return fetchAndCheckPublicProxies(countryCode).verifiedServers
    }

    /**
     * Tự động quét và kiểm tra **Proxy thật** (chỉ HTTP CONNECT & SOCKS5 thật — KHÔNG lấy VPNGate OpenVPN):
     * 1. Thu thập ứng viên từ danh sách hạt giống GitHub (`extraSeeds`) + các nguồn proxy công cộng HTTPS (`remoteSources` hoặc mặc định ProxyScrape + GeoNode).
     * 2. Lọc bỏ IP nội bộ/bogon, cổng hệ thống (`DISALLOWED_PORTS`), máy chủ đang bị chặn bảo mật hoặc khác vùng hỗ trợ Xbox.
     * 3. Xác minh bắt tay Proxy thật qua [PingTester.verifyRealProxyEndpoint] (HTTP `CONNECT www.xbox.com:443` trả 200 hoặc SOCKS5 CONNECT thành công).
     * 4. Chỉ giữ lại các Proxy thật phản hồi hợp lệ, gắn `kind = ServerKind.PROXY` để tách biệt hoàn toàn với danh sách Bypass.
     */
    suspend fun fetchAndCheckPublicProxies(
        countryCode: String = "ALL",
        extraSeeds: List<ServerLocation> = emptyList(),
        remoteSources: List<ProxyFeedSource> = emptyList(),
        blockedServerKeys: Set<String> = emptySet()
    ): LiveProxySyncResult = withContext(Dispatchers.IO) {
        if (VpnManager.isStreamActive()) {
            return@withContext LiveProxySyncResult(emptyList(), 0)
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

        // 0. Nạp trước các Proxy Seed từ GitHub catalog (ưu tiên kiểm tra trước)
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
            if (!GeoIpDetector.SUPPORTED_COUNTRIES.contains(cc)) continue
            if (seenIpPort.add(key)) {
                candidates.add(
                    CandidateNode(
                        countryCode = cc,
                        ip = ip,
                        port = port,
                        protocol = if (seed.protocol.equals("socks5", ignoreCase = true)) "socks5" else "http",
                        sourceLabel = "GitHub Proxy",
                        preferredName = seed.name,
                        priorityBias = 2
                    )
                )
            }
        }

        // 1. Thu thập song song từ các nguồn Proxy thật (ProxyScrape HTTP/SOCKS5 + GeoNode JSON hoặc nguồn từ GitHub)
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
                                when (source.type) {
                                    "geonode_json" -> fetchGeoNodeJsonCandidates(
                                        urlStr = resolvedUrl,
                                        defaultCc = cc,
                                        filterByCountry = filterByCountry,
                                        normalizedFilter = normalizedFilter,
                                        blockedServerKeys = blockedServerKeys,
                                        seenIpPort = seenIpPort,
                                        candidates = candidates,
                                        filteredErrorCounter = filteredErrorCounter
                                    )
                                    else -> fetchPlainIpPortCandidates(
                                        urlStr = resolvedUrl,
                                        protocol = if (source.protocol == "socks5") "socks5" else "http",
                                        defaultCc = cc,
                                        filterByCountry = filterByCountry,
                                        normalizedFilter = normalizedFilter,
                                        blockedServerKeys = blockedServerKeys,
                                        seenIpPort = seenIpPort,
                                        candidates = candidates,
                                        filteredErrorCounter = filteredErrorCounter
                                    )
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
            return@withContext LiveProxySyncResult(emptyList(), 0)
        }

        val prioritizedCandidates = synchronized(candidates) {
            candidates
                .sortedWith(
                    compareByDescending<CandidateNode> { it.priorityBias }
                        .thenByDescending { it.protocol == "http" }
                        .thenByDescending { it.port in COMMON_PROXY_PORTS }
                )
                .take(28)
        }

        if (prioritizedCandidates.isEmpty()) {
            return@withContext LiveProxySyncResult(
                verifiedServers = emptyList(),
                filteredOutErrorCount = filteredErrorCounter.get()
            )
        }

        val currentGen = VpnManager.getCurrentMeasurementGeneration()
        val isEn = try {
            com.example.data.SettingsRepository.config.value.isEnglishUI()
        } catch (_: Exception) {
            false
        }

        // 2. Xác minh bắt tay Proxy thật song song (giới hạn tối đa 5 socket nhờ PingTester.socketSemaphore)
        val verifiedServers = prioritizedCandidates.map { node ->
            async {
                if (VpnManager.isStreamActive()) return@async null
                val (isValidRegion, realCc) = GeoIpDetector.fastCheckIpSecurityAndRegion(
                    ip = node.ip,
                    declaredCountryCode = node.countryCode,
                    allowFastNetworkFallback = false
                )
                if (!isValidRegion || (realCc.isNotBlank() && !GeoIpDetector.SUPPORTED_COUNTRIES.contains(realCc))) {
                    filteredErrorCounter.incrementAndGet()
                    return@async null
                }
                if (filterByCountry && realCc.isNotBlank() && realCc != normalizedFilter) {
                    filteredErrorCounter.incrementAndGet()
                    return@async null
                }

                val handshake = PingTester.verifyRealProxyEndpoint(
                    host = node.ip,
                    port = node.port,
                    protocol = node.protocol,
                    timeoutMs = MAX_ALLOWED_LIVE_PING_MS
                )

                if (!handshake.ok || handshake.rttMs !in 1..MAX_ALLOWED_LIVE_PING_MS) {
                    filteredErrorCounter.incrementAndGet()
                    return@async null
                }

                val effectiveCc = realCc.ifBlank { node.countryCode }
                val flag = GeoIpDetector.getFlagEmoji(effectiveCc)
                val countryDisplayName = GeoIpDetector.getCountryDisplayName(effectiveCc, isEn)
                val azureEndpoint = GeoIpDetector.getAzureEndpointForCountry(effectiveCc)
                val safeCcId = effectiveCc.ifBlank { "unk" }.lowercase(Locale.ROOT)
                val protoTag = node.protocol.uppercase(Locale.ROOT)
                val serverId = "proxy_${safeCcId}_${node.protocol}_${node.ip.replace('.', '_')}_${node.port}"
                val displayName = node.preferredName?.takeIf { it.isNotBlank() }
                    ?: "$flag $countryDisplayName $protoTag • ${node.ip}"

                val priorityScore = computeConnectionPriorityScore(
                    port = node.port,
                    rttMs = handshake.rttMs,
                    isCuratedSeed = node.priorityBias > 0,
                    isUnstable = false
                )

                val verifiedServer = ServerLocation(
                    id = serverId,
                    name = displayName,
                    flag = flag,
                    ip = node.ip,
                    port = node.port,
                    protocol = node.protocol,
                    azureEndpoint = azureEndpoint,
                    description = "$countryDisplayName • $protoTag Proxy • ${handshake.rttMs}ms",
                    speedMbps = 0,
                    countryCode = effectiveCc,
                    isCustom = false,
                    lastPingMs = handshake.rttMs,
                    isVerified = true,
                    trustScore = priorityScore,
                    safetyBadge = "Đã kiểm tra kết nối",
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
                    isUnstable = handshake.rttMs >= 1400
                )
                VpnManager.recordMeasurementIfCurrentGeneration(currentGen, verifiedServer.id, measurement)
                verifiedServer
            }
        }.awaitAll().filterNotNull().sortedWith(
            compareBy<ServerLocation> { it.lastPingMs ?: Int.MAX_VALUE }
                .thenByDescending { it.trustScore }
        )

        LiveProxySyncResult(
            verifiedServers = verifiedServers,
            filteredOutErrorCount = filteredErrorCounter.get()
        )
    }

    private fun fetchPlainIpPortCandidates(
        urlStr: String,
        protocol: String,
        defaultCc: String,
        filterByCountry: Boolean,
        normalizedFilter: String,
        blockedServerKeys: Set<String>,
        seenIpPort: MutableSet<String>,
        candidates: MutableList<CandidateNode>,
        filteredErrorCounter: AtomicInteger
    ) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 2500
                readTimeout = 2500
                setRequestProperty("User-Agent", "Ravno-Android/1.0")
            }
            if (conn.responseCode != 200) return
            var addedForFeed = 0
            BufferedReader(InputStreamReader(conn.inputStream)).useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isBlank() || !trimmed.contains(":")) continue
                    val parts = trimmed.split(":")
                    if (parts.size != 2) continue
                    val ip = parts[0].trim()
                    val port = parts[1].trim().toIntOrNull()
                    val key = "${ip.lowercase(Locale.ROOT)}:${port ?: -1}"
                    if (port == null || key in blockedServerKeys || !isValidPublicEndpoint(ip, port)) {
                        filteredErrorCounter.incrementAndGet()
                        continue
                    }
                    val (isRegionValid, effectiveCc) = GeoIpDetector.fastCheckIpSecurityAndRegion(
                        ip = ip,
                        declaredCountryCode = defaultCc,
                        allowFastNetworkFallback = false
                    )
                    if (!isRegionValid || effectiveCc.isBlank()) {
                        filteredErrorCounter.incrementAndGet()
                        continue
                    }
                    if (filterByCountry && effectiveCc != normalizedFilter) continue
                    if (seenIpPort.add(key)) {
                        GeoIpDetector.cacheResolvedIpCountry(ip, effectiveCc)
                        candidates.add(
                            CandidateNode(
                                countryCode = effectiveCc,
                                ip = ip,
                                port = port,
                                protocol = protocol,
                                sourceLabel = "Public Proxy",
                                priorityBias = 0
                            )
                        )
                        addedForFeed++
                        if (addedForFeed >= 10) break
                    }
                }
            }
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun fetchGeoNodeJsonCandidates(
        urlStr: String,
        defaultCc: String,
        filterByCountry: Boolean,
        normalizedFilter: String,
        blockedServerKeys: Set<String>,
        seenIpPort: MutableSet<String>,
        candidates: MutableList<CandidateNode>,
        filteredErrorCounter: AtomicInteger
    ) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 2800
                readTimeout = 3000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Ravno-Android/1.0")
            }
            if (conn.responseCode != 200) return
            val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            val root = JSONObject(body)
            val data = root.optJSONArray("data") ?: return
            var addedForFeed = 0
            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val ip = obj.optString("ip", "").trim()
                val port = obj.optString("port", "").trim().toIntOrNull() ?: obj.optInt("port", -1)
                val key = "${ip.lowercase(Locale.ROOT)}:$port"
                if (port <= 0 || key in blockedServerKeys || !isValidPublicEndpoint(ip, port)) {
                    filteredErrorCounter.incrementAndGet()
                    continue
                }
                val protoArray = obj.optJSONArray("protocols")
                var chosenProto: String? = null
                if (protoArray != null) {
                    for (pIdx in 0 until protoArray.length()) {
                        val p = protoArray.optString(pIdx, "").trim().lowercase(Locale.ROOT)
                        if (p == "http" || p == "https") {
                            chosenProto = "http"
                            break
                        } else if (p == "socks5" && chosenProto == null) {
                            chosenProto = "socks5"
                        }
                    }
                }
                if (chosenProto == null) continue

                val declaredCc = obj.optString("country", defaultCc).trim().uppercase(Locale.ROOT)
                val (isRegionValid, effectiveCc) = GeoIpDetector.fastCheckIpSecurityAndRegion(
                    ip = ip,
                    declaredCountryCode = declaredCc,
                    allowFastNetworkFallback = false
                )
                if (!isRegionValid || effectiveCc.isBlank()) {
                    filteredErrorCounter.incrementAndGet()
                    continue
                }
                if (filterByCountry && effectiveCc != normalizedFilter) continue
                if (seenIpPort.add(key)) {
                    GeoIpDetector.cacheResolvedIpCountry(ip, effectiveCc)
                    candidates.add(
                        CandidateNode(
                            countryCode = effectiveCc,
                            ip = ip,
                            port = port,
                            protocol = chosenProto,
                            sourceLabel = "GeoNode Proxy",
                            priorityBias = 1
                        )
                    )
                    addedForFeed++
                    if (addedForFeed >= 10) break
                }
            }
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
