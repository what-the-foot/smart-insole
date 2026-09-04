#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
RUN_E2E_VALUE=${RUN_E2E:-0}
RUN_GATEWAY_E2E_VALUE=${RUN_GATEWAY_E2E:-0}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --e2e) RUN_E2E_VALUE=1 ;;
    --no-e2e) RUN_E2E_VALUE=0 ;;
    # Hardware-free gateway E2E (MySQL + backend + smart-insole-ble-gateway CLI required). Default: skip.
    --gateway-e2e) RUN_GATEWAY_E2E_VALUE=1 ;;
    --no-gateway-e2e) RUN_GATEWAY_E2E_VALUE=0 ;;
    *)
      echo "Usage: $0 [--e2e|--no-e2e] [--gateway-e2e|--no-gateway-e2e]" >&2
      exit 2
      ;;
  esac
  shift
done

if command -v python3 >/dev/null 2>&1; then
  PYTHON_BIN=python3
elif command -v python >/dev/null 2>&1; then
  PYTHON_BIN=python
else
  echo "[FAIL] Python 3 is required." >&2
  exit 2
fi

run_step() {
  label=$1
  shift
  echo "[RUN] $label"
  if "$@"; then
    echo "[PASS] $label"
  else
    code=$?
    echo "[FAIL] $label (exit $code)" >&2
    exit "$code"
  fi
}

run_step "contracts and fixtures" "$PYTHON_BIN" "$SCRIPT_DIR/validate_contracts.py"
run_step "integration helper unit tests" \
  "$PYTHON_BIN" -m unittest discover -s "$SCRIPT_DIR" -p "test_*.py"

BACKEND_DIR="$REPOSITORY_ROOT/backend"
if [ ! -d "$BACKEND_DIR" ]; then
  echo "[FAIL] Required backend directory is missing: $BACKEND_DIR" >&2
  exit 2
fi
if [ ! -f "$BACKEND_DIR/gradlew" ]; then
  echo "[FAIL] Required Gradle wrapper is missing: backend/gradlew" >&2
  exit 2
fi
(
  cd "$BACKEND_DIR"
  run_step "backend tests" ./gradlew test
  run_step "backend checks" ./gradlew check
)

FRONTEND_DIR="$REPOSITORY_ROOT/frontend"
if [ ! -d "$FRONTEND_DIR" ] || [ ! -f "$FRONTEND_DIR/package.json" ]; then
  echo "[FAIL] Required frontend/package.json is missing." >&2
  exit 2
fi

lock_count=0
[ -f "$FRONTEND_DIR/package-lock.json" ] && lock_count=$((lock_count + 1))
[ -f "$FRONTEND_DIR/pnpm-lock.yaml" ] && lock_count=$((lock_count + 1))
[ -f "$FRONTEND_DIR/yarn.lock" ] && lock_count=$((lock_count + 1))
if [ "$lock_count" -ne 1 ]; then
  echo "[FAIL] Expected exactly one frontend lockfile (package-lock.json, pnpm-lock.yaml, or yarn.lock)." >&2
  exit 2
fi

(
  cd "$FRONTEND_DIR"
  if [ -f package-lock.json ]; then
    command -v npm >/dev/null 2>&1 || { echo "[FAIL] npm is required." >&2; exit 2; }
    run_step "frontend OpenAPI generation" npm run api:generate
    run_step "frontend lint" npm run lint
    run_step "frontend tests" npm run test -- --run
    run_step "frontend build" npm run build
  elif [ -f pnpm-lock.yaml ]; then
    command -v pnpm >/dev/null 2>&1 || { echo "[FAIL] pnpm is required." >&2; exit 2; }
    run_step "frontend OpenAPI generation" pnpm run api:generate
    run_step "frontend lint" pnpm run lint
    run_step "frontend tests" pnpm run test -- --run
    run_step "frontend build" pnpm run build
  else
    command -v yarn >/dev/null 2>&1 || { echo "[FAIL] yarn is required." >&2; exit 2; }
    run_step "frontend OpenAPI generation" yarn run api:generate
    run_step "frontend lint" yarn run lint
    run_step "frontend tests" yarn run test --run
    run_step "frontend build" yarn run build
  fi
)

case "$RUN_E2E_VALUE" in
  1|true|TRUE|yes|YES|on|ON)
    run_step "optional API E2E smoke" "$PYTHON_BIN" "$SCRIPT_DIR/e2e_smoke.py"
    ;;
  *)
    echo "[SKIP] Optional API E2E smoke is disabled. Use --e2e or RUN_E2E=1 to enable it."
    ;;
esac

case "$RUN_GATEWAY_E2E_VALUE" in
  1|true|TRUE|yes|YES|on|ON)
    run_step "optional gateway mock E2E" "$PYTHON_BIN" "$SCRIPT_DIR/e2e_gateway_mock.py"
    ;;
  *)
    echo "[SKIP] Optional gateway mock E2E is disabled. Use --gateway-e2e or RUN_GATEWAY_E2E=1 (needs MySQL, backend and the gateway CLI)."
    ;;
esac

echo "[PASS] All required verification stages completed."
