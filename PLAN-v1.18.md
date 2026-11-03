# Plan v1.18.0 — générer la facture depuis un ticket de caisse

> Statut : livré en v1.18.0 (versionCode 33), 170 tests unitaires verts.


Quand un justificatif n'est qu'un ticket, l'app doit permettre de récupérer la
vraie facture sur le site de l'enseigne, de la joindre à la dépense, puis de
proposer de retirer le ticket pour ne pas partir avec deux pièces pour une seule
dépense.

URL des services vérifiées le 03/11/2026 :
- Carrefour : https://www.carrefour.fr/services/facture
- Auchan : https://www.auchan.fr/facture — demande le n° du code-barres du
  ticket (22 chiffres minimum), refusé au-delà de 30 jours
- Lidl : https://www.lidl.fr/c/facture-en-ligne/s10019836
- Magasins U : https://www.magasins-u.com/ma-facture-u
- E.Leclerc et Monoprix : pas de formulaire ticket → facture, tout passe par
  l'espace client (Mon E.Leclerc « Mes tickets de caisse et factures »,
  Monoprix+ « Mes tickets de caisse ») : on propose le lien, pas un formulaire
- Intermarché : aucun service trouvé, donc pas d'entrée

Les URL changent chez les enseignes : centraliser dans un seul fichier pour
qu'une correction soit une ligne.

## 1. Données (phase 1)

`data/Enseignes.kt` :

```kotlin
data class Enseigne(
    val nom: String,              // « Auchan »
    val urlFacture: String,       // portail « facture en ligne »
    val motsCles: List<String>,   // détection sur le texte OCR, en majuscules sans accents
    val champDemande: String,     // « N° du code-barres du ticket (22 chiffres) »
    val delaiJours: Int?,         // 30 pour Auchan, null quand inconnu
    val note: String = "",        // « espace client », « formulaire ticket », etc.
    val formulaireTicket: Boolean, // true = on peut générer depuis le ticket
)

object Enseignes {
    val toutes: List<Enseigne>
    fun detecter(texteOcr: String): Enseigne?   // mots-clés, insensible à la casse et aux accents
    fun parNom(nom: String): Enseigne?
}
```

Pièges de détection : « U » seul est trop large (U Express, magasins U, mais
aussi « U » dans un mot), donc exiger `SUPER U`, `HYPER U`, `MAGASINS U`,
`U EXPRESS`, `UTILE`. Idem `CARREFOUR` avant `MARKET`/`CONTACT`, `LECLERC` et
`E.LECLERC`, `MONOPRIX`, `LIDL`, `AUCHAN`.

`ocr/CodeBarre.kt` : lecture du code-barres du ticket avec ML Kit
(`com.google.mlkit:barcode-scanning:17.3.0`, on-device comme l'OCR déjà présent).

```kotlin
object CodeBarre {
    /** Codes numériques trouvés dans l'image, les plus longs d'abord (≥ 8 chiffres). */
    suspend fun lire(context: Context, fichier: File): List<String>
    /** Le meilleur candidat pour un portail enseigne : ≥ 22 chiffres d'abord. */
    fun meilleur(codes: List<String>): String?
}
```

Tests (JUnit, sans Android) : `EnseignesTest` (détection de chaque enseigne,
absence de faux positif sur « U » isolé, `parNom`), `CodeBarreTest` sur
`meilleur()` (priorité aux 22+ chiffres, rejet des codes courts ou non numériques).

## 2. Interface (phase 2)

`ui/DialogueDepense.kt`, section « Facture » visible quand `initiale.id != 0L` :
- enseigne détectée depuis `initiale.texteOcr` → « Ticket Auchan : générer la
  facture sur auchan.fr » ;
- bouton « Générer la facture » → ouvre l'URL dans le navigateur **et** copie le
  code-barres du ticket dans le presse-papier (lu à la demande depuis la photo,
  ou depuis l'annexe si le ticket est déjà en annexe) ; message « N° de ticket
  copié, collez-le sur le site » ;
- bouton « J'ai la facture » quand le formulaire est déjà passé → sélecteur
  PDF/photo → `vm.ajouterFacture(depense, uri)`.

`ui/NotesViewModel.kt` :
- `fun ajouterFacture(depense: Depense, uri: Uri)` : stocke le fichier avec le
  même recadrage que les autres pièces, l'ajoute aux annexes, puis publie
  `remplacementTicket` (état = dépense + nom de la facture + nom du ticket) ;
- `refusRemplacementTicket()` : ferme la proposition, on garde les deux pièces ;
- `remplacerTicketParFacture(depense, nomFacture)` : la facture devient la pièce
  principale (`fichier = nomFacture`), elle sort des annexes, la photo du ticket
  est supprimée du disque, la dépense est enregistrée, message « Ticket remplacé
  par la facture ».

`ui/App.kt` : fenêtre « Ticket et facture » quand `remplacementTicket` est
publié — « La facture est jointe. Supprimer la photo du ticket pour éviter un
doublon ? » avec « Supprimer le ticket » et « Garder les deux ».

Règles : la suppression n'est jamais automatique, et la facture n'écrase jamais
le ticket sans accord. Un échec de lecture du code-barres ne bloque rien : le
bouton ouvre quand même le portail.

## 3. Vérification

- Phase 1 : `./gradlew :app:compileDebugKotlin` + `testDebugUnitTest` verts.
- Phase 2 : `./gradlew :app:assembleDebug` + `testDebugUnitTest` verts.
- Supervision : diff relu, `--rerun-tasks` sur toute la suite, build release
  signé, APK v1.18.0, commit, push (CI publie `latest`), release versionnée.
