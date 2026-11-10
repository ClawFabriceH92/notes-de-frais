package com.trucdecomptable.notesdefrais.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import com.trucdecomptable.notesdefrais.BuildConfig
import com.trucdecomptable.notesdefrais.data.NotesDb
import com.trucdecomptable.notesdefrais.data.ParametresStore
import com.trucdecomptable.notesdefrais.data.Profil
import com.trucdecomptable.notesdefrais.export.ExportManager
import com.trucdecomptable.notesdefrais.util.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The app keeps everything on the phone: no cloud, no account. Without a backup
 * an uninstall, a lost phone or a bad restore is a total loss, so a full archive
 * (database + receipts + settings) can be written and read back.
 *
 * It is also written automatically before an in-app update installs, which is
 * exactly the moment a schema migration can go wrong.
 *
 * La restauration est la seule opération destructrice de l'application. Ses
 * règles décidables (validation de l'archive, espace, rétention) vivent dans
 * [ReglesRestauration], la lecture en flux dans [ExtractionArchive] et la
 * bascule par renommages dans [EchangeAtomique] : trois fichiers testés sur la JVM.
 */
object Sauvegarde {

    const val DOSSIER = "sauvegardes"

    /** Dossier temporaire de la restauration, sous filesDir (même volume que la base). */
    const val DOSSIER_RESTAURATION = "restauration-tmp"
    private const val NOM_DB = ReglesRestauration.NOM_DB
    private const val ENTREE_DB = NOM_DB
    private const val ENTREE_PARAMETRES = ReglesRestauration.ENTREE_PARAMETRES
    private const val ENTREE_META = ReglesRestauration.ENTREE_META

    /** Une seule restauration à la fois dans le processus ; protège aussi le dossier temporaire. */
    private val restaurationEnCours = AtomicBoolean(false)

    data class Resume(val fichier: File, val projets: Int, val depenses: Int, val justificatifs: Int)

    fun dossier(context: Context): File = File(context.filesDir, DOSSIER).apply { mkdirs() }

    /**
     * Writes a full archive and returns it. [etiquette] names the reason.
     *
     * L'archive est écrite sous un nom .tmp puis renommée : un ZIP incomplet
     * (disque plein, processus tué) n'est jamais pris pour une sauvegarde.
     * meta.json puis la base sont écrites en tête, pour qu'un aperçu de
     * l'archive ne lise que deux en-têtes.
     */
    suspend fun creer(context: Context, etiquette: String): Resume = withContext(Dispatchers.IO) {
        val dao = NotesDb.get(context).dao()
        val projets = dao.tousLesProjets()
        val depenses = dao.toutesLesDepenses()
        val justificatifs = ExportManager.dossierJustificatifs(context).listFiles()?.toList().orEmpty()

        val horodatage = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .format(java.time.LocalDateTime.now())
        val fichier = File(dossier(context), "notes-de-frais-${etiquette}-$horodatage.zip")
        val partiel = File(fichier.path + ".tmp")

        // Fold the write-ahead log back into the main file so the copy is whole.
        runCatching {
            NotesDb.get(context).openHelper.writableDatabase
                .execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
        }.onFailure { Diag.record("sauvegarde/checkpoint", it) }

        try {
            ZipOutputStream(partiel.outputStream().buffered()).use { zip ->
                val meta = JSONObject().apply {
                    put("version", BuildConfig.VERSION_NAME)
                    put("date", java.time.LocalDateTime.now().toString())
                    put("motif", etiquette)
                    put("projets", projets.size)
                    put("depenses", depenses.size)
                    put("justificatifs", justificatifs.size)
                }
                zip.putNextEntry(ZipEntry(ENTREE_META))
                zip.write(meta.toString(2).toByteArray())
                zip.closeEntry()

                val base = context.getDatabasePath(NOM_DB)
                for (suffixe in listOf("", "-wal", "-shm")) {
                    val source = File(base.path + suffixe)
                    if (!source.exists()) continue
                    zip.putNextEntry(ZipEntry(ENTREE_DB + suffixe))
                    source.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                for (justificatif in justificatifs) {
                    zip.putNextEntry(ZipEntry("${ExportManager.DOSSIER_JUSTIFICATIFS}/${justificatif.name}"))
                    justificatif.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                ParametresStore(context).lireJson()?.let { json ->
                    zip.putNextEntry(ZipEntry(ENTREE_PARAMETRES))
                    zip.write(json.toByteArray())
                    zip.closeEntry()
                }
            }
            if (!partiel.renameTo(fichier)) throw IOException("Archive impossible à finaliser : ${fichier.name}")
        } finally {
            if (partiel.exists()) partiel.delete()
        }

        Diag.record("sauvegarde", "archive ${fichier.name} (${depenses.size} dépenses, ${justificatifs.size} fichiers)")
        Resume(fichier, projets.size, depenses.size, justificatifs.size)
    }

    // ------------------------------------------------------------ restauration

    data class Resultat(
        val depenses: Int,
        val justificatifs: Int,
        val message: String,
        val sauvegardeSecurite: File? = null,
    )

    /** Ce que l'utilisateur voit avant de confirmer : l'archive et ce qui sera écrasé. */
    data class ApercuRestauration(
        val meta: MetaArchive?,
        val avertissements: List<String>,
        val actuelProjets: Int,
        val actuelDepenses: Int,
        val actuelJustificatifs: Int,
    ) {
        val lignes: List<String>
            get() = ReglesRestauration.lignesConfirmation(
                meta, avertissements, actuelProjets, actuelDepenses, actuelJustificatifs,
            )
    }

    /**
     * Étape 1 de toute restauration : lire l'archive pour la présenter, sans
     * rien écrire. Lève [RestaurationRefusee] si elle ne peut pas servir.
     */
    suspend fun inspecter(context: Context, uri: Uri): ApercuRestauration = withContext(Dispatchers.IO) {
        val apercu = try {
            context.contentResolver.openInputStream(uri)?.use { flux ->
                ExtractionArchive.apercu(flux) { ensureActive() }
            } ?: throw RestaurationRefusee("Archive illisible.")
        } catch (e: IOException) {
            throw RestaurationRefusee("Ce fichier n'est pas une archive ZIP lisible (${e.message ?: "erreur"}).")
        }
        val decision = ReglesRestauration.decider(apercu.meta, apercu.baseVue, BuildConfig.VERSION_NAME)
        if (decision is Decision.Refusee) throw RestaurationRefusee(decision.raison)
        controlerEspace(context, uri)

        val dao = NotesDb.get(context).dao()
        ApercuRestauration(
            meta = apercu.meta,
            avertissements = (decision as Decision.Autorisee).avertissements,
            actuelProjets = dao.tousLesProjets().size,
            actuelDepenses = dao.toutesLesDepenses().size,
            actuelJustificatifs = ExportManager.dossierJustificatifs(context).listFiles()?.size ?: 0,
        )
    }

    /**
     * Replaces the database, the receipts and the settings with the archive's.
     *
     * Déroulé : (1) espace libre, (2) lecture en flux vers un dossier
     * temporaire, (3) validation de la base de l'archive, (4) sauvegarde de
     * sécurité de l'état courant, (5) fermeture de Room puis bascule par
     * renommages, ancien contenu rangé de côté, (6) suppression de l'ancien
     * contenu seulement après succès complet.
     *
     * Une exception avant l'étape 5 laisse tout intact ([RestaurationRefusee]).
     * Une exception pendant la bascule remet les fichiers d'origine en place
     * puis lève [RestaurationInterrompue] : la base de l'application est fermée,
     * l'appelant doit redémarrer le processus. Dans tous les cas où elle réussit,
     * il doit aussi redémarrer (les DAO ouverts visent l'ancienne base).
     */
    suspend fun restaurer(
        context: Context,
        uri: Uri,
        progression: (String) -> Unit = {},
    ): Resultat = withContext(Dispatchers.IO) {
        if (!restaurationEnCours.compareAndSet(false, true)) {
            throw RestaurationRefusee("Une restauration est déjà en cours.")
        }
        val racine = File(context.filesDir, DOSSIER_RESTAURATION)
        var conserverRacine = false
        try {
            // Un reste de restauration précédente : on le défait, ou on refuse s'il garde des données.
            EchangeAtomique.recuperer(racine)?.let { Diag.record("restauration/reprise", it) }
            if (racine.exists()) {
                throw RestaurationRefusee(
                    "Une restauration précédente a laissé des fichiers à ${racine.path}. " +
                        "Rien n'a été modifié : contactez le support avant de recommencer.",
                )
            }

            progression("Vérification de l'espace libre…")
            val libre = controlerEspace(context, uri)
            val etatCourant = tailleEtatCourant(context)

            val nouveau = File(racine, EchangeAtomique.NOUVEAU)
            if (!nouveau.mkdirs()) throw RestaurationRefusee("Dossier temporaire impossible à créer. Rien n'a été modifié.")

            progression("Lecture de l'archive…")
            val extrait = try {
                context.contentResolver.openInputStream(uri)?.use { flux ->
                    ExtractionArchive.extraire(
                        flux = flux,
                        destination = nouveau,
                        budget = ReglesRestauration.budgetExtraction(libre, etatCourant),
                        annule = { ensureActive() },
                    )
                } ?: throw RestaurationRefusee("Archive illisible.")
            } catch (e: IOException) {
                throw RestaurationRefusee(
                    "Lecture de l'archive impossible (${e.message ?: "erreur"}), probablement un ZIP corrompu " +
                        "ou un disque plein. Rien n'a été modifié.",
                )
            }
            val decision = ReglesRestauration.decider(extrait.meta, extrait.baseVue, BuildConfig.VERSION_NAME)
            if (decision is Decision.Refusee) throw RestaurationRefusee(decision.raison)
            val baseTemporaire = extrait.base
                ?: throw RestaurationRefusee("Cette archive ne contient pas de base Notes de frais.")

            progression("Contrôle de la base de l'archive…")
            val contenu = controlerBase(context, baseTemporaire)
            extrait.parametres?.let { fichier ->
                runCatching { JSONObject(fichier.readText(Charsets.UTF_8)) }.getOrElse {
                    throw RestaurationRefusee("Les réglages de l'archive sont illisibles. Rien n'a été modifié.")
                }
            }
            // Le fichier -shm que le contrôle a pu créer n'a rien à faire dans l'état final.
            File(baseTemporaire.path + "-shm").delete()

            progression("Sauvegarde de sécurité de l'état actuel…")
            val securite = try {
                creer(context, ReglesRestauration.MOTIF_AVANT_RESTAURATION)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Diag.record("restauration/securite", e)
                throw RestaurationRefusee(
                    "La sauvegarde de sécurité de l'état actuel n'a pas pu être écrite (${e.message ?: "erreur"}). " +
                        "Restauration annulée, rien n'a été modifié.",
                )
            }
            verifierArchiveEcrite(securite.fichier)

            progression("Remplacement des données…")
            conserverRacine = true
            // Plus d'annulation possible à partir d'ici : une coroutine annulée à mi-bascule laisserait un état mixte.
            withContext(NonCancellable) { basculer(context, racine, nouveau, extrait) }
            conserverRacine = false

            val message = "Sauvegarde restaurée : ${contenu.depenses} dépense(s), ${contenu.projets} projet(s), " +
                "${extrait.justificatifs} justificatif(s). L'application va redémarrer pour charger les nouvelles données. " +
                "Votre état précédent est conservé dans la sauvegarde ${securite.fichier.name}."
            Diag.record(
                "restauration",
                "archive restaurée (${contenu.depenses} dépenses, ${extrait.justificatifs} justificatifs), " +
                    "sécurité : ${securite.fichier.name}",
            )
            runCatching { purger(context, garder = Int.MAX_VALUE) }
            Resultat(contenu.depenses, extrait.justificatifs, message, securite.fichier)
        } finally {
            if (!conserverRacine) racine.deleteRecursively()
            restaurationEnCours.set(false)
        }
    }

    /**
     * La bascule proprement dite. Tout est déjà validé dans [nouveau]. Ferme
     * Room, échange par renommages (ancien contenu rangé dans [racine]), écrit
     * les réglages, puis valide le journal. Le dossier [racine], et donc
     * l'ancien contenu, n'est supprimé par l'appelant qu'une fois tout réussi.
     */
    private fun basculer(context: Context, racine: File, nouveau: File, extrait: ExtractionArchive.Extrait) {
        val base = context.getDatabasePath(NOM_DB)
        base.parentFile?.mkdirs()
        val dossierPieces = ExportManager.dossierJustificatifs(context)
        val store = ParametresStore(context)
        val anciensParametres = store.lireJson()
        var parametresEcrits = false
        val echange = EchangeAtomique(racine)

        try {
            // Fermer Room d'abord : remplacer un fichier de base ouvert est la source des corruptions.
            NotesDb.fermer()
            echange.echanger(
                listOf(
                    EchangeAtomique.Remplacement(base, File(nouveau, "${ExtractionArchive.SOUS_DOSSIER_BASE}/$NOM_DB")),
                    EchangeAtomique.Remplacement(
                        File(base.path + "-wal"),
                        File(nouveau, "${ExtractionArchive.SOUS_DOSSIER_BASE}/$NOM_DB-wal").takeIf { it.exists() },
                    ),
                    EchangeAtomique.Remplacement(File(base.path + "-shm"), null),
                    EchangeAtomique.Remplacement(dossierPieces, File(nouveau, ExtractionArchive.SOUS_DOSSIER_JUSTIFICATIFS)),
                ),
            )
            extrait.parametres?.let { fichier ->
                if (!store.ecrireJsonSynchrone(fichier.readText(Charsets.UTF_8))) {
                    throw IOException("Réglages impossibles à écrire")
                }
                parametresEcrits = true
            }
            echange.valider()
        } catch (e: Throwable) {
            Diag.record("restauration/bascule", e)
            if (parametresEcrits && anciensParametres != null) store.ecrireJsonSynchrone(anciensParametres)
            val echecs = echange.annuler()
            val suite = if (echecs.isEmpty()) {
                // Tout est revenu en place : le dossier temporaire ne contient plus que du jetable.
                racine.deleteRecursively()
                "Vos données d'origine ont été remises en place."
            } else {
                "ATTENTION : ${echecs.size} élément(s) n'ont pas pu être remis en place, ils sont dans ${racine.path}."
            }
            throw RestaurationInterrompue(
                "La restauration a échoué pendant le remplacement des fichiers (${e.message ?: "erreur"}). $suite " +
                    "L'application doit redémarrer.",
                e,
            )
        }
    }

    /** Espace libre sous filesDir ; refuse s'il est insuffisant. Retourne l'espace libre. */
    private fun controlerEspace(context: Context, uri: Uri): Long {
        val libre = StatFs(context.filesDir.path).availableBytes
        val necessaire = ReglesRestauration.espaceNecessaire(tailleArchive(context, uri), tailleEtatCourant(context))
        if (libre < necessaire) {
            throw RestaurationRefusee(ReglesRestauration.messageEspaceInsuffisant(libre, necessaire))
        }
        return libre
    }

    /** Taille de l'archive d'après le fournisseur du fichier, ou -1 si elle est inconnue. */
    private fun tailleArchive(context: Context, uri: Uri): Long {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 }?.let { return it }
            }
        }
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { d ->
                if (d.length > 0) return d.length
            }
        }
        return -1
    }

    /** Octets que la sauvegarde de sécurité va recopier : base, journaux et justificatifs. */
    private fun tailleEtatCourant(context: Context): Long {
        val base = context.getDatabasePath(NOM_DB)
        return listOf("", "-wal", "-shm").sumOf { ExtractionArchive.taille(File(base.path + it)) } +
            ExtractionArchive.taille(ExportManager.dossierJustificatifs(context))
    }

    private data class ContenuBase(val version: Int, val projets: Int, val depenses: Int)

    /**
     * Valide la base extraite AVANT la bascule : en-tête SQLite, user_version
     * (jamais au-dessus de celle de l'application), quick_check, tables
     * attendues. Ouverture en lecture seule, hors du chemin final.
     */
    private fun controlerBase(context: Context, base: File): ContenuBase {
        if (!ReglesRestauration.enteteSqliteValide(ExtractionArchive.lireEntete(base), base.length())) {
            throw RestaurationRefusee("La base de l'archive n'est pas un fichier SQLite valide. Rien n'a été modifié.")
        }
        val (versionApp, identiteApp) = lireVersionEtIdentite(context)
        val db = try {
            SQLiteDatabase.openDatabase(base.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: SQLiteException) {
            throw RestaurationRefusee("La base de l'archive ne peut pas être ouverte (${e.message ?: "erreur"}). Rien n'a été modifié.")
        }
        try {
            val version = db.rawQuery("PRAGMA user_version", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            val quickCheck = db.rawQuery("PRAGMA quick_check", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0).orEmpty()) }
            }
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0).orEmpty()) }
            }
            val identiteArchive = if (tables.contains("room_master_table")) {
                runCatching {
                    db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42", null).use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }.getOrNull()
            } else {
                null
            }
            ReglesRestauration.verifierBase(version, versionApp, quickCheck, tables, identiteArchive, identiteApp)
                ?.let { throw RestaurationRefusee("$it Rien n'a été modifié.") }
            val projets = db.rawQuery("SELECT COUNT(*) FROM projets", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            val depenses = db.rawQuery("SELECT COUNT(*) FROM depenses", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            return ContenuBase(version, projets, depenses)
        } catch (e: SQLiteException) {
            throw RestaurationRefusee("La base de l'archive est illisible (${e.message ?: "erreur"}). Rien n'a été modifié.")
        } finally {
            db.close()
        }
    }

    /** Version et empreinte de schéma de la base de l'application (Room ouverte, à jour). */
    private fun lireVersionEtIdentite(context: Context): Pair<Int, String?> {
        val vivante = NotesDb.get(context).openHelper.readableDatabase
        val identite = runCatching {
            vivante.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        return vivante.version to identite
    }

    /** Relit la sauvegarde de sécurité : une archive illisible n'est pas un filet. */
    private fun verifierArchiveEcrite(fichier: File) {
        val saine = runCatching {
            ZipFile(fichier).use { it.getEntry(ENTREE_DB) != null && it.getEntry(ENTREE_META) != null }
        }.getOrDefault(false)
        if (!saine || fichier.length() <= 0) {
            throw RestaurationRefusee(
                "La sauvegarde de sécurité de l'état actuel est illisible après écriture. " +
                    "Restauration annulée, rien n'a été modifié.",
            )
        }
    }

    /**
     * Au démarrage, avant toute ouverture de la base : défait une restauration
     * que la mort du processus aurait interrompue, ou nettoie ses restes. Sans
     * effet pendant une restauration en cours dans ce processus (rotation de l'écran).
     */
    fun recupererApresInterruption(context: Context) {
        if (restaurationEnCours.get()) return
        val racine = File(context.filesDir, DOSSIER_RESTAURATION)
        EchangeAtomique.recuperer(racine)?.let { Diag.record("restauration/reprise", it) }
    }

    /**
     * Housekeeping: keep the ten most recent archives. Les archives « avant
     * restauration » ont leur propre quota (voir [ReglesRestauration.aSupprimer]).
     */
    suspend fun purger(
        context: Context,
        garder: Int = ReglesRestauration.GARDER_GENERALES,
    ) = withContext(Dispatchers.IO) {
        val fichiers = dossier(context).listFiles()?.filter { it.isFile }.orEmpty()
        val noms = ReglesRestauration.aSupprimer(
            fichiers = fichiers.map { ReglesRestauration.ArchiveFichier(it.name, it.lastModified()) },
            maintenant = System.currentTimeMillis(),
            garderGenerales = garder,
        ).toSet()
        fichiers.filter { it.name in noms }.forEach { it.delete() }
    }

    // -------------------------------------------------- paramétrage seul

    /**
     * Le paramétrage et les sociétés, sans les dépenses : un petit JSON qu'on
     * peut ranger dans un mail ou sur Drive, et remettre en place sur un autre
     * téléphone sans écraser les notes de frais déjà saisies.
     */
    suspend fun creerParametres(context: Context): File = withContext(Dispatchers.IO) {
        val store = ParametresStore(context)
        val profils = NotesDb.get(context).profilsDao().tousLesProfils()
        val racine = JSONObject().apply {
            put("application", "notes-de-frais")
            put("version", BuildConfig.VERSION_NAME)
            put("date", java.time.LocalDateTime.now().toString())
            put(
                "parametres",
                store.lireJson()?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?: store.lire().toJson(),
            )
            put("profils", JSONArray().apply {
                profils.forEach { profil ->
                    put(
                        JSONObject().apply {
                            put("nom", profil.nom)
                            put("siret", profil.siret)
                            put("adresse", profil.adresse)
                            put("emailDestinataire", profil.emailDestinataire)
                            put("moyenPaiementDefaut", profil.moyenPaiementDefaut)
                            put("moyensPaiement", profil.moyensPaiement)
                        },
                    )
                }
            })
        }
        val horodatage = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .format(java.time.LocalDateTime.now())
        val fichier = File(dossier(context), "parametrage-$horodatage.json")
        fichier.writeText(racine.toString(2))
        Diag.record("sauvegarde/parametres", "paramétrage seul : ${fichier.name} (${profils.size} société(s))")
        fichier
    }

    /**
     * Relit un fichier de paramétrage. Les sociétés sont rapprochées par nom et
     * SIRET : une société déjà présente est mise à jour, les autres sont
     * ajoutées, et celles qui ne sont pas dans le fichier restent en place.
     * Aucune dépense, aucun projet n'est touché.
     */
    suspend fun restaurerParametres(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val brut = context.contentResolver.openInputStream(uri)?.use { flux ->
            flux.readBytes().toString(Charsets.UTF_8)
        } ?: error("Fichier illisible")
        val racine = runCatching { JSONObject(brut) }.getOrElse { error("Ce fichier n'est pas un paramétrage Notes de frais") }

        val parametres = racine.optJSONObject("parametres")
            ?: error("Le fichier ne contient pas de paramétrage")
        ParametresStore(context).ecrireJson(parametres.toString())

        val dao = NotesDb.get(context).profilsDao()
        val existants = dao.tousLesProfils()
        var ajoutes = 0
        var maj = 0
        val profils = racine.optJSONArray("profils") ?: JSONArray()
        for (index in 0 until profils.length()) {
            val json = profils.optJSONObject(index) ?: continue
            val nom = json.optString("nom").trim()
            if (nom.isEmpty()) continue
            val siret = json.optString("siret").trim()
            val adresse = json.optString("adresse")
            val email = json.optString("emailDestinataire")
            val moyen = json.optString("moyenPaiementDefaut")
            val moyens = json.optString("moyensPaiement")
            val connu = existants.firstOrNull {
                it.nom.equals(nom, ignoreCase = true) && it.siret == siret
            }
            if (connu != null) {
                dao.maj(
                    connu.copy(
                        adresse = adresse,
                        emailDestinataire = email,
                        moyenPaiementDefaut = moyen,
                        moyensPaiement = moyens,
                    ),
                )
                maj++
            } else {
                dao.ajouter(
                    Profil(
                        nom = nom,
                        siret = siret,
                        adresse = adresse,
                        emailDestinataire = email,
                        moyenPaiementDefaut = moyen,
                        moyensPaiement = moyens,
                    ),
                )
                ajoutes++
            }
        }
        Diag.record("restauration/parametres", "paramétrage restauré ($ajoutes ajoutée(s), $maj mise(s) à jour)")
        "Paramétrage restauré : $ajoutes société(s) ajoutée(s), $maj mise(s) à jour. Les dépenses sont intactes."
    }
}
