package com.trucdecomptable.notesdefrais.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trucdecomptable.notesdefrais.data.Depense
import com.trucdecomptable.notesdefrais.data.NotesDb
import com.trucdecomptable.notesdefrais.data.Projet
import com.trucdecomptable.notesdefrais.data.TauxBdf
import com.trucdecomptable.notesdefrais.data.TauxBdfClient
import com.trucdecomptable.notesdefrais.data.TypeDepense
import com.trucdecomptable.notesdefrais.export.ExportManager
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

    init {
        // Rates come from trucdecomptable.fr (Banque de France series). A phone
        // that is offline keeps the last downloaded copy.
        viewModelScope.launch { tauxClient.rafraichir() }
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

    // ------------------------------------------------------------------ projets

    fun creerProjet(nom: String) {
        val propre = nom.trim()
        if (propre.isEmpty()) return
        viewModelScope.launch {
            val id = dao.ajouterProjet(Projet(nom = propre))
            _projet.value = dao.projet(id)
        }
    }

    fun ouvrirProjet(projet: Projet) {
        _projet.value = projet
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
        val projet = _projet.value
        if (projet == null) {
            _message.value = "Ouvre d'abord un projet"
            return
        }
        viewModelScope.launch {
            _traitement.value = true
            try {
                val fichier = withContext(Dispatchers.IO) { ReceiptOcr.stocker(getApplication(), uri) }
                val texte = runCatching { ReceiptOcr.texte(getApplication(), fichier) }
                    .onFailure { Diag.record("ocr/texte", it) }
                    .getOrDefault("")
                val champs = ReceiptParser.parse(texte)
                Diag.record(
                    "ocr/resultat",
                    "date=${champs.date} montant=${champs.montantCentimes} type=${champs.type} tva=${champs.tvaTauxBp}",
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
                )

                if (champs.complet) {
                    val id = dao.ajouterDepense(base)
                    _derniere.value = base.copy(id = id)
                    _message.value = "Enregistré · ${montantLisible(base)} · ${base.typeDepense.label}"
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
    }

    fun ajouterManuellement() {
        val projet = _projet.value ?: return
        _brouillon.value = Depense(
            projetId = projet.id,
            date = LocalDate.now().toString(),
            montantCentimes = 0L,
            type = TypeDepense.AUTRE.name,
        )
    }

    fun validerBrouillon(depense: Depense) {
        viewModelScope.launch {
            if (depense.id == 0L) {
                val id = dao.ajouterDepense(depense)
                _derniere.value = depense.copy(id = id)
                _message.value = "Dépense ajoutée"
            } else {
                dao.majDepense(depense)
                _message.value = "Dépense modifiée"
            }
            _brouillon.value = null
            _edition.value = null
        }
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
                val fichier = ExportManager.exporter(getApplication(), projet, liste)
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
