# Notes de frais

Application Android de notes de frais : un projet par mission, une photo par
justificatif, un ZIP en sortie (récap Excel + PDF renommés).

## Périmètre validé

- **Projets** : nom libre, on passe de l'un à l'autre, renommage et suppression.
- **Capture** : appareil photo ou fichier existant (PDF accepté).
- **Extraction 100 % locale** (ML Kit, hors ligne, aucune donnée envoyée) :
  date, montant TTC, type de dépense, taux de TVA, devise.
- **Enregistrement direct** quand la date et le montant sont trouvés ; sinon le
  formulaire s'ouvre avec ce qui a été lu, et les champs manquants en clair.
- **Types** : avion, hôtel, train, taxi, restaurant midi, restaurant soir, autres.
- **Devises** : EUR, USD, GBP, CHF avec le taux de la **Banque de France**
  (série BCE), récupéré depuis https://trucdecomptable.fr/taux-devises-bdf.json
  à la **date de la dépense** (repli sur la dernière parité publiée, week-end
  compris), et modifiable à la main.
- **Ventilation HT / TVA** en option, par dépense (20 %, 10 %, 5,5 %, 0 %).
- **Export ZIP par projet** :
  - `recap.xlsx` : feuille « Dépenses » (détail + total) et feuille « Synthèse »
    (par type, dont TVA) ;
  - un PDF par dépense, nommé `Projet_AAAA-MM-JJ_type_montantDEVISE.pdf`,
    par exemple `Mission_Paris_2026-10-12_hotel_248.50EUR.pdf`.

## Taux de change (trucdecomptable.fr)

Les parités viennent de la Banque de France, publiées par le site de Fabrice :

- source : `https://webstat.banque-france.fr/export/csv-columns/fr/selection/5385698`
  (série EXR, source BCE, parité = nombre d'unités de devise pour 1 €) ;
- `scripts/maj_taux_bdf.py` télécharge ce CSV, le convertit en
  `{date: {DEVISE: parité}}` (12 ans d'historique, 30 devises, ~1,2 Mo), le
  dépose par FTP à la racine du site et vérifie l'URL publique ;
- publié ici : `https://trucdecomptable.fr/taux-devises-bdf.json` ;
- l'app le télécharge une fois puis le rafraîchit toutes les 20 h, et applique
  la parité du jour de la dépense (ou la dernière publiée avant, 7 jours max) ;
- cron quotidien ARGOS à 6 h 10 (`taux-bdf-quotidien`), silencieux si tout va
  bien, alerte si le fichier ne bouge plus.

## Mise à jour intégrée

Même mécanisme qu'ollamachat : le workflow publie une release roulante tag
`latest` contenant `app-release.apk` et `version.json`. L'app lit d'abord
`version.json` (un simple téléchargement d'asset, sans le quota de 60 requêtes
par heure de l'API GitHub), puis l'API en secours. Téléchargement direct avec
vérification des octets `PK`, puis installation par FileProvider.

Le dépôt doit rester **public**, sinon les assets de release répondent 404.

## Build

```bash
export ANDROID_HOME=/opt/android-sdk
./gradlew testDebugUnitTest assembleRelease
```

APK : `app/build/outputs/apk/release/app-release.apk`.

Signature : keystore `notesdefrais-release.keystore` (alias `notesdefrais`),
empreinte SHA-256 `7A:AD:68:FF:1F:21:30:F3:E3:DC:B8:B7:D0:C8:4C:29:81:A9:D2:9C:75:D2:89:4C:27:9E:CF:D9:3D:59:99:C9`.
Secrets CI : `NOTESDEFRAIS_KEYSTORE_B64`, `NOTESDEFRAIS_KEYSTORE_PASSWORD`,
`NOTESDEFRAIS_KEY_ALIAS`, `NOTESDEFRAIS_KEY_PASSWORD`.

## Tests

29 tests unitaires JVM : lecture des tickets (12 formats), générateur xlsx
(vérifié en dézippant le classeur), nommage des PDF, conversion de devises,
ventilation TVA, comparaison de versions et lecture du manifeste de MAJ.

## Technique

Kotlin · Jetpack Compose · Room · ML Kit Text Recognition · PdfDocument ·
OkHttp · minSdk 26 · targetSdk 35. Aucune dépendance pour produire le .xlsx :
les parties SpreadsheetML sont écrites directement.
