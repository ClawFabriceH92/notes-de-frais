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
import kotlinx.coroutines.flow.Flow

@Dao
interface NotesDao {

    @Query("SELECT * FROM projets ORDER BY creeLe DESC")
    fun projets(): Flow<List<Projet>>

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
}

@Database(entities = [Projet::class, Depense::class], version = 1, exportSchema = false)
abstract class NotesDb : RoomDatabase() {
    abstract fun dao(): NotesDao

    companion object {
        @Volatile
        private var instance: NotesDb? = null

        fun get(context: Context): NotesDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                NotesDb::class.java,
                "notes-de-frais.db",
            ).build().also { instance = it }
        }
    }
}
