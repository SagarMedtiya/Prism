#!/usr/bin/env python3
"""
Routing eval: tests the auto-classifier against the labeled eval set.
Reports per-case and overall accuracy for both the classifier and the
length-only baseline.

Usage:
    python scripts/routing_eval.py [--gateway http://localhost:8080]
"""

import json
import sys
import argparse
import requests
from pathlib import Path

def load_eval_set():
    path = Path(__file__).parent.parent / "eval" / "routing_eval_set.json"
    with open(path) as f:
        return json.load(f)

def classify_via_gateway(gateway_url, prompt, api_key):
    resp = requests.post(
        f"{gateway_url}/v1/chat/completions",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json"
        },
        json={
            "model": "auto",
            "messages": [{"role": "user", "content": prompt}],
            "stream": False,
            "temperature": 1.0
        },
        timeout=30
    )

    if resp.status_code != 200:
        return None, resp.status_code

    provider = resp.headers.get("x-prism-provider", "")
    body = resp.json()
    model = body.get("model", "")

    if "fast" in model.lower():
        return "easy", resp.status_code
    elif "smart" in model.lower():
        return "hard", resp.status_code
    else:
        return "unknown", resp.status_code

def length_baseline(prompt):
    return "hard" if len(prompt) > 200 else "easy"

def run_eval(gateway_url, api_key):
    eval_set = load_eval_set()

    classifier_correct = 0
    baseline_correct = 0
    total = len(eval_set)
    results = []

    print(f"\n{'='*80}")
    print(f"Prism Routing Eval - {total} cases")
    print(f"{'='*80}\n")
    print(f"{'ID':<25} {'Expected':<8} {'Classifier':<12} {'Baseline':<12} {'Trap':<15}")
    print(f"{'-'*25} {'-'*8} {'-'*12} {'-'*12} {'-'*15}")

    for case in eval_set:
        expected = case["expected"]
        prompt = case["prompt"]
        trap = case.get("trap") or ""

        classified, status = classify_via_gateway(gateway_url, prompt, api_key)
        classifier_ok = classified == expected

        baseline = length_baseline(prompt)
        baseline_ok = baseline == expected

        if classifier_ok:
            classifier_correct += 1
        if baseline_ok:
            baseline_correct += 1

        status_icon = "+" if classifier_ok else "X"
        baseline_icon = "+" if baseline_ok else "X"

        print(f"{case['id']:<25} {expected:<8} "
              f"{status_icon} {classified or 'err':<10} "
              f"{baseline_icon} {baseline:<10} {trap}")

        results.append({
            "id": case["id"],
            "expected": expected,
            "classified": classified,
            "baseline": baseline,
            "classifier_correct": classifier_ok,
            "baseline_correct": baseline_ok,
            "trap": trap
        })

    classifier_accuracy = classifier_correct / total * 100
    baseline_accuracy = baseline_correct / total * 100
    beats_baseline = classifier_accuracy > baseline_accuracy

    print(f"\n{'='*80}")
    print(f"Results:")
    print(f"  Classifier: {classifier_correct}/{total} = {classifier_accuracy:.1f}%")
    print(f"  Baseline:   {baseline_correct}/{total} = {baseline_accuracy:.1f}%")
    print(f"  Beats baseline: {'YES' if beats_baseline else 'NO'}")
    print(f"{'='*80}\n")

    categories = {}
    for r in results:
        cat = [c for c in eval_set if c["id"] == r["id"]][0].get("category", "other")
        if cat not in categories:
            categories[cat] = {"total": 0, "classifier_correct": 0, "baseline_correct": 0}
        categories[cat]["total"] += 1
        if r["classifier_correct"]:
            categories[cat]["classifier_correct"] += 1
        if r["baseline_correct"]:
            categories[cat]["baseline_correct"] += 1

    print("Per-category breakdown:")
    for cat, stats in sorted(categories.items()):
        c_acc = stats["classifier_correct"] / stats["total"] * 100
        b_acc = stats["baseline_correct"] / stats["total"] * 100
        print(f"  {cat:<15} classifier={c_acc:.0f}%  baseline={b_acc:.0f}%")

    return beats_baseline

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--gateway", default="http://localhost:8080")
    parser.add_argument("--key", default="sk-prism-demo-alpha-001")
    args = parser.parse_args()

    success = run_eval(args.gateway, args.key)
    sys.exit(0 if success else 1)
