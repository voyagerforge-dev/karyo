#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# Karyo WMS - Production Deploy Script
# Builds all artifacts and starts the full stack with health verification.
# Re-running recreates the selected stack; plan an outage and follow DEPLOY.md.
# Supports both Docker (Ubuntu/cloud) and Podman (Fedora/local) runtimes.
#
# Usage:
#   ./scripts/deploy-server.sh              Full rebuild + deploy (default)
#   ./scripts/deploy-server.sh --quick      Skip build, just restart containers
#   ./scripts/deploy-server.sh --reset-db   Destroy DB volumes, start fresh
#   ./scripts/deploy-server.sh --help       Show all options
# ============================================================================

# --- Colors and helpers ---

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_stage() {
    local n=$1
    shift
    echo -e "\n${BLUE}[Stage ${n}/8]${NC} $*\n"
}

log_ok() {
    echo -e "  ${GREEN}OK${NC} $*"
}

log_warn() {
    echo -e "  ${YELLOW}WARN${NC} $*"
}

log_err() {
    echo -e "  ${RED}ERROR${NC} $*"
}

# --- CLI Flag Parsing ---

QUICK=false
RESET_DB=false
FRESH_DATABASE=false
VALIDATE_ENV_FILE=""

show_help() {
    echo "Karyo WMS Deploy Script"
    echo ""
    echo "Usage: ./scripts/deploy-server.sh [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --quick             Skip build stages (2-4), just restart containers."
    echo "                      Use after .env.prod changes or config-only updates."
    echo "  --reset-db          Destroy database volumes and start fresh."
    echo "                      Supply new one-time bootstrap credentials first; after reset,"
    echo "                      provision and verify the first user, then delete bootstrap."
    echo "  --validate-env FILE Validate one production environment file and exit."
    echo "  --help, -h          Show this help message and exit."
    echo ""
    echo "Examples:"
    echo "  ./scripts/deploy-server.sh              Full rebuild + deploy"
    echo "  ./scripts/deploy-server.sh --quick      Restart containers only"
    echo "  ./scripts/deploy-server.sh --reset-db   Fresh start (wipes data)"
    echo "  ./scripts/deploy-server.sh --validate-env scripts/.env.prod"
    echo ""
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --quick)   QUICK=true; shift ;;
        --reset-db) RESET_DB=true; shift ;;
        --validate-env)
            if [ $# -lt 2 ]; then
                log_err "--validate-env requires a file path"
                exit 1
            fi
            VALIDATE_ENV_FILE=$2
            shift 2
            ;;
        --help|-h) show_help; exit 0 ;;
        *) log_err "Unknown flag: $1"; echo ""; show_help; exit 1 ;;
    esac
done

# --- Helper functions ---

check_command() {
    local name=$1
    local cmd=$2
    if ! command -v "$cmd" &>/dev/null; then
        log_err "$name not found (command: $cmd)"
        return 1
    fi
    local version
    version=$("$cmd" --version 2>&1 | head -1)
    log_ok "$name: $version"
}

# Stage 3 runs `npm ci` in frontend/web on the HOST (the container builds have their own pinned
# Node), and that package.json declares engines.node >= 22. npm only *warns* on an engine
# mismatch, so an older host Node would sail past install and fail deep inside the build with an
# unrelated-looking error. Reject it here, where the message can name the version.
# scripts/run-e2e.sh enforces the same major for its own reason (22.6+ for
# --experimental-strip-types), so the two checks agree on the floor.
NODE_MIN_MAJOR=22
check_node_version() {
    local raw major
    raw=$(node --version 2>/dev/null || true)   # e.g. v22.22.2
    major=${raw#v}; major=${major%%.*}
    if ! [[ "$major" =~ ^[0-9]+$ ]] || [ "$major" -lt "$NODE_MIN_MAJOR" ]; then
        log_err "Node ${raw:-(version unreadable)} is too old. The frontend build needs Node ${NODE_MIN_MAJOR}+ (frontend/web declares engines.node >= ${NODE_MIN_MAJOR}); upgrade Node and re-run."
        return 1
    fi
}

wait_for_health() {
    local name=$1
    local cmd=$2
    local timeout=$3
    local service_name=${4:-$(echo "$name" | tr '[:upper:]' '[:lower:]')}
    local elapsed=0
    local interval=3

    echo -n "  Waiting for $name "
    while ! eval "$cmd" &>/dev/null; do
        if [ "$elapsed" -ge "$timeout" ]; then
            echo ""
            log_err "$name did not become healthy within ${timeout}s"
            # Print container logs for diagnostics before returning
            echo "  --- Last 20 lines of $name logs ---"
            $COMPOSE_CMD -f "$COMPOSE_FILE" logs --tail=20 "$service_name" 2>/dev/null || true
            echo "  --- End logs ---"
            return 1
        fi
        echo -n "."
        sleep "$interval"
        elapsed=$((elapsed + interval))
    done
    echo ""
    log_ok "$name is healthy (${elapsed}s)"
}

# --- Detect container runtime ---

detect_runtime() {
    if docker compose version &>/dev/null; then
        COMPOSE_CMD="docker compose"
        CONTAINER_CMD="docker"
        log_ok "Container runtime: Docker (docker compose)"
    elif command -v podman-compose &>/dev/null; then
        COMPOSE_CMD="podman-compose"
        CONTAINER_CMD="podman"
        log_ok "Container runtime: Podman (podman-compose)"
    else
        log_err "No container runtime found. Install Docker (with compose plugin) or podman-compose."
        exit 1
    fi
}

# Both Docker Compose and podman-compose set these labels. A name substring can
# select a healthy container from another deployment and conceal this stack's failure.
service_container_id() {
    $CONTAINER_CMD ps -q \
        --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME}" \
        --filter "label=com.docker.compose.service=$1" 2>/dev/null | head -1 | tr -d '[:space:]'
}

check_service_healthy() {
    local service_name=$1
    local container_id
    container_id=$(service_container_id "$service_name")
    if [ -z "$container_id" ]; then
        return 1
    fi
    # Exact match — plain 'grep -q "healthy"' also matches "unhealthy"
    [ "$($CONTAINER_CMD inspect --format '{{.State.Health.Status}}' "$container_id" 2>/dev/null)" = "healthy" ]
}

bootstrap_credentials_required() {
    [ "$RESET_DB" = true ]
}

bootstrap_credentials_assigned() {
    local file=${1:-scripts/.env.prod}
    local var
    for var in KC_BOOTSTRAP_ADMIN_USERNAME KC_BOOTSTRAP_ADMIN_PASSWORD; do
        local -a assignments=()
        mapfile -t assignments < <(grep "^${var}=" "$file" 2>/dev/null || true)
        [ ${#assignments[@]} -eq 1 ] || return 1
        [ -n "${assignments[0]#*=}" ] || return 1
    done
    return 0
}

postgres_container_id() {
    service_container_id postgresql
}

keycloak_database_initialized() {
    local container_id realm_table master_realm
    container_id=$(postgres_container_id)
    [ -n "$container_id" ] || return 1
    # Expand credentials inside the container shell, never in the host command arguments.
    # shellcheck disable=SC2016
    realm_table=$($CONTAINER_CMD exec "$container_id" sh -c \
        'PGPASSWORD="$POSTGRES_PASSWORD" psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U "$POSTGRES_USER" -d keycloak -Atqc "SELECT COALESCE(to_regclass('"'"'public.realm'"'"')::text, '"'"''"'"')"' \
        2>/dev/null) || return 1
    [ -n "$realm_table" ] || return 1
    # shellcheck disable=SC2016 # Credentials expand inside the container shell.
    master_realm=$($CONTAINER_CMD exec "$container_id" sh -c \
        'PGPASSWORD="$POSTGRES_PASSWORD" psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U "$POSTGRES_USER" -d keycloak -Atqc "SELECT EXISTS (SELECT 1 FROM public.realm WHERE name = '"'"'master'"'"')"' \
        2>/dev/null) || return 1
    [ "$master_realm" = "t" ]
}

detect_fresh_database() {
    if keycloak_database_initialized; then
        FRESH_DATABASE=false
    else
        FRESH_DATABASE=true
    fi
}

verify_postgres_role_password() {
    local container_id role
    container_id=$(postgres_container_id)
    role=$(grep "^POSTGRES_USER=" scripts/.env.prod | cut -d= -f2-)
    if [ -z "$container_id" ] || [ -z "$role" ]; then
        log_err "PostgreSQL role password could not be verified before starting Keycloak"
        exit 1
    fi
    # shellcheck disable=SC2016 # Credentials expand inside the container shell.
    if $CONTAINER_CMD exec "$container_id" \
        sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U "$POSTGRES_USER" -d postgres -c "SELECT 1"' \
        >/dev/null 2>&1; then
        log_ok "PostgreSQL role $role authenticated with the environment password"
        return 0
    fi
    log_err "PostgreSQL role '$role' rejected the password from DB_PASSWORD/POSTGRES_PASSWORD/KC_DB_PASSWORD."
    echo "         Updating those three environment values does not rotate the persisted role password."
    echo "         The environment was updated but the live role was not rotated."
    echo "         Follow DEPLOY.md 'Rotate the PostgreSQL role password' before retrying."
    exit 1
}

credential_meets_policy() {
    printf '%s' "$1" |
        python3 "$PROJECT_ROOT/scripts/validate_credential.py" \
            --min-length "$2" --username "$3" --env-literal >/dev/null 2>&1
}

enforce_private_mode() {
    local file=$1
    chmod 0600 "$file"
    local mode
    mode=$(stat -c '%a' "$file" 2>/dev/null || stat -f '%OLp' "$file")
    if [ "$mode" != "600" ]; then
        log_err "$file must be mode 0600"
        exit 1
    fi
}

# Validate required .env.prod variables are set, safe, and internally consistent.
validate_env() {
    local file=${1:-scripts/.env.prod}
    if [ ! -f "$file" ]; then
        log_err "Environment file not found: $file"
        exit 1
    fi
    enforce_private_mode "$file"
    local missing=()
    local required_vars=(
        DB_USERNAME
        DB_PASSWORD
        POSTGRES_USER
        POSTGRES_PASSWORD
        KC_DB_USERNAME
        KC_DB_PASSWORD
        KEYCLOAK_ADMIN_CLIENT_SECRET
        KARYO_PUBLIC_ORIGIN
        KARYO_DOMAIN
        KC_HOSTNAME
        OIDC_SECRET
    )
    local -a bootstrap_vars=(KC_BOOTSTRAP_ADMIN_USERNAME KC_BOOTSTRAP_ADMIN_PASSWORD)
    local bootstrap_required=false
    if bootstrap_credentials_required; then
        bootstrap_required=true
        required_vars+=("${bootstrap_vars[@]}")
    fi

    local line line_number=0
    while IFS= read -r line || [ -n "$line" ]; do
        line_number=$((line_number + 1))
        if [[ "$line" =~ ^[[:space:]]*$ ]] || [[ "$line" =~ ^[[:space:]]*# ]]; then
            continue
        fi
        if [[ ! "$line" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]]; then
            missing+=("line $line_number (must use NAME=value syntax without export or whitespace around the name or equals sign)")
        fi
    done < "$file"

    local -A values=([KC_BOOTSTRAP_ADMIN_USERNAME]="" [KC_BOOTSTRAP_ADMIN_PASSWORD]="")
    for var in "${required_vars[@]}"; do
        local -a assignments=()
        mapfile -t assignments < <(grep "^${var}=" "$file" 2>/dev/null || true)
        values["$var"]=""
        if [ ${#assignments[@]} -eq 0 ]; then
            missing+=("$var (missing)")
            continue
        fi
        if [ ${#assignments[@]} -ne 1 ]; then
            missing+=("$var (must be assigned exactly once)")
            continue
        fi

        local val=${assignments[0]#*=}
        values["$var"]=$val
        if [[ "$val" =~ [[:space:]] ]] ||
           [[ "$val" == *\"* ]] ||
           [[ "$val" == *\'* ]] ||
           [[ "$val" == *\#* ]] ||
           [[ "$val" == *\$* ]] ||
           [[ "$val" == *\\* ]]; then
            missing+=("$var (must use one unquoted literal value without whitespace, comments, interpolation, or escapes)")
            continue
        fi
        if [ -z "$val" ] || [[ "$val" == *"CHANGE_ME"* ]]; then
            missing+=("$var (empty or contains CHANGE_ME)")
        elif [ "$var" = "KARYO_PUBLIC_ORIGIN" ] && [[ "$val" == *"karyo.example.com"* ]]; then
            missing+=("$var (still set to placeholder $val)")
        elif [ "$var" = "KARYO_DOMAIN" ] && [[ "$val" == *"karyo.example.com"* ]]; then
            missing+=("$var (still set to placeholder $val)")
        elif [ "$var" = "KC_HOSTNAME" ] && [[ "$val" == *"karyo.example.com"* ]]; then
            missing+=("$var (still set to placeholder $val)")
        elif [ "$var" = "OIDC_SECRET" ] && [ "$val" = "dev-backend-secret" ]; then
            missing+=("$var (still set to dev-backend-secret, which is unsafe for the karyo-backend audit service identity)")
        fi
    done

    if [ "$bootstrap_required" = false ]; then
        for var in "${bootstrap_vars[@]}"; do
            local -a retained=()
            mapfile -t retained < <(grep "^${var}=" "$file" 2>/dev/null || true)
            if [ ${#retained[@]} -gt 1 ]; then
                missing+=("$var (must be assigned at most once)")
            elif [ ${#retained[@]} -eq 1 ]; then
                local retained_value=${retained[0]#*=}
                values["$var"]=$retained_value
                if [[ "$retained_value" =~ [[:space:]] ]] ||
                   [[ "$retained_value" == *\"* ]] ||
                   [[ "$retained_value" == *\'* ]] ||
                   [[ "$retained_value" == *\#* ]] ||
                   [[ "$retained_value" == *\$* ]] ||
                   [[ "$retained_value" == *\\* ]]; then
                    missing+=("$var (must use one unquoted literal value without whitespace, comments, interpolation, or escapes)")
                elif [ -z "$retained_value" ] || [[ "$retained_value" == *"CHANGE_ME"* ]]; then
                    missing+=("$var (empty or contains CHANGE_ME)")
                fi
            fi
        done
    fi

    local db_username postgres_username kc_db_username
    local bootstrap_username bootstrap_password admin_client_secret oidc_secret public_origin
    local db_password postgres_password kc_db_password public_domain keycloak_hostname keycloak_url
    local maintenance_port
    db_username=${values[DB_USERNAME]}
    postgres_username=${values[POSTGRES_USER]}
    kc_db_username=${values[KC_DB_USERNAME]}
    bootstrap_username=${values[KC_BOOTSTRAP_ADMIN_USERNAME]}
    bootstrap_password=${values[KC_BOOTSTRAP_ADMIN_PASSWORD]}
    admin_client_secret=${values[KEYCLOAK_ADMIN_CLIENT_SECRET]}
    oidc_secret=${values[OIDC_SECRET]}
    public_origin=${values[KARYO_PUBLIC_ORIGIN]}
    db_password=${values[DB_PASSWORD]}
    postgres_password=${values[POSTGRES_PASSWORD]}
    kc_db_password=${values[KC_DB_PASSWORD]}
    public_domain=${values[KARYO_DOMAIN]}
    keycloak_hostname=${values[KC_HOSTNAME]}
    keycloak_url=/auth
    local -a keycloak_url_assignments=()
    mapfile -t keycloak_url_assignments < <(grep '^KEYCLOAK_URL=' "$file" 2>/dev/null || true)
    if [ ${#keycloak_url_assignments[@]} -gt 1 ]; then
        missing+=("KEYCLOAK_URL (must be assigned at most once)")
    elif [ ${#keycloak_url_assignments[@]} -eq 1 ]; then
        keycloak_url=${keycloak_url_assignments[0]#*=}
    fi
    maintenance_port=8181
    local -a maintenance_port_assignments=()
    mapfile -t maintenance_port_assignments < <(
        grep '^KARYO_KEYCLOAK_MAINTENANCE_PORT=' "$file" 2>/dev/null || true
    )
    if [ ${#maintenance_port_assignments[@]} -gt 1 ]; then
        missing+=("KARYO_KEYCLOAK_MAINTENANCE_PORT (must be assigned at most once)")
    elif [ ${#maintenance_port_assignments[@]} -eq 1 ]; then
        maintenance_port=${maintenance_port_assignments[0]#*=}
    fi
    if [[ ! "$maintenance_port" =~ ^[0-9]+$ ]] ||
       [ "$maintenance_port" -lt 1 ] 2>/dev/null ||
       [ "$maintenance_port" -gt 65535 ] 2>/dev/null; then
        missing+=("KARYO_KEYCLOAK_MAINTENANCE_PORT (must be an integer from 1 through 65535)")
    fi

    if [ -n "$db_username" ] && [ -n "$postgres_username" ] && [ -n "$kc_db_username" ] &&
       { [ "$db_username" != "$postgres_username" ] || [ "$db_username" != "$kc_db_username" ]; }; then
        missing+=("DB_USERNAME, POSTGRES_USER, and KC_DB_USERNAME (must name the same provisioned PostgreSQL role)")
    fi
    if [ -n "$db_password" ] && [ -n "$postgres_password" ] && [ -n "$kc_db_password" ] &&
       { [ "$db_password" != "$postgres_password" ] || [ "$db_password" != "$kc_db_password" ]; }; then
        missing+=("DB_PASSWORD, POSTGRES_PASSWORD, and KC_DB_PASSWORD (must use the same provisioned PostgreSQL role password)")
    fi
    if [ -n "$db_password" ] && [ -n "$db_username" ] &&
       ! credential_meets_policy "$db_password" 16 "$db_username"; then
        missing+=("DB_PASSWORD/POSTGRES_PASSWORD/KC_DB_PASSWORD for PostgreSQL role '$db_username' (credential policy: at least 16 characters, not a dictionary, predictable, repeated, or username-derived value). Existing role passwords are not grandfathered. Updating the three environment values does not rotate the persisted role; follow DEPLOY.md 'Rotate the PostgreSQL role password'.")
    fi
    if { [ -n "$bootstrap_password" ] && [ -z "$bootstrap_username" ]; } ||
       { [ -n "$bootstrap_username" ] && [ -z "$bootstrap_password" ]; }; then
        missing+=("KC_BOOTSTRAP_ADMIN_USERNAME and KC_BOOTSTRAP_ADMIN_PASSWORD (must be supplied together)")
    elif [ -n "$bootstrap_password" ] &&
       ! credential_meets_policy "$bootstrap_password" 16 "$bootstrap_username"; then
        missing+=("KC_BOOTSTRAP_ADMIN_PASSWORD (must be at least 16 characters and not a dictionary, predictable, repeated, or username-derived value)")
    fi
    if [ -n "$admin_client_secret" ] && ! credential_meets_policy "$admin_client_secret" 32 "karyo-admin"; then
        missing+=("KEYCLOAK_ADMIN_CLIENT_SECRET (must be at least 32 characters and not a dictionary, predictable, repeated, or username-derived value)")
    fi
    if [ -n "$oidc_secret" ] && ! credential_meets_policy "$oidc_secret" 32 "karyo-backend"; then
        missing+=("OIDC_SECRET (must be at least 32 characters and not a dictionary, predictable, repeated, or username-derived value)")
    fi
    if [ -n "$admin_client_secret" ] &&
       { [ "$admin_client_secret" = "$oidc_secret" ] || [ "$admin_client_secret" = "$bootstrap_password" ]; }; then
        missing+=("KEYCLOAK_ADMIN_CLIENT_SECRET (must be distinct from bootstrap and OIDC credentials)")
    fi
    if [ -n "$oidc_secret" ] && [ "$oidc_secret" = "$bootstrap_password" ]; then
        missing+=("OIDC_SECRET (must be distinct from bootstrap credentials)")
    fi
    if [ -n "$public_origin" ]; then
        local url_error="" url_status=0
        url_error=$(python3 "$PROJECT_ROOT/scripts/validate_public_origin.py" \
               "$public_origin" "$public_domain" "$keycloak_hostname" "$keycloak_url" 2>&1 >/dev/null) || url_status=$?
        if [ "$url_status" -eq 3 ]; then
            # Exit 3 means the validator could not run. That is a toolchain problem, and listing
            # it under "Required variables not configured" sends the operator to edit an env file
            # that is not at fault.
            log_err "Cannot validate public URLs: ${url_error:-toolchain unavailable}"
            log_err "Install the missing tool and rerun; $file was not the problem."
            return 1
        elif [ "$url_status" -ne 0 ]; then
            missing+=("public URLs: ${url_error:-validation failed}")
        fi
    fi

    if [ ${#missing[@]} -gt 0 ]; then
        log_err "Required variables not configured in $file:"
        for entry in "${missing[@]}"; do
            echo "         - $entry"
        done
        echo ""
        echo "  Edit $file and set real values, then re-run."
        exit 1
    fi
    log_ok "Required environment variables validated"
}

# Check for port conflicts before starting containers
check_ports() {
    local blocked=false
    for port in "${NGINX_HTTP_PORT:-80}" 5432; do
        local line
        line=$(ss -tlnp "sport = :$port" 2>/dev/null | tail -n +2)
        if [ -n "$line" ]; then
            local pid
            pid=$(echo "$line" | grep -oP 'pid=\K[0-9]+' | head -1)
            local pname="unknown"
            if [ -n "$pid" ]; then
                pname=$(ps -p "$pid" -o comm= 2>/dev/null || echo "unknown")
            fi
            log_err "Port $port in use by $pname (PID ${pid:-?})"
            blocked=true
        fi
    done
    if [ "$blocked" = true ]; then
        log_err "Free the ports above and re-run"
        exit 1
    fi
    log_ok "Ports ${NGINX_HTTP_PORT:-80}, 5432 are available"
}

# --- Resolve project root ---

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

echo -e "${BLUE}Karyo WMS Deploy${NC}"
echo "Project root: $PROJECT_ROOT"

if [ -n "$VALIDATE_ENV_FILE" ]; then
    check_command "Python" python3 || exit 1
    check_command "Node.js" node || exit 1
    validate_env "$VALIDATE_ENV_FILE"
    exit 0
fi

if [ "$QUICK" = true ]; then
    echo -e "  ${YELLOW}--quick mode:${NC} skipping build stages 2-4"
fi
if [ "$RESET_DB" = true ]; then
    echo -e "  ${YELLOW}--reset-db mode:${NC} volumes will be destroyed"
    echo "  New one-time bootstrap credentials and mandatory post-deploy retirement are required."
fi

detect_runtime

COMPOSE_FILE="infrastructure/docker/docker-compose.prod.yml"
# Native Compose project isolation. Export the same identity for every compose call
# and runtime lookup. Explicit image tags avoid overwriting another stack's local tags.
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-karyo-prod}"
export KARYO_APP_IMAGE="${KARYO_APP_IMAGE:-karyo/karyo-app:latest}"
export KARYO_NGINX_IMAGE="${KARYO_NGINX_IMAGE:-karyo/nginx:latest}"

# ============================================================================
# Stage 1: Check prerequisites
# ============================================================================

log_stage 1 "Checking prerequisites"

MISSING=0

check_command "Java" java || MISSING=1
check_command "Node.js" node && check_node_version || MISSING=1
check_command "Python" python3 || MISSING=1
# cloudflared is only needed for public access via Cloudflare Tunnel, not for the deploy itself
check_command "cloudflared" cloudflared || log_warn "cloudflared not found — stack will be local-only"

# Verify container runtime works
if ! $CONTAINER_CMD info &>/dev/null; then
    log_err "$CONTAINER_CMD is installed but not responding (daemon not running?)"
    MISSING=1
else
    log_ok "$CONTAINER_CMD daemon is responsive"
fi

if [ "$MISSING" -ne 0 ]; then
    log_err "Missing prerequisites. Install them and re-run."
    exit 1
fi

# Rootless Podman needs linger to keep containers running after SSH disconnect
if [ "$CONTAINER_CMD" = "podman" ]; then
    LINGER=$(loginctl show-user "$(whoami)" -p Linger --value 2>/dev/null || echo "no")
    if [ "$LINGER" != "yes" ]; then
        log_warn "Linger not enabled — containers will stop when you disconnect"
        echo "         Fix: sudo loginctl enable-linger $(whoami)"
    fi
fi

# Rootless Podman needs unprivileged port 80 for nginx
if [ "$CONTAINER_CMD" = "podman" ]; then
    UNPRIV_PORT=$(sysctl -n net.ipv4.ip_unprivileged_port_start 2>/dev/null || echo 1024)
    if [ "$UNPRIV_PORT" -gt "${NGINX_HTTP_PORT:-80}" ]; then
        log_warn "Rootless containers cannot bind port ${NGINX_HTTP_PORT:-80} (current: ip_unprivileged_port_start=$UNPRIV_PORT)"
        echo "         Fix: sudo sysctl -w net.ipv4.ip_unprivileged_port_start=80"
        echo "         Or run with a high port: NGINX_HTTP_PORT=8088 (or set it in .env.prod)"
    fi
fi

# Verify javac exists (JDK, not just JRE) — honor JAVA_HOME first
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    log_ok "JDK via JAVA_HOME: $JAVA_HOME"
elif ! command -v javac &>/dev/null; then
    log_err "JDK required but only JRE found (javac missing). Set JAVA_HOME to a JDK or: sudo dnf install java-21-openjdk-devel"
    exit 1
fi

# Verify Java >= 21
JAVA_VERSION=$(java -version 2>&1 | head -1 | sed 's/.*"\([0-9]*\)\..*/\1/')
if [ "$JAVA_VERSION" -lt 21 ]; then
    log_err "Java 21+ required, found Java $JAVA_VERSION. Install: sudo dnf install java-21-openjdk-devel"
    exit 1
fi
log_ok "Java version $JAVA_VERSION meets minimum (21)"

# ============================================================================
# Stage 2: Build backend
# ============================================================================

if [ "$QUICK" = true ]; then
    log_stage 2 "Building backend (Gradle) -- SKIPPED (--quick mode)"
    log_stage 3 "Building frontend (npm) -- SKIPPED (--quick mode)"
    log_stage 4 "Building container images -- SKIPPED (--quick mode)"
else

log_stage 2 "Building backend (Gradle)"

# Constrain Gradle heap -- overridable via .env.prod for cloud machines with more RAM
export GRADLE_OPTS="${GRADLE_OPTS:--Xmx512m -XX:MaxMetaspaceSize=256m}"

# Single modular-monolith application build
echo "  Building :services:karyo-app:quarkusBuild..."
./gradlew ":services:karyo-app:quarkusBuild" -Dquarkus.profile=prod --no-daemon

# Verify artifact
if [ ! -f services/karyo-app/build/quarkus-app/quarkus-run.jar ]; then
    log_err "Missing build artifact: services/karyo-app/build/quarkus-app/quarkus-run.jar"
    exit 1
fi
log_ok "Built: services/karyo-app"

# ============================================================================
# Stage 3: Build frontend
# ============================================================================

log_stage 3 "Building frontend (npm)"

cd frontend/web
npm ci
npm run build
cd "$PROJECT_ROOT"

if [ ! -f frontend/web/dist/index.html ]; then
    log_err "Frontend build failed: dist/index.html not found"
    exit 1
fi
log_ok "Frontend built"

echo "Building floor PWA (frontend/mobile)…"
cd "$PROJECT_ROOT/frontend/mobile"
npm ci
npm run build
cd "$PROJECT_ROOT"

if [ ! -f frontend/mobile/dist/index.html ]; then
    log_err "Floor PWA build failed: dist/index.html not found"
    exit 1
fi
log_ok "Floor PWA built"

# ============================================================================
# Stage 4: Build container images
# ============================================================================

log_stage 4 "Building container images ($CONTAINER_CMD)"

# Reproducible timestamps. quarkusBuild restamps all 347 dependency jars with the
# build time on every run, so without this the 94 MiB `lib/` COPY layer gets a new
# digest every build even when no dependency changed - layer deduplication stops
# working and an image consumer re-downloads the whole payload instead of the delta.
# Measured: two builds of identical content differed in all six application layers
# without this, and are byte-identical with it.
#
# The two runtimes spell it differently and neither understands the other's spelling:
# podman/buildah take --timestamp, docker BuildKit has no such flag and reads the
# SOURCE_DATE_EPOCH environment variable instead.
build_image() {
    if [ "$CONTAINER_CMD" = "podman" ]; then
        $CONTAINER_CMD build --timestamp 0 --no-cache "$@"
    else
        SOURCE_DATE_EPOCH=0 $CONTAINER_CMD build --no-cache "$@"
    fi
}

build_image \
    -f infrastructure/docker/Dockerfile.service \
    --build-arg "SERVICE_DIR=services/karyo-app" \
    -t "$KARYO_APP_IMAGE" .
log_ok "Image: $KARYO_APP_IMAGE"

build_image -f infrastructure/docker/Dockerfile.nginx -t "$KARYO_NGINX_IMAGE" .
log_ok "Image: $KARYO_NGINX_IMAGE"

echo ""
echo "  Image sizes:"
$CONTAINER_CMD images --filter "reference=karyo/*" --format "  {{.Repository}}:{{.Tag}}  {{.Size}}"

# Image cleanup is an explicit operator action, not a deployment side effect.
# Even an untagged image can belong to another project on a shared runtime.

fi  # end of --quick skip

# ============================================================================
# Stage 5: Environment file
# ============================================================================

log_stage 5 "Checking environment file"

if [ ! -f scripts/.env.prod ]; then
    install -m 0600 scripts/.env.prod.example scripts/.env.prod
    log_warn ".env.prod created from template -- edit it with real passwords before production use"
else
    log_ok "Using existing scripts/.env.prod"
fi
enforce_private_mode scripts/.env.prod

# Validate required environment variables
validate_env
python3 scripts/render_compose_env.py scripts/.env.prod >/dev/null
log_ok "Rendered least-privilege Compose environment files"

# Container environment is injected via service-specific env_file entries in the compose YAML.
# Exception: APP_MEM_LIMIT drives the karyo-app mem_limit, which compose
# interpolates from the shell environment (defaults to 1536m if unset).
APP_MEM_LIMIT_VAL=$(grep "^APP_MEM_LIMIT=" scripts/.env.prod 2>/dev/null | cut -d= -f2- || true)
if [ -n "$APP_MEM_LIMIT_VAL" ]; then
    export APP_MEM_LIMIT="$APP_MEM_LIMIT_VAL"
    log_ok "APP_MEM_LIMIT=$APP_MEM_LIMIT (from .env.prod)"
fi
NGINX_HTTP_PORT_VAL=$(grep "^NGINX_HTTP_PORT=" scripts/.env.prod 2>/dev/null | cut -d= -f2- || true)
if [ -n "$NGINX_HTTP_PORT_VAL" ]; then
    export NGINX_HTTP_PORT="$NGINX_HTTP_PORT_VAL"
fi
[ -n "${NGINX_HTTP_PORT:-}" ] && log_ok "NGINX_HTTP_PORT=$NGINX_HTTP_PORT"
KARYO_KEYCLOAK_MAINTENANCE_PORT_VAL=$(
    grep '^KARYO_KEYCLOAK_MAINTENANCE_PORT=' scripts/.env.prod 2>/dev/null | cut -d= -f2- || true
)
if [ -n "$KARYO_KEYCLOAK_MAINTENANCE_PORT_VAL" ]; then
    export KARYO_KEYCLOAK_MAINTENANCE_PORT="$KARYO_KEYCLOAK_MAINTENANCE_PORT_VAL"
fi
[ -n "${KARYO_KEYCLOAK_MAINTENANCE_PORT:-}" ] &&
    log_ok "KARYO_KEYCLOAK_MAINTENANCE_PORT=$KARYO_KEYCLOAK_MAINTENANCE_PORT"
KARYO_PUBLIC_ORIGIN=$(grep '^KARYO_PUBLIC_ORIGIN=' scripts/.env.prod | cut -d= -f2-)
export KARYO_PUBLIC_ORIGIN
log_ok "Environment file validated"

# ============================================================================
# Stage 6: Start infrastructure
# ============================================================================

log_stage 6 "Starting infrastructure (PostgreSQL, Keycloak)"

# Tear down stale containers from previous runs.
# Clean slate avoids stale container conflicts on re-deploy.
echo "  Cleaning up previous deployment..."
if [ "$RESET_DB" = true ]; then
    log_warn "Resetting database -- all data will be destroyed"
    log_warn "Confirm scripts/.env.prod contains newly supplied one-time bootstrap credentials"
    $COMPOSE_CMD -f "$COMPOSE_FILE" down -v --remove-orphans 2>/dev/null || true
    log_ok "Volumes removed"
else
    $COMPOSE_CMD -f "$COMPOSE_FILE" down --remove-orphans 2>/dev/null || true
    log_ok "Previous containers removed (volumes preserved)"
fi

# Check for port conflicts after compose down, before compose up
check_ports

# Start PostgreSQL first so a changed environment password cannot take Keycloak or
# karyo-app down before the persisted role is checked.
$COMPOSE_CMD -f "$COMPOSE_FILE" up -d postgresql

# Poll container health via compose service lookup (runtime-agnostic).
# Podman note: health check exec can hang on certain commands. Docker is unaffected.
wait_for_health "PostgreSQL" "check_service_healthy postgresql" 120
verify_postgres_role_password
detect_fresh_database
if [ "$FRESH_DATABASE" = true ]; then
    if ! bootstrap_credentials_assigned scripts/.env.prod; then
        log_err "Keycloak database is not initialized and one-time bootstrap credentials are missing."
        echo "         Set both KC_BOOTSTRAP_ADMIN_USERNAME and KC_BOOTSTRAP_ADMIN_PASSWORD to"
        echo "         externally generated policy-compliant values, then rerun. Keycloak was not started."
        exit 1
    fi
    log_warn "Keycloak database is uninitialized: this is a first/reset deployment"
else
    log_ok "Initialized Keycloak database detected: routine redeploy"
    if bootstrap_credentials_assigned scripts/.env.prod; then
        log_warn "KC_BOOTSTRAP_ADMIN_* is still set in scripts/.env.prod"
        echo "         Keycloak ignores it on an initialized database. Remove both values once the"
        echo "         bootstrap administrator has been retired."
    fi
fi

$COMPOSE_CMD -f "$COMPOSE_FILE" up -d --no-deps keycloak
# 420s, not 200s: on a COLD volume Keycloak runs Liquibase to build its entire schema
# and then imports the realm. Measured on a cold volume with an empty DB: container
# start -> healthy took 224s, which blew the old 200s budget by ~24s and failed the
# deploy after the images were already built. Warm-volume starts are ~30s, so this
# headroom costs nothing in the common case and prevents a very expensive late failure.
wait_for_health "Keycloak" "check_service_healthy keycloak" 420

# ============================================================================
# Stage 7: Start application services
# ============================================================================

log_stage 7 "Starting application"

# Dependencies were checked above. Start and bound each service separately: starting nginx
# in the same Compose call can wait forever for a restart-looping app before our timer runs.
for svc in karyo-app nginx; do
    $COMPOSE_CMD -f "$COMPOSE_FILE" up -d --no-deps "$svc"
    wait_for_health "$svc" "check_service_healthy $svc" 150
done

# ============================================================================
# Stage 8: Verify stack
# ============================================================================

log_stage 8 "Verifying stack"

echo "  API endpoint health (all served by karyo-app):"

SERVICES_OK=0
SERVICES_TOTAL=4

for endpoint in stock-units products locations users; do
    HTTP_CODE=$(curl -so /dev/null -w '%{http_code}' "http://localhost:${NGINX_HTTP_PORT:-80}/api/v1/$endpoint" 2>/dev/null || echo "000")
    if [[ "$HTTP_CODE" =~ ^(200|401|403)$ ]]; then
        log_ok "/api/v1/$endpoint -> HTTP $HTTP_CODE"
        SERVICES_OK=$((SERVICES_OK + 1))
    else
        log_err "/api/v1/$endpoint -> HTTP $HTTP_CODE"
    fi
done

echo ""
echo -e "  ${GREEN}$SERVICES_OK/$SERVICES_TOTAL endpoints responding${NC}"
echo ""

# Check Keycloak
KC_CODE=$(curl -so /dev/null -w '%{http_code}' "http://localhost:${NGINX_HTTP_PORT:-80}/auth/realms/karyo" 2>/dev/null || echo "000")
if [ "$KC_CODE" = "200" ]; then
    log_ok "Keycloak realm accessible at /auth/realms/karyo"
else
    log_warn "Keycloak returned HTTP $KC_CODE (may still be starting)"
fi

# SPA check
SPA_CODE=$(curl -so /dev/null -w '%{http_code}' "http://localhost:${NGINX_HTTP_PORT:-80}/" 2>/dev/null || echo "000")
if [ "$SPA_CODE" = "200" ]; then
    log_ok "SPA accessible at /"
else
    log_warn "SPA returned HTTP $SPA_CODE"
fi

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Karyo WMS is running!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "  Local:  http://localhost:${NGINX_HTTP_PORT:-80}"
echo ""

# Cloudflare tunnel reminder
if ! systemctl is-active --quiet cloudflared 2>/dev/null; then
    log_warn "cloudflared service is not running. Start it for public access:"
    echo "         sudo systemctl start cloudflared"
fi

echo ""
echo "  The production realm import defines no application users."
if [ "$FRESH_DATABASE" = true ]; then
    echo "  Keycloak started on a fresh database and created the temporary bootstrap administrator."
    echo "  Use it at ${KARYO_PUBLIC_ORIGIN:-your public origin}/auth/admin/ to provision the first Karyo administrator."
    echo "  After verifying Karyo login, delete the temporary bootstrap user from the master realm."
    echo "  Then remove KC_BOOTSTRAP_ADMIN_* from scripts/.env.prod and refresh rendered environments."
else
    echo "  This redeploy reused the existing database, so Keycloak created no bootstrap administrator."
    echo "  KC_BOOTSTRAP_ADMIN_* are inert here -- sign in with an existing Karyo application administrator."

fi
echo ""
