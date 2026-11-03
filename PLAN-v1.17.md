# Plan v1.17.0 — contrôles de cohérence de la note de frais

> Statut : livré en v1.17.0 (versionCode 32), 152 tests unitaires verts.


Six corrections demandées par Fabrice après revue du module. Objectif : que l'app
signale elle-même les erreurs qui coûtent de l'argent ou un aller-retour avec la
comptabilité, sans jamais bloquer la saisie d'une dépense réelle.

Règle de maison : un contrôle **avertit**, il ne refuse pas l'enregistrement.
Sauf la date future, qui reste une alerte aussi.

## 1. Plafond repas selon la situation (bug de fond)

`Plafonds.kt` déclare `REPAS_LIEU_TRAVAIL = 750` et `REPAS_NON_CONTRAINT = 1040`
mais `plafondCentimes()` renvoie toujours `REPAS_RESTAURANT = 2140`.

- Nouvel enum `SituationRepas` (`GRAND_DEPLACEMENT`, `LIEU_TRAVAIL`, `HORS_DEPLACEMENT`)
  dans `data/Modeles.kt`, avec `label`, `slug`, `fromName`, `fromSlug`.
- Nouveau champ `Depense.situationRepas: String = SituationRepas.GRAND_DEPLACEMENT.name`,
  plus un accesseur `Depense.situation: SituationRepas`.
- `Plafonds.plafondCentimes(type, zoneParis, situation)` et
  `Plafonds.alerte(depense, zoneParis, parNuit, situation = depense.situation)`
  appliquent 21,40 / 7,50 / 10,40 € selon la situation. Le petit-déjeuner garde
  son plafond actuel.
- Migration Room 13 → 14 : `ALTER TABLE depenses ADD COLUMN situationRepas TEXT NOT NULL DEFAULT 'GRAND_DEPLACEMENT'`.

**Recette** : un repas au lieu de travail à 15 € doit alerter (dépassement de
7,50 €) alors qu'il ne déclenchait rien avant.

## 2. Contrôle « justificatif manquant » avant l'export

`Controles.controleAvantExport(depenses)` renvoie une liste de phrases :
- dépenses sans aucune pièce jointe (nom, date, montant) ;
- repas > 150 € HT dont le justificatif n'est pas une pièce supplémentaire
  (une seule pièce = le ticket, donc facture probablement absente) ;
- en-tête chiffré : « 3 dépenses sans justificatif sur 27 ».

Côté UI : avant d'exporter, si la liste n'est pas vide, une fenêtre affiche le
détail avec « Exporter quand même » / « Annuler ». Rien n'est bloqué.

## 3. Cohérence du taux de TVA

`Controles.alerteTauxTva(depense)` compare le taux saisi à ceux possibles pour le
type :
- repas : 10 % (20 % toléré pour les boissons alcoolisées) → alerte si 5,5 % ou 0 % ;
- hôtel / petit-déjeuner : 10 % → alerte si 20 % ou 5,5 % ;
- train / avion : 10 %, 5,5 % ou 0 % (international) ;
- essence, péage, taxi, location : 20 % ;
- autres et indemnité km : pas de contrôle.

## 4. Repas avec invité incomplet

`Controles.alerteRepasSansInvite(depense)` :
- « Repas midi avec invité » sans `participants` → alerte (dépense non déductible) ;
- tout repas sans `motifRepas` → alerte plus douce.

## 5. N° de facture et doublon fiable

- Nouveau champ `Depense.numFacture: String = ""`, migration Room (même passage
  que le point 1) : `ALTER TABLE depenses ADD COLUMN numFacture TEXT NOT NULL DEFAULT ''`.
- `Doublons.alerteNumero(candidate, existantes)` : deux dépenses du même projet
  avec le même numéro non vide (comparaison insensible à la casse et aux espaces)
  → « Facture n° X déjà enregistrée le ... ».
- Champ « N° de facture » dans le formulaire, colonne « N° facture » dans le XLSX.
- L'OCR remplit le numéro s'il le trouve (`ReceiptParser` : motif `FACTURE N°`,
  `N° de facture`, `Fact.`), champ vide sinon.

## 6. Date postérieure à aujourd'hui

`Controles.alerteDateFuture(depense)` : date ISO > `LocalDate.now()` → « Date
postérieure à aujourd'hui (jj/mm/aaaa) : à vérifier ». Le formulaire affiche
l'alerte, l'enregistrement reste possible.

## Contrat technique (à respecter à la lettre, les deux phases s'y raccordent)

```kotlin
// data/Modeles.kt
enum class SituationRepas(val label: String, val slug: String) { ... }
data class Depense(..., val numFacture: String = "", val situationRepas: String = SituationRepas.GRAND_DEPLACEMENT.name)
    val situation: SituationRepas

// data/Plafonds.kt
fun plafondCentimes(type: TypeDepense, zoneParis: Boolean, situation: SituationRepas = SituationRepas.GRAND_DEPLACEMENT): Long?
fun alerte(depense: Depense, zoneParis: Boolean, parNuit: Int = 1, situation: SituationRepas = depense.situation): String?
fun libellePlafond(type: TypeDepense, zoneParis: Boolean, situation: SituationRepas = SituationRepas.GRAND_DEPLACEMENT): String?

// data/Controles.kt (nouveau)
object Controles {
    fun alerteDateFuture(depense: Depense, aujourdhui: LocalDate = LocalDate.now()): String?
    fun alerteTauxTva(depense: Depense): String?
    fun alerteRepasSansInvite(depense: Depense): String?
    fun controleAvantExport(depenses: List<Depense>): List<String>
    fun alertesSaisie(depense: Depense): List<String>   // date future + TVA + repas incomplet
}

// data/Plafonds.kt (objet Doublons)
fun alerteNumero(candidate: Depense, existantes: List<Depense>): String?
```

## Découpage et propriété des fichiers

**Phase 1 — données et contrôles (aucune UI)**
`data/Modeles.kt`, `data/Plafonds.kt`, `data/NotesDb.kt`, `data/Controles.kt` (nouveau),
`data/Justificatifs.kt` (inchangé sauf besoin), `ocr/ReceiptParser.kt` (n° de facture),
`app/src/test/java/com/trucdecomptable/notesdefrais/ControlesTest.kt` (nouveau),
`PlafondsTest.kt` (nouveau ou étendu).
Livrable : `./gradlew :app:compileDebugKotlin` vert + tests unitaires verts.

**Phase 2 — interface et export**
`ui/DialogueDepense.kt` (puces de situation, champ n° de facture, alertes en rouge,
rappel du bon plafond), `ui/NotesViewModel.kt` (alertes dans `avertissements()`,
état de contrôle avant export), `ui/Ecrans.kt` + `ui/App.kt` (fenêtre de contrôle
avant export), `export/ExportManager.kt` (colonne n° de facture, récap des pièces
manquantes).
Livrable : `./gradlew :app:assembleDebug` vert.

## Supervison (Etoile Magique)

1. Relire chaque diff (`git diff`) et vérifier le contrat ci-dessus.
2. `./gradlew :app:testDebugUnitTest` complet, puis `assembleRelease`.
3. Vérifier à la main les 6 recettes.
4. Bump 1.17.0 / versionCode 32, commit au format maison, APK livré avec son numéro.
5. Ne pas pousser de release GitHub sans accord de Fabrice.

## Contraintes communes

- Travailler dans `/root/projects/notes-de-frais` (arbre partagé, pas de copie).
- Ne pas committer, ne pas changer `versionName`/`versionCode`.
- Aucune dépendance nouvelle. Kotlin/Compose existants uniquement.
- Commentaires en français, expliquant le *pourquoi* métier (style du dépôt).
- Room : suivre le motif `MIGRATION_x_y` existant et l'ajouter à `.addMigrations(...)`.
