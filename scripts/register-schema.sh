#!/usr/bin/env bash
#
# Registers the message schema with a Confluent Schema Registry.
#
#   ./scripts/register-schema.sh --dry-run             # show what would be sent
#   ./scripts/register-schema.sh --check               # compatibility only, registers nothing
#   ./scripts/register-schema.sh                       # register for every configured topic
#   ./scripts/register-schema.sh --topic test-topic-one
#   ./scripts/register-schema.sh --list                # what is registered now
#
# Registering the same schema twice is a no-op: the registry returns the existing id rather than
# creating a version, so this is safe to re-run.
#
set -euo pipefail

REGISTRY=${SCHEMA_REGISTRY_URL:-http://kafka-00.podspace.internal:8081}
SCHEMA_FILE="$(dirname "$0")/../src/main/resources/avro/temperature.avsc"

# Confluent's default TopicNameStrategy derives the subject from the topic as "<topic>-value".
# Getting this wrong is the usual reason a serializer cannot find a schema it can see in the UI,
# so the suffix is applied here rather than left to the caller.
SUBJECT_SUFFIX="-value"

# Every topic the application is configured to use. Kept in step with terraform/main.tf, where the
# same names are the single source of truth for both the topics and the Consul configuration.
DEFAULT_TOPICS=(
    test-topic-one test-topic-one-echo
    test-topic-two test-topic-two-echo
    test-topic-three test-topic-three-echo
)

TOPICS=()
ACTION=register

die() { echo "error: $*" >&2; exit 1; }

usage() {
    awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "$0"
    cat <<'EOF'

Options:
  --registry URL   schema registry base URL (default: $SCHEMA_REGISTRY_URL or kafka-00:8081)
  --schema FILE    schema to register (default: src/main/resources/avro/temperature.avsc)
  --topic NAME     register for this topic only; repeatable
  --check          run a compatibility check and stop, registering nothing
  --dry-run        print the request that would be sent
  --list           show currently registered subjects and versions
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --registry) REGISTRY=$2; shift 2 ;;
        --schema)   SCHEMA_FILE=$2; shift 2 ;;
        --topic)    TOPICS+=("$2"); shift 2 ;;
        --check)    ACTION=check; shift ;;
        --dry-run)  ACTION=dryrun; shift ;;
        --list)     ACTION=list; shift ;;
        -h|--help)  usage; exit 0 ;;
        *)          die "unknown argument: $1 (try --help)" ;;
    esac
done

[ ${#TOPICS[@]} -gt 0 ] || TOPICS=("${DEFAULT_TOPICS[@]}")

# Validate before any network call, so a typo is reported immediately rather than after a timeout.
[ -f "$SCHEMA_FILE" ] || die "schema not found: $SCHEMA_FILE"
python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$SCHEMA_FILE" 2>/dev/null \
    || die "schema is not valid JSON: $SCHEMA_FILE"
REGISTRY=${REGISTRY%/}

# The API takes the schema as a JSON *string* inside a JSON envelope, so it has to be escaped
# rather than embedded. python3 does that correctly; hand-rolled quoting does not.
payload() {
    python3 -c "
import json, sys
print(json.dumps({'schema': open(sys.argv[1]).read(), 'schemaType': 'AVRO'}))
" "$SCHEMA_FILE"
}

reachable() {
    curl -sf --max-time 10 "$REGISTRY/subjects" >/dev/null 2>&1 \
        || die "cannot reach the registry at $REGISTRY
  It listens inside the VPC only, so this needs to run from a host that can see it -
  the same constraint that applies to the brokers themselves."
}

case "$ACTION" in
    dryrun)
        echo "registry : $REGISTRY"
        echo "schema   : $SCHEMA_FILE"
        for t in "${TOPICS[@]}"; do echo "subject  : ${t}${SUBJECT_SUFFIX}"; done
        echo "body     :"
        payload | head -c 400; echo " ..."
        exit 0 ;;
    list)
        reachable
        subjects=$(curl -sf --max-time 10 "$REGISTRY/subjects" | python3 -c "
import json,sys
s=json.load(sys.stdin)
print(' '.join(s) if s else '')")
        [ -n "$subjects" ] || { echo "no subjects registered"; exit 0; }
        for s in $subjects; do
            vers=$(curl -sf --max-time 10 "$REGISTRY/subjects/$s/versions" | tr -d '[]')
            printf '  %-32s versions: %s\n' "$s" "$vers"
        done
        exit 0 ;;
esac

reachable
BODY=$(payload)
failed=0

for topic in "${TOPICS[@]}"; do
    subject="${topic}${SUBJECT_SUFFIX}"

    # Compatibility is checked first and separately. Registering an incompatible schema is
    # rejected anyway, but the check reports *why* before anything changes, and it is the only
    # way to ask the question without side effects.
    existing=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
        "$REGISTRY/subjects/$subject/versions/latest")
    if [ "$existing" = "200" ]; then
        verdict=$(curl -s --max-time 10 -X POST \
            -H "Content-Type: application/vnd.schemaregistry.v1+json" \
            --data "$BODY" \
            "$REGISTRY/compatibility/subjects/$subject/versions/latest" \
            | python3 -c "import json,sys; print(json.load(sys.stdin).get('is_compatible','?'))" 2>/dev/null || echo '?')
        if [ "$verdict" != "True" ] && [ "$verdict" != "true" ]; then
            echo "  INCOMPATIBLE  $subject  (existing version would break)"
            failed=1
            continue
        fi
        compat="compatible with existing"
    else
        compat="new subject"
    fi

    if [ "$ACTION" = "check" ]; then
        printf '  %-32s %s\n' "$subject" "$compat"
        continue
    fi

    id=$(curl -s --max-time 15 -X POST \
        -H "Content-Type: application/vnd.schemaregistry.v1+json" \
        --data "$BODY" "$REGISTRY/subjects/$subject/versions" \
        | python3 -c "
import json,sys
d=json.load(sys.stdin)
print(d['id'] if 'id' in d else 'ERROR: ' + str(d.get('message', d)))" 2>/dev/null || echo "ERROR")
    case "$id" in
        ERROR*) echo "  FAILED        $subject  ($id)"; failed=1 ;;
        *)      printf '  registered    %-32s schema id %s (%s)\n' "$subject" "$id" "$compat" ;;
    esac
done

exit $failed
