package com.example.vpn

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

class ProxyAndCatalogUnitTest {

    @Test
    fun docsServersJson_isValidAndStrictlySeparatesBypassAndProxy() {
        val candidates = listOf(
            File("docs/servers.json"),
            File("../docs/servers.json")
        )
        val file = candidates.firstOrNull { it.exists() }
        assertTrue("docs/servers.json must exist in repository", file != null)
        val raw = file!!.readText()

        val parsed = RemoteServerCatalog.parseCatalogJson(raw, "test_docs")
        assertNotNull("docs/servers.json must parse cleanly", parsed)
        val data = parsed!!
        assertTrue("Catalog must contain Bypass servers", data.bypassServers.size >= 9)
        assertTrue(
            "All bypassServers must be tagged ServerKind.BYPASS",
            data.bypassServers.all { it.kind == ServerKind.BYPASS }
        )
        assertTrue("Bypass-only catalog must have empty proxyServers", data.proxyServers.isEmpty())
    }

    @Test
    fun parseCatalogJson_rejectsPrivateIpsDisallowedPortsAndBadCountries() {
        val maliciousJson = """
            {
              "schemaVersion": 1,
              "updatedAt": "2026-11-26",
              "bypassServers": [
                {"id":"ok_jp","name":"Tokyo Good","ip":"153.122.90.11","port":443,"countryCode":"JP"},
                {"id":"bad_loopback","name":"Loopback","ip":"127.0.0.1","port":443,"countryCode":"JP"},
                {"id":"bad_lan","name":"LAN","ip":"192.168.1.10","port":443,"countryCode":"US"},
                {"id":"bad_port","name":"SSH","ip":"153.122.90.12","port":22,"countryCode":"JP"},
                {"id":"bad_country","name":"Unknown","ip":"153.122.90.13","port":443,"countryCode":"VN"}
              ],
              "proxyServers": [
                {"id":"ok_proxy","name":"JP Proxy","ip":"219.100.37.241","port":8080,"protocol":"http","countryCode":"JP"},
                {"id":"bad_proxy_lan","name":"LAN Proxy","ip":"10.0.0.5","port":8080,"protocol":"http","countryCode":"US"},
                {"id":"bad_proxy_port","name":"DB Proxy","ip":"219.100.37.242","port":3306,"protocol":"socks5","countryCode":"KR"}
              ],
              "proxySources": [
                {"id":"ok_v4","type":"proxyscrape_v4_json","protocol":"http","urlTemplate":"https://api.proxyscrape.com/v4/free-proxy-list/get?format=json"},
                {"id":"ok_pf","type":"proxifly_json","protocol":"http","urlTemplate":"https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/http/data.json"},
                {"id":"bad_http","type":"plain_ip_port","protocol":"http","urlTemplate":"http://insecure.example.com/list"}
              ]
            }
        """.trimIndent()

        val parsed = RemoteServerCatalog.parseCatalogJson(maliciousJson, "security_test")
        assertNotNull("Valid JSON envelope must parse", parsed)
        val data = parsed!!

        assertEquals(1, data.bypassServers.size)
        assertEquals("ok_jp", data.bypassServers.first().id)
        assertEquals(ServerKind.BYPASS, data.bypassServers.first().kind)

        assertEquals(1, data.proxyServers.size)
        assertEquals("ok_proxy", data.proxyServers.first().id)
        assertEquals(ServerKind.PROXY, data.proxyServers.first().kind)

        assertEquals(2, data.proxySources.size)
        assertEquals(listOf("ok_v4", "ok_pf"), data.proxySources.map { it.id })
    }

    @Test
    fun liveProxyFetcher_parsesAllFourFeedFormatsCleanlyWithMoshi() {
        val v4Json = """
            {
              "proxies": [
                {"ip":"45.12.34.56","port":8080,"protocol":"http","alive":true,"timeout":420.5,"ip_data":{"countryCode":"JP"}},
                {"ip":"45.12.34.57","port":8080,"protocol":"http","alive":false,"timeout":100.0,"ip_data":{"countryCode":"JP"}},
                {"ip":"192.168.1.1","port":8080,"protocol":"http","alive":true,"timeout":10.0,"ip_data":{"countryCode":"US"}}
              ]
            }
        """.trimIndent()
        val v4 = LiveProxyFetcher.parseProxyScrapeV4Json(v4Json)
        assertEquals(1, v4.size)
        assertEquals("45.12.34.56", v4.first().ip)
        assertEquals(8080, v4.first().port)
        assertEquals("JP", v4.first().countryCode)

        val proxiflyJson = """
            [
              {"ip":"104.20.11.22","port":3128,"protocol":"http","https":true,"geolocation":{"country":"US"}},
              {"ip":"127.0.0.1","port":3128,"protocol":"http","https":true,"geolocation":{"country":"US"}}
            ]
        """.trimIndent()
        val pf = LiveProxyFetcher.parseProxiflyJson(proxiflyJson)
        assertEquals(1, pf.size)
        assertEquals("104.20.11.22", pf.first().ip)
        assertEquals("US", pf.first().countryCode)

        val geonodeJson = """
            {
              "data": [
                {"ip":"203.11.22.33","port":"8888","country":"KR","latency":210.4,"protocols":["http"]},
                {"ip":"10.0.0.2","port":"8888","country":"KR","latency":10.0,"protocols":["http"]}
              ]
            }
        """.trimIndent()
        val gn = LiveProxyFetcher.parseGeoNodeJson(geonodeJson)
        assertEquals(1, gn.size)
        assertEquals("203.11.22.33", gn.first().ip)
        assertEquals(8888, gn.first().port)
        assertEquals("KR", gn.first().countryCode)

        val plain = LiveProxyFetcher.parsePlainIpPortText(
            rawText = "http://91.10.20.30:8080\n91.10.20.31:3128\n127.0.0.1:8080\n# comment",
            protocol = "http",
            defaultCc = "JP"
        )
        assertEquals(2, plain.size)
        assertEquals("91.10.20.30", plain[0].ip)
        assertEquals("91.10.20.31", plain[1].ip)
    }

    @Test
    fun verifyRealProxyEndpoint_acceptsHttpConnect200AndRejectsBrokenTlsOrNonProxy() = runBlocking {
        // 1. Valid HTTP CONNECT proxy returning 200 (raw CONNECT handshake check)
        ServerSocket(0).use { server ->
            val port = server.localPort
            val worker = thread(start = true) {
                server.soTimeout = 2500
                server.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    val requestLine = reader.readLine() ?: ""
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val out = client.getOutputStream()
                    if (requestLine.startsWith("CONNECT www.xbox.com:443")) {
                        out.write("HTTP/1.1 200 Connection established\r\nProxy-Agent: test\r\n\r\n".toByteArray())
                    } else {
                        out.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                    }
                    out.flush()
                }
            }

            val res = PingTester.verifyRealProxyEndpoint(
                host = "127.0.0.1",
                port = port,
                protocol = "http",
                timeoutMs = 1500,
                verifyTlsAndHttp = false
            )
            worker.join(2000)
            assertTrue("Expected valid HTTP CONNECT proxy to pass: ${res.statusDetail}", res.ok)
            assertTrue("Expected positive RTT measurement", res.rttMs >= 1)
        }

        // 2. Fake/broken proxy returning HTTP 200 then closing/non-TLS MUST be rejected when verifyTlsAndHttp = true
        ServerSocket(0).use { server ->
            val port = server.localPort
            val worker = thread(start = true) {
                server.soTimeout = 2500
                server.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val out = client.getOutputStream()
                    out.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                    out.flush()
                    // Immediately drops without real TLS tunnel to Xbox
                }
            }

            val res = PingTester.verifyRealProxyEndpoint(
                host = "127.0.0.1",
                port = port,
                protocol = "http",
                timeoutMs = 1500,
                verifyTlsAndHttp = true
            )
            worker.join(2000)
            assertFalse("Broken proxy that fails real TLS tunnel MUST be rejected", res.ok)
            assertEquals(-1, res.rttMs)
        }

        // 3. HTTP 403 / non-proxy / OpenVPN server MUST be rejected
        ServerSocket(0).use { server ->
            val port = server.localPort
            val worker = thread(start = true) {
                server.soTimeout = 2500
                server.accept().use { client ->
                    val out = client.getOutputStream()
                    out.write("HTTP/1.1 403 Forbidden\r\n\r\n".toByteArray())
                    out.flush()
                }
            }

            val res = PingTester.verifyRealProxyEndpoint(
                host = "127.0.0.1",
                port = port,
                protocol = "http",
                timeoutMs = 1500,
                verifyTlsAndHttp = false
            )
            worker.join(2000)
            assertFalse("HTTP 403 must be rejected as non-working proxy", res.ok)
            assertEquals(-1, res.rttMs)
        }
    }

    @Test
    fun verifyRealProxyEndpoint_acceptsValidSocks5AndRejectsRejectedAuth() = runBlocking {
        // 1. Valid SOCKS5 no-auth greeting + CONNECT success
        ServerSocket(0).use { server ->
            val port = server.localPort
            val worker = thread(start = true) {
                server.soTimeout = 2500
                server.accept().use { client ->
                    val input = client.getInputStream()
                    val output = client.getOutputStream()
                    val greeting = ByteArray(3)
                    input.read(greeting)
                    assertEquals(0x05.toByte(), greeting[0])
                    output.write(byteArrayOf(0x05, 0x00))
                    output.flush()

                    val reqHead = ByteArray(5)
                    input.read(reqHead)
                    val hostLen = reqHead[4].toInt() and 0xFF
                    val rest = ByteArray(hostLen + 2)
                    input.read(rest)

                    // Reply success: VER=5, REP=0, RSV=0, ATYP=1, IP=0.0.0.0, PORT=0
                    output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                }
            }

            val res = PingTester.verifyRealProxyEndpoint(
                host = "127.0.0.1",
                port = port,
                protocol = "socks5",
                timeoutMs = 1500,
                verifyTlsAndHttp = false
            )
            worker.join(2000)
            assertTrue("Expected valid SOCKS5 proxy to pass: ${res.statusDetail}", res.ok)
            assertTrue("Expected positive RTT measurement", res.rttMs >= 1)
        }

        // 2. SOCKS5 auth rejection (0x05 0xFF) MUST fail
        ServerSocket(0).use { server ->
            val port = server.localPort
            val worker = thread(start = true) {
                server.soTimeout = 2500
                server.accept().use { client ->
                    val input = client.getInputStream()
                    val output = client.getOutputStream()
                    input.read(ByteArray(3))
                    output.write(byteArrayOf(0x05, 0xFF.toByte()))
                    output.flush()
                }
            }

            val res = PingTester.verifyRealProxyEndpoint(
                host = "127.0.0.1",
                port = port,
                protocol = "socks5",
                timeoutMs = 1500,
                verifyTlsAndHttp = false
            )
            worker.join(2000)
            assertFalse("SOCKS5 auth rejection must fail verification", res.ok)
            assertEquals(-1, res.rttMs)
        }
    }

    @Test
    fun vpnManager_enforcesBypassOnlyModeAndNeverExposesProxyServers() {
        // Attempting to switch to PROXY_TUNNEL or EXTERNAL_VPN must always stay on SMART_BYPASS
        VpnManager.setEngine(VpnEngine.PROXY_TUNNEL, null)
        assertEquals(VpnEngine.SMART_BYPASS, VpnManager.state.value.engine)

        VpnManager.setEngine(VpnEngine.EXTERNAL_VPN, null)
        assertEquals(VpnEngine.SMART_BYPASS, VpnManager.state.value.engine)

        VpnManager.activateVpnMode(null, VpnEngine.PROXY_TUNNEL)
        assertEquals(VpnEngine.SMART_BYPASS, VpnManager.state.value.engine)

        val bypassServers = VpnManager.getServersForEngine(VpnEngine.SMART_BYPASS)
        assertTrue("Bypass pool must never be empty (built-in 9 fallback nodes)", bypassServers.size >= 9)
        assertTrue("Bypass pool must ONLY contain BYPASS servers", bypassServers.all { it.kind == ServerKind.BYPASS })

        val serversViaLegacyProxyArg = VpnManager.getServersForEngine(VpnEngine.PROXY_TUNNEL)
        assertTrue("All server queries must return Bypass servers only", serversViaLegacyProxyArg.all { it.kind == ServerKind.BYPASS })
        assertEquals(ServerKind.BYPASS, VpnManager.state.value.currentServer.kind)
        assertFalse("In-app proxy security guard must stay inactive in Bypass-only mode", VpnManager.isInAppProxySecurityGuardActive())

        VpnManager.ensureWebViewProxyCleanIfNotProxyEngine()
        assertEquals(VpnEngine.SMART_BYPASS, VpnManager.state.value.engine)
    }

    @Test
    fun xboxBridgeInjectionScript_preservesHeadersSupportsMultiRegionAndDisablesOnDirectNetwork() {
        val activeScript = com.example.cloudplay.XboxBridge.getInjectionScript(
            clarityBoost = false,
            targetCountry = "US",
            targetIp = "34.43.46.91",
            targetAzureEndpoint = "westus2.cloudapp.azure.com",
            bypassEnabled = true
        )
        assertTrue("Must normalize Headers safely via new Headers()", activeScript.contains("new Headers("))
        assertTrue("Must inject X-Forwarded-For cleanly", activeScript.contains("'X-Forwarded-For'"))
        assertFalse("Must NOT inject CORS-breaking X-Client-IP", activeScript.contains("X-Client-IP"))
        assertFalse("Must NOT inject CORS-breaking X-Real-IP", activeScript.contains("X-Real-IP"))
        assertTrue("Must include targetAzureEndpoint", activeScript.contains("westus2.cloudapp.azure.com"))
        assertTrue("Must include 30-country region priority table", activeScript.contains("COUNTRY_REGION_PRIORITIES"))
        assertTrue("Must detect instant network-layer 403/451 UNLOCK_FAIL", activeScript.contains("response.status === 403 || response.status === 451"))

        val directScript = com.example.cloudplay.XboxBridge.getInjectionScript(
            clarityBoost = false,
            targetCountry = "JP",
            targetIp = "219.100.37.197",
            targetAzureEndpoint = "japaneast.cloudapp.azure.com",
            bypassEnabled = false
        )
        assertTrue("When bypassEnabled=false, bypassEnabled flag must be false", directScript.contains("let bypassEnabled = false;"))
        assertTrue("When bypassEnabled=false, bypassIp must be empty", directScript.contains("let bypassIp = \"\";"))
    }

    @Test
    fun parseCatalogJson_acceptsHunterMetadataFieldsCleanly() {
        val hunterJson = """
            {
              "schemaVersion": 1,
              "updatedAt": "2026-11-26T08:00:00Z",
              "bypassServers": [
                {
                  "id": "gh_auto_jp_153_122_10_5_443",
                  "name": "Japan Tokyo #1",
                  "flag": "🇯🇵",
                  "ip": "153.122.10.5",
                  "port": 443,
                  "azureEndpoint": "japaneast.cloudapp.azure.com",
                  "description": "NTT Communications • GeoIP 2x • Top #1",
                  "countryCode": "JP",
                  "verifiedAt": "2026-11-26T08:00:00Z",
                  "latencyMs": 42,
                  "source": "vpngate"
                }
              ],
              "proxyServers": [],
              "proxySources": []
            }
        """.trimIndent()
        val parsed = RemoteServerCatalog.parseCatalogJson(hunterJson, "hunter_test")
        assertNotNull("Hunter catalog JSON must parse cleanly", parsed)
        assertEquals(1, parsed!!.bypassServers.size)
        assertEquals("153.122.10.5", parsed.bypassServers.first().ip)
        assertEquals("japaneast.cloudapp.azure.com", parsed.bypassServers.first().azureEndpoint)
        assertEquals(ServerKind.BYPASS, parsed.bypassServers.first().kind)
    }
}

