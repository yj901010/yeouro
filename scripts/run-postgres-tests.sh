#!/bin/sh
# Run tests against a fresh, loopback-only PostgreSQL cluster. No Docker required.
# Requires a full JDK 21, Maven Wrapper dependencies, and PostgreSQL 17 tools.
# Override PG_BIN if PostgreSQL binaries are not on PATH. Pass a command after --
# to use a preinstalled Maven or additional test options.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
PG_BIN=${PG_BIN:-$(dirname "$(command -v initdb || true)")}
for tool in initdb pg_ctl createdb; do
  if [ ! -x "$PG_BIN/$tool" ]; then
    echo "Missing PostgreSQL tool: $tool. Set PG_BIN to PostgreSQL 17's bin directory." >&2
    exit 1
  fi
done
PORT=${PG_TEST_PORT:-55432}
case "$PORT" in
  ''|*[!0-9]*) echo "PG_TEST_PORT must be an integer between 1024 and 65535." >&2; exit 1 ;;
esac
if [ "$PORT" -lt 1024 ] || [ "$PORT" -gt 65535 ]; then
  echo "PG_TEST_PORT must be between 1024 and 65535." >&2
  exit 1
fi
WORK=$(mktemp -d "${TMPDIR:-/tmp}/yeouro-pg-tests.XXXXXX")
cleanup() {
  "$PG_BIN/pg_ctl" -D "$WORK/data" -m immediate stop >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
"$PG_BIN/initdb" -D "$WORK/data" -U yeouro --auth-local=trust --auth-host=trust --no-locale -E UTF8 >"$WORK/init.log" 2>&1 || { cat "$WORK/init.log" >&2; exit 1; }
# Unix sockets are intentionally disabled; some cloud sandboxes permit TCP only.
"$PG_BIN/pg_ctl" -D "$WORK/data" -l "$WORK/postgres.log" \
  -o "-h 127.0.0.1 -p $PORT -c unix_socket_directories=''" -w start || { cat "$WORK/postgres.log" >&2; exit 1; }
"$PG_BIN/createdb" -h 127.0.0.1 -p "$PORT" -U yeouro yeouro_test
export TEST_DATABASE_URL="jdbc:postgresql://127.0.0.1:$PORT/yeouro_test"
export TEST_DATABASE_USERNAME=yeouro
export TEST_DATABASE_PASSWORD=
cd "$ROOT/services/backend"
if [ "${1:-}" = -- ]; then shift; fi
if [ "$#" -gt 0 ]; then
  "$@"
else
  sh ./mvnw --batch-mode --no-transfer-progress test
fi
