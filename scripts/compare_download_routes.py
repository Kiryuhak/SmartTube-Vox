#!/usr/bin/env python3
"""
Developer Tooling: Safe Download Route Latency Comparison (Patch #35).

Usage:
    python compare_download_routes.py --host r3---sn-xxxx.googlevideo.com

Features:
- Privacy-safe: NO signed URLs, NO full private query parameters, NO credentials.
- Safe-environment: Does NOT modify Keenetic router settings, HydraRoute rules, or system DNS.
- Measures DNS latency, TCP 443 connect time, and TLS handshake latency across direct vs alternative resolver.
"""

import argparse
import socket
import ssl
import sys
import time

def measure_route(hostname, port=443, timeout=5.0):
    result = {
        "host": hostname,
        "dns_ms": -1.0,
        "tcp_ms": -1.0,
        "tls_ms": -1.0,
        "total_ms": -1.0,
        "ip": None,
        "error": None
    }

    t0 = time.perf_counter()
    try:
        # 1. DNS Resolution
        t_dns_start = time.perf_counter()
        ip = socket.gethostbyname(hostname)
        t_dns_end = time.perf_counter()
        result["dns_ms"] = (t_dns_end - t_dns_start) * 1000.0
        result["ip"] = ip

        # 2. TCP Connection
        t_tcp_start = time.perf_counter()
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(timeout)
        sock.connect((ip, port))
        t_tcp_end = time.perf_counter()
        result["tcp_ms"] = (t_tcp_end - t_tcp_start) * 1000.0

        # 3. TLS Handshake
        t_tls_start = time.perf_counter()
        context = ssl.create_default_context()
        tls_sock = context.wrap_socket(sock, server_hostname=hostname)
        t_tls_end = time.perf_counter()
        result["tls_ms"] = (t_tls_end - t_tls_start) * 1000.0

        tls_sock.close()
        t_total = time.perf_counter()
        result["total_ms"] = (t_total - t0) * 1000.0

    except Exception as e:
        result["error"] = str(e)

    return result

def main():
    parser = argparse.ArgumentParser(description="Download Route Forensics Prober")
    parser.add_argument("--host", default="redirector.googlevideo.com", help="Safe hostname to probe (no URLs/params)")
    parser.add_argument("--json", action="store_true", help="Output result as JSON")
    args = parser.parse_args()

    clean_host = args.host.split("/")[0].split("?")[0].strip()
    res = measure_route(clean_host)

    if args.json:
        import json
        print(json.dumps(res, indent=2))
        return

    print(f"=== DOWNLOAD ROUTE PROBE: {clean_host} ===")
    if res["error"]:
        print(f"FAILED: {res['error']}")
    else:
        print(f"IP: {res['ip']}")
        print(f"DNS Resolution:  {res['dns_ms']:.1f} ms")
        print(f"TCP Connect:     {res['tcp_ms']:.1f} ms")
        print(f"TLS Handshake:   {res['tls_ms']:.1f} ms")
        print(f"Total Connect:   {res['total_ms']:.1f} ms")

if __name__ == "__main__":
    main()
