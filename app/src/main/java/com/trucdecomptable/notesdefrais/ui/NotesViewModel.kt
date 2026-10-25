package com.trucdecomptable.notesdefrais.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trucdecomptable.notesdefrais.backup.Sauvegarde
import com.trucdecomptable.notesdefrais.data.BaremeKm
import com.trucdecomptable.notesdefrais.data.Depense
import com.trucdecomptable.notesdefrais.data.Doublons
import com.trucdecomptable.notesdefrais.data.NotesDb
import com.trucdecomptable.notesdefrais.data.Parametres
import com.trucdecomptable.notesdefrais.data.ParametresStore
import com.trucdecomptable.notesdefrais.data.Plafonds
import com.trucdecomptable.notesdefrais.data.Projet
import com.trucdecomptable.notesdefrais.data.TauxBdf
import com.trucdecomptable.notesdefrais.data.TauxBdfClient
import com.trucdecomptable.notesdefrais.data.TypeDepense
import com.trucdecomptable.notesdefrais.export.ExportManager
import com.trucdecomptable.notesdefrais.export.FeuilleDeRoutePdf
import com.trucdecomptable.notesdefrais.ocr.ReceiptOcr
import com.trucdecomptable.notesdefrais.ocr.ReceiptParser
import com.trucdecomptable.notesdefrais.util.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * One ViewModel for the whole app: projects, the selected project's expenses,
 * the OCR pipeline and the export.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = NotesDb.get(application).dao()
    private val tauxClient = TauxBdfClient(application)
    private val parametresStore = ParametresStore(application)

    private val _parametres = MutableStateFlow(parametresStore.lire())
    val parametres: StateFlow<Parametres> = _parametres.asStateFlow()

    fun enregistrerParametres(nouveaux: Parametres) {
        parametresStore.ecrire(nouveaux)
        _parametres.value = nouveaux
        _message.value = "Paramétrage enregistré"
    }

    /** Backup and restore, since everything lives on the phone. */
    fun sauvegarder(motif: String = "manuel") {
        viewModelScope.launch {
            runCatching { Sauvegarde.creer(getApplication(), motif) }
                .onSuccess { resume ->
                    Sauvegarde.purger(getApplication())
                    _sauvegarde.value = resume.fichier
                    _message.value = "Sauvegarde prête : ${resume.depenses} dépense(s), ${resume.justificatifs} fichier(s)"
                }
                .onFailure {
                    Diag.record("sauvegarde", it)
                    _message.value = "Sauvegarde impossible : ${it.message ?: "erreur"}"
                }
        }
    }

    fun restaurer(uri: android.net.Uri) {
        viewModelScope.launch {
            runCatching { Sauvegarde.restaurer(getApplication(), uri) }
                .onSuccess {
                    _message.value = it.message
                    _parametres.value = parametresStore.lire()
                }
                .onFailure {
                    Diag.record("restauration", it)
                    _message.value = "Restauration impossible : ${it.message ?: "erreur"}"
                }
        }
    }

    fun sauvegardeConsommee() {
        _sauvegarde.value = null
    }

    /**
     * Archive first, then hand the APK to the installer. Doing both in one
     * coroutine is the point: an install that starts before the backup finishes
     * would defeat it, and an update is exactly when data is at risk.
     */
    fun sauvegarderPuisInstaller(installer: () -> Unit) {
        viewModelScope.launch {
            runCatching { Sauvegarde.creer(getApplication(), "avant-maj") }
                .onSuccess { Diag.record("sauvegarde", "archive avant mise à jour : ${it.fichier.name}") }
                .onFailure { Diag.record("sauvegarde/avant-maj", it) }
            installer()
        }
    }

    init {
        // Rates come from trucdecomptable.fr (Banque de France series). A phone
        // that is offline keeps the last downloaded copy.
        viewModelScope.launch { tauxClient.rafraichir() }
        // Expenses recorded before the payment method existed inherit the
        // default card, once: the flag stops a later choice from being undone.
        viewModelScope.launch {
            val p = _parametres.value
            if (p.paiementRempliAuto || p.moyenPaiementDefaut.isBlank()) return@launch
            val touchees = runCatching { dao.remplirMoyenPaiement(p.moyenPaiementDefaut) }
                .onFailure { Diag.record("paiement/pre-remplissage", it) }
                .getOrDefault(0)
            enregistrerParametresSilencieux(p.copy(paiementRempliAuto = true))
            if (touchees > 0) {
                _message.value = "$touchees dépense(s) existante(s) marquées « ${p.moyenPaiementDefaut} »"
            }
        }
    }

    /** Rate for the expense date, from the Banque de France series. */
    fun tauxBdf(dateIso: String, devise: String): TauxBdf.Taux? =
        runCatching { tauxClient.taux(dateIso, devise) }.getOrNull()

    fun couvertureTaux(): Pair<String, String>? = runCatching { tauxClient.couverture() }.getOrNull()

    /** Manual refresh from the top bar. */
    fun rafraichirTaux() {
        viewModelScope.launch {
            val ok = tauxClient.rafraichir(force = true)
            _message.value = if (ok) "Parités Banque de France mises à jour" else "Parités inchangées ou injoignables"
        }
    }

    val projets: StateFlow<List<Projet>> = dao.projets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _projet = MutableStateFlow<Projet?>(null)
    val projet: StateFlow<Projet?> = _projet.asStateFlow()

    val depenses: StateFlow<List<Depense>> = _projet
        .flatMapLatest { p -> if (p == null) flowOf(emptyList()) else dao.depenses(p.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Set while the OCR pipeline runs, so the UI can show a spinner. */
    private val _traitement = MutableStateFlow(false)
    val traitement: StateFlow<Boolean> = _traitement.asStateFlow()

    /** Fields to confirm by hand: OCR found neither amount nor date. */
    private val _brouillon = MutableStateFlow<Depense?>(null)
    val brouillon: StateFlow<Depense?> = _brouillon.asStateFlow()

    /** Editor open on an existing expense. */
    private val _edition = MutableStateFlow<Depense?>(null)
    val edition: StateFlow<Depense?> = _edition.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Last expense saved straight from OCR, offered for a quick correction. */
    private val _derniere = MutableStateFlow<Depense?>(null)
    val derniere: StateFlow<Depense?> = _derniere.asStateFlow()

    private val _export = MutableStateFlow<File?>(null)
    val export: StateFlow<File?> = _export.asStateFlow()

    private val _sauvegarde = MutableStateFlow<File?>(null)
    val sauvegarde: StateFlow<File?> = _sauvegarde.asStateFlow()

    // ------------------------------------------------------------------ indemnité km

    /**
     * A mileage allowance has no receipt: the amount comes from the official
     * bracket, and the proof is a generated feuille de route.
     */
    fun ajouterIndemniteKm(
        date: String,
        distanceKm: Int,
        trajet: String,
        fraisAnnexesCentimes: Long,
        libelle: String,
    ) {
        val projet = _projet.value ?: return
        if (distanceKm <= 0) {
            _message.value = "Distance manquante"
            return
        }
        viewModelScope.launch {
            val p = _parametres.value
            val indemnite = BaremeKm.indemniteCentimes(
                distanceKm = distanceKm,
                cumulAnnuelKm = p.kmAnnuels,
                categorie = p.categorie,
                puissanceFiscale = p.puissanceFiscale,
                electrique = p.electrique,
            )
            val depense = Depense(
                projetId = projet.id,
                date = date,
                montantCentimes = indemnite + fraisAnnexesCentimes,
                devise = "EUR",
                type = TypeDepense.INDENNITE_KM.name,
                libelle = libelle,
                distanceKm = distanceKm,
                trajet = trajet,
                indemniteCentimes = indemnite,
                fraisAnnexesCentimes = fraisAnnexesCentimes,
            )
            val fichier = withContext(Dispatchers.IO) {
                runCatching {
                    val nom = "feuille-de-route-${java.util.UUID.randomUUID()}.pdf"
                    val cible = File(ExportManager.dossierJustificatifs(getApplication()), nom)
                    FeuilleDeRoutePdf.generer(cible, p, projet, depense)
                    nom
                }.onFailure { Diag.record("feuille-de-route", it) }.getOrNull()
            }
            val id = dao.ajouterDepense(depense.copy(fichier = fichier))
            // The yearly total drives the bracket: keep it in step with reality.
            if (p.kmAnnuels > 0) enregistrerParametresSilencieux(p.copy(kmAnnuels = p.kmAnnuels + distanceKm))
            _derniere.value = depense.copy(id = id, fichier = fichier)
            _message.value = "Indemnité km · ${NotesViewModel.montantLisible(depense)} · $distanceKm km"
        }
    }

    private fun enregistrerParametresSilencieux(nouveaux: Parametres) {
        parametresStore.ecrire(nouveaux)
        _parametres.value = nouveaux
    }

    // ------------------------------------------------------------------ projets

    fun creerProjet(nom: String) {
        val propre = nom.trim()
        if (propre.isEmpty()) return
        viewModelScope.launch {
            val id = dao.ajouterProjet(Projet(nom = propre))
            _projet.value = dao.projet(id)
            traiterPartagesEnAttente()
        }
    }

    fun ouvrirProjet(projet: Projet) {
        _projet.value = projet
        traiterPartagesEnAttente()
    }

    fun fermerProjet() {
        _projet.value = null
    }

    fun renommerProjet(projet: Projet, nom: String) {
        val propre = nom.trim()
        if (propre.isEmpty()) return
        viewModelScope.launch {
            dao.majProjet(projet.copy(nom = propre))
            if (_projet.value?.id == projet.id) _projet.value = projet.copy(nom = propre)
        }
    }

    /** Closing a project freezes it: the report has been handed over. */
    fun cloturerProjet(projet: Projet) {
        viewModelScope.launch {
            val ferme = projet.copy(cloture = !projet.cloture, clotureLe = if (projet.cloture) null else System.currentTimeMillis())
            dao.majProjet(ferme)
            if (_projet.value?.id == projet.id) _projet.value = ferme
            _message.value = if (ferme.cloture) "Projet clôturé (lecture seule)" else "Projet réouvert"
        }
    }

    /** Duplicates the project's name and settings, without its expenses. */
    fun dupliquerProjet(projet: Projet) {
        viewModelScope.launch {
            val id = dao.ajouterProjet(Projet(nom = "${projet.nom} (copie)"))
            _projet.value = dao.projet(id)
            _message.value = "Projet dupliqué"
        }
    }

    /** Adds a second attachment (invoice, back of the receipt) to an expense. */
    fun ajouterPieceJointe(depense: Depense, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val fichier = withContext(Dispatchers.IO) { ReceiptOcr.stocker(getApplication(), uri) }
                val liste = depense.tousLesFichiers + fichier.name
                val maj = depense.copy(fichiersSupplementaires = liste.drop(1).joinToString("|"))
                dao.majDepense(maj)
                _edition.value = maj
                _message.value = "Pièce jointe ajoutée"
            }.onFailure {
                Diag.record("piece-jointe", it)
                _message.value = "Ajout impossible : ${it.message ?: "erreur"}"
            }
        }
    }

    fun supprimerProjet(projet: Projet) {
        viewModelScope.launch {
            val fichiers = withContext(Dispatchers.IO) { dao.depensesSync(projet.id) }
                .mapNotNull { it.fichier }
            dao.supprimerDepensesDe(projet.id)
            dao.supprimerProjet(projet.id)
            withContext(Dispatchers.IO) {
                fichiers.forEach { File(ExportManager.dossierJustificatifs(getApplication()), it).delete() }
            }
            if (_projet.value?.id == projet.id) _projet.value = null
            _message.value = "Projet supprimé"
        }
    }

    // ------------------------------------------------------------------ captures

    /**
     * The path Fabrice asked for: photograph, read, save. Only a missing amount
     * or date opens a form; anything else is saved straight away and can be
     * corrected from the list.
     */
    fun traiterJustificatif(uri: Uri) {
        if (_projet.value == null) {
            _message.value = "Ouvre d'abord un projet"
            return
        }
        viewModelScope.launch { enregistrerJustificatif(uri) }
    }

    /** Files handed over by another app, waiting for a project to land in. */
    private val _partagesEnAttente = MutableStateFlow<List<Uri>>(emptyList())

    /**
     * A PDF or a photo shared from the mail, the gallery or a shop app. With a
     * project open it is filed immediately; otherwise it waits for one, since
     * the share sheet says nothing about where it belongs.
     */
    fun recevoirPartage(uris: List<Uri>) {
        val propres = uris.filter { it.scheme != null }
        if (propres.isEmpty()) return
        if (_projet.value == null) {
            _partagesEnAttente.value = _partagesEnAttente.value + propres
            _message.value = if (propres.size == 1) {
                "Fichier reçu : ouvre le projet où le ranger"
            } else {
                "${propres.size} fichiers reçus : ouvre le projet où les ranger"
            }
        } else {
            viewModelScope.launch { propres.forEach { enregistrerJustificatif(it) } }
        }
    }

    /** Called when a project opens: the shared files finally have a home. */
    private fun traiterPartagesEnAttente() {
        val liste = _partagesEnAttente.value
        if (liste.isEmpty()) return
        _partagesEnAttente.value = emptyList()
        viewModelScope.launch { liste.forEach { enregistrerJustificatif(it) } }
    }

    private suspend fun enregistrerJustificatif(uri: Uri) {
        val projet = _projet.value ?: return
        _traitement.value = true
        try {
            val fichier = withContext(Dispatchers.IO) { ReceiptOcr.stocker(getApplication(), uri) }
            val texte = runCatching { ReceiptOcr.texte(getApplication(), fichier) }
                .onFailure { Diag.record("ocr/texte", it) }
                .getOrDefault("")
            val champs = ReceiptParser.parse(texte)
            Diag.record(
                "ocr/resultat",
                "${fichier.name} date=${champs.date} montant=${champs.montantCentimes} type=${champs.type} tva=${champs.tvaTauxBp}",
            )

            val base = Depense(
                projetId = projet.id,
                date = champs.date ?: LocalDate.now().toString(),
                montantCentimes = champs.montantCentimes ?: 0L,
                devise = champs.devise,
                type = (champs.type ?: TypeDepense.AUTRE).name,
                libelle = champs.libelle,
                tvaTauxBp = champs.tvaTauxBp,
                fichier = fichier.name,
                texteOcr = texte,
                moyenPaiement = _parametres.value.moyenPaiementDefaut,
            )

            if (champs.complet) {
                // A foreign receipt saved straight from OCR still needs its
                // euro equivalent: take the Banque de France parity here,
                // since no form was opened to ask for it.
                val avecTaux = if (!base.devise.equals("EUR", ignoreCase = true)) {
                    val trouve = runCatching { tauxClient.taux(base.date, base.devise) }.getOrNull()
                    base.copy(tauxChange = trouve?.valeur)
                } else {
                    base
                }
                val id = dao.ajouterDepense(avecTaux)
                _derniere.value = avecTaux.copy(id = id)
                val devise = if (avecTaux.tauxChange == null && avecTaux.devise != "EUR") " · taux BdF absent" else ""
                val avertissement = avertissements(avecTaux.copy(id = id))
                _message.value = avertissement
                    ?: "Enregistré · ${montantLisible(avecTaux)} · ${avecTaux.typeDepense.label}$devise"
            } else {
                val manquants = buildList {
                    if (champs.date == null) add("date")
                    if (champs.montantCentimes == null) add("montant")
                }.joinToString(" et ")
                _brouillon.value = base
                _message.value = "À compléter : $manquants"
            }
        } catch (e: Exception) {
            Diag.record("capture", e)
            _message.value = "Lecture impossible : ${e.message ?: "erreur"}"
        } finally {
            _traitement.value = false
        }
    }

    fun ajouterManuellement() {
        val projet = _projet.value ?: return
        val p = _parametres.value
        _brouillon.value = Depense(
            projetId = projet.id,
            date = LocalDate.now().toString(),
            montantCentimes = 0L,
            devise = p.deviseDefaut,
            type = TypeDepense.AUTRE.name,
            tvaTauxBp = p.tvaDefautBp.takeIf { it > 0 },
            moyenPaiement = p.moyenPaiementDefaut,
        )
    }

    fun validerBrouillon(depense: Depense) {
        viewModelScope.launch {
            // A rate left empty on a foreign expense is filled with the parity
            // of the expense date rather than stored as a dead amount.
            val finalisee = if (!depense.devise.equals("EUR", ignoreCase = true) && depense.tauxChange == null) {
                val trouve = runCatching { tauxClient.taux(depense.date, depense.devise) }.getOrNull()
                depense.copy(tauxChange = trouve?.valeur)
            } else {
                depense
            }
            if (finalisee.id == 0L) {
                val id = dao.ajouterDepense(finalisee)
                _derniere.value = finalisee.copy(id = id)
                val avertissement = avertissements(finalisee)
                _message.value = avertissement ?: "Dépense ajoutée"
            } else {
                dao.majDepense(finalisee)
                val avertissement = avertissements(finalisee)
                _message.value = avertissement ?: "Dépense modifiée"
            }
            _brouillon.value = null
            _edition.value = null
        }
    }

    /**
     * Duplicate and ceiling checks: neither blocks the save, because a real
     * expense stays real, but both are worth saying out loud once.
     */
    private suspend fun avertissements(depense: Depense): String? {
        val existantes = dao.depensesSync(depense.projetId)
        return listOfNotNull(
            Doublons.alerte(depense, existantes.filter { it.id != depense.id }),
            Plafonds.alerte(depense, _parametres.value.zoneParis),
        ).joinToString(" · ").ifBlank { null }
    }

    fun editer(depense: Depense) {
        _edition.value = depense
    }

    fun fermerEditeur() {
        _edition.value = null
        _brouillon.value = null
    }

    fun supprimerDepense(depense: Depense) {
        viewModelScope.launch {
            dao.supprimerDepense(depense.id)
            withContext(Dispatchers.IO) {
                depense.fichier?.let {
                    File(ExportManager.dossierJustificatifs(getApplication()), it).delete()
                }
            }
            _message.value = "Dépense supprimée"
        }
    }

    // ------------------------------------------------------------------- export

    fun exporter() {
        val projet = _projet.value ?: return
        viewModelScope.launch {
            try {
                _traitement.value = true
                val liste = withContext(Dispatchers.IO) { dao.depensesSync(projet.id) }
                if (liste.isEmpty()) {
                    _message.value = "Aucune dépense à exporter"
                    return@launch
                }
                val fichier = ExportManager.exporter(getApplication(), projet, liste, _parametres.value)
                _export.value = fichier
                _message.value = "ZIP prêt : ${liste.size} justificatif(s)"
            } catch (e: Exception) {
                Diag.record("export", e)
                _message.value = "Export impossible : ${e.message ?: "erreur"}"
            } finally {
                _traitement.value = false
            }
        }
    }

    fun exportConsomme() {
        _export.value = null
    }

    fun messageLu() {
        _message.value = null
    }

    fun derniereOubliee() {
        _derniere.value = null
    }

    companion object {
        fun montantLisible(depense: Depense): String {
            val montant = depense.montantCentimes / 100.0
            val nombre = String.format(java.util.Locale.FRANCE, "%.2f", montant)
            return if (depense.devise == "EUR") "$nombre €" else "$nombre ${depense.devise}"
        }
    }
}
