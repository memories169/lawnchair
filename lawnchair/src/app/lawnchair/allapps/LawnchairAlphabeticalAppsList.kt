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
        } else {
            // [xperia-port] Sort folders by the persisted order here (not just in observeFolders), so a
            // drag-reorder persists immediately even when the folders LiveData does not re-emit.
            val order = folderOrder
            val orderedFolders = folderList.sortedBy { order.indexOf(it.id) }
            orderedFolders.forEach { folder ->
                if (folder.getContents().size > 1) {
                    val folderInfo = FolderInfo()
                    folderInfo.id = folder.id // carry the DB id so onDragEnd can persist folder order
                    folderInfo.title = folder.title
                    mAdapterItems.add(AdapterItem.asFolder(folderInfo))
                    folder.getContents().forEach { app ->
                        (appsStore.getApp(app.componentKey) as? AppInfo)?.let {
                            folderInfo.add(it)
                            if (prefs.folderApps.get()) filteredList.add(it)
                        }
                    }
                }
                position++
            }
            val remainingApps = appList.filterNot { app -> filteredList.contains(app) && prefs.folderApps.get() }
            // [xperia-port] OWN_ORDER: lay out standalone apps by the user's manual order instead of A-Z.
            position = if (prefs.drawerSortMode.get() == SORT_MODE_OWN_ORDER) {
                addAppsInOwnOrder(remainingApps, position)
            } else {
                super.addAppsWithSections(remainingApps, position)
            }
        }

        return position
    }

    // [xperia-port] Adds standalone apps following the persisted manual order (drawerAppOrder),
    // with any not-yet-ordered apps appended alphabetically. No A-Z section headers are created.
    private fun addAppsInOwnOrder(appList: List<AppInfo?>, startPosition: Int): Int {
        val order = prefs.drawerAppOrder.get()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val orderIndex = HashMap<String, Int>(order.size)
        order.forEachIndexed { index, key -> if (!orderIndex.containsKey(key)) orderIndex[key] = index }
        val sorted = appList.mapNotNull { it }.sortedWith(
            compareBy(
                { orderIndex[it.toComponentKey().toString()] ?: Int.MAX_VALUE },
                { it.title?.toString().orEmpty() },
            ),
        )
        var position = startPosition
        for (app in sorted) {
            mAdapterItems.add(AdapterItem.asApp(app))
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

    // Reorders the live adapter list in memory during a drag. Icons only swap with icons, folders only
    // with folders (matches Xperia's same-viewType rule), so the folder block and app block stay intact.
    fun onDragMove(from: Int, to: Int): Boolean {
        if (!isOwnOrderDragEnabled()) return false
        if (from !in mAdapterItems.indices || to !in mAdapterItems.indices) return false
        val fromType = mAdapterItems[from].viewType
        val toType = mAdapterItems[to].viewType
        if (fromType != toType) return false
        if (fromType != BaseAllAppsAdapter.VIEW_TYPE_ICON && fromType != BaseAllAppsAdapter.VIEW_TYPE_FOLDER) return false
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
        // Ensure folder contents are hidden from the loose app list so the pair doesn't appear twice.
        if (!prefs.folderApps.get()) prefs.folderApps.set(true)
        viewModel.createFolderWithItems(target.title?.toString().orEmpty(), listOf(source, target))
    }

    // Persists the current app sequence into drawerAppOrder and the folder sequence into drawerListOrder.
    fun onDragEnd() {
        if (!isOwnOrderDragEnabled()) return
        val newOrder = mAdapterItems
            .filter { it.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON }
            .mapNotNull { it.itemInfo?.toComponentKey()?.toString() }
            .filter { it.isNotEmpty() }
        prefs.drawerAppOrder.set(newOrder.joinToString(","))
        val newFolderOrder = mAdapterItems
            .filter { it.viewType == BaseAllAppsAdapter.VIEW_TYPE_FOLDER }
            .mapNotNull { it.folderInfo?.id }
            .filter { it > 0 }
        if (newFolderOrder.isNotEmpty()) {
            prefs.drawerListOrder.set(FolderOrderUtils.intListToString(newFolderOrder))
        }
    }

    override fun onIdpChanged(modelPropertiesChanged: Boolean) {
        onAppsUpdated()
    }

    companion object {
        const val SORT_MODE_ALPHABETICAL = "alphabetical"
        const val SORT_MODE_OWN_ORDER = "ownOrder"
    }
}
