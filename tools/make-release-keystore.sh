#!/usr/bin/env bash
# Creates the release keystore and uploads the four signing secrets the Release
# workflow needs. Asks for the password once; it never touches disk except
# inside the keystore itself, and is passed to `gh` over stdin.
#
#   tools/make-release-keystore.sh
#
# Needs keytool (any JDK) and `gh` logged in with access to the repository.
set -euo pipefail

REPO="${REPO:-Yahel0506/Auden}"
DIR="${KEYSTORE_DIR:-$HOME/auden-signing}"
ALIAS="auden"
JKS="$DIR/auden-release.jks"

if [ -e "$JKS" ]; then
  echo "$JKS already exists; refusing to overwrite a signing key." >&2
  exit 1
fi

read -r -s -p "Keystore password (min 6 chars): " PASS; echo
read -r -s -p "Repeat it: " PASS2; echo
[ "$PASS" = "$PASS2" ] || { echo "Passwords differ." >&2; exit 1; }
[ "${#PASS}" -ge 6 ] || { echo "Too short." >&2; exit 1; }

mkdir -p "$DIR"; chmod 700 "$DIR"
keytool -genkeypair -keystore "$JKS" -alias "$ALIAS" -keyalg RSA -keysize 2048 \
  -validity 10000 -dname "CN=Auden" -storepass "$PASS" -keypass "$PASS"
chmod 600 "$JKS"

base64 -w0 "$JKS" | gh secret set KEYSTORE_BASE64 --repo "$REPO"
printf '%s' "$PASS" | gh secret set KEYSTORE_PASSWORD --repo "$REPO"
printf '%s' "$PASS" | gh secret set KEY_PASSWORD --repo "$REPO"
printf '%s' "$ALIAS" | gh secret set KEY_ALIAS --repo "$REPO"

echo
echo "Done. Keystore: $JKS"
echo "Back it up together with the password: losing either means no build can ever update an installed Auden."
