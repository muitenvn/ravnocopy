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
        assertTrue("Catalog must contain proxySources", data.proxySources.isNotEmpty())
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
                {"id":"ok_src","type":"plain_ip_port","protocol":"http","urlTemplate":"https://api.proxyscrape.com/v2/?request=getproxies"},
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

        assertEquals(1, data.proxySources.size)
        assertEquals("ok_src", data.proxySources.first().id)
    }

    @Test
    fun verifyRealProxyEndpoint_acceptsHttpConnect200AndRejectsNonProxy() = runBlocking {
        // 1. Valid HTTP CONNECT proxy returning 200
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
                        out.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
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
                timeoutMs = 1500
            )
            worker.join(2000)
            assertTrue("Expected valid HTTP CONNECT proxy to pass: ${res.statusDetail}", res.ok)
            assertTrue("Expected positive RTT measurement", res.rttMs >= 1)
        }

        // 2. HTTP 403 / non-proxy / OpenVPN server MUST be rejected
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
                timeoutMs = 1500
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
                timeoutMs = 1500
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
                timeoutMs = 1500
            )
            worker.join(2000)
            assertFalse("SOCKS5 auth rejection must fail verification", res.ok)
            assertEquals(-1, res.rttMs)
        }
    }

    @Test
    fun vpnManager_strictlySeparatesBypassAndProxyPools() {
        val bypassServers = VpnManager.getServersForEngine(VpnEngine.SMART_BYPASS)
        assertTrue("Bypass pool must never be empty (built-in 9 fallback nodes)", bypassServers.size >= 9)
        assertTrue("Bypass pool must ONLY contain BYPASS servers", bypassServers.all { it.kind == ServerKind.BYPASS })

        val proxyServers = VpnManager.getServersForEngine(VpnEngine.PROXY_TUNNEL)
        assertTrue(
            "Proxy pool must NEVER contain any BYPASS server",
            proxyServers.none { it.kind == ServerKind.BYPASS }
        )
        val defaultBypassKeys = DEFAULT_SERVERS.map { it.addressPortKey.lowercase() }.toSet()
        assertTrue(
            "Proxy pool must NEVER include DEFAULT_SERVERS Bypass nodes",
            proxyServers.none { it.addressPortKey.lowercase() in defaultBypassKeys }
        )
    }
}
