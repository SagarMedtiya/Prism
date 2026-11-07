#!/usr/bin/env python3
"""
Smoke test for Prism gateway.
Verifies all major features work end-to-end.

Usage:
    python scripts/smoke_test.py [--gateway http://localhost:8080]
"""

import json
import sys
import time
import argparse
import requests

ALPHA_KEY = "sk-prism-demo-alpha-001"
BETA_KEY = "sk-prism-demo-beta-002"
GAMMA_KEY = "sk-prism-demo-gamma-003"

def test(name, func, gateway):
    try:
        func(gateway)
        print(f"  + {name}")
        return True
    except AssertionError as e:
        print(f"  X {name}: {e}")
        return False
    except Exception as e:
        print(f"  X {name}: EXCEPTION - {e}")
        return False

def api(gateway, key, model="fast", stream=False, messages=None, temperature=1.0):
    if messages is None:
        messages = [{"role": "user", "content": "Hello, how are you?"}]
    return requests.post(
        f"{gateway}/v1/chat/completions",
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
        json={"model": model, "messages": messages, "stream": stream,
              "temperature": temperature},
        timeout=30,
        stream=stream
    )

# --- Health ---

def test_health(gw):
    r = requests.get(f"{gw}/health", timeout=5)
    assert r.status_code == 200, f"status={r.status_code}"
    body = r.json()
    assert body.get("mysql") == "up", "MySQL not up"
    assert body.get("redis") == "up", "Redis not up"

# --- Auth ---

def test_valid_key(gw):
    r = api(gw, ALPHA_KEY)
    assert r.status_code == 200, f"status={r.status_code}"

def test_invalid_key(gw):
    r = api(gw, "sk-invalid-key-xyz")
    assert r.status_code == 401, f"expected 401, got {r.status_code}"
    body = r.json()
    assert body["error"]["code"] == "invalid_key"

def test_missing_key(gw):
    r = requests.post(f"{gw}/v1/chat/completions",
                      json={"model": "fast", "messages": [{"role": "user", "content": "hi"}]},
                      timeout=10)
    assert r.status_code == 401

# --- Headers ---

def test_header_contract(gw):
    r = api(gw, ALPHA_KEY)
    assert r.status_code == 200
    for h in ["x-prism-provider", "x-prism-cache", "x-prism-fallback", "x-prism-cost-usd"]:
        assert h in r.headers, f"Missing header: {h}"

# --- OpenAI shape ---

def test_response_shape(gw):
    r = api(gw, ALPHA_KEY)
    body = r.json()
    assert "id" in body
    assert body.get("object") == "chat.completion"
    assert "choices" in body and len(body["choices"]) > 0
    assert "message" in body["choices"][0]
    assert "usage" in body

# --- Model allowlist ---

def test_model_not_allowed(gw):
    r = api(gw, BETA_KEY, model="smart")
    assert r.status_code == 403, f"expected 403, got {r.status_code}"
    body = r.json()
    assert body["error"]["code"] == "model_not_allowed"

# --- Streaming ---

def test_streaming(gw):
    r = api(gw, ALPHA_KEY, stream=True)
    assert r.status_code == 200
    chunks = []
    for line in r.iter_lines(decode_unicode=True):
        if line.startswith("data: "):
            data = line[6:]
            chunks.append(data)
            if data == "[DONE]":
                break
    assert len(chunks) > 1, "Expected multiple chunks"
    assert chunks[-1] == "[DONE]", "Stream must end with [DONE]"

# --- Semantic cache ---

def test_cache_hit(gw):
    msg = [{"role": "user", "content": "What is the capital of France?"}]

    r1 = api(gw, ALPHA_KEY, messages=msg, temperature=0)
    assert r1.status_code == 200
    assert r1.headers.get("x-prism-cache") == "miss"

    r2 = api(gw, ALPHA_KEY, messages=msg, temperature=0)
    assert r2.status_code == 200
    assert r2.headers.get("x-prism-cache") == "hit", \
        f"Expected cache hit, got {r2.headers.get('x-prism-cache')}"

def test_cache_isolation(gw):
    msg = [{"role": "user", "content": "Unique prompt for cache isolation test " + str(time.time())}]

    r1 = api(gw, ALPHA_KEY, messages=msg, temperature=0)
    assert r1.status_code == 200

    r2 = api(gw, BETA_KEY, messages=msg, temperature=0)
    if r2.status_code == 200:
        assert r2.headers.get("x-prism-cache") == "miss", \
            "Cross-tenant cache leak detected!"

# --- Auto routing ---

def test_auto_routing(gw):
    r = api(gw, ALPHA_KEY, model="auto",
            messages=[{"role": "user", "content": "What is 2+2?"}])
    assert r.status_code == 200
    assert "x-prism-provider" in r.headers

# --- Cost ---

def test_cost_header(gw):
    r = api(gw, ALPHA_KEY)
    cost = r.headers.get("x-prism-cost-usd", "0")
    assert float(cost) >= 0, f"Invalid cost: {cost}"

# --- Usage API ---

def test_usage_summary(gw):
    r = requests.get(f"{gw}/v1/usage/summary?keyId=1", timeout=5)
    assert r.status_code == 200
    body = r.json()
    assert "totalRequests" in body
    assert "cacheHitRate" in body

def test_recent_requests(gw):
    r = requests.get(f"{gw}/v1/usage/requests?page=0&size=5", timeout=5)
    assert r.status_code == 200

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gateway", default="http://localhost:8080")
    args = parser.parse_args()
    gw = args.gateway

    print(f"\nPrism Smoke Test - {gw}")
    print("=" * 50)

    tests = [
        ("Health check", test_health),
        ("Valid key authenticates", test_valid_key),
        ("Invalid key rejected", test_invalid_key),
        ("Missing key rejected", test_missing_key),
        ("Header contract present", test_header_contract),
        ("Response is OpenAI-shaped", test_response_shape),
        ("Model not allowed rejected", test_model_not_allowed),
        ("Streaming works", test_streaming),
        ("Semantic cache hit", test_cache_hit),
        ("Cache isolation (no cross-key leak)", test_cache_isolation),
        ("Auto routing works", test_auto_routing),
        ("Cost header present", test_cost_header),
        ("Usage summary API", test_usage_summary),
        ("Recent requests API", test_recent_requests),
    ]

    passed = sum(1 for name, func in tests if test(name, func, gw))
    total = len(tests)

    print(f"\n{'=' * 50}")
    print(f"Results: {passed}/{total} passed")
    print(f"{'=' * 50}\n")

    sys.exit(0 if passed == total else 1)

if __name__ == "__main__":
    main()
