#!/usr/bin/env python3
"""Backend-only end-to-end check: real web commands, S3, workers and callback."""
import argparse
from http.cookiejar import CookieJar
import json
from html.parser import HTMLParser
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]

class Tokens(HTMLParser):
    token = None
    def handle_starttag(self, tag, attrs):
        fields = dict(attrs)
        if tag == "input" and fields.get("name") == "_csrf":
            self.token = fields.get("value")

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--env-file", default=".env")
    parser.add_argument("--check-restart", action="store_true")
    args = parser.parse_args()
    env_path = ROOT / args.env_file
    env = dict(line.split("=", 1) for line in env_path.read_text().splitlines()
               if line and not line.startswith("#") and "=" in line)
    compose = ["docker", "compose", "--env-file", str(env_path)]
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()), NoRedirect())

    def http(path, data=None, content_type=None):
        req = urllib.request.Request(args.base_url + path, data=data,
            headers={"Content-Type": content_type} if content_type else {})
        try:
            result = opener.open(req, timeout=30)
        except urllib.error.HTTPError as error:
            result = error
        return result.code, result.headers, result.read()

    def form(path, fields):
        return http(path, urllib.parse.urlencode(fields, doseq=True).encode(), "application/x-www-form-urlencoded")

    def csrf():
        code, _, body = http("/login")
        assert code == 200, ("login page", code)
        token = Tokens(); token.feed(body.decode()); assert token.token
        return token.token

    def sql(query):
        return subprocess.check_output(compose + ["exec", "-T", "postgres", "psql", "-U", "postgres", "-d", "credit", "-At", "-c", query], cwd=ROOT, text=True).strip()

    def state(app):
        return sql(f"select status||':'||processing_state from core.application where id='{app}'")

    def version(app):
        return sql(f"select version from core.application where id='{app}'")

    def wait(app, wanted):
        end = time.monotonic() + 90
        while time.monotonic() < end:
            current = state(app)
            if current == wanted:
                return
            if current.endswith(":FAILED"):
                raise AssertionError(("technical failure", app, current))
            time.sleep(1)
        raise AssertionError(("timeout", app, state(app), wanted))

    code, _, _ = form("/login", {"username": "alice", "password": env["DEMO_PASSWORD"], "_csrf": csrf()})
    assert code == 302, ("authentication", code)
    token = csrf()
    boundary = "credit-demo-" + uuid.uuid4().hex
    company = "20000000-0000-0000-0000-000000000001"
    report = (ROOT / "demo/financial-report-1.json").read_bytes()
    parts = []
    for name, value in {"_csrf": token, "companyId": company, "kind": "FINANCIAL_REPORT", "command_id": str(uuid.uuid4())}.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="report.json"\r\nContent-Type: application/json\r\n\r\n'.encode() + report + f'\r\n--{boundary}--\r\n'.encode())
    code, headers, _ = http("/app/documents", b"".join(parts), "multipart/form-data; boundary=" + boundary)
    assert code == 303, ("document upload", code)
    document = uuid.UUID(headers["Location"].split("#document-")[1])
    code, _, downloaded = http(f"/app/documents/{document}/content")
    assert code == 200 and downloaded == report

    def create(with_report):
        fields = {"_csrf": token, "companyId": company, "productId": "working-capital",
                  "amountMinor": 100000000, "termMonths": 12, "purpose": "Синтетическая заявка",
                  "revenueMinor": 100000000, "expensesMinor": 60000000,
                  "debtMinor": 5000000, "period": "2026-09", "command_id": str(uuid.uuid4())}
        if with_report:
            fields["documentIds"] = str(document)
        code, headers, body = form("/app/applications", fields)
        assert code == 303, ("create", code, body.decode())
        return uuid.UUID(headers["Location"].rsplit("/", 1)[1])

    app = create(True)
    code, _, _ = form(f"/app/applications/{app}/submit", {"version": version(app), "command_id": str(uuid.uuid4())})
    assert code == 403, ("missing csrf", code)
    if args.check_restart:
        subprocess.run(compose + ["stop", "credit-worker"], cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
    try:
        command = {"_csrf": token, "version": version(app), "command_id": str(uuid.uuid4())}
        for _ in range(2):
            code, _, body = form(f"/app/applications/{app}/submit", command)
            assert code == 303, ("submit", code, body.decode())
        if args.check_restart:
            time.sleep(1)
            assert state(app) == "SUBMITTED:RUNNING"
    finally:
        if args.check_restart:
            subprocess.run(compose + ["start", "credit-worker"], cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
    wait(app, "REJECTED:IDLE")
    assert sql(f"select count(*) from core.application_revision where application_id='{app}'") == "1"
    assert "SCORER_ALWAYS_REJECT" in sql(f"select reasons from core.decision where application_id='{app}'")

    incomplete = create(False)
    code, _, _ = form(f"/app/applications/{incomplete}/submit", {"_csrf": token, "version": version(incomplete), "command_id": str(uuid.uuid4())})
    assert code == 303
    wait(incomplete, "NEEDS_INFO:IDLE")
    code, _, _ = form(f"/app/applications/{incomplete}", {"_csrf": token, "version": version(incomplete), "command_id": str(uuid.uuid4()), "amountMinor": 100000000, "termMonths": 12, "purpose": "Дополнение сведений", "revenueMinor": 100000000, "expensesMinor": 60000000, "debtMinor": 5000000, "period": "2026-09", "documentIds": str(document)})
    assert code == 303
    code, _, _ = form(f"/app/applications/{incomplete}/resubmit", {"_csrf": token, "version": version(incomplete), "command_id": str(uuid.uuid4())})
    assert code == 303
    wait(incomplete, "REJECTED:IDLE")
    assert sql(f"select count(*) from core.application_revision where application_id='{incomplete}'") == "2"
    for path in ["/internal/processed", "/request", "/actuator/health"]:
        assert http(path)[0] == 404, ("gateway boundary", path)
    assert http(f"/app/applications/{app}")[0] == 501
    assert http("/openapi/internal.yaml")[0] == 200
    code, _, _ = form("/logout", {"_csrf": token})
    assert code == 302
    code, _, _ = http(f"/app/documents/{document}/content")
    assert code == 302, ("session invalidation", code)
    print(json.dumps({"result": "PASS", "application_id": str(app), "resubmitted_application_id": str(incomplete), "document_id": str(document), "worker_restart_checked": args.check_restart}, ensure_ascii=False))

if __name__ == "__main__":
    main()
