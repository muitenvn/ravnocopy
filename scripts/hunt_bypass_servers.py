#!/usr/bin/env python3
"""
Ravno — Experimental Continuous Bypass Server Hunter (GitHub Actions)

Runs continuously up to a configurable time budget (default 9 minutes inside a
10-12 minute GitHub Actions job) to discover, verify, score, and rank clean,
fast, geo-verified public IPv4 endpoints in Xbox Cloud Gaming supported regions
for Smart Bypass (X-Forwarded-For), then saves the verified catalog to
`docs/servers.json` (consumed directly by Android `RemoteServerCatalog`).

Zero external dependencies (Python 3 standard library only).
"""

from __future__ import annotations

import argparse
import base64
import csv
import datetime as dt
import io
import ipaddress
import json
import os
import re
import socket
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Optional, Sequence, Set, Tuple

# Exact match with GeoIpDetector.SUPPORTED_COUNTRIES (30 Xbox Cloud Gaming countries)
SUPPORTED_COUNTRIES: Set[str] = {
    "JP", "US", "KR", "DE", "GB", "FR", "ES", "IT", "PT", "NL", "BE", "AT", "CH", "IE",
    "AU", "NZ", "CA", "SE", "NO", "DK", "FI", "PL", "CZ", "HU", "SK", "BR", "MX", "AR", "CL", "CO",
}

# Exact match with LiveProxyFetcher.DISALLOWED_PORTS
DISALLOWED_PORTS: Set[int] = {
    21, 22, 23, 25, 53, 69, 110, 111, 135, 137, 138, 139,
    143, 161, 389, 445, 512, 513, 514, 1433, 1521, 2049,
    3306, 3389, 5432, 5900, 6379, 11211, 27017,
}

PREFERRED_SAFE_PORTS: Set[int] = {443, 80, 995, 8443, 8080, 1194, 1443, 1557, 1819, 1828, 1980}

PRIORITY_COUNTRY_WEIGHTS: Dict[str, int] = {
    "JP": 32,
    "KR": 28,
    "US": 24,
    "AU": 16,
    "GB": 14,
    "DE": 14,
    "NL": 12,
    "FR": 12,
    "CA": 12,
    "BR": 10,
}

COUNTRY_QUOTAS: Dict[str, int] = {
    "JP": 24,
    "KR": 16,
    "US": 14,
}
OTHER_COUNTRIES_QUOTA = 10
MAX_PER_SUBNET_24 = 3
MAX_TOTAL_SERVERS = 64

AZURE_ENDPOINT_BY_COUNTRY: Dict[str, str] = {
    "JP": "japaneast.cloudapp.azure.com",
    "KR": "koreacentral.cloudapp.azure.com",
    "US": "westus2.cloudapp.azure.com",
    "CA": "eastus2.cloudapp.azure.com",
    "AU": "australiaeast.cloudapp.azure.com",
    "NZ": "australiaeast.cloudapp.azure.com",
    "GB": "uksouth.cloudapp.azure.com",
    "IE": "uksouth.cloudapp.azure.com",
    "DE": "westeurope.cloudapp.azure.com",
    "FR": "westeurope.cloudapp.azure.com",
    "NL": "westeurope.cloudapp.azure.com",
    "BE": "westeurope.cloudapp.azure.com",
    "AT": "westeurope.cloudapp.azure.com",
    "CH": "westeurope.cloudapp.azure.com",
    "ES": "westeurope.cloudapp.azure.com",
    "PT": "westeurope.cloudapp.azure.com",
    "IT": "westeurope.cloudapp.azure.com",
    "SE": "northeurope.cloudapp.azure.com",
    "NO": "northeurope.cloudapp.azure.com",
    "DK": "northeurope.cloudapp.azure.com",
    "FI": "northeurope.cloudapp.azure.com",
    "PL": "westeurope.cloudapp.azure.com",
    "CZ": "westeurope.cloudapp.azure.com",
    "HU": "westeurope.cloudapp.azure.com",
    "SK": "westeurope.cloudapp.azure.com",
    "BR": "brazilsouth.cloudapp.azure.com",
    "MX": "southcentralus.cloudapp.azure.com",
    "AR": "brazilsouth.cloudapp.azure.com",
    "CL": "brazilsouth.cloudapp.azure.com",
    "CO": "eastus2.cloudapp.azure.com",
}

COUNTRY_DISPLAY_NAME: Dict[str, str] = {
    "JP": "Japan",
    "KR": "South Korea",
    "US": "United States",
    "AU": "Australia",
    "NZ": "New Zealand",
    "CA": "Canada",
    "GB": "United Kingdom",
    "IE": "Ireland",
    "DE": "Germany",
    "FR": "France",
    "NL": "Netherlands",
    "BE": "Belgium",
    "AT": "Austria",
    "CH": "Switzerland",
    "ES": "Spain",
    "PT": "Portugal",
    "IT": "Italy",
    "SE": "Sweden",
    "NO": "Norway",
    "DK": "Denmark",
    "FI": "Finland",
    "PL": "Poland",
    "CZ": "Czechia",
    "HU": "Hungary",
    "SK": "Slovakia",
    "BR": "Brazil",
    "MX": "Mexico",
    "AR": "Argentina",
    "CL": "Chile",
    "CO": "Colombia",
}

BLOCKED_NETWORKS: Tuple[ipaddress.IPv4Network, ...] = (
    ipaddress.IPv4Network("0.0.0.0/8"),
    ipaddress.IPv4Network("10.0.0.0/8"),
    ipaddress.IPv4Network("100.64.0.0/10"),      # CGNAT
    ipaddress.IPv4Network("127.0.0.0/8"),        # Loopback
    ipaddress.IPv4Network("169.254.0.0/16"),     # Link-local
    ipaddress.IPv4Network("172.16.0.0/12"),      # Private
    ipaddress.IPv4Network("192.0.0.0/24"),       # IETF Protocol
    ipaddress.IPv4Network("192.0.2.0/24"),       # TEST-NET-1
    ipaddress.IPv4Network("192.168.0.0/16"),     # Private
    ipaddress.IPv4Network("198.18.0.0/15"),      # Benchmark
    ipaddress.IPv4Network("198.51.100.0/24"),    # TEST-NET-2
    ipaddress.IPv4Network("203.0.113.0/24"),     # TEST-NET-3
    ipaddress.IPv4Network("224.0.0.0/4"),        # Multicast
    ipaddress.IPv4Network("240.0.0.0/4"),        # Reserved / Broadcast
)

OVPN_REMOTE_RE = re.compile(r"^\s*remote\s+([0-9.]+)\s+(\d{1,5})\b", re.MULTILINE)
USER_AGENT = "Ravno-BypassHunter/1.0 (+https://github.com/muitenvn/Ravno-App)"


@dataclass
class Candidate:
    ip: str
    port: int
    claimed_country: str
    source: str
    name_hint: str = ""
    description_hint: str = ""
    azure_hint: str = ""
    reputation_bonus: int = 0
    existing_id: str = ""


@dataclass
class VerifiedNode:
    ip: str
    port: int
    country: str
    source: str
    name_hint: str
    description_hint: str
    azure_endpoint: str
    existing_id: str
    median_rtt_ms: int
    jitter_ms: int
    probe_ok: int
    probe_total: int
    geo_verified: bool
    geo_sources: int
    city: str = ""
    isp: str = ""
    reputation_bonus: int = 0
    waves_passed: int = 1
    score: float = 0.0
    verified_at: str = ""


def country_flag(code: str) -> str:
    clean = code.strip().upper()
    if clean == "UK":
        clean = "GB"
    if len(clean) != 2 or not clean.isalpha():
        return "🌐"
    return chr(0x1F1E6 + ord(clean[0]) - ord("A")) + chr(0x1F1E6 + ord(clean[1]) - ord("A"))


def subnet_24(ip: str) -> str:
    parts = ip.strip().split(".")
    return ".".join(parts[:3]) if len(parts) == 4 else ip.strip()


def is_valid_public_endpoint(ip: str, port: int) -> bool:
    if not isinstance(port, int) or port < 1 or port > 65535 or port in DISALLOWED_PORTS:
        return False
    try:
        addr = ipaddress.IPv4Address(ip.strip())
    except ValueError:
        return False
    for net in BLOCKED_NETWORKS:
        if addr in net:
            return False
    return True


def http_get_text(url: str, timeout: float = 10.0, max_bytes: int = 4 * 1024 * 1024) -> Optional[str]:
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "*/*",
            "Cache-Control": "no-cache",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if getattr(resp, "status", 200) != 200:
                return None
            raw = resp.read(max_bytes)
            return raw.decode("utf-8", errors="replace")
    except Exception:
        return None


def parse_existing_catalog(path: str) -> List[Candidate]:
    if not path or not os.path.isfile(path):
        return []
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception:
        return []
    out: List[Candidate] = []
    for item in data.get("bypassServers", []):
        if not isinstance(item, dict):
            continue
        ip = str(item.get("ip", "")).strip()
        try:
            port = int(item.get("port", 443))
        except (TypeError, ValueError):
            port = 443
        cc = str(item.get("countryCode", "")).strip().upper()
        if not is_valid_public_endpoint(ip, port):
            continue
        if cc and cc not in SUPPORTED_COUNTRIES:
            continue
        out.append(
            Candidate(
                ip=ip,
                port=port,
                claimed_country=cc,
                source="existing_catalog",
                name_hint=str(item.get("name", "")).strip(),
                description_hint=str(item.get("description", "")).strip(),
                azure_hint=str(item.get("azureEndpoint", "")).strip(),
                reputation_bonus=10,
                existing_id=str(item.get("id", "")).strip(),
            )
        )
    return out


def extract_vpngate_port(ovpn_b64: str) -> int:
    if not ovpn_b64:
        return 443
    try:
        decoded = base64.b64decode(ovpn_b64).decode("utf-8", errors="ignore")
        m = OVPN_REMOTE_RE.search(decoded)
        if m:
            port = int(m.group(2))
            if 1 <= port <= 65535 and port not in DISALLOWED_PORTS:
                return port
    except Exception:
        pass
    return 443


def parse_vpngate_csv(raw_text: str) -> List[Candidate]:
    """Parse SoftEther / University of Tsukuba VPNGate public CSV feed."""
    out: List[Candidate] = []
    lines = [ln for ln in raw_text.splitlines() if ln.strip() and not ln.startswith("*")]
    header_idx = -1
    for i, ln in enumerate(lines):
        if ln.startswith("#HostName,"):
            header_idx = i
            break
    if header_idx < 0:
        return out
    header = lines[header_idx].lstrip("#")
    reader = csv.DictReader(io.StringIO("\n".join([header] + lines[header_idx + 1:])))
    for row in reader:
        ip = (row.get("IP") or "").strip()
        cc = (row.get("CountryShort") or "").strip().upper()
        if cc == "UK":
            cc = "GB"
        if cc not in SUPPORTED_COUNTRIES:
            continue
        port = extract_vpngate_port((row.get("OpenVPN_ConfigData_Base64") or "").strip())
        if not is_valid_public_endpoint(ip, port):
            continue
        try:
            score_val = int(row.get("Score") or 0)
        except ValueError:
            score_val = 0
        try:
            speed_bps = int(row.get("Speed") or 0)
        except ValueError:
            speed_bps = 0
        operator = (row.get("Operator") or "").strip()
        hostname = (row.get("HostName") or "").strip()
        rep = 14
        if score_val >= 1_000_000:
            rep += 6
        elif score_val >= 200_000:
            rep += 3
        if speed_bps >= 100_000_000:
            rep += 5
        elif speed_bps >= 30_000_000:
            rep += 2
        desc = f"VPNGate Academic/ISP Relay"
        if operator and len(operator) <= 36:
            desc = f"{operator} • VPNGate Relay"
        out.append(
            Candidate(
                ip=ip,
                port=port,
                claimed_country=cc,
                source="vpngate",
                name_hint=hostname,
                description_hint=desc,
                reputation_bonus=rep,
            )
        )
    return out


def parse_proxyscrape_v4_json(raw_text: str) -> List[Candidate]:
    out: List[Candidate] = []
    try:
        payload = json.loads(raw_text)
    except Exception:
        return out
    items = payload.get("proxies", []) if isinstance(payload, dict) else []
    for item in items:
        if not isinstance(item, dict):
            continue
        if item.get("alive") is False:
            continue
        ip = str(item.get("ip", "")).strip()
        try:
            port = int(item.get("port", 0))
        except (TypeError, ValueError):
            continue
        ip_data = item.get("ip_data") if isinstance(item.get("ip_data"), dict) else {}
        cc = str(ip_data.get("countryCode", "")).strip().upper()
        if cc == "UK":
            cc = "GB"
        if cc not in SUPPORTED_COUNTRIES:
            continue
        if not is_valid_public_endpoint(ip, port):
            continue
        isp = str(ip_data.get("isp") or ip_data.get("org") or "").strip()
        city = str(ip_data.get("city") or "").strip()
        desc = f"{city} • {isp}".strip(" •") if (city or isp) else "Public Regional Relay"
        out.append(
            Candidate(
                ip=ip,
                port=port,
                claimed_country=cc,
                source="proxyscrape_v4",
                description_hint=desc[:72],
                reputation_bonus=6,
            )
        )
    return out


def parse_proxifly_json(raw_text: str) -> List[Candidate]:
    out: List[Candidate] = []
    try:
        items = json.loads(raw_text)
    except Exception:
        return out
    if not isinstance(items, list):
        return out
    for item in items:
        if not isinstance(item, dict):
            continue
        ip = str(item.get("ip", "")).strip()
        try:
            port = int(item.get("port", 0))
        except (TypeError, ValueError):
            continue
        geo = item.get("geolocation") if isinstance(item.get("geolocation"), dict) else {}
        cc = str(geo.get("country", "")).strip().upper()
        if cc == "UK":
            cc = "GB"
        if cc not in SUPPORTED_COUNTRIES:
            continue
        if not is_valid_public_endpoint(ip, port):
            continue
        city = str(geo.get("city") or "").strip()
        desc = f"{city} • Public Node" if city else "Public Regional Node"
        out.append(
            Candidate(
                ip=ip,
                port=port,
                claimed_country=cc,
                source="proxifly",
                description_hint=desc[:72],
                reputation_bonus=5,
            )
        )
    return out


def parse_geonode_json(raw_text: str) -> List[Candidate]:
    out: List[Candidate] = []
    try:
        payload = json.loads(raw_text)
    except Exception:
        return out
    items = payload.get("data", []) if isinstance(payload, dict) else []
    for item in items:
        if not isinstance(item, dict):
            continue
        ip = str(item.get("ip", "")).strip()
        try:
            port = int(item.get("port", 0))
        except (TypeError, ValueError):
            continue
        cc = str(item.get("country", "")).strip().upper()
        if cc == "UK":
            cc = "GB"
        if cc not in SUPPORTED_COUNTRIES:
            continue
        if not is_valid_public_endpoint(ip, port):
            continue
        isp = str(item.get("isp") or item.get("org") or "").strip()
        city = str(item.get("city") or "").strip()
        desc = f"{city} • {isp}".strip(" •") if (city or isp) else "GeoNode Regional Node"
        out.append(
            Candidate(
                ip=ip,
                port=port,
                claimed_country=cc,
                source="geonode",
                description_hint=desc[:72],
                reputation_bonus=5,
            )
        )
    return out


def tcp_probe_multi(ip: str, port: int, attempts: int = 3, timeout: float = 1.6) -> Tuple[int, int, int]:
    """Return (ok_count, median_rtt_ms, jitter_ms). Returns (0, -1, 0) if unreachable."""
    rtts: List[int] = []
    for _ in range(attempts):
        start = time.monotonic()
        sock = None
        try:
            sock = socket.create_connection((ip, port), timeout=timeout)
            elapsed_ms = max(1, int(round((time.monotonic() - start) * 1000)))
            rtts.append(elapsed_ms)
        except Exception:
            pass
        finally:
            if sock is not None:
                try:
                    sock.close()
                except Exception:
                    pass
    if not rtts:
        return (0, -1, 0)
    med = int(round(statistics.median(rtts)))
    jitter = max(rtts) - min(rtts) if len(rtts) >= 2 else 0
    return (len(rtts), med, jitter)


class GeoIpVerifier:
    """Rate-limit-safe, cached GeoIP consensus checker using public HTTPS endpoints."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._cache: Dict[str, Tuple[str, int, str, str, bool]] = {}
        self._last_country_is = 0.0
        self._last_ipwho = 0.0

    def verify(self, ip: str, need_deep_check: bool = False) -> Tuple[str, int, str, str, bool]:
        """
        Return (country_code, sources_agreed, city, isp, is_clean).
        is_clean=False if Tor/bogon/invalid reported.
        """
        with self._lock:
            cached = self._cache.get(ip)
            if cached is not None and (not need_deep_check or cached[1] >= 2):
                return cached

        cc1 = ""
        city = ""
        isp = ""
        is_clean = True
        sources = 0

        # Primary fast lookup: api.country.is
        with self._lock:
            wait = 0.12 - (time.monotonic() - self._last_country_is)
            if wait > 0:
                time.sleep(wait)
            self._last_country_is = time.monotonic()
        body1 = http_get_text(f"https://api.country.is/{ip}", timeout=4.0, max_bytes=4096)
        if body1:
            try:
                j1 = json.loads(body1)
                c = str(j1.get("country", "")).strip().upper()
                if c == "UK":
                    c = "GB"
                if len(c) == 2 and c.isalpha():
                    cc1 = c
                    sources = 1
            except Exception:
                pass

        # Secondary check (when primary failed OR deep consensus requested for top candidates)
        cc2 = ""
        if not cc1 or need_deep_check:
            with self._lock:
                wait2 = 0.25 - (time.monotonic() - self._last_ipwho)
                if wait2 > 0:
                    time.sleep(wait2)
                self._last_ipwho = time.monotonic()
            body2 = http_get_text(f"https://ipwho.is/{ip}", timeout=5.0, max_bytes=16384)
            if body2:
                try:
                    j2 = json.loads(body2)
                    if j2.get("success") is True:
                        c2 = str(j2.get("country_code", "")).strip().upper()
                        if c2 == "UK":
                            c2 = "GB"
                        if len(c2) == 2 and c2.isalpha():
                            cc2 = c2
                        city = str(j2.get("city") or "").strip()
                        conn = j2.get("connection") if isinstance(j2.get("connection"), dict) else {}
                        isp = str(conn.get("isp") or conn.get("org") or "").strip()
                        sec = j2.get("security") if isinstance(j2.get("security"), dict) else {}
                        if sec.get("tor") is True or sec.get("bogon") is True:
                            is_clean = False
                except Exception:
                    pass

        if cc1 and cc2:
            if cc1 == cc2:
                final_cc = cc1
                sources = 2
            else:
                # Conflicting GeoIP databases -> reject to guarantee Xbox region recognition accuracy
                final_cc = ""
                sources = 0
                is_clean = False
        else:
            final_cc = cc1 or cc2
            sources = 1 if final_cc else 0

        result = (final_cc, sources, city, isp, is_clean)
        with self._lock:
            self._cache[ip] = result
        return result


def compute_node_score(node: VerifiedNode) -> float:
    score = 0.0
    score += PRIORITY_COUNTRY_WEIGHTS.get(node.country, 6)
    score += node.reputation_bonus
    if node.geo_verified:
        score += 18.0 if node.geo_sources >= 2 else 11.0
    if node.probe_total > 0:
        success_ratio = node.probe_ok / float(node.probe_total)
        score += success_ratio * 20.0
    # Lower RTT gets higher score (runner is usually in US, so keep RTT bonus smooth across global regions)
    if node.median_rtt_ms <= 80:
        score += 18.0
    elif node.median_rtt_ms <= 160:
        score += 15.0
    elif node.median_rtt_ms <= 260:
        score += 12.0
    elif node.median_rtt_ms <= 450:
        score += 8.0
    elif node.median_rtt_ms <= 800:
        score += 4.0
    # Stability bonus (low jitter + survived multiple waves)
    if node.jitter_ms <= 25:
        score += 8.0
    elif node.jitter_ms <= 70:
        score += 4.0
    elif node.jitter_ms > 200:
        score -= 6.0
    score += min(12.0, (node.waves_passed - 1) * 4.0)
    if node.port in PREFERRED_SAFE_PORTS:
        score += 4.0
    return round(score, 2)


def select_diverse_top_nodes(nodes: Sequence[VerifiedNode]) -> List[VerifiedNode]:
    """Select balanced, /24-subnet-diverse top Bypass nodes across Xbox supported countries."""
    ranked = sorted(
        nodes,
        key=lambda n: (-n.score, n.median_rtt_ms, n.jitter_ms, n.ip),
    )

    selected: List[VerifiedNode] = []
    seen_ips: Set[str] = set()
    subnet_counts: Dict[str, int] = {}
    country_counts: Dict[str, int] = {}
    other_count = 0

    for node in ranked:
        if len(selected) >= MAX_TOTAL_SERVERS:
            break
        if node.ip in seen_ips:
            continue
        sub = subnet_24(node.ip)
        if subnet_counts.get(sub, 0) >= MAX_PER_SUBNET_24:
            continue
        if node.country in COUNTRY_QUOTAS:
            if country_counts.get(node.country, 0) >= COUNTRY_QUOTAS[node.country]:
                continue
        else:
            if other_count >= OTHER_COUNTRIES_QUOTA:
                continue

        selected.append(node)
        seen_ips.add(node.ip)
        subnet_counts[sub] = subnet_counts.get(sub, 0) + 1
        country_counts[node.country] = country_counts.get(node.country, 0) + 1
        if node.country not in COUNTRY_QUOTAS:
            other_count += 1

    # Order cleanly: JP -> KR -> US -> others, highest score first within each group
    group_order = {"JP": 0, "KR": 1, "US": 2}
    selected.sort(key=lambda n: (group_order.get(n.country, 3), -n.score, n.median_rtt_ms))
    return selected


def build_catalog_payload(nodes: Sequence[VerifiedNode], now_iso: str) -> Dict[str, object]:
    country_seq: Dict[str, int] = {}
    bypass_entries: List[Dict[str, object]] = []

    for node in nodes:
        cc = node.country
        idx = country_seq.get(cc, 0) + 1
        country_seq[cc] = idx
        country_name = COUNTRY_DISPLAY_NAME.get(cc, cc)
        flag = country_flag(cc)
        city_part = f" {node.city}" if node.city and node.city.lower() not in country_name.lower() else ""
        if node.existing_id and node.source == "existing_catalog":
            node_id = node.existing_id
            display_name = node.name_hint or f"{country_name}{city_part} #{idx}"
        else:
            ip_slug = node.ip.replace(".", "_")
            node_id = f"gh_auto_{cc.lower()}_{ip_slug}_{node.port}"
            display_name = f"{country_name}{city_part} #{idx}"

        desc_parts: List[str] = []
        if node.isp:
            desc_parts.append(node.isp[:40])
        elif node.description_hint:
            desc_parts.append(node.description_hint[:48])
        else:
            desc_parts.append(f"{country_name} Verified Bypass")
        if node.geo_sources >= 2:
            desc_parts.append("GeoIP 2x")
        elif node.geo_verified:
            desc_parts.append("GeoIP OK")
        if idx <= 3 and cc in ("JP", "KR", "US"):
            desc_parts.append(f"Top #{idx}")

        bypass_entries.append(
            {
                "id": node_id,
                "name": display_name,
                "flag": flag,
                "ip": node.ip,
                "port": node.port,
                "azureEndpoint": node.azure_endpoint or AZURE_ENDPOINT_BY_COUNTRY.get(cc, ""),
                "description": " • ".join(desc_parts),
                "countryCode": cc,
                "verifiedAt": node.verified_at or now_iso,
                "latencyMs": node.median_rtt_ms,
                "source": node.source,
            }
        )

    return {
        "schemaVersion": 1,
        "updatedAt": now_iso,
        "bypassServers": bypass_entries,
        "proxyServers": [],
        "proxySources": [],
    }


def harvest_wave_candidates(wave_num: int, existing_path: str) -> Tuple[List[Candidate], Dict[str, int]]:
    stats: Dict[str, int] = {}
    all_cands: List[Candidate] = []

    if wave_num == 1:
        existing = parse_existing_catalog(existing_path)
        stats["existing_catalog"] = len(existing)
        all_cands.extend(existing)

    # 1. VPNGate CSV (academic & residential JP/KR/US relays)
    vg_text = http_get_text("https://www.vpngate.net/api/iphone/", timeout=14.0, max_bytes=6 * 1024 * 1024)
    if vg_text:
        vg = parse_vpngate_csv(vg_text)
        stats["vpngate"] = len(vg)
        all_cands.extend(vg)

    # 2. ProxyScrape v4 JSON
    ps_url = (
        "https://api.proxyscrape.com/v4/free-proxy-list/get?"
        "request=display_proxies&protocol=http&proxy_format=ipport&format=json&timeout=3000"
    )
    ps_text = http_get_text(ps_url, timeout=12.0, max_bytes=6 * 1024 * 1024)
    if ps_text:
        ps = parse_proxyscrape_v4_json(ps_text)
        stats["proxyscrape_v4"] = len(ps)
        all_cands.extend(ps)

    # 3. Proxifly JSON (first wave + every 3rd wave)
    if wave_num == 1 or wave_num % 3 == 0:
        pf_text = http_get_text(
            "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/http/data.json",
            timeout=12.0,
            max_bytes=6 * 1024 * 1024,
        )
        if pf_text:
            pf = parse_proxifly_json(pf_text)
            stats["proxifly"] = len(pf)
            all_cands.extend(pf)

    # 4. GeoNode JSON (rotates pages across waves to keep discovering fresh IPs)
    gn_page = ((wave_num - 1) % 6) + 1
    gn_url = (
        f"https://proxylist.geonode.com/api/proxy-list?"
        f"limit=150&page={gn_page}&sort_by=lastChecked&sort_type=desc"
    )
    gn_text = http_get_text(gn_url, timeout=12.0, max_bytes=4 * 1024 * 1024)
    if gn_text:
        gn = parse_geonode_json(gn_text)
        stats[f"geonode_p{gn_page}"] = len(gn)
        all_cands.extend(gn)

    # Prioritize JP/KR/US and higher-reputation candidates first
    all_cands.sort(
        key=lambda c: (
            -PRIORITY_COUNTRY_WEIGHTS.get(c.claimed_country, 0),
            -c.reputation_bonus,
            0 if c.port in PREFERRED_SAFE_PORTS else 1,
        )
    )
    return all_cands, stats


def run_hunt(
    output_path: str,
    max_seconds: int,
    min_servers: int,
    workers: int,
    dry_run: bool,
    summary_file: Optional[str],
) -> int:
    start_ts = time.monotonic()
    deadline = start_ts + max(30, max_seconds)
    # Keep last 25 seconds for deep 2x GeoIP consensus & saving
    harvest_deadline = deadline - 25

    geo_verifier = GeoIpVerifier()
    verified_by_ip: Dict[str, VerifiedNode] = {}
    tested_keys: Set[Tuple[str, int]] = set()
    total_probed = 0
    total_rejected_geo = 0
    waves_completed = 0

    def verify_one(cand: Candidate) -> Optional[VerifiedNode]:
        ok, med_rtt, jitter = tcp_probe_multi(cand.ip, cand.port, attempts=3, timeout=1.6)
        if ok < 2 or med_rtt <= 0 or med_rtt > 1600:
            return None

        cc_geo, geo_sources, city, isp, is_clean = geo_verifier.verify(cand.ip, need_deep_check=False)
        if not is_clean:
            return None

        final_cc = cc_geo or cand.claimed_country
        if final_cc not in SUPPORTED_COUNTRIES:
            return None
        # If source claimed a country AND GeoIP returned a different country, trust GeoIP only if supported,
        # else if GeoIP disagrees with claimed country on non-vpngate sources, drop for higher region accuracy.
        if cc_geo and cand.claimed_country and cc_geo != cand.claimed_country:
            if cand.source not in ("existing_catalog", "vpngate"):
                return None

        now_iso = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        azure = cand.azure_hint or AZURE_ENDPOINT_BY_COUNTRY.get(final_cc, "")
        node = VerifiedNode(
            ip=cand.ip,
            port=cand.port,
            country=final_cc,
            source=cand.source,
            name_hint=cand.name_hint,
            description_hint=cand.description_hint,
            azure_endpoint=azure,
            existing_id=cand.existing_id,
            median_rtt_ms=med_rtt,
            jitter_ms=jitter,
            probe_ok=ok,
            probe_total=3,
            geo_verified=bool(cc_geo),
            geo_sources=geo_sources,
            city=city,
            isp=isp,
            reputation_bonus=cand.reputation_bonus,
            waves_passed=1,
            verified_at=now_iso,
        )
        node.score = compute_node_score(node)
        return node

    wave = 0
    while time.monotonic() < harvest_deadline:
        wave += 1
        waves_completed = wave
        remaining = int(harvest_deadline - time.monotonic())
        print(f"[Wave {wave}] Hunting fresh Bypass candidates... ({remaining}s remaining)", flush=True)

        candidates, source_stats = harvest_wave_candidates(wave, output_path)
        print(f"[Wave {wave}] Sources fetched: {source_stats}", flush=True)

        # Deduplicate against already-tested (ip, port) this run
        fresh_batch: List[Candidate] = []
        seen_in_wave: Set[Tuple[str, int]] = set()
        for c in candidates:
            key = (c.ip, c.port)
            if key in tested_keys or key in seen_in_wave:
                continue
            seen_in_wave.add(key)
            fresh_batch.append(c)
            if len(fresh_batch) >= 240:
                break

        if fresh_batch:
            with ThreadPoolExecutor(max_workers=workers) as pool:
                future_map = {}
                for cand in fresh_batch:
                    if time.monotonic() >= harvest_deadline:
                        break
                    tested_keys.add((cand.ip, cand.port))
                    future_map[pool.submit(verify_one, cand)] = cand

                for fut in as_completed(future_map):
                    total_probed += 1
                    try:
                        node = fut.result()
                    except Exception:
                        node = None
                    if node is None:
                        continue
                    prev = verified_by_ip.get(node.ip)
                    if prev is None or node.score > prev.score:
                        verified_by_ip[node.ip] = node
                    if time.monotonic() >= harvest_deadline:
                        break

        # Stability re-probe on already-verified nodes across waves
        if wave >= 2 and verified_by_ip and time.monotonic() < harvest_deadline:
            top_to_recheck = sorted(verified_by_ip.values(), key=lambda n: -n.score)[:60]
            with ThreadPoolExecutor(max_workers=min(workers, 16)) as pool:
                re_futs = {
                    pool.submit(tcp_probe_multi, n.ip, n.port, 2, 1.5): n
                    for n in top_to_recheck
                }
                for fut in as_completed(re_futs):
                    node = re_futs[fut]
                    try:
                        ok, rtt, jit = fut.result()
                    except Exception:
                        ok, rtt, jit = (0, -1, 0)
                    if ok >= 1 and 0 < rtt <= 1600:
                        node.waves_passed += 1
                        node.median_rtt_ms = int(round((node.median_rtt_ms + rtt) / 2.0))
                        node.jitter_ms = int(round((node.jitter_ms + jit) / 2.0))
                        node.score = compute_node_score(node)
                    else:
                        # Penalize intermittent drops
                        node.score = round(max(0.0, node.score - 12.0), 2)

        print(
            f"[Wave {wave}] Verified pool: {len(verified_by_ip)} unique IPs | Total probed: {total_probed}",
            flush=True,
        )

        # Short breath before next wave if time remains
        sleep_left = min(15.0, harvest_deadline - time.monotonic())
        if sleep_left > 1.0:
            time.sleep(sleep_left)

    # Final Deep 2x GeoIP Consensus Check on top ~50 candidates while within overall deadline
    preliminary_top = select_diverse_top_nodes(list(verified_by_ip.values()))
    deep_checked: List[VerifiedNode] = []
    for node in preliminary_top:
        if time.monotonic() < deadline - 3:
            cc_deep, src_cnt, city, isp, is_clean = geo_verifier.verify(node.ip, need_deep_check=True)
            if not is_clean or (cc_deep and cc_deep not in SUPPORTED_COUNTRIES):
                total_rejected_geo += 1
                continue
            if cc_deep:
                node.country = cc_deep
                node.azure_endpoint = AZURE_ENDPOINT_BY_COUNTRY.get(cc_deep, node.azure_endpoint)
            if city:
                node.city = city
            if isp:
                node.isp = isp
            node.geo_verified = src_cnt >= 1
            node.geo_sources = max(node.geo_sources, src_cnt)
            node.score = compute_node_score(node)
        deep_checked.append(node)

    final_nodes = select_diverse_top_nodes(deep_checked)
    elapsed_sec = int(round(time.monotonic() - start_ts))
    now_iso = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    country_breakdown: Dict[str, int] = {}
    unique_subnets: Set[str] = set()
    for n in final_nodes:
        country_breakdown[n.country] = country_breakdown.get(n.country, 0) + 1
        unique_subnets.add(subnet_24(n.ip))

    saved = False
    skip_reason = ""
    if len(final_nodes) < min_servers:
        skip_reason = f"Verified count ({len(final_nodes)}) < safety minimum ({min_servers}); keeping existing {output_path} unchanged."
        print(f"[SAFEGUARD] {skip_reason}", flush=True)
    elif dry_run:
        skip_reason = "Dry-run mode enabled; skipped writing file."
        print(f"[DRY-RUN] Would write {len(final_nodes)} verified Bypass servers to {output_path}", flush=True)
    else:
        payload = build_catalog_payload(final_nodes, now_iso)
        os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
        with open(output_path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
            f.write("\n")
        saved = True
        print(f"[SAVED] Wrote {len(final_nodes)} verified Bypass servers to {output_path}", flush=True)

    summary_lines = [
        "## Ravno Bypass Server Hunter (Experimental)",
        "",
        f"- **Updated At (UTC):** `{now_iso}`",
        f"- **Duration:** `{elapsed_sec}s` ({waves_completed} hunting waves)",
        f"- **Candidates Probed:** `{total_probed}` | **Unique Verified Pool:** `{len(verified_by_ip)}`",
        f"- **Final Ranked Servers:** `{len(final_nodes)}` across `{len(unique_subnets)}` distinct `/24` subnets",
        f"- **GeoIP Consensus Rejected:** `{total_rejected_geo}`",
        f"- **Country Distribution:** `{country_breakdown}`",
        f"- **Catalog Write Status:** `{'SAVED to ' + output_path if saved else 'SKIPPED (' + skip_reason + ')'}`",
        "",
        "### Top 10 Ranked Bypass Nodes",
        "",
        "| # | Country | IP:Port | Median RTT (runner) | Jitter | GeoIP | Waves | Score | Source |",
        "|---|---|---|---:|---:|---|---:|---:|---|",
    ]
    for i, n in enumerate(final_nodes[:10], start=1):
        geo_tag = f"{n.geo_sources}x OK" if n.geo_verified else "claimed"
        summary_lines.append(
            f"| {i} | {country_flag(n.country)} {n.country} | `{n.ip}:{n.port}` | {n.median_rtt_ms} ms | {n.jitter_ms} ms | {geo_tag} | {n.waves_passed} | {n.score} | `{n.source}` |"
        )

    summary_md = "\n".join(summary_lines) + "\n"
    if summary_file:
        try:
            with open(summary_file, "a", encoding="utf-8") as sf:
                sf.write(summary_md)
        except Exception as e:
            print(f"[WARN] Could not write summary file: {e}", flush=True)

    return 0


def run_self_test() -> int:
    """Offline deterministic self-test verifying safety filters, parsers, diversity, and JSON schema."""
    # 1. Public IPv4 & disallowed port filtering
    assert is_valid_public_endpoint("219.100.37.197", 443) is True
    assert is_valid_public_endpoint("127.0.0.1", 443) is False
    assert is_valid_public_endpoint("10.0.0.1", 443) is False
    assert is_valid_public_endpoint("192.168.1.5", 443) is False
    assert is_valid_public_endpoint("100.64.10.2", 443) is False  # CGNAT
    assert is_valid_public_endpoint("203.0.113.5", 443) is False  # TEST-NET-3
    assert is_valid_public_endpoint("224.0.0.1", 443) is False  # Multicast
    assert is_valid_public_endpoint("219.100.37.197", 22) is False  # SSH disallowed
    assert is_valid_public_endpoint("219.100.37.197", 3306) is False  # MySQL disallowed
    assert is_valid_public_endpoint("not-an-ip", 443) is False

    # 2. VPNGate CSV parser
    ovpn_sample = base64.b64encode(b"client\nproto tcp\nremote 153.122.10.5 1443\n").decode("ascii")
    vg_csv = (
        "*vpn_servers\n"
        "#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64\n"
        f"public-jp-1,153.122.10.5,1500000,12,120000000,Japan,JP,10,99999,100,1000,2weeks,Tsukuba,,{ovpn_sample}\n"
        f"bad-vn-1,113.160.1.2,1500000,12,120000000,Vietnam,VN,10,99999,100,1000,2weeks,VNISP,,{ovpn_sample}\n"
        f"bad-lan-1,192.168.0.1,1500000,12,120000000,Japan,JP,10,99999,100,1000,2weeks,LAN,,{ovpn_sample}\n"
    )
    vg_cands = parse_vpngate_csv(vg_csv)
    assert len(vg_cands) == 1
    assert vg_cands[0].ip == "153.122.10.5"
    assert vg_cands[0].port == 1443
    assert vg_cands[0].claimed_country == "JP"

    # 3. ProxyScrape v4 / Proxifly / GeoNode parsers
    ps_cands = parse_proxyscrape_v4_json(json.dumps({
        "proxies": [
            {"ip": "49.165.237.183", "port": 995, "alive": True, "ip_data": {"countryCode": "KR", "isp": "KT"}},
            {"ip": "49.165.237.184", "port": 995, "alive": False, "ip_data": {"countryCode": "KR"}},
        ]
    }))
    assert len(ps_cands) == 1 and ps_cands[0].claimed_country == "KR"

    pf_cands = parse_proxifly_json(json.dumps([
        {"ip": "34.44.49.215", "port": 443, "geolocation": {"country": "US", "city": "San Jose"}},
        {"ip": "10.1.2.3", "port": 443, "geolocation": {"country": "US"}},
    ]))
    assert len(pf_cands) == 1 and pf_cands[0].claimed_country == "US"

    gn_cands = parse_geonode_json(json.dumps({
        "data": [
            {"ip": "185.10.20.30", "port": "8080", "country": "GB", "city": "London"},
            {"ip": "185.10.20.31", "port": "22", "country": "GB"},
        ]
    }))
    assert len(gn_cands) == 1 and gn_cands[0].claimed_country == "GB"

    # 4. /24 subnet diversity cap (max 3 per /24)
    same_subnet_nodes = [
        VerifiedNode(
            ip=f"219.100.37.{10 + i}",
            port=443,
            country="JP",
            source="vpngate",
            name_hint="",
            description_hint="Tsukuba",
            azure_endpoint="japaneast.cloudapp.azure.com",
            existing_id="",
            median_rtt_ms=50 + i,
            jitter_ms=5,
            probe_ok=3,
            probe_total=3,
            geo_verified=True,
            geo_sources=2,
            score=90.0 - i,
        )
        for i in range(6)
    ]
    diverse = select_diverse_top_nodes(same_subnet_nodes)
    assert len(diverse) == MAX_PER_SUBNET_24, f"Expected {MAX_PER_SUBNET_24}, got {len(diverse)}"

    # 5. Output schema compatibility with RemoteServerCatalog.kt
    payload = build_catalog_payload(diverse, "2026-11-26T00:00:00Z")
    assert payload["schemaVersion"] == 1
    assert payload["proxyServers"] == []
    assert payload["proxySources"] == []
    assert len(payload["bypassServers"]) == MAX_PER_SUBNET_24
    first_srv = payload["bypassServers"][0]
    for req_key in ("id", "name", "flag", "ip", "port", "azureEndpoint", "description", "countryCode"):
        assert req_key in first_srv and first_srv[req_key], f"Missing {req_key}"

    print("SELF-TEST PASSED: all safety filters, parsers, /24 diversity, and schema checks OK.")
    return 0


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Ravno Continuous Bypass Server Hunter")
    parser.add_argument("--output", default="docs/servers.json", help="Output JSON catalog path")
    parser.add_argument("--max-seconds", type=int, default=540, help="Continuous hunting budget in seconds (default 540 = 9m)")
    parser.add_argument("--min-servers", type=int, default=15, help="Minimum verified servers required to overwrite catalog")
    parser.add_argument("--workers", type=int, default=24, help="Concurrent TCP probe workers")
    parser.add_argument("--dry-run", action="store_true", help="Run without modifying output file")
    parser.add_argument("--summary-file", default=os.environ.get("GITHUB_STEP_SUMMARY", ""), help="Markdown summary path")
    parser.add_argument("--self-test", action="store_true", help="Run offline deterministic self-test and exit")
    args = parser.parse_args(argv)

    if args.self_test:
        return run_self_test()

    return run_hunt(
        output_path=args.output,
        max_seconds=max(30, min(600, args.max_seconds)),
        min_servers=max(1, args.min_servers),
        workers=max(4, min(48, args.workers)),
        dry_run=args.dry_run,
        summary_file=args.summary_file or None,
    )


if __name__ == "__main__":
    sys.exit(main())
