package com.trucdecomptable.notesdefrais.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trucdecomptable.notesdefrais.backup.Sauvegarde
import com.trucdecomptable.notesdefrais.data.AnnuaireEntreprises
import com.trucdecomptable.notesdefrais.data.BaremeKm
import com.trucdecomptable.notesdefrais.data.Depense
import com.trucdecomptable.notesdefrais.data.Doublons
import com.trucdecomptable.notesdefrais.data.NotesDb
import com.trucdecomptable.notesdefrais.data.Parametres
import com.trucdecomptable.notesdefrais.data.ParametresStore
import com.trucdecomptable.notesdefrais.data.Plafonds
import com.trucdecomptable.notesdefrais.data.Profil
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * Un mail à remettre à la messagerie : les fichiers qu'il emporte, son type et
 * son rang quand l'envoi a dû être découpé en plusieurs mails.
 */
data class Envoi(
    val fichiers: List<File>,
    val type: String,
    val sujet: String,
    val rang: Int,
    val total: Int,
) {
    val poids: Long get() = fichiers.sumOf { it.length() }
    val decoupe: Boolean get() = total > 1
}

/**
 * One ViewModel for the whole app: projects, the selected project's expenses,
 * the OCR pipeline and the export.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = NotesDb.get(application).dao()
    private val profilsDao = NotesDb.get(application).profilsDao()
    private val tauxClient = TauxBdfClient(application)
    private val parametresStore = ParametresStore(application)

    private val _parametres = MutableStateFlow(parametresStore.lire())
    val parametres: StateFlow<Parametres> = _parametres.asStateFlow()

    // ------------------------------------------------------------------ profils

    val profils: StateFlow<List<Profil>> = profilsDao.profils()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Société sur laquelle on travaille : ses projets, son export. */
    private val _profilCourant = MutableStateFlow(parametresStore.lire().profilCourant)
    val profilCourant: StateFlow<Long> = _profilCourant.asStateFlow()

    /** Le profil en cours, pour ses moyens de paiement propres notamment. */
    val profilActif: StateFlow<Profil?> = combine(profils, _profilCourant) { liste, id ->
        liste.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun choisirProfil(id: Long) {
        _profilCourant.value = id
        enregistrerParametresSilencieux(_parametres.value.copy(profilCourant = id))
        _projet.value = null
    }

    fun ajouterProfil(profil: Profil) {
        viewModelScope.launch {
            val id = profilsDao.ajouter(profil)
            if (_profilCourant.value == 0L) choisirProfil(id)
            _message.value = "Société ajoutée"
        }
    }

    fun majProfil(profil: Profil) {
        viewModelScope.launch {
            profilsDao.maj(profil)
            _message.value = "Société enregistrée"
        }
    }

    /**
     * Affecte un projet (donc ses dépenses) à une société. [tousLesSansSociete]
     * range d'un coup les projets encore sans société : c'est la reprise des
     * anciens projets après la création des profils.
     */
    fun affecterProfil(projet: Projet?, profilId: Long, tousLesSansSociete: Boolean = false) {
        viewModelScope.launch {
            if (tousLesSansSociete) {
                val nombre = profilsDao.affecterProjetsSansSociete(profilId)
                _message.value = "$nombre projet(s) affecté(s)"
            } else if (projet != null) {
                dao.majProjet(projet.copy(profilId = profilId))
                if (_projet.value?.id == projet.id) _projet.value = projet.copy(profilId = profilId)
                _message.value = "Projet affecté"
            }
        }
    }

    /** Supprimer une société ne perd pas ses projets : ils repassent en « sans société ». */
    fun supprimerProfil(profil: Profil) {
        viewModelScope.launch {
            profilsDao.transfererProjets(profil.id, 0)
            profilsDao.supprimer(profil.id)
            if (_profilCourant.value == profil.id) choisirProfil(0)
            _message.value = "Société supprimée, ses projets sont conservés"
        }
    }

    /** SIRET -> raison sociale et adresse du siège (API publique de l'État). */
    suspend fun chercherSiret(siret: String): AnnuaireEntreprises.Info? =
        AnnuaireEntreprises.chercher(siret)

    /**
     * Moyen de paiement proposé sur une nouvelle dépense : celui de la société
     * en cours, sinon celui du paramétrage général.
     */
    private suspend fun moyenPaiementParDefaut(): String {
        val id = _profilCourant.value
        if (id > 0L) {
            profilsDao.profil(id)?.moyenPaiementDefaut
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return _parametres.value.moyenPaiementDefaut
    }

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

    /** Paramétrage et sociétés seuls, dans un JSON : les dépenses ne bougent pas. */
    private val _fichierParametres = MutableStateFlow<File?>(null)
    val fichierParametres: StateFlow<File?> = _fichierParametres.asStateFlow()

    fun sauvegarderParametres() {
        viewModelScope.launch {
            runCatching { Sauvegarde.creerParametres(getApplication()) }
                .onSuccess { fichier ->
                    _fichierParametres.value = fichier
                    _message.value = "Paramétrage prêt : ${fichier.name}"
                }
                .onFailure {
                    Diag.record("sauvegarde/parametres", it)
                    _message.value = "Sauvegarde impossible : ${it.message ?: "erreur"}"
                }
        }
    }

    fun fichierParametresConsomme() {
        _fichierParametres.value = null
    }

    fun restaurerParametres(uri: Uri) {
        viewModelScope.launch {
            runCatching { Sauvegarde.restaurerParametres(getApplication(), uri) }
                .onSuccess { resume ->
                    _message.value = resume
                    _parametres.value = parametresStore.lire()
                }
                .onFailure {
                    Diag.record("restauration/parametres", it)
                    _message.value = "Restauration impossible : ${it.message ?: "erreur"}"
                }
        }
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
            if (!p.moyensPaiementV2) {
                // « Payé par un tiers » s'ajoute aux listes déjà enregistrées.
                val complete = (p.moyensPaiement + MOYEN_TIERS).distinct()
                enregistrerParametresSilencieux(p.copy(moyensPaiement = complete, moyensPaiementV2 = true))
            }
            val aJour = _parametres.value
            // La pièce jointe en premier devient le comportement par défaut :
            // appliqué une fois aux réglages déjà enregistrés.
            if (!aJour.pieceEnGrandV2) {
                enregistrerParametresSilencieux(aJour.copy(pieceEnGrand = true, pieceEnGrandV2 = true))
            }
            val regle = _parametres.value
            if (regle.paiementRempliAuto || regle.moyenPaiementDefaut.isBlank()) return@launch
            val touchees = runCatching { dao.remplirMoyenPaiement(regle.moyenPaiementDefaut) }
                .onFailure { Diag.record("paiement/pre-remplissage", it) }
                .getOrDefault(0)
            enregistrerParametresSilencieux(regle.copy(paiementRempliAuto = true))
            if (touchees > 0) {
                _message.value = "$touchees dépense(s) existante(s) marquées « ${regle.moyenPaiementDefaut} »"
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

    val projets: StateFlow<List<Projet>> = _profilCourant
        .flatMapLatest { id -> dao.projetsDuProfil(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Tous les projets, sans filtre de société : sert au déplacement d'une dépense. */
    val tousProjets: StateFlow<List<Projet>> = dao.projets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _projet = MutableStateFlow<Projet?>(null)
    val projet: StateFlow<Projet?> = _projet.asStateFlow()

    val depenses: StateFlow<List<Depense>> = _projet
        .flatMapLatest { p -> if (p == null) flowOf(emptyList()) else dao.depenses(p.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Dépenses jetées du projet en cours, la plus récemment supprimée d'abord. */
    val corbeille: StateFlow<List<Depense>> = _projet
        .flatMapLatest { p -> if (p == null) flowOf(emptyList()) else dao.corbeille(p.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Badge de la corbeille : ce qui a été jeté et peut encore revenir. */
    val nbCorbeille: StateFlow<Int> = _projet
        .flatMapLatest { p -> if (p == null) flowOf(0) else dao.nombreCorbeille(p.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

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

    /** Export trop lourd : il attend la décision (découper en deux ou envoyer tel quel). */
    private val _exportTrop = MutableStateFlow<File?>(null)
    val exportTrop: StateFlow<File?> = _exportTrop.asStateFlow()

    /** Un mail prêt à partir, avec son rang quand l'envoi est découpé. */
    private val _envoi = MutableStateFlow<Envoi?>(null)
    val envoi: StateFlow<Envoi?> = _envoi.asStateFlow()

    /** L'envoi qui attend son tour : proposé par un dialogue, jamais imposé. */
    private val _envoiSuivant = MutableStateFlow<Envoi?>(null)
    val envoiSuivant: StateFlow<Envoi?> = _envoiSuivant.asStateFlow()

    private var planEnvois: List<Envoi> = emptyList()
    private var rangEnvoi = 0

    /** Projet dont l'archivage attend la réponse : envoyer les justificatifs ou non. */
    private val _archivageEnAttente = MutableStateFlow<Projet?>(null)
    val archivageEnAttente: StateFlow<Projet?> = _archivageEnAttente.asStateFlow()

    /** Ce qu'il faut pour reconstruire les parties sans relire la base. */
    private val _projetExporte = MutableStateFlow<Projet?>(null)
    private val _listeExportee = MutableStateFlow<List<Depense>>(emptyList())
    private val _profilExporte = MutableStateFlow<Profil?>(null)

    private val _sauvegarde = MutableStateFlow<File?>(null)
    val sauvegarde: StateFlow<File?> = _sauvegarde.asStateFlow()

    /** Destinataire du prochain partage : celui du profil, sinon celui du paramétrage. */
    private val _emailExport = MutableStateFlow<String?>(null)
    val emailExport: StateFlow<String?> = _emailExport.asStateFlow()

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
        val profil = _profilCourant.value
        viewModelScope.launch {
            val id = dao.ajouterProjet(Projet(nom = propre, profilId = profil))
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

    /** Les projets rangés : hors de l'accueil, mais entiers. */
    val projetsArchives: StateFlow<List<Projet>> = _profilCourant
        .flatMapLatest { id -> dao.projetsArchives(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Archiver range un projet (il disparaît de l'accueil) sans le clôturer :
     * les dépenses, les pièces jointes et l'export restent possibles si on le
     * rouvre depuis les archives. Un projet clôturé peut aussi être archivé.
     *
     * Si les justificatifs ne sont jamais partis à la comptabilité, on le
     * propose avant de ranger le projet, plutôt que de le faire disparaître de
     * l'accueil sans que personne n'ait les pièces. Un projet sans pièce jointe,
     * ou déjà envoyé, s'archive direct.
     */
    fun demanderArchivage(projet: Projet) {
        viewModelScope.launch {
            val aEnvoyer = withContext(Dispatchers.IO) {
                projet.justificatifsEnvoyesLe == null &&
                    dao.depensesSync(projet.id).any { it.tousLesFichiers.isNotEmpty() }
            }
            if (aEnvoyer) _archivageEnAttente.value = projet else archiver(projet)
        }
    }

    /** Envoie les justificatifs à la comptabilité, puis archive le projet. */
    fun envoyerEtArchiver(projet: Projet) {
        _archivageEnAttente.value = null
        viewModelScope.launch {
            val envoyes = envoyerJustificatifs(projet)
            if (envoyes == 0) return@launch
            archiver(projet)
            _message.value =
                "« ${projet.nom} » : $envoyes justificatif(s) envoyé(s), projet archivé"
        }
    }

    fun annulerArchivage() {
        _archivageEnAttente.value = null
    }

    fun archiverProjet(projet: Projet) {
        _archivageEnAttente.value = null
        viewModelScope.launch { archiver(projet) }
    }

    private suspend fun archiver(projet: Projet) {
        dao.archiverProjet(projet.id, System.currentTimeMillis())
        if (_projet.value?.id == projet.id) _projet.value = null
        _message.value = "« ${projet.nom} » archivé (voir les projets archivés)"
    }

    fun desarchiverProjet(projet: Projet) {
        viewModelScope.launch {
            dao.desarchiverProjet(projet.id)
            _message.value = "« ${projet.nom} » de retour dans l'accueil"
        }
    }

    /** Duplicates the project's name and settings, without its expenses. */
    fun dupliquerProjet(projet: Projet) {
        viewModelScope.launch {
            val id = dao.ajouterProjet(
                Projet(nom = "${projet.nom} (copie)", profilId = projet.profilId),
            )
            _projet.value = dao.projet(id)
            _message.value = "Projet dupliqué"
        }
    }

    /**
     * Recadrage : la nouvelle image devient la pièce principale, l'ancienne
     * passe en annexe. Rien n'est écrasé, donc un recadrage raté se rattrape.
     */
    fun recadrerPiece(depense: Depense, nom: String, bitmap: android.graphics.Bitmap) {
        viewModelScope.launch {
            runCatching {
                val fichier = withContext(Dispatchers.IO) {
                    val enregistre = enregistrerBitmap(getApplication(), bitmap)
                    enregistre.name
                }
                val maj = if (depense.fichier == nom) {
                    val annexes = (depense.fichiersSupplementaires.split('|').map { it.trim() }
                        .filter { it.isNotBlank() } + nom).joinToString("|")
                    depense.copy(fichier = fichier, fichiersSupplementaires = annexes)
                } else {
                    val annexes = depense.fichiersSupplementaires.split('|').map { it.trim() }
                        .filter { it.isNotBlank() }
                        .map { if (it == nom) fichier else it }
                        .joinToString("|")
                    depense.copy(fichiersSupplementaires = annexes)
                }
                dao.majDepense(maj)
                _edition.value = maj
                _message.value = "Pièce recadrée"
            }.onFailure {
                Diag.record("recadrage", it)
                _message.value = "Recadrage impossible : ${it.message ?: "erreur"}"
            }
        }
    }

    /** Adds a second attachment (invoice, back of the receipt) to an expense. */
    fun ajouterPieceJointe(depense: Depense, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val fichier = withContext(Dispatchers.IO) { ReceiptOcr.stocker(getApplication(), uri) }
                // Même recadrage que la pièce principale : une photo de facture
                // posée sur un bureau ne garde que la facture.
                withContext(Dispatchers.IO) {
                    runCatching {
                        ReceiptOcr.lire(
                            getApplication(),
                            fichier,
                            recadrage = _parametres.value.recadrageAuto,
                        )
                    }.onFailure { Diag.record("piece-jointe/recadrage", it) }
                }
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
            // Les blocs de texte lus par l'OCR servent aussi à ne garder que le
            // ticket : le fond de la photo (bureau, main, table) est coupé.
            val lecture = runCatching {
                ReceiptOcr.lire(
                    getApplication(),
                    fichier,
                    recadrage = _parametres.value.recadrageAuto,
                )
            }
                .onFailure { Diag.record("ocr/texte", it) }
                .getOrDefault(ReceiptOcr.Lecture("", recadre = false))
            val texte = lecture.texte
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
                moyenPaiement = moyenPaiementParDefaut(),
                // Gares lues sur un billet de train : pré-remplies, corrigeables
                // dans le formulaire. Vides quand le billet ne les imprime pas.
                gareDepart = champs.gareDepart,
                gareArrivee = champs.gareArrivee,
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
        viewModelScope.launch {
            val p = _parametres.value
            _brouillon.value = Depense(
                projetId = projet.id,
                date = LocalDate.now().toString(),
                montantCentimes = 0L,
                devise = p.deviseDefaut,
                type = TypeDepense.AUTRE.name,
                tvaTauxBp = p.tvaDefautBp.takeIf { it > 0 },
                moyenPaiement = moyenPaiementParDefaut(),
            )
        }
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

    /**
     * Déplace une dépense vers un autre projet : la pièce jointe et tout le
     * détail suivent, seuls le rattachement et les contrôles de doublons
     * changent. Refusé si le projet d'arrivée est clôturé (lecture seule).
     */
    fun deplacerDepense(depense: Depense, cible: Projet) {
        if (cible.cloture) {
            _message.value = "Projet clôturé : choisis un projet ouvert"
            return
        }
        if (cible.id == depense.projetId) {
            _message.value = "Dépense déjà dans « ${cible.nom} »"
            return
        }
        viewModelScope.launch {
            val deplacee = depense.copy(projetId = cible.id)
            dao.majDepense(deplacee)
            _edition.value = null
            _message.value = "Dépense déplacée vers « ${cible.nom} »"
        }
    }

    fun fermerEditeur() {
        _edition.value = null
        _brouillon.value = null
    }

    /**
     * Supprimer une dépense la jette à la corbeille : la ligne et sa pièce
     * jointe restent sur le téléphone, elle sort seulement des totaux et de
     * l'export. La suppression définitive se fait depuis la corbeille.
     */
    fun supprimerDepense(depense: Depense) {
        viewModelScope.launch {
            dao.mettreACorbeille(depense.id, System.currentTimeMillis())
            _edition.value = null
            _message.value = "Dépense mise à la corbeille"
        }
    }

    fun restaurerDepense(depense: Depense) {
        viewModelScope.launch {
            dao.retirerDeCorbeille(depense.id)
            _message.value = "Dépense restaurée"
        }
    }

    /** Efface pour de bon une dépense de la corbeille, et ses pièces jointes. */
    fun supprimerDefinitivement(depense: Depense) {
        viewModelScope.launch {
            val fichiers = depense.tousLesFichiers
            dao.supprimerDepense(depense.id)
            withContext(Dispatchers.IO) { effacerFichiersInutiles(fichiers) }
            _message.value = "Dépense supprimée définitivement"
        }
    }

    fun viderCorbeille() {
        val projet = _projet.value ?: return
        viewModelScope.launch {
            val jetees = withContext(Dispatchers.IO) { dao.corbeilleSync(projet.id) }
            jetees.forEach { dao.supprimerDepense(it.id) }
            withContext(Dispatchers.IO) { effacerFichiersInutiles(jetees.flatMap { it.tousLesFichiers }) }
            _message.value = when (jetees.size) {
                0 -> "Corbeille déjà vide"
                1 -> "1 dépense effacée définitivement"
                else -> "${jetees.size} dépenses effacées définitivement"
            }
        }
    }

    /**
     * Efface les fichiers qu'aucune dépense ne référence plus : une même pièce
     * peut être partagée (dépense dupliquée, annexe), elle ne doit pas
     * disparaître sous les pieds de celle qui reste.
     */
    private suspend fun effacerFichiersInutiles(noms: List<String>) {
        val dossier = ExportManager.dossierJustificatifs(getApplication())
        noms.distinct().forEach { nom ->
            if (nom.isBlank()) return@forEach
            val restantes = runCatching { dao.compterUtilisations(nom) }.getOrDefault(1)
            if (restantes == 0) File(dossier, nom).delete()
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
                // Le profil du projet porte la société, le SIRET et le destinataire.
                val profil = projet.profilId.takeIf { it > 0L }?.let { profilsDao.profil(it) }
                val fichier = ExportManager.exporter(
                    getApplication(), projet, liste, _parametres.value, profil,
                )
                _emailExport.value = profil?.emailDestinataire?.takeIf { it.isNotBlank() }
                    ?: _parametres.value.emailDestinataire
                _projetExporte.value = projet
                _listeExportee.value = liste
                _profilExporte.value = profil
                if (fichier.length() > ExportManager.SEUIL_DECOUPAGE) {
                    // Trop lourd pour un mail : on demande avant de partager.
                    _exportTrop.value = fichier
                    _message.value = "ZIP de ${tailleLisible(fichier.length())} : le découper en 2 ?"
                } else {
                    _export.value = fichier
                    _message.value = "ZIP prêt : ${liste.size} justificatif(s)"
                }
            } catch (e: Exception) {
                Diag.record("export", e)
                _message.value = "Export impossible : ${e.message ?: "erreur"}"
            } finally {
                _traitement.value = false
            }
        }
    }

    /**
     * Envoie les justificatifs seuls, en PDF, à l'adresse email du profil du
     * projet : ni ZIP, ni classeur Excel. C'est ce qu'attend la personne qui
     * reçoit les pièces pour les classer. Au-delà de la limite d'un mail, les
     * pièces partent en plusieurs mails, proposés l'un après l'autre.
     */
    fun envoyerJustificatifsPdf() {
        val projet = _projet.value ?: return
        viewModelScope.launch { envoyerJustificatifs(projet) }
    }

    /**
     * Prépare les PDF du projet et lance le premier envoi. Renvoie le nombre de
     * fichiers remis à la messagerie, 0 si rien n'est parti.
     */
    private suspend fun envoyerJustificatifs(projet: Projet): Int {
        var envoyes = 0
        try {
            _traitement.value = true
            val liste = withContext(Dispatchers.IO) { dao.depensesSync(projet.id) }
            if (liste.isEmpty()) {
                _message.value = "Aucune dépense à envoyer"
                return 0
            }
            val profil = projet.profilId.takeIf { it > 0L }?.let { profilsDao.profil(it) }
            val fichiers = ExportManager.justificatifsPdf(
                getApplication(), projet, liste, _parametres.value, profil,
            )
            if (fichiers.isEmpty()) {
                _message.value = "Aucune pièce jointe à envoyer"
                return 0
            }
            _emailExport.value = profil?.emailDestinataire?.takeIf { it.isNotBlank() }
                ?: _parametres.value.emailDestinataire
            val sujet = "Justificatifs · ${projet.nom} · ${_parametres.value.mention}"
            lancerEnvois(
                ExportManager.repartirEnEnvois(fichiers).map { lot ->
                    Envoi(lot, "application/pdf", sujet, 0, 0)
                },
                "PDF",
            )
            // La date d'envoi est retenue : archiver ne reproposera plus l'envoi.
            val maintenant = System.currentTimeMillis()
            withContext(Dispatchers.IO) { dao.marquerJustificatifsEnvoyes(projet.id, maintenant) }
            _projet.value?.let { ouvert ->
                if (ouvert.id == projet.id) _projet.value = ouvert.copy(justificatifsEnvoyesLe = maintenant)
            }
            envoyes = fichiers.size
        } catch (e: Exception) {
            Diag.record("export/pdf", e)
            _message.value = "Envoi des PDF impossible : ${e.message ?: "erreur"}"
        } finally {
            _traitement.value = false
        }
        return envoyes
    }

    /**
     * Met les envois en file : le premier part tout de suite, les suivants
     * attendent leur tour. L'app ne peut pas envoyer à la place de la
     * messagerie : chaque mail se valide dans son application.
     */
    private fun lancerEnvois(lots: List<Envoi>, quoi: String) {
        val sujetBase = lots.firstOrNull()?.sujet.orEmpty()
        val plan = lots.mapIndexed { index, lot ->
            val sujet = if (lots.size > 1) "$sujetBase (${index + 1}/${lots.size})" else sujetBase
            lot.copy(sujet = sujet, rang = index + 1, total = lots.size)
        }
        planEnvois = plan
        rangEnvoi = 0
        _envoi.value = plan.firstOrNull()
        _envoiSuivant.value = plan.getOrNull(1)
        val destinataire = _emailExport.value?.takeIf { it.isNotBlank() }
        _message.value = when {
            plan.isEmpty() -> "Rien à envoyer"
            plan.size == 1 && destinataire != null ->
                "${plan[0].fichiers.size} fichier(s) $quoi pour $destinataire"
            plan.size == 1 -> "${plan[0].fichiers.size} fichier(s) $quoi : aucun email dans le profil"
            else -> "$quoi découpé en ${plan.size} mails (max 10 Mo chacun)" +
                if (destinataire != null) " pour $destinataire" else " : aucun email dans le profil"
        }
        if (plan.size == 1) planEnvois = emptyList()
    }

    fun envoiConsomme() {
        _envoi.value = null
    }

    /** Fait partir l'envoi proposé, puis propose le suivant s'il en reste un. */
    fun envoyerEnvoiSuivant() {
        if (rangEnvoi + 1 >= planEnvois.size) {
            arreterEnvois()
            return
        }
        rangEnvoi += 1
        _envoi.value = planEnvois[rangEnvoi]
        _envoiSuivant.value = planEnvois.getOrNull(rangEnvoi + 1)
    }

    /** Interrompt la suite : les envois pas encore partis sont abandonnés. */
    fun arreterEnvois() {
        val restants = (planEnvois.size - rangEnvoi - 1).coerceAtLeast(0)
        planEnvois = emptyList()
        rangEnvoi = 0
        _envoiSuivant.value = null
        if (restants > 0) _message.value = "Envoi arrêté : $restants mail(s) non parti(s)"
    }

    /** Découpe l'export en attente en archives qui tiennent chacune dans un mail. */
    fun decouperExport() {
        val fichier = _exportTrop.value ?: return
        val projet = _projetExporte.value
        val liste = _listeExportee.value
        if (projet == null || liste.isEmpty()) {
            _exportTrop.value = null
            return
        }
        viewModelScope.launch {
            try {
                _traitement.value = true
                _exportTrop.value = null
                val parties = ExportManager.decouper(
                    getApplication(), projet, liste, _parametres.value, _profilExporte.value,
                )
                // L'archive complète est remplacée par ses parties : pas de doublon
                // de 20 Mo qui traîne dans filesDir.
                if (parties.size > 1) fichier.delete()
                if (parties.size == 1) {
                    _export.value = parties[0]
                    _message.value = "Rien à découper : tout tient dans une seule archive"
                    return@launch
                }
                val sujet = "Notes de frais · ${projet.nom} · ${_parametres.value.mention}"
                lancerEnvois(parties.map { Envoi(listOf(it), "application/zip", sujet, 0, 0) }, "ZIP")
            } catch (e: Exception) {
                Diag.record("export/decoupage", e)
                _message.value = "Découpage impossible : ${e.message ?: "erreur"}"
            } finally {
                _traitement.value = false
            }
        }
    }

    /** Garde l'archive telle quelle pour l'envoi. */
    fun envoyerExportTelQuel() {
        val fichier = _exportTrop.value ?: return
        _exportTrop.value = null
        _export.value = fichier
    }

    fun annulerDecoupage() {
        _exportTrop.value?.delete()
        _exportTrop.value = null
        _message.value = "Export annulé"
    }

    private fun tailleLisible(octets: Long): String {
        val mo = octets / (1024.0 * 1024.0)
        return String.format(java.util.Locale.FRANCE, "%.1f Mo", mo)
    }

    fun exportConsomme() {
        _export.value = null
    }

    fun messageLu() {
        _message.value = null
    }

    /**
     * Un partage raté doit se voir : l'app ne peut pas ouvrir la messagerie,
     * mais elle peut dire pourquoi au lieu d'échouer en silence.
     */
    fun signalerPartage(erreur: Throwable) {
        _message.value = "Partage impossible : ${erreur.message ?: erreur.javaClass.simpleName}"
    }

    fun derniereOubliee() {
        _derniere.value = null
    }

    companion object {
        /** Le moyen de paiement qui manquait dans les listes déjà enregistrées. */
        const val MOYEN_TIERS = "Payé par un tiers"

        fun montantLisible(depense: Depense): String {
            val montant = depense.montantCentimes / 100.0
            val nombre = String.format(java.util.Locale.FRANCE, "%.2f", montant)
            return if (depense.devise == "EUR") "$nombre €" else "$nombre ${depense.devise}"
        }
    }
}
