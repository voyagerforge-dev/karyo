#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# Karyo WMS - E2E Test Runner
# Starts the full stack if not already running, then runs Playwright E2E tests.
#
# Usage:
#   ./scripts/run-e2e.sh                      Run all E2E tests
#   ./scripts/run-e2e.sh auth.spec.ts         Run specific test file
#   ./scripts/run-e2e.sh --headed             Run in headed browser mode
#   ./scripts/run-e2e.sh --debug              Run with Playwright inspector
#
# Provisions four ephemeral human users from generated or externally supplied
# KARYO_E2E_{ADMIN,MANAGER,OPERATOR,VIEWER}_PASSWORD values and requires
# KEYCLOAK_ADMIN_CLIENT_SECRET. The production realm has no seeded human users and there is no
# bootstrap-admin fallback. Because provisioning and session tests mutate Keycloak, the target must
# pass the loopback or explicit remote-origin provisioning guard below. See docs/guides/implementer-guide.md#run-browser-acceptance.
# BASE_URL defaults to http://localhost (the same default as Playwright).
# ============================================================================

# Help must be available before toolchain checks, network probes or stack/provisioning changes.
for argument in "$@"; do
    if [ "$argument" = "--help" ] || [ "$argument" = "-h" ]; then
        printf '%s\n' 'Usage: ./scripts/run-e2e.sh [Playwright options or spec filenames]

Runs deterministic browser tests against BASE_URL (default http://localhost).
An unavailable local target starts the configured Compose stack after environment validation.
An unavailable remote target fails; it never starts a local stack.

Required: Node.js 22.6+, browser dependencies and a disposable configured target.
KEYCLOAK_ADMIN_CLIENT_SECRET authenticates the permanent user-management service account.
For a local target it may be read from the validated scripts/.env.prod; remote targets require
it explicitly. There is no bootstrap-administrator fallback.

The runner provisions ephemeral users and generates passwords unless supplied through
KARYO_E2E_{ADMIN,MANAGER,OPERATOR,VIEWER}_PASSWORD. It changes target Keycloak state.
Remote provisioning additionally requires KARYO_E2E_ALLOW_REMOTE_PROVISION=true and
KARYO_E2E_EXPECTED_ORIGIN equal to the exact target origin. Never point this at real users/data.

For isolated local deployments retain the same COMPOSE_PROJECT_NAME, image tags and
NGINX_HTTP_PORT used at deployment, and set BASE_URL to that exact port/origin.
See docs/guides/implementer-guide.md#run-browser-acceptance for the connected workflow.
Operational checks: ./scripts/run-e2e.sh --config=playwright.operational.config.ts <file>.
Direct npm/Playwright calls require supplying the provisioning opt-in and credentials yourself.
Optional direct database cleanup needs KARYO_PG_CONTAINER set to your verified disposable
container, plus KARYO_CONTAINER_CLI for its runtime (default podman). Without a container the
sweep is disabled; browser tests still run with unique records.

This help does not connect, install tools, start containers or provision users.'
        exit 0
    fi
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[e2e]${NC} $*"; }
log_ok()   { echo -e "${GREEN}[e2e]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[e2e]${NC} $*"; }
log_err()  { echo -e "${RED}[e2e]${NC} $*"; }

# --- Check if the stack is already running ---

# The target is resolved through tests/e2e/fixtures/provisioning-target.ts -- the same module the
# Playwright fixtures call before reading KEYCLOAK_ADMIN_CLIENT_SECRET. One implementation, so
# this wrapper and a direct `npx playwright test` cannot drift apart on what a permitted stack is.
# A missing or too-old toolchain is a toolchain problem, and saying anything else sends the
# operator to debug their own BASE_URL over a Node version. The interpreter is checked before it
# is used, so a non-zero exit below genuinely means the target was rejected.
NODE_MIN_MAJOR=22
NODE_MIN_MINOR=6
require_node() {
    if ! command -v node &>/dev/null; then
        log_err "Node.js not found. scripts/run-e2e.sh needs Node ${NODE_MIN_MAJOR}.${NODE_MIN_MINOR}+ to resolve the E2E target; install it and rerun."
        exit 1
    fi
    local raw major minor
    raw=$(node --version 2>/dev/null)          # e.g. v22.22.2
    major=${raw#v}; major=${major%%.*}
    minor=${raw#v*.}; minor=${minor%%.*}
    if [ "$major" -lt "$NODE_MIN_MAJOR" ] ||
       { [ "$major" -eq "$NODE_MIN_MAJOR" ] && [ "$minor" -lt "$NODE_MIN_MINOR" ]; }; then
        log_err "Node ${raw} is too old. scripts/run-e2e.sh needs Node ${NODE_MIN_MAJOR}.${NODE_MIN_MINOR}+ for --experimental-strip-types, which resolves the E2E target; upgrade Node and rerun."
        exit 1
    fi
}
require_node

target_args=()
if [ -n "${BASE_URL:-}" ]; then
    target_args+=("$BASE_URL")
fi
target_error=""
target_stderr=$(mktemp "${TMPDIR:-/tmp}/karyo-e2e-target.XXXXXXXXXX")
chmod 600 "$target_stderr"
trap 'rm -f "$target_stderr"' EXIT
if ! target_result=$(node --no-warnings --experimental-strip-types \
        "$PROJECT_ROOT/tests/e2e/fixtures/provisioning-target-cli.ts" \
        --classify "${target_args[@]}" 2>"$target_stderr"); then
    target_error=$(cat "$target_stderr" 2>/dev/null)
    log_err "Refusing to run: the E2E target is not a permitted provisioning stack"
    [ -n "$target_error" ] && log_err "$target_error"
    exit 1
fi
IFS=$'\t' read -r BASE_URL TARGET_LOCALITY <<< "$target_result"
export BASE_URL

check_stack() {
    local http_code
    http_code=$(curl -so /dev/null -w '%{http_code}' "$BASE_URL" 2>/dev/null || echo "000")
    # 200 = SPA served, 302 = Keycloak redirect, 401/403 = auth required
    [[ "$http_code" =~ ^(200|302|301|401|403)$ ]]
}

if check_stack; then
    log_ok "Stack is already running at $BASE_URL"
elif [ "$TARGET_LOCALITY" = "remote" ]; then
    log_err "Remote E2E target is unavailable at $BASE_URL; refusing to start the local stack"
    exit 1
else
    log_info "Stack not detected at $BASE_URL -- starting it..."

    ENV_FILE="$PROJECT_ROOT/scripts/.env.prod"
    "$PROJECT_ROOT/scripts/deploy-server.sh" --validate-env "$ENV_FILE" >/dev/null
    python3 "$PROJECT_ROOT/scripts/render_compose_env.py" "$ENV_FILE" >/dev/null
    COMPOSE_FILE="$PROJECT_ROOT/infrastructure/docker/docker-compose.prod.yml"

    if [ ! -f "$COMPOSE_FILE" ]; then
        log_err "Compose file not found: $COMPOSE_FILE"
        log_err "Run the deploy script first: ./scripts/deploy-server.sh"
        exit 1
    fi

    # Detect container runtime
    if docker compose version &>/dev/null; then
        COMPOSE_CMD="docker compose"
    elif command -v podman-compose &>/dev/null; then
        COMPOSE_CMD="podman-compose"
    else
        log_err "No container runtime found. Install Docker (with compose plugin) or podman-compose."
        exit 1
    fi

    $COMPOSE_CMD -f "$COMPOSE_FILE" up -d

    # Wait for the stack to be ready (up to 5 minutes)
    log_info "Waiting for stack to become healthy..."
    elapsed=0
    while ! check_stack; do
        if [ "$elapsed" -ge 300 ]; then
            log_err "Stack did not become healthy within 300s"
            log_err "Check container logs: $COMPOSE_CMD -f $COMPOSE_FILE logs"
            exit 1
        fi
        sleep 5
        elapsed=$((elapsed + 5))
        echo -n "."
    done
    echo ""
    log_ok "Stack is healthy (${elapsed}s)"
fi

if [ -z "${KEYCLOAK_ADMIN_CLIENT_SECRET:-}" ]; then
    if [ "$TARGET_LOCALITY" = "remote" ]; then
        log_err "Remote E2E provisioning requires KEYCLOAK_ADMIN_CLIENT_SECRET to be supplied explicitly"
        exit 1
    fi
    ENV_FILE="$PROJECT_ROOT/scripts/.env.prod"
    "$PROJECT_ROOT/scripts/deploy-server.sh" --validate-env "$ENV_FILE" >/dev/null
    KEYCLOAK_ADMIN_CLIENT_SECRET=$(grep '^KEYCLOAK_ADMIN_CLIENT_SECRET=' "$ENV_FILE" | cut -d= -f2-)
fi
export KEYCLOAK_ADMIN_CLIENT_SECRET
export KARYO_E2E_PROVISION=true
random_password() { python3 -c 'import secrets; print(secrets.token_urlsafe(32))'; }
export KARYO_E2E_ADMIN_PASSWORD="${KARYO_E2E_ADMIN_PASSWORD:-$(random_password)}"
export KARYO_E2E_MANAGER_PASSWORD="${KARYO_E2E_MANAGER_PASSWORD:-$(random_password)}"
export KARYO_E2E_OPERATOR_PASSWORD="${KARYO_E2E_OPERATOR_PASSWORD:-$(random_password)}"
export KARYO_E2E_VIEWER_PASSWORD="${KARYO_E2E_VIEWER_PASSWORD:-$(random_password)}"

# --- Run Playwright tests ---

log_info "Running Playwright E2E tests..."
echo ""

cd "$PROJECT_ROOT/tests/e2e"

# Ensure local dependencies are installed
if [ ! -d "node_modules/@playwright/test" ]; then
    log_info "Installing E2E dependencies..."
    npm install
fi

# Install Chromium browser if not already cached
# Try --with-deps first (installs OS libs via apt/dnf), fall back to browser-only install
if ! npx --no-install playwright install chromium --with-deps 2>/dev/null; then
    log_warn "Could not install system deps (needs root + apt/dnf). Installing browser only..."
    npx --no-install playwright install chromium
fi

# Run tests, passing through all CLI args
npx --no-install playwright test "$@"
EXIT_CODE=$?

echo ""
if [ "$EXIT_CODE" -eq 0 ]; then
    log_ok "All E2E tests passed!"
else
    log_warn "Some tests failed (exit code: $EXIT_CODE)"
fi

echo ""
log_info "HTML report: $PROJECT_ROOT/tests/e2e/playwright-report/index.html"
log_info "View report: cd tests/e2e && npx playwright show-report"
echo ""
log_info "Stack is still running. Stop it with:"
log_info "  docker compose -f infrastructure/docker/docker-compose.prod.yml down"

exit "$EXIT_CODE"
