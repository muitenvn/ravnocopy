package com.example.vpn

import androidx.compose.runtime.Immutable

enum class NetworkMode {
    VPN_JAPAN,       // Traffic routing through Japan/Target region to pass Xbox region check
    DIRECT_NETWORK,  // Traffic routing directly through user's local ISP (low ping, zero-reload)
    DISCONNECTED     // Idle / normal connection
}

enum class VpnEngine {
    PROXY_TUNNEL,    // Proxy Route: Routes traffic through connectivity-checked Japan/International proxy servers with multi-server backup
    SMART_BYPASS,    // Better-xCloud Smart Header & Region Spoof (Guaranteed Japan East, 0 lag, no dead proxy)
    EXTERNAL_VPN     // External VPN Assistant: Use with Warp, Proton, Kiwi, etc. with auto-detect and zero-reload handoff
}

enum class ServerKind {
    BYPASS,
    PROXY
}

enum class VpnSecurityViolationType {
    SSL_TAMPERING,
    EXTERNAL_REDIRECT_OR_SCHEME
}

@Immutable
data class VpnSecurityAlert(
    val serverKey: String,
    val serverName: String,
    val violationType: VpnSecurityViolationType,
    val detail: String,
    val timestampMs: Long = System.currentTimeMillis()
)

enum class RegionUnlockStatus(val labelVi: String, val labelEn: String) {
    UNLOCKED("Đã mở khóa", "Unlocked"),
    FAILED("Không mở khóa", "Unlock Failed"),
    UNTESTED("Chưa thử", "Untested"),
    WRONG_REGION("Sai vùng", "Wrong Region");

    fun localizedLabel(isEn: Boolean): String = if (isEn) labelEn else labelVi
}

@Immutable
data class ServerUnlockRecord(
    val serverKey: String,
    val status: RegionUnlockStatus = RegionUnlockStatus.UNTESTED,
    val timestampMs: Long = System.currentTimeMillis(),
    val detectedCountryCode: String? = null
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
        return status == RegionUnlockStatus.FAILED && (nowMs - timestampMs) >= UNLOCK_FAIL_EXPIRY_MS
    }

    fun effectiveStatus(nowMs: Long = System.currentTimeMillis()): RegionUnlockStatus {
        return if (isExpired(nowMs)) RegionUnlockStatus.UNTESTED else status
    }

    companion object {
        const val UNLOCK_FAIL_EXPIRY_MS: Long = 24L * 60L * 60L * 1000L // 24 giờ

        fun keyFor(ip: String, port: Int): String = "${ip.trim()}:$port"
        fun keyFor(server: ServerLocation): String = keyFor(server.ip, server.port)
    }
}

@Immutable
data class ServerLocation(
    val id: String,
    val name: String,
    val flag: String,
    val ip: String,
    val port: Int = 8080,
    val protocol: String = "http", // "http", "socks5"
    val azureEndpoint: String = "japaneast.cloudapp.azure.com",
    val description: String = "Tokyo, Japan",
    val speedMbps: Int = 0,
    val countryCode: String = "JP",
    val isCustom: Boolean = false,
    val lastPingMs: Int? = null,
    val isVerified: Boolean = true,
    val unlockStatus: RegionUnlockStatus = RegionUnlockStatus.UNTESTED,
    val trustScore: Int = 96,
    val safetyBadge: String = "Đã kiểm tra kết nối",
    val kind: ServerKind = ServerKind.BYPASS
) {
    val addressPortKey: String
        get() = ServerUnlockRecord.keyFor(ip, port)

    fun localizedSafetyBadge(isEn: Boolean): String {
        val normalizedVi = when {
            safetyBadge.contains("Tự thêm", ignoreCase = true) -> "Tự thêm • Đã kiểm tra kết nối"
            else -> "Đã kiểm tra kết nối"
        }
        if (!isEn) return normalizedVi
        return when {
            safetyBadge.contains("Tự thêm", ignoreCase = true) ||
                safetyBadge.contains("Custom", ignoreCase = true) -> "Custom • Connectivity checked"
            else -> "Connectivity checked"
        }
    }

    @Transient
    private var cachedDescVi: String? = null

    @Transient
    private var cachedDescEn: String? = null

    fun localizedDescription(isEn: Boolean): String {
        val vi = cachedDescVi ?: run {
            val cleaned = description
                .replace(PORT_BULLET_REGEX, "")
                .replace(PORT_PAREN_WORD_REGEX, "")
                .replace(PORT_PAREN_SHORT_REGEX, "")
                .replace("Đã xác minh IP Tokyo", "Tokyo, Nhật Bản")
                .replace("Đã xác minh IP Nhật Bản", "Nhật Bản")
                .replace("Đã xác minh IP Osaka", "Osaka, Nhật Bản")
                .replace("Uy tín cao • Đã lọc lỗi", "Đã kiểm tra kết nối")
                .replace("An toàn • Đã xác minh", "Đã kiểm tra kết nối")
                .replace("An toàn • Uy tín", "Đã kiểm tra kết nối")
            cachedDescVi = cleaned
            cleaned
        }
        if (!isEn) return vi
        return cachedDescEn ?: run {
            val en = vi
                .replace("Đại học Tsukuba Academic", "Tsukuba University Academic")
                .replace("Đại học Tsukuba", "Tsukuba University")
                .replace("Đã kiểm tra kết nối", "Connectivity checked")
                .replace("Tokyo, Nhật Bản", "Tokyo, Japan")
                .replace("Osaka, Nhật Bản", "Osaka, Japan")
                .replace("Nhật Bản", "Japan")
                .replace("Hàn Quốc", "South Korea")
                .replace("Hoa Kỳ", "United States")
                .replace("Khuyên dùng", "Recommended")
                .replace("Dự phòng", "Backup")
                .replace("Trục cáp quang", "Fiber Backbone")
                .replace("Cụm máy chủ", "Server Cluster")
                .replace("Độ trễ thấp cho VN", "Low Latency Regional Route")
                .replace("Kết nối khu vực Đông Nam Á", "Asia-Pacific Regional Hub")
                .replace("Đầu mối quốc tế tốc độ cao", "High-Speed International Node")
                .replace("Mạng lưới toàn cầu", "Global Edge Network")
                .replace("Cổng kết nối ổn định", "Stable Gateway")
                .replace("Proxy cá nhân", "Custom proxy")
                .replace("Máy chủ SOCKS5 do người dùng tự thêm", "User-added custom SOCKS5 proxy")
                .replace("Máy chủ HTTP do người dùng tự thêm", "User-added custom HTTP proxy")
                .replace("do người dùng tự thêm", "User-added custom proxy")
            cachedDescEn = en
            en
        }
    }

    companion object {
        private val PORT_BULLET_REGEX = Regex("\\s*•\\s*Cổng\\s*:\\d+")
        private val PORT_PAREN_WORD_REGEX = Regex("\\s*\\(Cổng\\s*:\\d+\\)")
        private val PORT_PAREN_SHORT_REGEX = Regex("\\s*\\(:\\d+\\)")
    }
}

// 9 máy chủ Bypass dự phòng gọn nhẹ tích hợp sẵn (3 JP, 3 KR, 3 US).
// Danh sách Bypass đầy đủ & danh sách Proxy được tự động tải từ GitHub (docs/servers.json) và lưu vào máy.
val DEFAULT_SERVERS = listOf(
    // === NHẬT BẢN (JAPAN) - 3 MÁY CHỦ BYPASS DỰ PHÒNG ===
    ServerLocation(
        id = "jp_tsukuba_softether_7",
        name = "Japan Tsukuba #7",
        flag = "🇯🇵",
        ip = "219.100.37.197",
        port = 443,
        protocol = "http",
        azureEndpoint = "japaneast.cloudapp.azure.com",
        description = "Đại học Tsukuba Node • AS4713 Tokyo (Khuyên dùng #1)",
        speedMbps = 0,
        countryCode = "JP",
        kind = ServerKind.BYPASS
    ),
    ServerLocation(
        id = "jp_tsukuba_softether_8",
        name = "Japan Tsukuba #8",
        flag = "🇯🇵",
        ip = "219.100.37.23",
        port = 443,
        protocol = "http",
        azureEndpoint = "japaneast.cloudapp.azure.com",
        description = "Đại học Tsukuba Backbone • AS4713 Tokyo (Khuyên dùng #2)",
        speedMbps = 0,
        countryCode = "JP",
        kind = ServerKind.BYPASS
    ),
    ServerLocation(
        id = "jp_tsukuba_softether_9",
        name = "Japan Tsukuba #9",
        flag = "🇯🇵",
        ip = "219.100.37.11",
        port = 443,
        protocol = "http",
        azureEndpoint = "japaneast.cloudapp.azure.com",
        description = "Đại học Tsukuba Transit • AS4713 Tokyo (Khuyên dùng #3)",
        speedMbps = 0,
        countryCode = "JP",
        kind = ServerKind.BYPASS
    ),

    // === HÀN QUỐC (SOUTH KOREA) - 3 MÁY CHỦ BYPASS DỰ PHÒNG ===
    ServerLocation(
        id = "kr_seoul_kt_1",
        name = "South Korea Incheon SSL #1",
        flag = "🇰🇷",
        ip = "49.165.237.183",
        port = 995,
        protocol = "http",
        azureEndpoint = "koreacentral.cloudapp.azure.com",
        description = "Incheon Korea Telecom SSL Backbone (Khuyên dùng #1)",
        speedMbps = 0,
        countryCode = "KR",
        kind = ServerKind.BYPASS
    ),
    ServerLocation(
        id = "kr_incheon_5",
        name = "South Korea Incheon #5",
        flag = "🇰🇷",
        ip = "49.165.136.156",
        port = 1828,
        protocol = "http",
        azureEndpoint = "koreacentral.cloudapp.azure.com",
        description = "Incheon Gyeonggi Fiber Gateway (Khuyên dùng #2)",
        speedMbps = 0,
        countryCode = "KR",
        kind = ServerKind.BYPASS
    ),
    ServerLocation(
        id = "kr_seoul_kt_2",
        name = "South Korea Seoul KT #2",
        flag = "🇰🇷",
        ip = "123.143.79.15",
        port = 1980,
        protocol = "http",
        azureEndpoint = "koreacentral.cloudapp.azure.com",
        description = "Seoul SK/KT Fiber Backbone (Khuyên dùng #3)",
        speedMbps = 0,
        countryCode = "KR",
        kind = ServerKind.BYPASS
    ),

    // === HOA KỲ (UNITED STATES) - 3 MÁY CHỦ BYPASS DỰ PHÒNG ===
    ServerLocation(
        id = "us_california_1",
        name = "United States Silicon Valley #1",
        flag = "🇺🇸",
        ip = "34.44.49.215",
        port = 80,
        protocol = "http",
        azureEndpoint = "westus.cloudapp.azure.com",
        description = "California Cloud Backbone",
        speedMbps = 0,
        countryCode = "US",
        kind = ServerKind.BYPASS
    ),
    ServerLocation(
        id = "us_west_oregon_2",
        name = "United States Oregon #2",
        flag = "🇺🇸",
        ip = "34.43.46.91",
        port = 443,
        protocol = "http",
        azureEndpoint = "westus2.cloudapp.azure.com",
        description = "Oregon Cloud Backbone",
        speedMbps = 0,
        countryCode = "US",
        kind = ServerKind.BYPASS
    ),
    ServerLocation(
        id = "us_central_iowa_3",
        name = "United States Central #3",
        flag = "🇺🇸",
        ip = "130.127.204.197",
        port = 8080,
        protocol = "http",
        azureEndpoint = "centralus.cloudapp.azure.com",
        description = "Clemson Research Backbone",
        speedMbps = 0,
        countryCode = "US",
        kind = ServerKind.BYPASS
    )
)

enum class SmartVpnPhase {
    IDLE,                      // Trạng thái chờ / kiểm tra IP
    BYPASS_ACTIVE,             // Giai đoạn 1: Đang BẬT VPN/Bypass để vượt rào vùng (duyệt catalog, mở game)
    DIRECT_GAMING,             // Giai đoạn 2: Đã TỰ ĐỘNG TẮT VPN khi vào trận (Mạng Direct ISP active - 0ms phụ trợ)
    DIRECT_SUPPORTED_REGION    // Người chơi ở sẵn khu vực hỗ trợ (Nhật, Hàn, Mỹ...), không cần bật VPN
}

@Immutable
data class SmartVpnDecision(
    val checked: Boolean = false,
    val needsVpn: Boolean = true,
    val userCountryCode: String = "--",
    val userCountryName: String = "Global",
    val isSupportedRegion: Boolean = false,
    val message: String = "Checking network region...",
    val actionSummary: String = "Auto: ON at launch ➔ OFF in-game"
)

@Immutable
data class StreamTelemetry(
    val isStreaming: Boolean = false,
    val fps: Int = 0,
    val bitrateMbps: Float = 0.0f,
    val rttMs: Int = 0,
    val jitterMs: Int = 0,
    val packetsLostPercent: Float = 0.0f,
    val framesDropped: Int = 0,
    val resolution: String = "1280x720",
    val codec: String = "H.264",
    val audioCodec: String = "Opus Stereo 48kHz",
    val qualityScore: Float = 0.0f
)

@Immutable
data class SystemBenchmarkResult(
    val isRunning: Boolean = false,
    val completed: Boolean = false,
    val dnsResolveMs: Int = 0,
    val azureTokyoPingMs: Int = 0,
    val directIspPingMs: Int = 0,
    val jitterMs: Int = 0,
    val packetLossPercent: Float = 0.0f,
    val hardwareDecoderOk: Boolean = true,
    val systemScore: Int = 0,
    val summary: String = "Chưa chạy đo kiểm đường truyền"
)

@Immutable
data class VpnConnectionState(
    val mode: NetworkMode = NetworkMode.DISCONNECTED,
    val engine: VpnEngine = VpnEngine.SMART_BYPASS,
    val isConnecting: Boolean = false,
    val currentServer: ServerLocation = DEFAULT_SERVERS[0],
    val latencyMs: Int? = null,
    val directLatencyMs: Int? = null,
    val pingHistory: List<Int> = emptyList(),
    val autoBypassTriggered: Boolean = false,
    val statusMessage: String = "Sẵn sàng kết nối",
    val lastError: String? = null,
    val detectedGeoIp: GeoIpResult? = null,
    val isBypassActiveInBrowser: Boolean = false,
    val customServers: List<ServerLocation> = emptyList(),
    val liveVerifiedServers: List<ServerLocation> = emptyList(),
    val isAutoSyncingLiveProxies: Boolean = false,
    val lastAutoSyncTimestampMs: Long = 0L,
    val autoFilteredOutErrorCount: Int = 0,
    val autoSwitchedBackupCount: Int = 0,
    val smartVpnPhase: SmartVpnPhase = SmartVpnPhase.IDLE,
    val smartDecision: SmartVpnDecision = SmartVpnDecision(),
    val telemetry: StreamTelemetry = StreamTelemetry(),
    val benchmark: SystemBenchmarkResult = SystemBenchmarkResult(),
    val unlockRecords: Map<String, ServerUnlockRecord> = emptyMap(),
    val unlockFailPromptServer: ServerLocation? = null,
    val unlockFailSuggestedServer: ServerLocation? = null,
    val unlockFailSuggestedPingMs: Int? = null,
    val unlockFailConsecutiveCount: Int = 0,
    val unlockFailAutoSwitchedFromServer: ServerLocation? = null,
    val serverPings: Map<String, Int> = emptyMap(),
    val serverMeasurements: Map<String, EndpointPingMeasurement> = emptyMap(),
    val selectedServerProbeEstimate: GlobalpingProbeEstimate? = null,
    val isTestingPings: Boolean = false,
    val activeStreamServerId: String? = null,
    val userManuallySelectedServer: Boolean = false,
    val lastAutoSelectTimestampMs: Long = 0L,
    val preferredVpnSubEngine: VpnEngine = VpnEngine.PROXY_TUNNEL,
    val securityBlockedServerKeys: Set<String> = emptySet(),
    val securityBlockAlert: VpnSecurityAlert? = null,
    val remoteBypassServers: List<ServerLocation> = emptyList(),
    val remoteProxySeeds: List<ServerLocation> = emptyList(),
    val isSyncingRemoteCatalog: Boolean = false,
    val remoteCatalogSourceLabel: String? = null,
    val lastRemoteCatalogSyncMs: Long = 0L,
    val isExternalSystemVpnDetected: Boolean = false
) {
    val isVpnModeSelected: Boolean
        get() = engine != VpnEngine.SMART_BYPASS
}
