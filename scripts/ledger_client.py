"""Small synchronous Memory Gateway client; Python 3.10+, standard library only."""
import json
import os
import urllib.error
import urllib.request


class LedgerError(RuntimeError):
    def __init__(self, status, payload):
        self.status, self.payload = status, payload
        super().__init__(f"HTTP {status}: {payload}")


class LedgerClient:
    def __init__(self, base_url=None, token=None, timeout=30):
        self.base_url = (base_url or os.getenv("LEDGER_URL", "http://localhost:8080")).rstrip("/")
        self.token = token or os.environ["LEDGER_TOKEN"]
        self.timeout = timeout

    def request(self, method, path, payload=None):
        data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(self.base_url + "/api/v1" + path, data=data, method=method,
            headers={"Authorization": "Bearer " + self.token, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as res:
                return json.load(res)
        except urllib.error.HTTPError as err:
            try:
                body = json.load(err)
            except (ValueError, UnicodeDecodeError):
                body = {"message": "non-JSON error"}
            raise LedgerError(err.code, body) from err

    def capture(self, content, **context):
        return self.request("POST", "/candidates/capture", {"content": content, **context})

    def search(self, query, **filters):
        return self.request("POST", "/experiences/search", {"query": query, **filters})

    def review(self, candidate_id, draft, reason="human review"):
        # A worker may update the candidate between GET and review; retry using the latest revision.
        for attempt in range(4):
            candidate = self.request("GET", f"/candidates/{candidate_id}")
            try:
                return self.request("POST", f"/candidates/{candidate_id}/review",
                    {"expectedRevision": candidate["revision"], "draft": draft, "reason": reason})
            except LedgerError as err:
                if err.payload.get("code") != "CANDIDATE_REVISION_CONFLICT" or attempt == 3:
                    raise

    def verify(self, reviewed, **promotion):
        return self.request("POST", f"/candidates/{reviewed['id']}/verify",
            {"expectedRevision": reviewed["revision"], **promotion})
