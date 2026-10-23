#!/usr/bin/env bash
# Publishes the four signing secrets for notes-de-frais. Passwords are read from
# the local secret file and never printed.
set -euo pipefail
REPO="ClawFabriceH92/notes-de-frais"
KS="/root/.secrets/keystores-android/notesdefrais-release.keystore"
ENVF="/root/.secrets/keystores-android/notesdefrais-keystore.env"

PASS=$(grep '^STORE_PASSWORD=' "$ENVF" | cut -d= -f2-)
ALIAS=$(grep '^KEY_ALIAS=' "$ENVF" | cut -d= -f2-)

gh secret set NOTESDEFRAIS_KEYSTORE_B64 --repo "$REPO" --body "$(base64 -w0 "$KS")"
gh secret set NOTESDEFRAIS_KEYSTORE_PASSWORD --repo "$REPO" --body "$PASS"
gh secret set NOTESDEFRAIS_KEY_PASSWORD --repo "$REPO" --body "$PASS"
gh secret set NOTESDEFRAIS_KEY_ALIAS --repo "$REPO" --body "$ALIAS"

gh secret list --repo "$REPO"
