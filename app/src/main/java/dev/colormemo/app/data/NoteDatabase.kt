package dev.colormemo.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notes")
data class NoteEntity(@PrimaryKey val id: String, val document: String)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes")
    fun getAll(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun find(id: String): NoteEntity?

    @Upsert
    fun upsert(notes: List<NoteEntity>)
}

@Database(entities = [NoteEntity::class], version = 1, exportSchema = true)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao

    companion object {
        fun open(context: Context): NoteDatabase = Room.databaseBuilder(
            context.applicationContext,
            NoteDatabase::class.java,
            "colormemo.db",
        ).build()
    }
}
