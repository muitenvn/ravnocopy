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
        assertTrue(
            "All proxyServers must be tagged ServerKind.PROXY",
            data.proxyServers.all { it.kind == ServerKind.PROXY }
        )
        assertTrue("Catalog must contain at least 5 HTTPS proxySources", data.proxySources.size >= 5)
        assertTrue(
            "Every proxySource urlTemplate must be HTTPS",
            data.proxySources.all { it.urlTemplate.startsWith("https://") }
        )
        // Verify no Bypass server accidentally shares an endpoint with a Proxy seed
        val bypassKeys = data.bypassServers.map { it.addressPortKey.lowercase() }.toSet()
        val proxyKeys = data.proxyServers.map { it.addressPortKey.lowercase() }.toSet()
        assertTrue(
            "Bypass and Proxy server pools must not overlap",
            bypassKeys.intersect(proxyKeys).isEmpty()
        )
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
    fun vpnManager_strictlySeparatesBypassAndProxyPools_andKeepsBypassClean() {
        VpnManager.setEngine(VpnEngine.SMART_BYPASS, null)
        val bypassBefore = VpnManager.state.value.currentServer

        val bypassServers = VpnManager.getServersForEngine(VpnEngine.SMART_BYPASS)
        assertTrue("Bypass pool must never be empty (built-in 9 fallback nodes)", bypassServers.size >= 9)
        assertTrue("Bypass pool must ONLY contain BYPASS servers", bypassServers.all { it.kind == ServerKind.BYPASS })

        val proxyServersBefore = VpnManager.getServersForEngine(VpnEngine.PROXY_TUNNEL)
        assertTrue(
            "Proxy pool must NEVER contain any BYPASS server",
            proxyServersBefore.none { it.kind == ServerKind.BYPASS }
        )
        val defaultBypassKeys = DEFAULT_SERVERS.map { it.addressPortKey.lowercase() }.toSet()
        assertTrue(
            "Proxy pool must NEVER include DEFAULT_SERVERS Bypass nodes",
            proxyServersBefore.none { it.addressPortKey.lowercase() in defaultBypassKeys }
        )

        // Adding a custom proxy while in SMART_BYPASS MUST NOT overwrite Bypass currentServer
        val customProxy = ServerLocation(
            id = "test_custom_proxy_jp",
            name = "Custom Test Proxy",
            flag = "🇯🇵",
            ip = "45.88.99.10",
            port = 8080,
            protocol = "http",
            azureEndpoint = "japaneast.cloudapp.azure.com",
            description = "Test custom proxy",
            countryCode = "JP",
            isCustom = true,
            kind = ServerKind.PROXY
        )
        VpnManager.addCustomServer(customProxy)
        assertEquals(
            "Bypass currentServer must NOT be overwritten when adding custom proxy in Bypass mode",
            bypassBefore.id,
            VpnManager.state.value.currentServer.id
        )
        assertTrue(
            "Custom proxy must appear in Proxy pool",
            VpnManager.getProxyServers().any { it.id == customProxy.id }
        )
        // Clean up custom proxy after test
        VpnManager.removeCustomServer(customProxy.id)

        // Ensure proxy-clean guard works cleanly in Bypass mode without throwing
        VpnManager.ensureWebViewProxyCleanIfNotProxyEngine()
        assertEquals(VpnEngine.SMART_BYPASS, VpnManager.state.value.engine)
    }
}
