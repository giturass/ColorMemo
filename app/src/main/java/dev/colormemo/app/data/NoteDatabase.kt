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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notes")
data class NoteEntity(@PrimaryKey val id: String, val document: String)

/** Device-local grants. Never serialized into notes, sync documents or backups. */
@Entity(tableName = "ai_note_grants")
data class AiNoteGrant(@PrimaryKey val noteId: String)

@Dao
interface AiNoteGrantDao {
    @Query("SELECT noteId FROM ai_note_grants")
    fun observeIds(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM ai_note_grants WHERE noteId = :id)")
    fun isGranted(id: String): Boolean

    @Query("SELECT notes.* FROM notes INNER JOIN ai_note_grants ON notes.id = ai_note_grants.noteId")
    fun grantedNotes(): List<NoteEntity>

    @Upsert
    fun grant(value: AiNoteGrant)

    @Query("DELETE FROM ai_note_grants WHERE noteId = :id")
    fun revoke(id: String)
}

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

@Database(entities = [NoteEntity::class, AiNoteGrant::class], version = 2, exportSchema = true)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun aiGrants(): AiNoteGrantDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `ai_note_grants` (`noteId` TEXT NOT NULL, PRIMARY KEY(`noteId`))")
            }
        }

        fun open(context: Context): NoteDatabase = Room.databaseBuilder(
            context.applicationContext,
            NoteDatabase::class.java,
            "colormemo.db",
        ).addMigrations(MIGRATION_1_2).build()
    }
}
