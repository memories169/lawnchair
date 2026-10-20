package app.lawnchair.data.folder.service

import android.content.Context
import android.content.pm.LauncherApps
import android.util.Log
import app.lawnchair.data.AppDatabase
import app.lawnchair.data.Converters
import app.lawnchair.data.folder.FolderInfoEntity
import app.lawnchair.data.toEntity
import com.android.launcher3.AppFilter
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.pm.UserCache
import com.android.launcher3.util.MainThreadInitializedObject
import com.android.launcher3.util.SafeCloseable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class FolderService(val context: Context) : SafeCloseable {

    private val folderDao = AppDatabase.INSTANCE.get(context).folderDao()
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userCache = UserCache.INSTANCE.get(context)
    private val appFilter = AppFilter(context)
    private val converters = Converters()

    fun getFoldersFlow(): Flow<List<FolderInfo>> {
        return folderDao.getAllFolders().map { folderEntities ->
            folderEntities.mapNotNull { folderEntity ->
                getFolderInfo(folderEntity.id, true)
            }
        }
    }

    suspend fun updateFolderWithItems(folderInfoId: Int, title: String, appInfos: List<AppInfo>) = withContext(Dispatchers.IO) {
        folderDao.insertFolderWithItems(
            FolderInfoEntity(id = folderInfoId, title = title),
            appInfos.mapIndexed { index, appInfo ->
                appInfo.toEntity(folderInfoId).copy(rank = index)
            }.toList(),
        )
    }

    suspend fun saveFolderInfo(folderInfo: FolderInfo) = withContext(Dispatchers.IO) {
        folderDao.insertFolder(FolderInfoEntity(title = folderInfo.title.toString()))
    }

    // [xperia-port] Creates a new folder pre-populated with the given apps (ordered by rank),
    // returning the generated folder id. Used when two drawer apps are stacked to form a folder.
    suspend fun createFolderWithItems(title: String, appInfos: List<AppInfo>): Long = withContext(Dispatchers.IO) {
        folderDao.createFolderWithItems(
            FolderInfoEntity(title = title),
            appInfos.mapIndexed { index, appInfo -> appInfo.toEntity(0).copy(rank = index) },
        )
    }

    suspend fun updateFolderInfo(folderInfo: FolderInfo, hide: Boolean = false) = withContext(Dispatchers.IO) {
        folderDao.updateFolderInfo(folderInfo.id, folderInfo.title.toString(), hide)
    }

    // [xperia-port] Persists a folder rename directly to Room by id. Used by the stock drawer-folder
    // popup, which otherwise tries to write the title to the Launcher model (crashes on id collision).
    suspend fun renameFolder(folderId: Int, title: String) = withContext(Dispatchers.IO) {
        folderDao.updateFolderInfo(folderId, title, false)
    }

    // [xperia-port] Removes one app from a drawer folder (drag-out eject). The ejected app stays in the
    // drawer (all apps always appear there). If the folder would drop to a single app, it is dissolved
    // entirely; its remaining app then also appears as a standalone drawer icon.
    suspend fun ejectAppFromFolder(folderId: Int, componentKey: String) = withContext(Dispatchers.IO) {
        val folder = folderDao.getFolderWithItems(folderId) ?: return@withContext
        val remaining = folder.items.sortedBy { it.rank }.filterNot { it.componentKey == componentKey }
        folderDao.deleteFolderItemsByFolderId(folderId)
        if (remaining.size <= 1) {
            folderDao.deleteFolder(folderId)
        } else {
            folderDao.insertFolderItems(remaining.mapIndexed { index, item -> item.copy(rank = index) })
        }
    }

    suspend fun deleteFolderInfo(id: Int) = withContext(Dispatchers.IO) {
        folderDao.deleteFolder(id)
    }

    suspend fun getFolderInfo(folderId: Int, hasId: Boolean = false): FolderInfo? = withContext(Dispatchers.Default) {
        folderDao.getFolderWithItems(folderId)?.let {
            mapToFolderInfo(it, hasId)
        }
    }

    private fun mapToFolderInfo(folderWithItems: FolderWithItems, hasId: Boolean): FolderInfo? {
        return try {
            val domainFolderInfo = FolderInfo().apply {
                // if no id, launcher automatically creates an id for this
                if (hasId) id = folderWithItems.folder.id
                title = folderWithItems.folder.title
            }

            folderWithItems.items.sortedBy { it.rank }.forEach { itemEntity ->
                // Consider caching toItemInfo results if componentKey lookups are slow
                // and items don't change frequently without folder data changing
                toItemInfo(itemEntity.componentKey)?.let { appInfo ->
                    domainFolderInfo.add(appInfo, false)
                }
            }
            domainFolderInfo
        } catch (e: Exception) {
            Log.e("FolderService", "Failed to map FolderWithItems for id: ${folderWithItems.folder.id}", e)
            null
        }
    }

    private fun toItemInfo(componentKey: String?): AppInfo? {
        if (launcherApps != null) {
            return userCache.userProfiles.asSequence()
                .flatMap { launcherApps.getActivityList(null, it) }
                .filter { appFilter.shouldShowApp(it.componentName) }
                .map { AppInfo(context, it, it.user) }
                .filter { converters.fromComponentKey(it.componentKey) == componentKey }
                .firstOrNull()
        }
        return null
    }

    suspend fun getAllFolders(): List<FolderInfo> = withContext(Dispatchers.Main) {
        try {
            val folderEntities = folderDao.getAllFolders().firstOrNull() ?: emptyList()
            folderEntities.mapNotNull { folderEntity ->
                getFolderInfo(folderEntity.id, true)
            }
        } catch (e: Exception) {
            Log.e("FolderService", "Failed to get all folders", e)
            emptyList()
        }
    }

    override fun close() {
        TODO("Not yet implemented")
    }

    companion object {
        @JvmField
        val INSTANCE = MainThreadInitializedObject(::FolderService)
    }
}
