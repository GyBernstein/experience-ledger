"""Create a fresh, clearly labelled sample dataset through the Gateway and assert the V1 loop.
Run: LEDGER_TOKEN=... python scripts/seed_demo.py
Each run has a new run ID. It never deletes existing data.
"""
from datetime import datetime, timezone
import json
import uuid
from urllib.parse import urlencode
from ledger_client import LedgerClient


def now():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def run():
    client = LedgerClient()
    tag = "DEMO-" + uuid.uuid4().hex[:10]
    captured = client.capture("候选生成过早剪枝会损失可用组合", sourceSystem="demo", sourceRef=tag,
        eventType="OPTIMIZATION_COMPLETED", episode={
            "title": tag + " 棒材组合优化", "summary": "演示数据，非生产事实", "occurredAt": now(),
            "goal": "提高有效棒材利用率", "importantObservations": ["候选过早排除"],
            "toolsUsed": ["test runner"], "importantToolResults": ["selected=79"],
            "changes": ["延后局部剪枝"], "validation": ["回归样例通过"], "result": "形成待审核经验"},
        evidence=[
            {"evidenceType": "TEST_RESULT", "sourceSystem": "demo", "sourceRef": tag + "/test",
             "snapshot": {"selected": 79, "effective": 79, "demo": True}, "observedAt": now()},
            {"evidenceType": "ENGINEER_CONFIRMATION", "sourceSystem": "demo", "sourceRef": tag + "/review",
             "snapshot": {"confirmed": True, "demo": True}, "observedAt": now()}])
    evidence_ids = captured["extracted_json"]["capturedEvidenceIds"]
    draft = {
        "title": tag + " 候选生成与全局互斥优化应分离",
        "summary": "示例：优化候选生成提高组合利用率", "problem": "可用棒材未被充分选择",
        "decision": "保留更多候选", "action": "移除过早局部剪枝", "outcomeSummary": "79/79 有效棒材被选择",
        "lesson": "候选阶段避免用局部最优替代全局选择",
        "validFrom": "2026-01-01T00:00:00Z", "applicability": {"lengthMin": 700, "material": ["A", "B"]},
        "constraints": {"gpuAvailable": False, "priority": "global_utilization"},
        "claims": [
            {"claimType": "OBSERVATION", "content": "样例中有效棒材全部被选择", "originType": "OBSERVED",
             "evidence": [{"evidenceId": evidence_ids[0], "supportType": "SUPPORTS"}]},
            {"claimType": "CAUSAL_HYPOTHESIS", "content": "过早剪枝可能损害全局利用率", "originType": "AGENT_DERIVED",
             "derivationMethod": "对比候选集合与优化结果的操作摘要",
             "evidence": [{"evidenceId": evidence_ids[0], "supportType": "CONTEXT"},
                          {"evidenceId": evidence_ids[1], "supportType": "SUPPORTS"}]}],
        "contextRefs": [{"refType": "PROJECT", "refValue": "rod_matching", "sourceSystem": "demo"}]}
    reviewed = client.review(captured["id"], draft)
    v1 = client.verify(reviewed, mode="CREATE_NEW_FAMILY", experienceKey=tag, domain="rod_matching",
                       experienceType="OPTIMIZATION", reason="示例人工审核")
    found = client.search("候选 剪枝", domain="rod_matching", context={"length": 800, "material": "A"}, limit=100)
    assert any(x["versionId"] == v1["id"] for x in found["results"])
    usage = client.request("POST", "/usages", {"versionId": v1["id"], "recommended": True, "actuallyUsed": True,
        "queryContext": {"demo": True}, "immediateOutcome": {"outcomeType": "SUCCESS", "metrics": {"selected": 79}, "observedAt": now()}})
    client.request("POST", f"/usages/{usage['id']}/outcomes", {"outcomeType": "PARTIAL_SUCCESS", "notes": "另一约束下需要调整", "observedAt": now()})
    again = client.search("候选 剪枝", domain="rod_matching", limit=100)
    package = next(x for x in again["results"] if x["versionId"] == v1["id"])
    assert package["usageStats"]["usage_count"] == 1 and package["outcomeStats"]["success"] == 1
    c2 = client.capture("补充材料适用范围", sourceSystem="demo", sourceRef=tag + "/v2", eventType="REVIEW")
    draft2 = {**draft, "title": tag + " 候选生成策略 V2", "applicability": {"lengthMin": 700, "material": ["A"]}}
    reviewed2 = client.review(c2["id"], draft2)
    v2 = client.verify(reviewed2, mode="CREATE_NEW_VERSION", familyId=v1["family_id"], expectedSupersedesId=v1["id"], reason="适用范围收窄")
    history = client.request("GET", f"/experiences/{v1['family_id']}/history")
    assert len(history) == 2 and history[0]["status"] == "SUPERSEDED"
    old = client.request("GET", f"/experiences/{v1['family_id']}?" + urlencode({"validAt": "2026-06-01T00:00:00Z", "knownAt": v1["recorded_at"]}))
    assert old["versionId"] == v1["id"]
    failure = client.capture("时间预算严格时，保留全部候选会超时", sourceSystem="demo", sourceRef=tag + "/failure", eventType="FAILURE_CONFIRMED")
    failure_draft = {**draft, "title": tag + " 严格时间预算下的候选规模风险", "lesson": "按预算限制候选规模并保留回退策略",
        "outcomeSummary": "示例求解超时", "claims": [{"claimType": "WARNING", "content": "候选规模过大会增加求解耗时", "originType": "HUMAN_ASSERTED"}],
        "constraints": {"maxExecutionSeconds": 1}, "applicability": {"lengthMin": 700}}
    failure_review = client.review(failure["id"], failure_draft)
    vf = client.verify(failure_review, mode="CREATE_NEW_FAMILY", experienceKey=tag + "-FAILURE", domain="rod_matching", experienceType="FAILURE", reason="记录失败经验")
    client.request("POST", "/relations", {"fromVersionId": v2["id"], "toVersionId": vf["id"], "relationType": "CONTRADICTS", "reason": "约束不同导致建议相反"})
    audit = client.request("GET", "/audit?" + urlencode({"targetId": v1["id"]}))
    assert any(x["action"] == "SUPERSEDE" for x in audit)
    current = client.request("GET", f"/experiences/{v1['family_id']}")
    assert current["contradictionWarnings"]
    result = {"run": tag, "familyId": v1["family_id"], "v1": v1["id"], "v2": v2["id"],
              "failureFamilyId": vf["family_id"], "usageId": usage["id"], "searchMode": found["mode"], "checks": "PASS"}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return result


if __name__ == "__main__":
    run()
