#!/usr/bin/env python3
"""End-to-end check of the running product, using the real Python model and synthetic accounts.
No callback stubs, generated scoring answers or database rewrites are used.
"""
import argparse
import http.cookiejar
import json
import time
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from uuid import uuid4

COMPANY = "10000000-0000-4000-8000-000000000001"
PASSWORD = "DemoCredit2026!"


class Client:
    def __init__(self, base, email):
        self.base = base.rstrip("/")
        self.http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        token = self.call("GET", "/auth/csrf")[1]
        self.call("POST", "/login", urllib.parse.urlencode({"email": email, "password": PASSWORD}).encode(),
                  content_type="application/x-www-form-urlencoded", csrf_token=token)

    def call(self, method, path, body=None, content_type="application/json", expected=None, csrf=True, csrf_token=None, headers=None):
        request_headers = dict(headers or {})
        if body is not None and not isinstance(body, bytes):
            body = json.dumps(body).encode()
        if body is not None:
            request_headers["Content-Type"] = content_type
        if method not in {"GET", "HEAD"} and csrf:
            token = csrf_token or self.call("GET", "/auth/csrf")[1]
            request_headers[token["headerName"]] = token["token"]
        request = urllib.request.Request(self.base + path, data=body, headers=request_headers, method=method)
        try:
            response = self.http.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status, content = response.status, response.read()
            result = json.loads(content) if content and "application/json" in response.headers.get("Content-Type", "") else None
        if expected is not None and status != expected:
            raise AssertionError(f"{method} {path}: expected {expected}, got {status}: {result}")
        return status, result

    def create(self, financials, amount="1000000.00", months=24):
        payload = {"productId": "working-capital", "application": {"businessId": COMPANY,
            "requestedAmount": amount, "termMonths": months, "paymentFrequency": "MONTHLY",
            "financingPurpose": "WORKING_CAPITAL", "declaredCollateralValue": None}, "financials": financials}
        key = str(uuid4())
        identity = self.call("POST", "/api/credit/applications", payload, expected=201,
                             headers={"Idempotency-Key": key})[1]["applicationId"]
        replayed = self.call("POST", "/api/credit/applications", payload, expected=201,
                             headers={"Idempotency-Key": key})[1]["applicationId"]
        assert identity == replayed, "Create retry produced a duplicate application"
        return identity, payload

    def report(self, identity, financials):
        boundary = "credit-report-" + uuid4().hex
        content = json.dumps({"inn": "7700000000", "financials": financials}).encode()
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="financial-report.json"\r\n'
                'Content-Type: application/json\r\n\r\n').encode() + content + f'\r\n--{boundary}--\r\n'.encode()
        return self.call("POST", f"/api/credit/applications/{identity}/documents", body,
                         content_type="multipart/form-data; boundary=" + boundary, expected=201)[1]["documentId"]

    def view(self, identity):
        return self.call("GET", "/api/credit/applications/" + identity, expected=200)[1]

    def submit(self, identity):
        self.call("POST", f"/api/credit/applications/{identity}/submit", expected=202)

    def wait(self, identity, expected, timeout=60):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            view = self.view(identity)
            if view["state"] not in {"SUBMITTED", "SCORING"}:
                assert view["state"] == expected, f"Expected {expected}, got {view['state']} / {view.get('reasonCode')}"
                return view
            time.sleep(.25)
        raise AssertionError(f"Application {identity} did not finish within {timeout}s")

    def action(self, identity, action, expected=204):
        return self.call("POST", f"/api/credit/applications/{identity}/actions/{action}", expected=expected)


def finance(revenue="1000000.00", expenses="600000.00", debt="10000.00"):
    return {"monthlyRevenue": revenue, "monthlyExpenses": expenses, "existingMonthlyPayment": debt}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--scorer-url", default="http://localhost:8000")
    parser.add_argument("--output", default=str(Path(tempfile.gettempdir()) / "credit-mvp-verification.json"))
    parser.add_argument("--verify-snapshot", type=Path)
    args = parser.parse_args()
    started = time.monotonic()
    owner = Client(args.base_url, "owner@demo.test")
    if args.verify_snapshot:
        snapshot = json.loads(args.verify_snapshot.read_text())
        for case in snapshot["cases"]:
            view = owner.view(case["id"])
            assert view["state"] == case["state"], case
            if "decision" in case:
                assert view.get("decision") == case["decision"], "Decision changed after restart"
                assert [item["id"] for item in view["documents"]] == case["documentIds"], "Documents changed after restart"
                assert view["history"] == case["history"], "Audit history changed after restart"
        print("PASS: applications, decisions and history survived restart and re-login")
        return
    with urllib.request.urlopen(args.scorer_url.rstrip("/") + "/health", timeout=10) as response:
        model_version = json.load(response)["model_version"]
    artifact = Path(__file__).resolve().parents[1] / "code/scorer/artifacts/selected_model.validation.json"
    if not artifact.is_file():
        artifact = Path("artifacts/selected_model.validation.json")
    assert model_version == json.loads(artifact.read_text())["artifact_sha256"], "Running model differs from supplied artifact"
    cases = []
    def record(name, identity):
        view = owner.view(identity)
        cases.append({"name": name, "id": identity, "state": view["state"], "reason": view.get("reasonCode"),
            "decision": view.get("decision"), "documentIds": [item["id"] for item in view["documents"]], "history": view["history"]})
        print(f"PASS: {name}: {view['state']} / {view.get('reasonCode')}")

    good, payload = owner.create(finance())
    document = owner.report(good, finance())
    owner.submit(good)
    offer = owner.wait(good, "OFFERED")
    assert offer["reasonCode"] in {"APPROVED_CONDITIONS", "SCORER_OFFER"}
    assert float(offer["decision"]["amount"]) == 1000000
    owner.action(good, "disburse", expected=409)
    for action in ["accept", "sign", "disburse"]:
        owner.action(good, action)
        owner.action(good, action)
    assert owner.view(good)["state"] == "DISBURSED_SIMULATED"
    record("real model -> offer -> acceptance -> simulated signing/disbursement", good)

    limited_finance = finance("100000.00", "60000.00", "0.00")
    limited, _ = owner.create(limited_finance)
    owner.report(limited, limited_finance)
    owner.submit(limited)
    reduced = owner.wait(limited, "OFFERED")
    assert reduced["reasonCode"] == "TERMS_ADJUSTED"
    assert 100000 <= float(reduced["decision"]["amount"]) < 1000000
    record("same model input, different Java affordability decision", limited)

    declined, _ = owner.create(finance(), amount="5000000.00", months=6)
    owner.report(declined, finance())
    owner.submit(declined)
    assert owner.wait(declined, "REJECTED")["reasonCode"] == "SCORER_DECLINED"
    record("genuine high-rate model prediction -> decline", declined)

    poor_finance = finance("100000.00", "90000.00", "10000.00")
    poor, _ = owner.create(poor_finance)
    owner.report(poor, poor_finance)
    owner.submit(poor)
    assert owner.wait(poor, "REJECTED")["reasonCode"] == "INSUFFICIENT_INCOME"
    record("positive model outcome -> Java affordability rejection", poor)

    missing, draft = owner.create(None)
    owner.submit(missing)
    assert owner.view(missing)["state"] == "NEEDS_INFO"
    draft["financials"] = finance()
    owner.call("PUT", "/api/credit/applications/" + missing, draft, expected=204)
    owner.report(missing, finance())
    owner.submit(missing)
    owner.wait(missing, "OFFERED")
    record("request information -> complete -> same application reviewed", missing)

    cancelled, _ = owner.create(finance())
    owner.action(cancelled, "cancel")
    owner.call("POST", f"/api/credit/applications/{cancelled}/submit", expected=409)
    record("cancelled draft cannot be submitted", cancelled)

    viewer = Client(args.base_url, "viewer@demo.test")
    assert viewer.view(limited)["canManage"] is False
    viewer.action(limited, "accept", expected=403)
    other = Client(args.base_url, "other@demo.test")
    other.call("GET", "/api/credit/applications/" + good, expected=404)
    other.call("GET", f"/api/credit/applications/{good}/documents/{document}", expected=404)
    owner.call("POST", "/api/credit/applications", payload, csrf=False,
               headers={"Idempotency-Key": str(uuid4())}, expected=403)
    owner.call("POST", "/scored", {"id": good, "status": "DECLINED"}, csrf=False, expected=401)
    print("PASS: company isolation, viewer permissions, CSRF and callback service authorization")
    owner.call("POST", "/logout", expected=200)  # urllib follows 303 to the login HTML.
    owner = Client(args.base_url, "owner@demo.test")
    assert owner.view(good)["state"] == "DISBURSED_SIMULATED"
    snapshot = {"model_version": model_version, "cases": cases, "elapsed_seconds": round(time.monotonic() - started, 2)}
    Path(args.output).write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n")
    print("PASS: re-login preserves results; snapshot saved to", args.output)


if __name__ == "__main__":
    main()
