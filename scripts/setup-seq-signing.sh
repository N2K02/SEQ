#!/usr/bin/env bash
# Run once on a trusted local machine with Java keytool, openssl and GitHub CLI.
# Never commit or upload the plaintext keystore. Save the encrypted backup offline.
set -euo pipefail
REPO="N2K02/SEQ"
BACKUP_DIR="${1:-}"
if [ -z "$BACKUP_DIR" ]; then
  echo "Usage: bash scripts/setup-seq-signing.sh /path/to/private/offline-backup" >&2
  exit 2
fi
for cmd in keytool openssl gh base64; do command -v "$cmd" >/dev/null; done
gh auth status >/dev/null
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
if [ -e "$BACKUP_DIR/seq-release.p12.enc" ]; then
  echo "Backup already exists. Reusing a new signer would break Android updates." >&2
  exit 1
fi
TMP="$(mktemp -d)"
chmod 700 "$TMP"
trap 'rm -rf "$TMP"; unset SEQ_SIGNING_PASSPHRASE' EXIT
read -r -s -p "Enter a strong, unique SEQ signing passphrase (12+ chars): " SEQ_SIGNING_PASSPHRASE
echo
if [ "${#SEQ_SIGNING_PASSPHRASE}" -lt 12 ]; then
  echo "Passphrase too short" >&2
  exit 1
fi
export SEQ_SIGNING_PASSPHRASE
keytool -genkeypair -keyalg RSA -keysize 3072 -validity 10950 -alias seqrelease \
  -keystore "$TMP/seq-release.p12" -storetype PKCS12 \
  -storepass "$SEQ_SIGNING_PASSPHRASE" -keypass "$SEQ_SIGNING_PASSPHRASE" \
  -dname "CN=SEQ, OU=Mobile, O=N2K02, C=MU" -noprompt >/dev/null
openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -salt \
  -pass env:SEQ_SIGNING_PASSPHRASE \
  -in "$TMP/seq-release.p12" -out "$BACKUP_DIR/seq-release.p12.enc"
chmod 600 "$BACKUP_DIR/seq-release.p12.enc"
FINGERPRINT="$(keytool -list -v -keystore "$TMP/seq-release.p12" \
  -storetype PKCS12 -storepass "$SEQ_SIGNING_PASSPHRASE" \
  -alias seqrelease | awk '/SHA256:/{gsub(":", "", $2);print tolower($2);exit}')"
test "${#FINGERPRINT}" -eq 64
base64 < "$BACKUP_DIR/seq-release.p12.enc" | tr -d '\n' | gh secret set SIGNING_KEYSTORE_ENC_BASE64 --repo "$REPO"
printf '%s' "$SEQ_SIGNING_PASSPHRASE" | gh secret set SIGNING_PASSPHRASE --repo "$REPO"
printf '%s' "$FINGERPRINT" | gh secret set SEQ_SIGNING_CERT_SHA256 --repo "$REPO"
echo "Encrypted offline backup: $BACKUP_DIR/seq-release.p12.enc"
echo "Certificate SHA-256: $FINGERPRINT"
echo "Signing secrets installed. Keep the encrypted backup and passphrase securely and independently."
echo "Previously installed debug-signed SEQ APKs may need one uninstall/reinstall."
