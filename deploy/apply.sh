#!/usr/bin/env bash
#
# Applies the k-producer manifests.
#
#   ./deploy/apply.sh            # apply
#   ./deploy/apply.sh --dry-run  # render and validate against the cluster, changing nothing
#   ./deploy/apply.sh --render   # print the manifests and exit, no cluster needed
#
# Exists for one reason: the --load-restrictor flag below. config/internal_ca_cert.pem is a symlink
# to the CA that terraform manages, so there is a single source of truth rather than a copy that
# silently goes stale. Kustomize resolves symlinks and refuses targets outside its own directory,
# so a plain `kubectl apply --kustomize deploy/` fails - and it fails confusingly, naming a path that is
# visibly inside deploy/ while complaining it is not. Wrapping the flag here means nobody has to
# know that.
#
set -euo pipefail

KUSTOMIZE_DIR="$(cd "$(dirname "$0")" && pwd)"
# Permits the symlinked CA. Everything the generators read still lives under deploy/ - the flag
# relaxes where a symlink may POINT, not what may be referenced.
RESTRICTOR="--load-restrictor LoadRestrictionsNone"

die() { echo "error: $*" >&2; exit 1; }

command -v kubectl >/dev/null 2>&1 || die "kubectl not found on PATH"

# Check the symlink resolves before doing anything. Kustomize does fail on a dangling link too, so
# this is not covering a silent failure - it is covering an unclear one. Kustomize reports it as a
# file-loading error deep in a generator; this says which link is broken and why it might be, which
# for a link into a sibling repository is usually the whole answer.
CA_LINK="$KUSTOMIZE_DIR/config/internal_ca_cert.pem"
[ -e "$CA_LINK" ] || die "CA certificate not readable: $CA_LINK
  It is a symlink into the terraform-managed secrets directory; check that repo is checked out
  alongside this one."
[ -s "$CA_LINK" ] || die "CA certificate is empty: $CA_LINK"

case "${1:-apply}" in
    --render)
        exec kubectl kustomize $RESTRICTOR "$KUSTOMIZE_DIR"
        ;;
    --dry-run)
        kubectl kustomize $RESTRICTOR "$KUSTOMIZE_DIR" | kubectl apply --dry-run=server --filename -
        ;;
    apply)
        kubectl kustomize $RESTRICTOR "$KUSTOMIZE_DIR" | kubectl apply --filename -
        echo
        echo "  kubectl --namespace app-ns rollout status deploy/k-producer"
        ;;
    --help)
        awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "$0"
        ;;
    *)
        die "unknown argument: ${1} (try --help)"
        ;;
esac
