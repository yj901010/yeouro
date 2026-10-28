#!/usr/bin/env python3
"""Run real v1+v2 Kotlin HTTP tests against disposable loopback servers.

Requires packaged backend JAR, JDK21, Python cryptography, and Gradle dependencies.
Run inside run-postgres-tests.sh so TEST_DATABASE_* identifies a fresh test DB.
Creates ephemeral RSA/JWKS fixtures only; never uses real account credentials.
"""
from __future__ import annotations
import base64
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from cryptography.hazmat.primitives import hashes

ROOT = Path(__file__).resolve().parents[1]

def b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()

def port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]

def ready(p: int, process: subprocess.Popen) -> None:
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("Synthetic backend fixture exited before readiness")
        try:
            with socket.create_connection(("127.0.0.1", p), timeout=0.5):
                return
        except OSError:
            time.sleep(0.25)
    raise RuntimeError("Synthetic backend fixture startup timed out")

def main() -> int:
    database = os.environ.get("TEST_DATABASE_URL", "")
    if not re.fullmatch(r"jdbc:postgresql://(?:localhost|127\.0\.0\.1):\d{4,5}/[A-Za-z0-9_]+_test", database):
        raise SystemExit("Run with a disposable loopback TEST_DATABASE_URL ending _test")
    jar = ROOT / "services/backend/target/backend-0.1.0-SNAPSHOT.jar"
    if not jar.is_file():
        raise SystemExit("Package the backend first")
    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public = private.public_key().public_numbers()
    integer = lambda n: b64(n.to_bytes((n.bit_length() + 7) // 8, "big"))
    jwks = json.dumps({"keys": [{"kty": "RSA", "use": "sig", "alg": "RS256", "kid": "synthetic", "n": integer(public.n), "e": integer(public.e)}]}).encode()
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(jwks)
        def log_message(self, *_):
            pass
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    issuer = f"http://127.0.0.1:{server.server_port}"
    def token() -> str:
        header = b64(json.dumps({"alg": "RS256", "kid": "synthetic"}).encode())
        body = b64(json.dumps({"iss": issuer, "aud": "authenticated", "sub": str(uuid.uuid4()), "iat": int(time.time()), "exp": int(time.time()) + 600}).encode())
        signing = f"{header}.{body}".encode()
        return f"{header}.{body}.{b64(private.sign(signing, padding.PKCS1v15(), hashes.SHA256()))}"
    processes = []
    try:
        with tempfile.TemporaryDirectory(prefix="yeouro-native-smoke-") as temp:
            env = os.environ.copy()
            # Neutralize ambient Spring configuration in this isolated fixture.
            for name in list(env):
                if name.startswith(("SPRING_", "DATABASE_", "MIGRATION_DATABASE_", "PHONE_JWT_")):
                    env.pop(name)
            p1, p2 = port(), port()
            while p2 == p1:
                p2 = port()
            for version, p in (("v1", p1), ("v2", p2)):
                options = [f"--server.address=127.0.0.1", f"--server.port={p}"]
                local = env.copy()
                if version == "v2":
                    local.update(DATABASE_URL=database, DATABASE_USERNAME=os.environ["TEST_DATABASE_USERNAME"], DATABASE_PASSWORD=os.environ.get("TEST_DATABASE_PASSWORD", ""), PHONE_JWT_ISSUER=issuer, PHONE_JWT_JWKS_URI=issuer + "/jwks", PHONE_JWT_AUDIENCE="authenticated")
                    options += ["--spring.profiles.active=v2"]
                else:
                    options += ["--spring.datasource.url=jdbc:h2:mem:native_smoke;DB_CLOSE_DELAY=-1", "--yeouro.v1.enabled=true", "--yeouro.v2.enabled=false"]
                log = open(Path(temp) / f"{version}.log", "w")
                process = subprocess.Popen(["java", "-jar", str(jar), *options], cwd=temp, env=local, stdout=log, stderr=subprocess.STDOUT)
                processes.append((process, log))
                ready(p, process)
            tests = env.copy()
            tests.update(YEOURO_TEST_BASE_URL=f"http://127.0.0.1:{p1}", YEOURO_V2_TEST_BASE_URL=f"http://127.0.0.1:{p2}", YEOURO_V2_TEST_DRIVER_JWT=token(), YEOURO_V2_TEST_COMPANION_JWT=token())
            return subprocess.call(["python3", "tools/gradle-env.py", "-PjvmOnly=true", "test", "--rerun-tasks", "--no-daemon"], cwd=ROOT / "apps/android", env=tests)
    finally:
        for process, log in processes:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            log.close()
        server.shutdown()
        server.server_close()

if __name__ == "__main__":
    raise SystemExit(main())
