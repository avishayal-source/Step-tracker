#!/usr/bin/env bash
# Materialize release signing files from environment secrets (never commit the outputs).
#
# Required env vars (Cursor Cloud / CI secrets):
#   YWALK_KEYSTORE_BASE64  — base64 of the .jks / .keystore file
#   YWALK_STORE_PASSWORD
#   YWALK_KEY_ALIAS
#   YWALK_KEY_PASSWORD
#
# Optional:
#   YWALK_KEYSTORE_PATH    — output path for the keystore (default: ./ywalk-release.jks)
#
# Usage (from repo root):
#   ./scripts/prepare-release-signing.sh
#   ./gradlew :app:bundleRelease

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

missing=()
for v in YWALK_KEYSTORE_BASE64 YWALK_STORE_PASSWORD YWALK_KEY_ALIAS YWALK_KEY_PASSWORD; do
  if [[ -z "${!v:-}" ]]; then
    missing+=("$v")
  fi
done

if ((${#missing[@]})); then
  echo "Missing signing secrets: ${missing[*]}" >&2
  echo "Add them in Cursor → Cloud Agents → Environment → Secrets," >&2
  echo "or as GitHub Actions secrets. See docs/PLAY_SIGNING.md" >&2
  exit 1
fi

KEYSTORE_PATH="${YWALK_KEYSTORE_PATH:-$ROOT/ywalk-release.jks}"
mkdir -p "$(dirname "$KEYSTORE_PATH")"

# Decode keystore (portable: prefer base64 -d, fall back to -D on macOS)
if base64 --help 2>&1 | grep -q -- '-d'; then
  printf '%s' "$YWALK_KEYSTORE_BASE64" | base64 -d > "$KEYSTORE_PATH"
else
  printf '%s' "$YWALK_KEYSTORE_BASE64" | base64 -D > "$KEYSTORE_PATH"
fi

# Gradle expects storeFile relative to project root, or absolute.
# Use a path relative to ROOT when possible.
rel="$KEYSTORE_PATH"
case "$KEYSTORE_PATH" in
  "$ROOT"/*) rel="${KEYSTORE_PATH#"$ROOT"/}" ;;
esac

# Escape backslashes for Properties format
props_store_file="${rel//\\/\\\\}"

cat > "$ROOT/keystore.properties" <<EOF
storeFile=$props_store_file
storePassword=$YWALK_STORE_PASSWORD
keyAlias=$YWALK_KEY_ALIAS
keyPassword=$YWALK_KEY_PASSWORD
EOF

echo "Wrote $KEYSTORE_PATH and keystore.properties (gitignored)."
echo "You can now run: ./gradlew :app:bundleRelease"
