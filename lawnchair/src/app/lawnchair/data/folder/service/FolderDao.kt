package app.lawnchair.data.folder.service

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Relation
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import app.lawnchair.data.folder.FolderInfoEntity
import app.lawnchair.data.folder.FolderItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderInfoEntity)

    // [xperia-port] Insert a brand-new folder and return its auto-generated row id, so callers can
    // stamp folder items with the correct folderId (needed when creating a folder by merging apps).
    @Insert
    suspend fun insertFolderReturningId(folder: FolderInfoEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolderItems(items: List<FolderItemEntity>)

    // [xperia-port] Creates a folder together with its items in one transaction. The caller passes
    // items with a placeholder folderId; we remap them to the newly generated folder id here.
    @Transaction
    suspend fun createFolderWithItems(folder: FolderInfoEntity, items: List<FolderItemEntity>): Long {
        val folderId = insertFolderReturningId(folder).toInt()
        insertFolderItems(items.map { it.copy(folderId = folderId) })
        return folderId.toLong()
    }

    @Query("SELECT * FROM Folders WHERE id = :folderId")
    @Transaction
    suspend fun getFolderWithItems(folderId: Int): FolderWithItems?

    @Query("SELECT * FROM FolderItems WHERE folderId IS NOT :folderId")
    @Transaction
    suspend fun getItems(folderId: Int): List<FolderItemEntity>

    @Query("SELECT * FROM Folders")
    fun getAllFolders(): Flow<List<FolderInfoEntity>>

    @Transaction
    suspend fun insertFolderWithItems(folder: FolderInfoEntity, items: List<FolderItemEntity>) {
        insertFolder(folder)
        insertFolderItems(items)
    }

    @Query("DELETE FROM FolderItems WHERE folderId = :folderId")
    suspend fun deleteFolderItemsByFolderId(folderId: Int)

    @Query(
        value = """
                UPDATE Folders
                SET title = :newTitle, hide = :hide, timestamp = :timestamp
                WHERE id = :folderId
            """,
    )
    suspend fun updateFolderInfo(
        folderId: Int,
        newTitle: String,
        hide: Boolean,
        timestamp: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM Folders WHERE id = :folderId")
    suspend fun deleteFolder(folderId: Int)

    @RawQuery
    suspend fun checkpoint(supportSQLiteQuery: SupportSQLiteQuery): Int
}

data class FolderWithItems(
    @Embedded val folder: FolderInfoEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "folderId",
    )
    val items: List<FolderItemEntity>,
)
