#!/usr/bin/env bash
# Daily refresh of the Banque de France rates published on trucdecomptable.fr.
# Silent on success (an alert every morning for a working job is noise); loud on
# failure, which is the only thing worth waking up for.
set -uo pipefail

SORTIE=$(python3 /root/projects/notes-de-frais/scripts/maj_taux_bdf.py 2>&1)
CODE=$?

if [ "$CODE" -ne 0 ]; then
  echo "⚠️ MAJ des taux Banque de France échouée (code $CODE)"
  echo "$SORTIE" | tail -15
  exit 1
fi

# A rate file that stopped moving would leave the app converting silently with
# stale parities: say so rather than staying quiet.
JOUR=$(python3 - <<'PY'
import json
try:
    d = json.load(open('/root/projects/notes-de-frais/data/taux-devises-bdf.json'))
    print(max(d))
except Exception:
    print('')
PY
)
DELAI=$(python3 - "$JOUR" <<'PY'
import sys
from datetime import date, datetime
try:
    jour = datetime.strptime(sys.argv[1], '%Y-%m-%d').date()
except Exception:
    print(99); raise SystemExit
print((date.today() - jour).days)
PY
)
if [ "${DELAI:-99}" -gt 5 ]; then
  echo "⚠️ Taux Banque de France figés : dernière parité publiée le $JOUR ($DELAI jours)"
  exit 1
fi

exit 0
