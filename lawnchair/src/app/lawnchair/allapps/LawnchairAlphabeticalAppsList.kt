package app.lawnchair.allapps

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import app.lawnchair.data.folder.model.FolderOrderUtils
import app.lawnchair.data.folder.model.FolderViewModel
import app.lawnchair.launcher
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.util.categorizeAppsWithSystemAndGoogle
import com.android.launcher3.InvariantDeviceProfile.OnIDPChangeListener
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.allapps.AlphabeticalAppsList
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem
import com.android.launcher3.allapps.PrivateProfileManager
import com.android.launcher3.allapps.WorkProfileManager
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.views.ActivityContext
import com.patrykmichalik.opto.core.onEach
import java.util.function.Predicate

@Suppress("SYNTHETIC_PROPERTY_WITHOUT_JAVA_ORIGIN")
class LawnchairAlphabeticalAppsList<T>(
    private val context: T,
    private val appsStore: AllAppsStore<T>,
    workProfileManager: WorkProfileManager?,
    privateProfileManager: PrivateProfileManager?,
) : AlphabeticalAppsList<T>(context, appsStore, workProfileManager, privateProfileManager),
    OnIDPChangeListener
    where T : Context, T : ActivityContext {

    private var hiddenApps: Set<String> = setOf()
    private val prefs2 = PreferenceManager2.getInstance(context)
    private val prefs = PreferenceManager.getInstance(context)

    private val viewModel: FolderViewModel by (context as ComponentActivity).viewModels()
    private var folderList = mutableListOf<FolderInfo>()
    private val filteredList = mutableListOf<AppInfo>()

    // [xperia-port] Guards against duplicate folders: signatures of app-sets whose folder creation is
    // still in flight (the Room flow hasn't re-emitted yet). Cleared once the DB list refreshes.
    private val pendingFolderMerges = mutableSetOf<String>()

    // [xperia-port] Read live so a persisted folder reorder takes effect without recreating the list.
    private val folderOrder: List<Int> get() = FolderOrderUtils.stringToIntList(prefs.drawerListOrder.get())

    init {
        context.launcher.deviceProfile.inv.addOnChangeListener(this)
        try {
            prefs2.hiddenApps.onEach(launchIn = context.launcher.lifecycleScope) {
                hiddenApps = it
                onAppsUpdated()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to initialize hidden apps", t)
        }
        observeFolders()
    }

    private fun observeFolders() {
        viewModel.foldersLiveData.observe(context as LifecycleOwner) { folders ->
            val order = folderOrder
            folderList = folders
                .sortedBy { order.indexOf(it.id) }
                .toMutableList()
            // Release the in-flight guard only for folders that have actually rendered, so a repeated
            // stack gesture can't slip through between the DB write and this emission and create a
            // second identical folder. Any still-pending signature stays guarded until its folder shows.
            pendingFolderMerges.removeAll { signature -> folderList.any { mergeSignatureOfFolder(it) == signature } }
            updateAdapterItems()
        }
    }

    override fun updateItemFilter(itemFilter: Predicate<ItemInfo>?) {
        mItemFilter = Predicate { info ->
            require(info is AppInfo) { "`info` must be an instance of `AppInfo`." }
            val componentKey = info.toComponentKey().toString()
            (itemFilter?.test(info) != false) && !hiddenApps.contains(componentKey)
        }
        onAppsUpdated()
    }

    override fun addAppsWithSections(appList: List<AppInfo?>?, startPosition: Int): Int {
        if (appList.isNullOrEmpty()) return startPosition
        val drawerListDefault = prefs.drawerList.get()
        filteredList.clear()
        var position = startPosition

        // Show app drawer folders only on main profile, to prevent state complexity
        if (isWorkOrPrivateSpace(appList)) return super.addAppsWithSections(appList, position)

        if (!drawerListDefault) {
            val validApps = appList.mapNotNull { it }
            val finalCategorizedApps = categorizeAppsWithSystemAndGoogle(validApps, context)

            finalCategorizedApps.forEach { (category, apps) ->
                if (apps.size == 1) {
                    mAdapterItems.add(AdapterItem.asApp(apps.first()))
                } else {
                    val folderInfo = FolderInfo().apply {
                        title = category
                        apps.forEach { add(it) }
                    }
                    mAdapterItems.add(AdapterItem.asFolder(folderInfo))
                }
                position++
            }
        } else if (prefs.drawerSortMode.get() == SORT_MODE_OWN_ORDER) {
            // [xperia-port] Interleaved manual grid: folders and apps share one order (drawerGridOrder),
            // so a folder can be placed anywhere among the apps (Xperia Home behaviour).
            position = addInterleavedOwnOrder(appList, position)
        } else {
            // Alphabetical mode: folders first (by drawerListOrder), then A-Z apps.
            val order = folderOrder
            val orderedFolders = folderList.sortedBy { order.indexOf(it.id) }
            orderedFolders.forEach { folder ->
                if (folder.getContents().size > 1) {
                    addFolderItem(folder)
                }
                position++
            }
            val remainingApps = appList.filterNot { app -> filteredList.contains(app) && prefs.folderApps.get() }
            position = super.addAppsWithSections(remainingApps, position)
        }

        return position
    }

    // [xperia-port] Adds a folder AdapterItem, carrying its DB id (so onDragEnd can persist order) and
    // resolving its contents. Foldered apps are recorded in filteredList so they're hidden from the
    // loose app list when "Apps in folder" is on.
    private fun addFolderItem(folder: FolderInfo) {
        val folderInfo = FolderInfo()
        folderInfo.id = folder.id
        folderInfo.title = folder.title
        mAdapterItems.add(AdapterItem.asFolder(folderInfo))
        folder.getContents().forEach { app ->
            (appsStore.getApp(app.componentKey) as? AppInfo)?.let {
                folderInfo.add(it)
                if (prefs.folderApps.get()) filteredList.add(it)
            }
        }
    }

    // [xperia-port] Builds the drawer as one interleaved list following the persisted manual order
    // (drawerGridOrder: "f:<id>"/"a:<key>" tokens). Anything not yet ordered is appended (folders, then
    // remaining apps alphabetically), so newly installed apps and new folders still show up.
    private fun addInterleavedOwnOrder(appList: List<AppInfo?>, startPosition: Int): Int {
        val order = prefs.drawerGridOrder.get()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val looseApps = LinkedHashMap<String, AppInfo>()
        appList.mapNotNull { it }.forEach { looseApps[it.toComponentKey().toString()] = it }

        val folders = folderList.filter { it.getContents().size > 1 }.associateBy { it.id }
        // Hide foldered apps from the loose list (mirrors the alphabetical path's filteredList behaviour).
        if (prefs.folderApps.get()) {
            folders.values.forEach { folder ->
                folder.getContents().mapNotNull { (it as? AppInfo)?.toComponentKey()?.toString() }
                    .forEach { looseApps.remove(it) }
            }
        }

        var position = startPosition
        val placedFolders = mutableSetOf<Int>()
        val placedApps = mutableSetOf<String>()
        for (token in order) {
            when {
                token.startsWith("f:") -> {
                    val id = token.substring(2).toIntOrNull() ?: continue
                    val folder = folders[id] ?: continue
                    if (placedFolders.add(id)) {
                        addFolderItem(folder)
                        position++
                    }
                }

                token.startsWith("a:") -> {
                    val key = token.substring(2)
                    val app = looseApps[key] ?: continue
                    if (placedApps.add(key)) {
                        mAdapterItems.add(AdapterItem.asApp(app))
                        position++
                    }
                }
            }
        }

        // Append folders not present in the saved order (e.g. just created), then remaining apps.
        folders.values.forEach { folder ->
            if (placedFolders.add(folder.id)) {
                addFolderItem(folder)
                position++
            }
        }
        looseApps.entries
            .filter { it.key !in placedApps }
            .sortedBy { it.value.title?.toString().orEmpty() }
            .forEach {
                mAdapterItems.add(AdapterItem.asApp(it.value))
                position++
            }
        return position
    }

    private fun contentsAsAppInfos(folder: FolderInfo): List<AppInfo> = folder.getContents().mapNotNull { it as? AppInfo }

    // [xperia-port] Drag-reorder is only available in Manual (own-order) sort mode AND while the
    // in-drawer edit toggle (DrawerEditMode) is armed. When disarmed, long-press falls through to
    // Launcher's normal drag-to-home, so users never lose the ability to add apps to the workspace.
    fun isOwnOrderDragEnabled(): Boolean = prefs.drawerSortMode.get() == SORT_MODE_OWN_ORDER && DrawerEditMode.armed

    // Reorder targets both standalone apps and folders.
    fun isAppAtPosition(position: Int): Boolean {
        val type = mAdapterItems.getOrNull(position)?.viewType
        return type == BaseAllAppsAdapter.VIEW_TYPE_ICON || type == BaseAllAppsAdapter.VIEW_TYPE_FOLDER
    }

    // Reorders the live adapter list in memory during a drag. In the interleaved manual grid any app or
    // folder may move to any other app/folder slot, so we only require both ends to be reorderable items
    // (never section headers etc.).
    fun onDragMove(from: Int, to: Int): Boolean {
        if (!isOwnOrderDragEnabled()) return false
        if (from !in mAdapterItems.indices || to !in mAdapterItems.indices) return false
        if (!isAppAtPosition(from) || !isAppAtPosition(to)) return false
        val item = mAdapterItems.removeAt(from)
        mAdapterItems.add(to, item)
        return true
    }

    // [xperia-port] Stack-to-folder / add-to-folder, decided at drop by the drag layer.
    // Returns true when a structural change was made (folder created or app added) so the list rebuilds
    // from the DB; false means it was a plain reorder and the caller persists the grid order.
    // [overlap] is the fraction (0..1) the released icon overlaps the item under its centre.
    fun handleDrop(from: Int, to: Int, overlap: Float): Boolean {
        if (!isOwnOrderDragEnabled()) return false
        if (from !in mAdapterItems.indices || to !in mAdapterItems.indices) return false
        if (overlap < MERGE_OVERLAP_RATIO) return false
        val fromKind = kindAt(from)
        val toKind = kindAt(to)
        if (fromKind == Kind.APP && toKind == Kind.APP) {
            mergeAppsIntoFolder(from, to)
            return true
        }
        if (fromKind == Kind.APP && toKind == Kind.FOLDER) {
            addAppIntoFolder(from, to)
            return true
        }
        return false
    }

    private fun kindAt(pos: Int): Kind {
        val item = mAdapterItems.getOrNull(pos) ?: return Kind.OTHER
        return when (item.viewType) {
            BaseAllAppsAdapter.VIEW_TYPE_FOLDER -> Kind.FOLDER
            BaseAllAppsAdapter.VIEW_TYPE_ICON -> Kind.APP
            else -> Kind.OTHER
        }
    }

    // [xperia-port] Drops a standalone app into an existing folder (persisted via Room, no grid reload).
    fun addAppIntoFolder(appPos: Int, folderPos: Int) {
        if (!isOwnOrderDragEnabled()) return
        val app = mAdapterItems.getOrNull(appPos)?.itemInfo ?: return
        val folderId = mAdapterItems.getOrNull(folderPos)?.folderInfo?.id ?: return
        val folder = folderList.find { it.id == folderId } ?: return
        val key = app.toComponentKey().toString()
        val contents = contentsAsAppInfos(folder)
        if (contents.any { it.toComponentKey().toString() == key }) return
        viewModel.setFolderItems(folderId, folder.title?.toString().orEmpty(), contents + app)
    }

    // [xperia-port] Creates a folder from two apps that were stacked together, then refreshes the drawer.
    // The merged apps disappear from the standalone list because "Apps in folder" excludes folder contents.
    fun mergeAppsIntoFolder(from: Int, to: Int) {
        if (!isOwnOrderDragEnabled()) return
        if (kindAt(from) != Kind.APP || kindAt(to) != Kind.APP) return
        val source = mAdapterItems[from].itemInfo ?: return
        val target = mAdapterItems[to].itemInfo ?: return
        val signature = mergeSignature(source, target)
        // Ignore repeated gestures for the same pair (e.g. the user stacks again before the first
        // folder has rendered) so we never end up with two folders holding the identical apps.
        if (signature in pendingFolderMerges) return
        if (folderList.any { folder -> mergeSignatureOfFolder(folder) == signature }) return
        pendingFolderMerges.add(signature)
        // Ensure folder contents are hidden from the loose app list so the pair doesn't appear twice.
        if (!prefs.folderApps.get()) prefs.folderApps.set(true)
        val title = target.title?.toString().orEmpty()
        // Snapshot the current grid order and remember the merge slot synchronously (before the async
        // insert) so the new folder is placed where the two apps were instead of appended at the end.
        val snapshot = currentGridTokens()
        val srcTok = "a:${source.toComponentKey()}"
        val tgtTok = "a:${target.toComponentKey()}"
        val anchor = listOf(snapshot.indexOf(srcTok), snapshot.indexOf(tgtTok))
            .filter { it >= 0 }
            .minOrNull() ?: snapshot.size
        viewModel.createFolderWithItems(title, listOf(source, target)) { newId ->
            // Render the folder the instant the insert commits, rather than waiting for the Room flow
            // to re-emit (which reads as "nothing happened" and invites a repeat gesture). If the flow
            // already delivered an equivalent folder, skip to avoid a transient duplicate.
            val exists = folderList.any { mergeSignatureOfFolder(it) == signature }
            if (!exists && folderList.none { it.id == newId }) {
                val folderInfo = FolderInfo().apply {
                    id = newId
                    this.title = title
                    add(source)
                    add(target)
                }
                folderList.add(folderInfo)
                // Place the folder at the drop slot: drop the two app tokens, insert the folder token
                // where the first one was, persist, then rebuild so it renders in place (not at the end).
                val order = snapshot.filterNot { it == srcTok || it == tgtTok }.toMutableList()
                order.add(anchor.coerceIn(0, order.size), "f:$newId")
                prefs.drawerGridOrder.set(order.joinToString(","))
                updateAdapterItems()
            }
        }
    }

    private fun mergeSignature(a: AppInfo, b: AppInfo): String = listOf(a.toComponentKey().toString(), b.toComponentKey().toString()).sorted().joinToString("|")

    private fun mergeSignatureOfFolder(folder: FolderInfo): String = folder.getContents().mapNotNull { (it as? AppInfo)?.toComponentKey()?.toString() }.sorted().joinToString("|")

    // [xperia-port] Builds the interleaved manual-order tokens ("f:<id>"/"a:<key>") from the live adapter.
    // Used both to persist after a reorder and to place a just-created folder at its drop slot.
    private fun currentGridTokens(): MutableList<String> {
        val tokens = mAdapterItems.mapIndexedNotNull { index, item ->
            when (kindAt(index)) {
                Kind.APP ->
                    item.itemInfo?.toComponentKey()?.toString()?.takeIf { it.isNotEmpty() }?.let { "a:$it" }

                Kind.FOLDER ->
                    item.folderInfo?.id?.takeIf { it > 0 }?.let { "f:$it" }

                else -> null
            }
        }
        return tokens.toMutableList()
    }

    // Persists the interleaved manual order (folders + standalone apps) into drawerGridOrder so a
    // folder's position among the apps survives restarts.
    fun onDragEnd() {
        if (!isOwnOrderDragEnabled()) return
        prefs.drawerGridOrder.set(currentGridTokens().joinToString(","))
    }

    // [xperia-port] Drag-out eject: removes an app from a drawer folder and re-inserts it into the manual
    // grid at the slot nearest the release point, so the icon lands where the finger dropped it (not at
    // the end of the drawer). Dissolves the folder when one or zero apps remain. Runs on the UI thread
    // (invoked from Folder.onDrop via DrawerFolderHost.eject).
    fun ejectAtWindowPoint(folderId: Int, componentKey: String, winX: Int, winY: Int) {
        val folder = folderList.find { it.id == folderId } ?: return
        val contents = folder.getContents().mapNotNull { it as? AppInfo }
        if (contents.none { it.toComponentKey().toString() == componentKey }) return
        val remaining = contents.filterNot { it.toComponentKey().toString() == componentKey }

        val order = currentGridTokens().toMutableList()
        val folderTok = "f:$folderId"
        val fIdx = order.indexOf(folderTok)
        val ejectedTok = "a:$componentKey"
        var tIdx = tokenIndexAtPoint(context.appsView?.activeRecyclerView, winX, winY)
        // Drop any stale loose token for this app so we never end up with a duplicate, adjusting the
        // insertion index if that removal shifted it left.
        val dupIdx = order.indexOf(ejectedTok)
        if (dupIdx >= 0) {
            order.removeAt(dupIdx)
            if (dupIdx < tIdx) tIdx--
        }

        if (remaining.size <= 1) {
            // Dissolve: the folder disappears; its surviving member (if any) keeps the folder's old slot.
            viewModel.deleteFolderWithoutReload(folderId)
            folderList.removeAll { it.id == folderId }
            if (fIdx >= 0) {
                order.removeAt(fIdx)
                val survivor = remaining.firstOrNull()
                if (survivor != null) {
                    order.add(fIdx, "a:${survivor.toComponentKey()}")
                } else if (fIdx < tIdx) {
                    tIdx--
                }
            }
        } else {
            // Folder stays: drop only the ejected app from it, optimistically so the rebuild is instant.
            viewModel.setFolderItems(folderId, folder.title?.toString().orEmpty(), remaining)
            val updated = FolderInfo().apply {
                id = folderId
                title = folder.title
                remaining.forEach { add(it) }
            }
            val idx = folderList.indexOfFirst { it.id == folderId }
            if (idx >= 0) folderList[idx] = updated
        }

        order.add(tIdx.coerceIn(0, order.size), ejectedTok)
        prefs.drawerGridOrder.set(order.joinToString(","))
        updateAdapterItems()
    }

    // [xperia-port] Maps a window coordinate (the released icon's centre) to an insertion index in the
    // grid-token space. Prefers the icon directly under the point; otherwise the nearest icon, inserting
    // after it when the point sits below/right of its centre. Section headers are skipped.
    private fun tokenIndexAtPoint(rv: RecyclerView?, winX: Int, winY: Int): Int {
        val tokenCount = currentGridTokens().size
        if (rv == null) return tokenCount
        val loc = IntArray(2)
        rv.getLocationInWindow(loc)
        val lx = (winX - loc[0]).toFloat()
        val ly = (winY - loc[1]).toFloat()
        var adapterPos: Int
        val under = rv.findChildViewUnder(lx, ly)
        if (under != null) {
            adapterPos = rv.getChildAdapterPosition(under)
        } else {
            var best = -1
            var bestDist = Long.MAX_VALUE
            var after = false
            val cloc = IntArray(2)
            for (i in 0 until rv.childCount) {
                val c = rv.getChildAt(i) ?: continue
                c.getLocationInWindow(cloc)
                val cx = cloc[0] + c.width / 2
                val cy = cloc[1] + c.height / 2
                val dx = (cx - winX).toLong()
                val dy = (cy - winY).toLong()
                val d = dx * dx + dy * dy
                if (d < bestDist) {
                    bestDist = d
                    best = rv.getChildAdapterPosition(c)
                    after = cy < winY || (cy == winY && cx < winX)
                }
            }
            if (best < 0) return tokenCount
            adapterPos = if (after) best + 1 else best
        }
        if (adapterPos < 0) adapterPos = 0
        return (0 until adapterPos.coerceAtMost(mAdapterItems.size)).count { kindAt(it) != Kind.OTHER }
    }

    override fun onIdpChanged(modelPropertiesChanged: Boolean) {
        onAppsUpdated()
    }

    // [xperia-port] High-level classification of a drawer slot, used to disambiguate drop gestures.
    private enum class Kind { APP, FOLDER, OTHER }

    companion object {
        const val SORT_MODE_ALPHABETICAL = "alphabetical"
        const val SORT_MODE_OWN_ORDER = "ownOrder"

        // Fraction of the dragged icon that must overlap the item under its centre to stack/merge rather
        // than reorder. Tuned for a deliberate "drop on top"; adjust here if merges feel too eager/loose.
        const val MERGE_OVERLAP_RATIO = 0.5f
    }
}
