#!/usr/bin/env python3
"""Submit a completed local-Agent run to the V1.2 review inbox (stdlib only)."""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--task", required=True)
    parser.add_argument("--raw", help="Raw facts; reads stdin when omitted")
    parser.add_argument("--result", default="")
    parser.add_argument("--outcome", default="")
    parser.add_argument("--role", default="local-coding-agent")
    parser.add_argument("--source-ref", default="")
    parser.add_argument("--domain", default="engineering")
    parser.add_argument("--task-type", default="")
    parser.add_argument("--evidence-ref", action="append", default=[])
    parser.add_argument("--dedup-key", required=True)
    args = parser.parse_args()
    token = os.environ.get("LEDGER_TOKEN", "")
    base = os.environ.get("LEDGER_URL", "http://localhost:8080").rstrip("/")
    if not token:
        parser.error("LEDGER_TOKEN must contain an AGENT credential")
    raw = args.raw if args.raw is not None else sys.stdin.read()
    payload = {
        "agentRole": args.role,
        "sourceType": "AGENT_RUN",
        "sourceRef": args.source_ref or None,
        "task": args.task,
        "rawContent": raw,
        "result": args.result,
        "outcome": args.outcome,
        "taskContext": {"domain": args.domain, "taskType": args.task_type},
        "evidenceRefs": args.evidence_ref,
        "language": "zh-CN",
        "dedupKey": args.dedup_key,
    }
    request = urllib.request.Request(
        base + "/api/v2/capture/agent",
        data=json.dumps(payload, ensure_ascii=False).encode(),
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=130) as response:
            print(json.dumps(json.load(response), ensure_ascii=False, indent=2))
            return 0
    except urllib.error.HTTPError as error:
        print(error.read().decode(errors="replace"), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
