package app.lawnchair.allapps

import android.content.Context
import app.lawnchair.data.folder.service.FolderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// [xperia-port] Bridges the stock Folder popup (com.android.launcher3.folder.Folder) to Lawnchair's
// Room folder store for app-drawer folders. Drawer folders live in Room, not the Launcher model, so
// their edits must never go through ModelWriter: the synthetic FolderInfo id reuses the Room folder id,
// which collides with real workspace item ids and crashes ("checkItemInfo doesn't match original").
object DrawerFolderHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Persists a drawer-folder rename to Room (fires the Room flow, which refreshes the drawer). */
    @JvmStatic
    fun renameFolder(context: Context, folderId: Int, title: String) {
        if (folderId <= 0) return
        val service = FolderService.INSTANCE.get(context.applicationContext)
        scope.launch {
            runCatching { service.renameFolder(folderId, title) }
        }
    }
}
