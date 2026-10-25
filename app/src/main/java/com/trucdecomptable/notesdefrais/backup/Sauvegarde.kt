package com.trucdecomptable.notesdefrais.backup

import android.content.Context
import android.net.Uri
import com.trucdecomptable.notesdefrais.BuildConfig
import com.trucdecomptable.notesdefrais.data.NotesDb
import com.trucdecomptable.notesdefrais.data.ParametresStore
import com.trucdecomptable.notesdefrais.data.Profil
import com.trucdecomptable.notesdefrais.export.ExportManager
import com.trucdecomptable.notesdefrais.util.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The app keeps everything on the phone: no cloud, no account. Without a backup
 * an uninstall, a lost phone or a bad restore is a total loss, so a full archive
 * (database + receipts + settings) can be written and read back.
 *
 * It is also written automatically before an in-app update installs, which is
 * exactly the moment a schema migration can go wrong.
 */
object Sauvegarde {

    const val DOSSIER = "sauvegardes"
    private const val NOM_DB = "notes-de-frais.db"
    private const val ENTREE_DB = NOM_DB
    private const val ENTREE_PARAMETRES = "parametres.json"
    private const val ENTREE_META = "meta.json"

    data class Resume(val fichier: File, val projets: Int, val depenses: Int, val justificatifs: Int)

    fun dossier(context: Context): File = File(context.filesDir, DOSSIER).apply { mkdirs() }

    /** Writes a full archive and returns it. [etiquette] names the reason. */
    suspend fun creer(context: Context, etiquette: String): Resume = withContext(Dispatchers.IO) {
        val dao = NotesDb.get(context).dao()
        val projets = dao.tousLesProjets()
        val depenses = dao.toutesLesDepenses()
        val justificatifs = ExportManager.dossierJustificatifs(context).listFiles()?.toList().orEmpty()

        val horodatage = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .format(java.time.LocalDateTime.now())
        val fichier = File(dossier(context), "notes-de-frais-${etiquette}-$horodatage.zip")

        // Fold the write-ahead log back into the main file so the copy is whole.
        runCatching {
            NotesDb.get(context).openHelper.writableDatabase
                .execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
        }.onFailure { Diag.record("sauvegarde/checkpoint", it) }

        ZipOutputStream(fichier.outputStream().buffered()).use { zip ->
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
        }

        Diag.record("sauvegarde", "archive ${fichier.name} (${depenses.size} dépenses, ${justificatifs.size} fichiers)")
        Resume(fichier, projets.size, depenses.size, justificatifs.size)
    }

    data class Resultat(val depenses: Int, val justificatifs: Int, val message: String)

    /**
     * Replaces the database, the receipts and the settings with the archive's.
     * The caller has to reload the UI afterwards: the open database instance is
     * closed here so the files can be swapped.
     */
    suspend fun restaurer(context: Context, uri: Uri): Resultat = withContext(Dispatchers.IO) {
        val temporaire = File(context.cacheDir, "restauration.zip")
        context.contentResolver.openInputStream(uri)?.use { entree ->
            temporaire.outputStream().use { sortie -> entree.copyTo(sortie) }
        } ?: error("Archive illisible")

        val contenu = HashMap<String, ByteArray>()
        ZipInputStream(temporaire.inputStream().buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) contenu[e.name] = zip.readBytes()
                e = zip.nextEntry
            }
        }
        if (!contenu.containsKey(ENTREE_DB)) {
            temporaire.delete()
            error("Cette archive ne contient pas de base Notes de frais")
        }
        val meta = contenu[ENTREE_META]?.toString(Charsets.UTF_8)
        val nbDepenses = meta?.let { runCatching { JSONObject(it).optInt("depenses") }.getOrNull() } ?: 0
        val nbJustificatifs = meta?.let { runCatching { JSONObject(it).optInt("justificatifs") }.getOrNull() } ?: 0

        // Close Room first: replacing an open database file is how corruption
        // starts.
        NotesDb.fermer()

        val base = context.getDatabasePath(NOM_DB)
        base.parentFile?.mkdirs()
        for (suffixe in listOf("-wal", "-shm")) {
            File(base.path + suffixe).takeIf { it.exists() }?.delete()
        }
        contenu.getValue(ENTREE_DB).let { octets ->
            base.outputStream().use { it.write(octets) }
        }
        contenu[ENTREE_DB + "-wal"]?.let { octets -> File(base.path + "-wal").outputStream().use { it.write(octets) } }

        val dossierJustificatifs = ExportManager.dossierJustificatifs(context)
        dossierJustificatifs.listFiles()?.forEach { it.delete() }
        for ((nom, octets) in contenu) {
            if (!nom.startsWith("${ExportManager.DOSSIER_JUSTIFICATIFS}/")) continue
            val cible = File(dossierJustificatifs, nom.substringAfterLast('/'))
            cible.outputStream().use { it.write(octets) }
        }

        contenu[ENTREE_PARAMETRES]?.let { octets ->
            ParametresStore(context).ecrireJson(octets.toString(Charsets.UTF_8))
        }

        temporaire.delete()
        Diag.record("restauration", "archive restaurée ($nbDepenses dépenses, $nbJustificatifs justificatifs)")
        Resultat(
            depenses = nbDepenses,
            justificatifs = nbJustificatifs,
            message = "Sauvegarde restaurée : $nbDepenses dépense(s), $nbJustificatifs justificatif(s). Relance l'app.",
        )
    }

    /** Housekeeping: keep the ten most recent archives. */
    suspend fun purger(context: Context, garder: Int = 10) = withContext(Dispatchers.IO) {
        val archives = dossier(context).listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        archives.drop(garder).forEach { it.delete() }
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
            val connu = existants.firstOrNull {
                it.nom.equals(nom, ignoreCase = true) && it.siret == siret
            }
            if (connu != null) {
                dao.maj(connu.copy(adresse = adresse, emailDestinataire = email))
                maj++
            } else {
                dao.ajouter(Profil(nom = nom, siret = siret, adresse = adresse, emailDestinataire = email))
                ajoutes++
            }
        }
        Diag.record("restauration/parametres", "paramétrage restauré ($ajoutes ajoutée(s), $maj mise(s) à jour)")
        "Paramétrage restauré : $ajoutes société(s) ajoutée(s), $maj mise(s) à jour. Les dépenses sont intactes."
    }
}
