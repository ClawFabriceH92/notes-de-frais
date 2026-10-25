package com.trucdecomptable.notesdefrais.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface NotesDao {

    @Query("SELECT * FROM projets ORDER BY creeLe DESC")
    fun projets(): Flow<List<Projet>>

    /** 0 = pas de filtre : tous les projets, comme avant les profils. */
    @Query("SELECT * FROM projets WHERE profilId = :profilId OR :profilId = 0 ORDER BY creeLe DESC")
    fun projetsDuProfil(profilId: Long): Flow<List<Projet>>

    @Query("SELECT * FROM projets ORDER BY creeLe DESC")
    suspend fun tousLesProjets(): List<Projet>

    @Insert
    suspend fun ajouterProjet(projet: Projet): Long

    @Update
    suspend fun majProjet(projet: Projet)

    @Query("SELECT * FROM projets WHERE id = :id")
    suspend fun projet(id: Long): Projet?

    @Query("DELETE FROM projets WHERE id = :id")
    suspend fun supprimerProjet(id: Long)

    @Query("SELECT * FROM depenses WHERE projetId = :projetId ORDER BY date DESC, creeLe DESC")
    fun depenses(projetId: Long): Flow<List<Depense>>

    @Query("SELECT * FROM depenses WHERE projetId = :projetId ORDER BY date ASC, creeLe ASC")
    suspend fun depensesSync(projetId: Long): List<Depense>

    @Query("SELECT * FROM depenses ORDER BY date ASC")
    suspend fun toutesLesDepenses(): List<Depense>

    @Insert
    suspend fun ajouterDepense(depense: Depense): Long

    @Update
    suspend fun majDepense(depense: Depense)

    @Query("SELECT * FROM depenses WHERE id = :id")
    suspend fun depense(id: Long): Depense?

    @Query("DELETE FROM depenses WHERE id = :id")
    suspend fun supprimerDepense(id: Long)

    @Query("DELETE FROM depenses WHERE projetId = :id")
    suspend fun supprimerDepensesDe(id: Long)

    @Query("SELECT COUNT(*) FROM depenses WHERE fichier = :fichier")
    suspend fun compterFichier(fichier: String): Int

    /** Fills the payment method of the expenses recorded before the feature. */
    @Query("UPDATE depenses SET moyenPaiement = :moyen WHERE moyenPaiement = ''")
    suspend fun remplirMoyenPaiement(moyen: String): Int
}

@Database(entities = [Projet::class, Depense::class, Profil::class], version = 7, exportSchema = false)
abstract class NotesDb : RoomDatabase() {
    abstract fun dao(): NotesDao
    abstract fun profilsDao(): ProfilsDao

    companion object {
        @Volatile
        private var instance: NotesDb? = null

        /**
         * Real migration rather than a destructive one: a new version of the app
         * must never wipe the expenses already recorded. Columns are added with
         * defaults, so earlier rows stay valid.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE depenses ADD COLUMN distanceKm INTEGER")
                db.execSQL("ALTER TABLE depenses ADD COLUMN trajet TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE depenses ADD COLUMN indemniteCentimes INTEGER")
                db.execSQL("ALTER TABLE depenses ADD COLUMN fraisAnnexesCentimes INTEGER")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE depenses ADD COLUMN refacturable INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE depenses ADD COLUMN fichiersSupplementaires TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE projets ADD COLUMN cloture INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE projets ADD COLUMN clotureLe INTEGER")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE depenses ADD COLUMN moyenPaiement TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE depenses ADD COLUMN sensTrajet TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE depenses ADD COLUMN participants TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE depenses ADD COLUMN societeParticipant TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE depenses ADD COLUMN motifRepas TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE projets ADD COLUMN profilId INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS profils (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "nom TEXT NOT NULL, " +
                        "siret TEXT NOT NULL DEFAULT '', " +
                        "adresse TEXT NOT NULL DEFAULT '', " +
                        "emailDestinataire TEXT NOT NULL DEFAULT '', " +
                        "creeLe INTEGER NOT NULL)",
                )
            }
        }

        /**
         * Les repas se séparent en « midi seul » et « midi avec invité » : les
         * dépenses déjà saisies deviennent « midi seul », ce qui est le cas le
         * plus fréquent et ne change pas leur montant.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE depenses SET type = 'RESTAURANT_MIDI_SEUL' WHERE type = 'RESTAURANT_MIDI'")
            }
        }

        /** Chaque société peut avoir son propre moyen de paiement par défaut. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profils ADD COLUMN moyenPaiementDefaut TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): NotesDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                NotesDb::class.java,
                "notes-de-frais.db",
            ).addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
            ).build().also { instance = it }
        }

        /** Closes the database so a restore can replace the file on disk. */
        @Synchronized
        fun fermer() {
            instance?.close()
            instance = null
        }
    }
}
