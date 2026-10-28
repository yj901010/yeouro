#!/usr/bin/env python3
"""Disposable cloud build convenience; not required on developer machines.

Runs the checked-in Gradle wrapper from the Android project, deriving Java proxy
flags from this command's HTTP_PROXY/HTTPS_PROXY (or lowercase equivalents).
Managed proxy ports may rotate per command. No proxy values are printed or
persisted. GRADLE_USER_HOME is respected, otherwise /tmp/yeouro-gradle-home is
used. Arguments and Gradle's exit code are passed through unchanged.

Examples:
  python3 tools/gradle-env.py -PjvmOnly=true :core-domain:test
  GRADLE_USER_HOME=/tmp/my-gradle python3 tools/gradle-env.py --version

This helper neither installs an Android SDK nor accepts licenses. Credentialed
or non-HTTP proxy URLs require the developer's own secure Gradle configuration.
"""
from __future__ import annotations

import os
from pathlib import Path
import shlex
import subprocess
import sys
from urllib.parse import urlsplit


def gradle_environment(source: dict[str, str]) -> dict[str, str]:
    env = source.copy()
    env.setdefault("GRADLE_USER_HOME", "/tmp/yeouro-gradle-home")
    proxy_options: list[str] = []
    for protocol in ("http", "https"):
        value = source.get(f"{protocol.upper()}_PROXY") or source.get(f"{protocol}_proxy")
        if not value:
            continue
        proxy = urlsplit(value)
        if proxy.username is not None or proxy.password is not None:
            raise ValueError("Credentialed proxies are not supported by this convenience helper.")
        if proxy.scheme != "http" or not proxy.hostname:
            raise ValueError("This convenience helper supports HTTP proxy URLs only.")
        port = proxy.port or 80
        proxy_options.extend((f"-D{protocol}.proxyHost={proxy.hostname}", f"-D{protocol}.proxyPort={port}"))
    if proxy_options:
        # Java uses pipe-separated patterns, not the comma-separated NO_PROXY form.
        patterns = ["localhost", "127.*", "[::1]"]
        for item in (source.get("NO_PROXY") or source.get("no_proxy") or "").split(","):
            host = item.strip()
            if not host or "/" in host:  # Java nonProxyHosts does not support CIDR.
                continue
            if host.startswith("."):
                patterns.extend((host[1:], "*" + host))
            else:
                patterns.append(host)
        proxy_options.append("-Dhttp.nonProxyHosts=" + "|".join(dict.fromkeys(patterns)))
        prior_options = shlex.split(source.get("GRADLE_OPTS", ""))
        env["GRADLE_OPTS"] = shlex.join(prior_options + proxy_options)
    return env


def main(arguments: list[str]) -> int:
    root = Path(__file__).resolve().parents[1]
    try:
        env = gradle_environment(dict(os.environ))
        return subprocess.call([str(root / "gradlew"), *arguments], cwd=root, env=env)
    except (ValueError, OSError) as error:
        # Do not include proxy URLs, environment values or argument strings.
        if isinstance(error, ValueError):
            print("Gradle environment helper: proxy/options configuration is unsupported or malformed.", file=sys.stderr)
        else:
            print("Gradle environment helper: could not start the checked-in Gradle wrapper.", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
