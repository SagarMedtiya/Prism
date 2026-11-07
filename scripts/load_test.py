#!/usr/bin/env python3
"""
Load test for Prism gateway.
Tests concurrent requests against RPM and budget limits.

Usage:
    python scripts/load_test.py [--gateway http://localhost:8080]
"""

import json
import sys
import time
import argparse
import requests
from concurrent.futures import ThreadPoolExecutor, as_completed
from collections import Counter

ALPHA_KEY = "sk-prism-demo-alpha-001"
BETA_KEY = "sk-prism-demo-beta-002"

def send_request(gateway, key, model="fast", idx=0):
    start = time.time()
    try:
        r = requests.post(
            f"{gateway}/v1/chat/completions",
            headers={"Authorization": f"Bearer {key}",
                     "Content-Type": "application/json"},
            json={"model": model,
                  "messages": [{"role": "user",
                                "content": f"Load test request {idx}"}],
                  "stream": False},
            timeout=30
        )
        latency = (time.time() - start) * 1000
        cost = float(r.headers.get("x-prism-cost-usd", "0"))
        return {
            "status": r.status_code,
            "latency_ms": latency,
            "cost_usd": cost,
            "error_code": r.json().get("error", {}).get("code") if r.status_code != 200 else None
        }
    except Exception as e:
        return {
            "status": 0,
            "latency_ms": (time.time() - start) * 1000,
            "cost_usd": 0,
            "error_code": str(e)
        }

def run_rpm_test(gateway, key, rpm_limit, num_requests):
    print(f"\n--- RPM Test: {num_requests} concurrent requests, limit={rpm_limit} ---")

    results = []
    with ThreadPoolExecutor(max_workers=num_requests) as pool:
        futures = [pool.submit(send_request, gateway, key, "fast", i)
                   for i in range(num_requests)]
        for f in as_completed(futures):
            results.append(f.result())

    status_counts = Counter(r["status"] for r in results)
    admitted = status_counts.get(200, 0)
    rejected = status_counts.get(429, 0)
    other = sum(v for k, v in status_counts.items() if k not in (200, 429))

    print(f"  Admitted: {admitted}")
    print(f"  Rate-limited (429): {rejected}")
    print(f"  Other: {other}")

    over_admission = max(0, admitted - rpm_limit)
    print(f"  Over-admission: {over_admission}")

    return {
        "admitted": admitted,
        "rejected": rejected,
        "over_admission": over_admission,
        "results": results
    }

def run_latency_test(gateway, key, num_requests=50):
    print(f"\n--- Latency Test: {num_requests} sequential requests ---")

    latencies = []
    for i in range(num_requests):
        result = send_request(gateway, key, "fast", i)
        if result["status"] == 200:
            latencies.append(result["latency_ms"])

    if not latencies:
        print("  No successful requests!")
        return {}

    latencies.sort()
    n = len(latencies)

    stats = {
        "count": n,
        "p50": latencies[int(n * 0.5)],
        "p95": latencies[int(n * 0.95)],
        "p99": latencies[int(n * 0.99)],
        "min": latencies[0],
        "max": latencies[-1],
        "avg": sum(latencies) / n
    }

    print(f"  Count: {stats['count']}")
    print(f"  p50:   {stats['p50']:.1f} ms")
    print(f"  p95:   {stats['p95']:.1f} ms")
    print(f"  p99:   {stats['p99']:.1f} ms")
    print(f"  min:   {stats['min']:.1f} ms")
    print(f"  max:   {stats['max']:.1f} ms")
    print(f"  avg:   {stats['avg']:.1f} ms")

    return stats

def check_accounting(gateway, key_id):
    print(f"\n--- Accounting Check: keyId={key_id} ---")

    r = requests.get(f"{gateway}/v1/usage/summary?keyId={key_id}", timeout=5)
    if r.status_code != 200:
        print(f"  Failed to get usage summary: {r.status_code}")
        return False

    summary = r.json()
    print(f"  Total requests: {summary.get('totalRequests', 'N/A')}")
    print(f"  Total cost: {summary.get('totalCostUsd', 'N/A')} USD")
    print(f"  Cache hit rate: {summary.get('cacheHitRate', 'N/A')}")
    return True

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gateway", default="http://localhost:8080")
    args = parser.parse_args()
    gw = args.gateway

    print(f"\nPrism Load Test - {gw}")
    print("=" * 60)

    # RPM test with beta key (limit = 10)
    rpm_result = run_rpm_test(gw, BETA_KEY, rpm_limit=10, num_requests=20)

    # Wait for RPM window to reset
    print("\n  Waiting 65s for RPM window reset...")
    time.sleep(65)

    # Latency test with alpha key
    latency_stats = run_latency_test(gw, ALPHA_KEY, num_requests=30)

    # Accounting
    check_accounting(gw, key_id=1)
    check_accounting(gw, key_id=2)

    # Summary
    print(f"\n{'=' * 60}")
    print("Load Test Summary:")
    print(f"  Over-admission: {rpm_result['over_admission']}")
    print(f"  p50 latency: {latency_stats.get('p50', 'N/A')} ms")
    print(f"  p99 latency: {latency_stats.get('p99', 'N/A')} ms")

    success = rpm_result["over_admission"] == 0
    print(f"  PASS: {'YES' if success else 'NO'}")
    print(f"{'=' * 60}\n")

    sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
