#!/usr/bin/env bash
#
# Runs k-producer in Docker without having to remember the flag soup.
#
#   ./scripts/run-docker.sh                                   # standalone, in-memory queue
#   ./scripts/run-docker.sh --broker 192.168.0.60:9092        # against real Kafka
#   ./scripts/run-docker.sh --profile test --ca ../vpcs/secrets/internal_ca_cert.pem
#   ./scripts/run-docker.sh --role echo --broker host:9092 --echo-topic t-echo
#   ./scripts/run-docker.sh stop|logs|status
#
set -euo pipefail

IMAGE=${IMAGE:-k-producer}
NAME=${NAME:-k-producer}
PORT=${PORT:-8080}

MESSENGER=queue          # no external dependency by default
BROKER=""
TOPIC=""
ECHO_TOPIC=""
ROLE=""
PROFILE=""
CA_FILE=""
CONFIG_FILE=""
AZ=""
INSTANCE=""
SLEEP=""
COUNT=""
FILLER=""
DETACH=1
AUTOSTART=0

die() { echo "error: $*" >&2; exit 1; }

version_from_gradle() {
    # Single source of truth: whatever the build says, so the script cannot drift from it.
    sed -n "s/^version *= *'\(.*\)'/\1/p" "$(dirname "$0")/../build.gradle" | head -1
}

usage() {
    awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "$0"
    cat <<'EOF'

Options:
  --broker HOST:PORT    use Kafka instead of the in-memory queue
  --topic NAME          topic to publish/consume (default: bundled config)
  --echo-topic NAME     return topic, required for --role origin|echo
  --role ROLE           loopback (default) | origin | echo
  --profile NAME        Spring profile, e.g. test or consul, to pull config from Consul
  --ca FILE             CA certificate to trust, needed when Consul serves HTTPS
  --config FILE         application.yaml to mount at /config, overriding bundled defaults
  --az NAME             availability zone tag on every metric
  --instance NAME       instance tag on every metric (default: container id)
  --sleep N             half-seconds between publishes
  --count N             messages per publish
  --filler N            filler bytes per message
  --port N              host port to expose (default 8080)
  --name NAME           container name (default k-producer)
  --tag VERSION         image tag (default: version from build.gradle)
  --start               start the publisher and consumer once healthy
  --foreground          run in the foreground instead of detached
EOF
}

TAG=""
while [ $# -gt 0 ]; do
    case "$1" in
        stop)        ACTION=stop; shift ;;
        logs)        ACTION=logs; shift ;;
        status)      ACTION=status; shift ;;
        --broker)    BROKER=$2; MESSENGER=kafka; shift 2 ;;
        --topic)     TOPIC=$2; shift 2 ;;
        --echo-topic) ECHO_TOPIC=$2; shift 2 ;;
        --role)      ROLE=$2; shift 2 ;;
        --profile)   PROFILE=$2; shift 2 ;;
        --ca)        CA_FILE=$2; shift 2 ;;
        --config)    CONFIG_FILE=$2; shift 2 ;;
        --az)        AZ=$2; shift 2 ;;
        --instance)  INSTANCE=$2; shift 2 ;;
        --sleep)     SLEEP=$2; shift 2 ;;
        --count)     COUNT=$2; shift 2 ;;
        --filler)    FILLER=$2; shift 2 ;;
        --port)      PORT=$2; shift 2 ;;
        --name)      NAME=$2; shift 2 ;;
        --tag)       TAG=$2; shift 2 ;;
        --start)     AUTOSTART=1; shift ;;
        --foreground) DETACH=0; shift ;;
        -h|--help)   usage; exit 0 ;;
        *)           die "unknown argument: $1 (try --help)" ;;
    esac
done

TAG=${TAG:-$(version_from_gradle)}
[ -n "$TAG" ] || die "could not determine version from build.gradle; pass --tag"
ACTION=${ACTION:-run}

case "$ACTION" in
    stop)   docker rm -f "$NAME" >/dev/null 2>&1 && echo "removed $NAME" || echo "$NAME not running"; exit 0 ;;
    logs)   exec docker logs -f "$NAME" ;;
    status)
        docker ps --filter "name=^${NAME}$" --format '  {{.Names}}  {{.Status}}  {{.Ports}}'
        curl -s --max-time 5 "http://localhost:${PORT}/actuator/health" | head -c 200; echo
        exit 0 ;;
esac

# Validate before touching docker, so a typo is reported even when the daemon is down.
# The echo relay re-consumes its own output if both topics are the same, and the application
# refuses to start in that case - catch it here with a clearer message.
if [ -n "$ROLE" ] && [ "$ROLE" != "loopback" ] && [ "$ROLE" != "origin" ] && [ "$ROLE" != "echo" ]; then
    die "--role must be loopback, origin or echo (got '$ROLE')"
fi
if [ "$ROLE" = "origin" ] || [ "$ROLE" = "echo" ]; then
    [ -n "$ECHO_TOPIC" ] || die "--role $ROLE needs --echo-topic"
    [ "$ECHO_TOPIC" != "$TOPIC" ] || die "--echo-topic must differ from --topic"
    [ "$MESSENGER" = "kafka" ] || die "--role $ROLE needs --broker (the queue transport shares one channel)"
fi

[ -z "$CA_FILE" ] || [ -f "$CA_FILE" ] || die "CA file not found: $CA_FILE"
[ -z "$CONFIG_FILE" ] || [ -f "$CONFIG_FILE" ] || die "config file not found: $CONFIG_FILE"

docker info >/dev/null 2>&1 || die "docker daemon is not running"

docker image inspect "${IMAGE}:${TAG}" >/dev/null 2>&1 || {
    echo "image ${IMAGE}:${TAG} not found locally; building it"
    (cd "$(dirname "$0")/.." && ./gradlew buildDockerImage)
}

args=(--name "$NAME" -p "${PORT}:8080")
[ "$DETACH" = "1" ] && args+=(-d) || args+=(--rm -it)

env_add() { [ -n "$2" ] && args+=(-e "$1=$2") || true; }
env_add MYAPP_MESSENGER               "$MESSENGER"
env_add MYAPP_KAFKA_BOOTSTRAPADDRESS  "$BROKER"
env_add MYAPP_KAFKA_TOPICNAME         "$TOPIC"
env_add MYAPP_KAFKA_ECHOTOPICNAME     "$ECHO_TOPIC"
env_add MYAPP_ROLE                    "$ROLE"
env_add MYAPP_AZ                      "$AZ"
env_add MYAPP_INSTANCE                "$INSTANCE"
env_add MYAPP_PUBLISHER_SLEEP         "$SLEEP"
env_add MYAPP_PUBLISHER_MESSAGECOUNT  "$COUNT"
env_add MYAPP_PUBLISHER_FILLERSIZE    "$FILLER"
env_add SPRING_PROFILES_ACTIVE        "$PROFILE"

if [ -n "$CA_FILE" ]; then
    [ -f "$CA_FILE" ] || die "CA file not found: $CA_FILE"
    args+=(-v "$(cd "$(dirname "$CA_FILE")" && pwd)/$(basename "$CA_FILE")":/etc/ssl/consul-ca/ca.pem:ro)
fi
if [ -n "$CONFIG_FILE" ]; then
    [ -f "$CONFIG_FILE" ] || die "config file not found: $CONFIG_FILE"
    args+=(-v "$(cd "$(dirname "$CONFIG_FILE")" && pwd)/$(basename "$CONFIG_FILE")":/config/application.yaml:ro)
fi

if [ -n "$PROFILE" ] && [ -z "$CA_FILE" ]; then
    echo "note: profile '$PROFILE' reads from Consul, which serves HTTPS from a private CA."
    echo "      Without --ca the TLS handshake fails; the app still starts, on bundled defaults."
fi

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run "${args[@]}" "${IMAGE}:${TAG}"

[ "$DETACH" = "1" ] || exit 0

printf 'waiting for %s to become healthy' "$NAME"
for _ in $(seq 1 40); do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://localhost:${PORT}/actuator/health/liveness" 2>/dev/null)" = "200" ]; then
        echo " ok"
        break
    fi
    printf '.'; sleep 2
done

if [ "$AUTOSTART" = "1" ]; then
    curl -s -o /dev/null --max-time 5 "http://localhost:${PORT}/consumer/start"
    curl -s -o /dev/null --max-time 5 "http://localhost:${PORT}/publisher/start"
    echo "publisher and consumer started"
fi

cat <<EOF

  http://localhost:${PORT}/actuator/info             build details
  http://localhost:${PORT}/actuator/health           health, including consul when enabled
  http://localhost:${PORT}/actuator/prometheus       metrics
  http://localhost:${PORT}/publisher/settings        current publish rate/size
  http://localhost:${PORT}/consumer/histogram        latency distribution
  http://localhost:${PORT}/consumer/reconciliation   delivery reconciliation

  $0 logs      follow the log
  $0 status    health summary
  $0 stop      remove the container
EOF
