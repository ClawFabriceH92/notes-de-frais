#!/usr/bin/env python3
"""Publishes the Banque de France daily exchange rates on trucdecomptable.fr.

The app "Notes de frais" needs one rate per expense, at the expense date, and
Fabrice wants that rate to come from his own site rather than from a third
party API. This script takes the very file the site's own currency tool uses
(webstat.banque-france.fr, série EXR, source BCE), turns it into a compact
{date: {DEVISE: parité}} mapping, and uploads it where the app can read it
without any credentials.

Parité = how many units of the currency 1 EUR buys.
Amount in EUR = amount in currency / parité.

Usage:
    python3 maj_taux_bdf.py            # download, build, upload, verify
    python3 maj_taux_bdf.py --dry-run  # download and build only
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import os
import sys
import urllib.request
from datetime import date, datetime, timedelta
from typing import Dict, Optional

CSV_URL = "https://webstat.banque-france.fr/export/csv-columns/fr/selection/5385698"
SITE_JSON = "taux-devises-bdf.json"
SITE_URL = "https://trucdecomptable.fr/" + SITE_JSON
FTP_ENV = "/root/.secrets/trucdecomptable-ftp.env"
FTP_PATH = "domains/trucdecomptable.fr/public_html"
KEEP_YEARS = 12
LOCAL_JSON = "/root/projects/notes-de-frais/data/taux-devises-bdf.json"


def telecharger(url: str = CSV_URL) -> str:
    requete = urllib.request.Request(url, headers={"User-Agent": "EtoileMagique/1.0"})
    with urllib.request.urlopen(requete, timeout=60) as reponse:
        brut = reponse.read()
    return brut.decode("utf-8-sig", errors="replace")


def codes_devises(ligne_series: list[str]) -> list[Optional[str]]:
    """Reads the ISO code out of "EXR.D.USD.EUR.SP00.A".

    The header cannot be used: it prints "Roupie Indienne (100 paise)", where
    the parenthesis holds an amount, not a currency code.
    """
    codes: list[Optional[str]] = []
    for cellule in ligne_series[1:]:
        parties = cellule.strip().split(".")
        code = parties[2] if len(parties) > 2 and len(parties[2]) == 3 else None
        codes.append(code)
    return codes


def construire(texte: str, garder_annees: int = KEEP_YEARS) -> Dict[str, Dict[str, float]]:
    lecteur = csv.reader(io.StringIO(texte), delimiter=";")
    lignes = list(lecteur)
    if not lignes:
        raise SystemExit("CSV vide")

    series = next((l for l in lignes if l and l[0].startswith("Code série")), None)
    if series is None:
        raise SystemExit("Ligne « Code série : » introuvable : format Banque de France changé ?")
    codes = codes_devises(series)

    limite = (date.today() - timedelta(days=365 * garder_annees)).isoformat()
    par_date: Dict[str, Dict[str, float]] = {}
    for ligne in lignes:
        if not ligne or len(ligne) < 2:
            continue
        jour = ligne[0].strip()
        if len(jour) != 10 or jour[4] != "-":
            continue
        if jour < limite:
            continue
        valeurs: Dict[str, float] = {}
        for code, cellule in zip(codes, ligne[1:]):
            if not code:
                continue
            cellule = cellule.strip().replace(",", ".").replace(" ", "")
            if not cellule or cellule in {"-", "."}:
                continue
            try:
                valeurs[code] = round(float(cellule), 6)
            except ValueError:
                continue
        if valeurs:
            par_date[jour] = valeurs

    if not par_date:
        raise SystemExit("Aucune parité extraite")
    return dict(sorted(par_date.items()))


def uploader(contenu: bytes, nom: str = SITE_JSON) -> None:
    import ftplib

    env: Dict[str, str] = {}
    for ligne in open(FTP_ENV, encoding="utf-8"):
        ligne = ligne.strip()
        if "=" in ligne and not ligne.startswith("#"):
            cle, valeur = ligne.split("=", 1)
            env[cle] = valeur.strip('"')

    ftp = ftplib.FTP()
    ftp.connect(env["FTP_HOST"].replace("ftp://", ""), int(env.get("FTP_PORT", "21")), timeout=60)
    ftp.login(env["FTP_USER"], env["FTP_PASSWORD"])
    ftp.cwd("/" + FTP_PATH)
    ftp.storbinary(f"STOR {nom}", io.BytesIO(contenu))
    ftp.quit()


def verifier(url: str = SITE_URL) -> Dict[str, Dict[str, float]]:
    requete = urllib.request.Request(url, headers={"User-Agent": "EtoileMagique/1.0"})
    with urllib.request.urlopen(requete, timeout=60) as reponse:
        if reponse.status != 200:
            raise SystemExit(f"URL publique HTTP {reponse.status}")
        donnees = json.loads(reponse.read().decode("utf-8"))
    return donnees


def main() -> int:
    parseur = argparse.ArgumentParser()
    parseur.add_argument("--dry-run", action="store_true")
    arguments = parseur.parse_args()

    print(f"Téléchargement {CSV_URL}")
    texte = telecharger()
    par_date = construire(texte)
    devises = sorted({code for valeurs in par_date.values() for code in valeurs})
    jours = sorted(par_date)
    print(f"{len(jours)} dates ({jours[0]} → {jours[-1]}), {len(devises)} devises")

    contenu = json.dumps(par_date, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    print(f"JSON compact : {len(contenu) / 1024:.0f} Ko")

    os.makedirs(os.path.dirname(LOCAL_JSON), exist_ok=True)
    with open(LOCAL_JSON, "wb") as fichier:
        fichier.write(contenu)
    print(f"Écrit localement : {LOCAL_JSON}")

    if arguments.dry_run:
        return 0

    uploader(contenu)
    print(f"Envoyé par FTP : /{FTP_PATH}/{SITE_JSON}")

    publie = verifier()
    jours_publies = sorted(publie)
    if jours_publies[-1] != jours[-1]:
        raise SystemExit(f"Publication incohérente : {jours_publies[-1]} au lieu de {jours[-1]}")
    dernier = publie[jours_publies[-1]]
    print(
        "Vérifié en public : "
        + ", ".join(f"{code}={dernier[code]}" for code in ("USD", "GBP", "CHF") if code in dernier)
    )
    print(f"Mis à jour le {datetime.now():%Y-%m-%d %H:%M}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
