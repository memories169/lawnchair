package app.lawnchair.allapps

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
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

    // [xperia-port] Stack-to-folder: two positions are eligible when both are standalone app icons
    // and Manual drag is active. (Folders merging folders is intentionally not supported here.)
    fun canMergeAt(from: Int, to: Int): Boolean {
        if (!isOwnOrderDragEnabled()) return false
        val a = mAdapterItems.getOrNull(from)?.viewType
        val b = mAdapterItems.getOrNull(to)?.viewType
        return a == BaseAllAppsAdapter.VIEW_TYPE_ICON && b == BaseAllAppsAdapter.VIEW_TYPE_ICON
    }

    // [xperia-port] Creates a folder from two apps that were stacked together, then refreshes the drawer.
    // The merged apps disappear from the standalone list because "Apps in folder" excludes folder contents.
    fun mergeAppsIntoFolder(from: Int, to: Int) {
        if (!canMergeAt(from, to)) return
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
                updateAdapterItems()
            }
        }
    }

    private fun mergeSignature(a: AppInfo, b: AppInfo): String = listOf(a.toComponentKey().toString(), b.toComponentKey().toString()).sorted().joinToString("|")

    private fun mergeSignatureOfFolder(folder: FolderInfo): String = folder.getContents().mapNotNull { (it as? AppInfo)?.toComponentKey()?.toString() }.sorted().joinToString("|")

    // Persists the interleaved manual order (folders + apps) into drawerGridOrder as "f:<id>"/"a:<key>"
    // tokens, so a folder's position among the apps survives restarts.
    fun onDragEnd() {
        if (!isOwnOrderDragEnabled()) return
        val tokens = mAdapterItems.mapNotNull { item ->
            when (item.viewType) {
                BaseAllAppsAdapter.VIEW_TYPE_ICON ->
                    item.itemInfo?.toComponentKey()?.toString()?.takeIf { it.isNotEmpty() }?.let { "a:$it" }

                BaseAllAppsAdapter.VIEW_TYPE_FOLDER ->
                    item.folderInfo?.id?.takeIf { it > 0 }?.let { "f:$it" }

                else -> null
            }
        }
        prefs.drawerGridOrder.set(tokens.joinToString(","))
    }

    override fun onIdpChanged(modelPropertiesChanged: Boolean) {
        onAppsUpdated()
    }

    companion object {
        const val SORT_MODE_ALPHABETICAL = "alphabetical"
        const val SORT_MODE_OWN_ORDER = "ownOrder"
    }
}
