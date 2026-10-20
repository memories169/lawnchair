package app.lawnchair.allapps

import android.content.Context
import app.lawnchair.data.folder.service.FolderService
import com.android.launcher3.views.ActivityContext
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

    /**
     * Ejects an app from a drawer folder (drag-out). Prefers the live apps list so the app is re-inserted
     * into the manual order at the drawer slot under the drop point; falls back to a Room-only removal
     * (folder dissolves at one app) when the list is unreachable. [activityContext] is the folder's host,
     * which at runtime is the Launcher (a Context as well as an ActivityContext).
     */
    @JvmStatic
    fun eject(activityContext: ActivityContext, folderId: Int, componentKey: String?, winX: Int, winY: Int) {
        if (folderId <= 0 || componentKey.isNullOrEmpty()) return
        val list = activityContext.appsView
            ?.activeRecyclerView
            ?.apps as? LawnchairAlphabeticalAppsList<*>
        if (list != null) {
            list.ejectAtWindowPoint(folderId, componentKey, winX, winY)
        } else {
            val app = activityContext as? Context ?: return
            val service = FolderService.INSTANCE.get(app.applicationContext)
            scope.launch {
                runCatching { service.ejectAppFromFolder(folderId, componentKey) }
            }
        }
    }
}
