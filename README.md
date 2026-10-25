# Notes de frais

Application Android de notes de frais : un projet par mission, une photo par
justificatif, un ZIP en sortie (récap Excel + PDF renommés).

## Périmètre validé

- **Projets** : nom libre, on passe de l'un à l'autre, renommage et suppression.
- **Capture** : appareil photo, fichier existant (photo **ou PDF**), ou
  **partage depuis une autre app** (mail, galerie, boutique) : le fichier arrive
  directement dans le projet ouvert, et attend le choix d'un projet si aucun
  n'est ouvert. Plusieurs fichiers partagés d'un coup sont acceptés.
- **Extraction 100 % locale** (ML Kit, hors ligne, aucune donnée envoyée) :
  date, montant TTC, type de dépense, taux de TVA, devise. Un PDF est rendu page
  par page (3 pages au plus) avant lecture, puisque le moteur ne lit que des
  images ; s'il est protégé ou abîmé, le formulaire s'ouvre et le fichier reste
  joint.
- **Enregistrement direct** quand la date et le montant sont trouvés ; sinon le
  formulaire s'ouvre avec ce qui a été lu, et les champs manquants en clair.
- **Types** : avion, train, taxi, location de voiture, essence, péage, hôtel,
  repas midi seul, repas midi avec invité, restaurant soir, indemnité km, autres.
  Les dépenses « restaurant midi » d'avant la séparation deviennent « midi seul ».
- **Train et avion** : un aller simple ou un aller-retour, à cocher sur la dépense.
- **Repas** : nom et prénom des invités, leur société, motif du repas, pour que la
  dépense reste justifiée sur le plan fiscal.
- **Dates** : saisies et affichées en `jj/mm/aaaa` (le 12/10/2026 s'écrit aussi
  12-10-2026 ou 12/10/26), stockées en ISO pour le tri et les noms de fichiers.
- **Mode de paiement** par dépense : les cartes et autres moyens sont nommés
  dans le paramétrage (plusieurs CB, espèces, virement, **payé par un tiers**), un
  moyen par défaut est proposé sur chaque nouvelle dépense, et le total par moyen
  apparaît dans la synthèse de l'export.
- **Profils de société** : une société par jeu de projets (deux cabinets, deux
  SARL…). L'écran Projets filtre sur la société choisie, l'export reprend son nom,
  son SIRET, son adresse et son destinataire, et **chaque société a son propre
  moyen de paiement par défaut**. La société du projet est rappelée sous le total.
  Supprimer une société ne perd pas ses projets : ils repassent en « sans société ».
- **SIRET** : vérifié par sa clé, puis l'annuaire des entreprises
  (recherche-entreprises.api.gouv.fr) remplit la raison sociale et l'adresse.
- **Devises** : EUR, USD, GBP, CHF avec le taux de la **Banque de France**
  (série BCE), récupéré depuis https://trucdecomptable.fr/taux-devises-bdf.json
  à la **date de la dépense** (repli sur la dernière parité publiée, week-end
  compris), et modifiable à la main.
- **Ventilation HT / TVA** en option, par dépense (20 %, 10 %, 5,5 %, 0 %).
- **Paramétrage** (icône engrenage) : prénom, nom, société, adresse, SIRET,
  email destinataire des exports, plaque, catégorie de véhicule, puissance
  fiscale, électrique ou non, cumul kilométrique annuel, devise et TVA par défaut.
- **Indemnité kilométrique** : le montant n'est pas saisi, il est calculé avec le
  barème fiscal 2026 (voiture, moto, cyclomoteur ; électrique majoré de 20 %),
  péages et stationnement en supplément, et une **feuille de route PDF** est
  générée comme justificatif.
- **Pièces jointes visibles** : vignette dans la liste des dépenses, **PDF
  rendus page par page** (PdfRenderer, sans dépendance) comme les photos, plein
  écran au toucher avec **zoom au pincement ou par boutons**, page suivante pour
  un PDF, bouton « Ouvrir avec… », et pièces multiples par dépense.
- **Recadrage** d'une photo dans la visionneuse : quatre coins à glisser,
  rotation par pas de 90°, la version recadrée devient la pièce principale et
  l'originale est conservée en annexe.
- **Option** : ouvrir la pièce jointe en grand dès qu'on touche une dépense,
  avant le formulaire (paramétrage).
- **Sauvegarde et restauration** : archive complète (base, justificatifs,
  paramétrage), créée à la demande et **automatiquement avant chaque mise à
  jour**, partageable par mail ou vers Drive.
- **Paramétrage seul** : un fichier JSON avec le titulaire, le véhicule, les
  moyens de paiement et les sociétés, **sans aucune dépense**, à sauvegarder et
  restaurer séparément. À la restauration, les sociétés sont rapprochées par nom
  et SIRET (mise à jour ou ajout) et les projets existants ne bougent pas.
- **Rangement** : un projet s'affecte à une société depuis son menu, et une
  dépense se déplace vers un autre projet (jamais vers un projet clôturé) depuis
  sa fiche.
- **Export ZIP par projet** :
  - `recap.xlsx` : feuille « Dépenses » (détail + total, colonnes « N° » qui
    numérote les justificatifs dans l'ordre du ZIP, « Paiement », « Trajet »,
    « Invités », « Société invités », « Motif » et le **nom exact du fichier
    joint**, annexes comprises) et feuille « Synthèse » (société du profil, SIRET,
    adresse, par type, par moyen de paiement, dont TVA) ;
  - un PDF par dépense, nommé `Projet_AAAA-MM-JJ_type_montantDEVISE.pdf`,
    par exemple `Mission_Paris_2026-10-12_hotel_248.50EUR.pdf`. Les pièces
    supplémentaires suivent en `_annexe2`, `_annexe3`… et deux dépenses qui
    donneraient le même nom sont suffixées `_2`.

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

**Quand la vérification tourne** : au démarrage, puis toutes les 30 minutes
tant que l'app reste ouverte (plancher d'une heure côté `UpdateManager`), et à
la demande via l'icône nuage de l'écran Projets.

**Ce qui est automatique** : la détection, et le téléchargement de l'APK
lorsque le réseau n'est pas facturé (Wi-Fi). Sur données mobiles, le
téléchargement reste un bouton. L'installation, elle, demande toujours le tap
« Installer » puis la confirmation Android : le système interdit une
installation silencieuse.

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

72 tests unitaires JVM : lecture des tickets (12 formats), générateur xlsx
(vérifié en dézippant le classeur), nommage et numérotation des justificatifs,
dates françaises (saisie et affichage), aller-retour et invités d'un repas,
profils de société dans l'export, validation du SIRET, conversion de devises,
ventilation TVA, barème kilométrique 2026 (exemples officiels de
l'administration), plafonds d'exonération URSSAF 2026, détection de doublons,
comparaison de versions et lecture du manifeste de MAJ, paramétrage des moyens de
paiement.

## Technique

Kotlin · Jetpack Compose · Room · ML Kit Text Recognition · PdfDocument ·
OkHttp · minSdk 26 · targetSdk 35. Aucune dépendance pour produire le .xlsx :
les parties SpreadsheetML sont écrites directement.
