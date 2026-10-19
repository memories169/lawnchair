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

    private val folderOrder = FolderOrderUtils.stringToIntList(prefs.drawerListOrder.get())

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
            folderList = folders
                .sortedBy { folderOrder.indexOf(it.id) }
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
            folderList.forEach { folder ->
                if (folder.getContents().size > 1) {
                    val folderInfo = FolderInfo()
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

    // [xperia-port] Drag-reorder support (OWN_ORDER mode only).
    fun isOwnOrderDragEnabled(): Boolean = prefs.drawerSortMode.get() == SORT_MODE_OWN_ORDER

    fun isAppAtPosition(position: Int): Boolean =
        mAdapterItems.getOrNull(position)?.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON

    // Reorders the live adapter list in memory during a drag; returns false for non-app slots.
    fun onDragMove(from: Int, to: Int): Boolean {
        if (!isOwnOrderDragEnabled()) return false
        if (from !in mAdapterItems.indices || to !in mAdapterItems.indices) return false
        if (mAdapterItems[from].viewType != BaseAllAppsAdapter.VIEW_TYPE_ICON) return false
        if (mAdapterItems[to].viewType != BaseAllAppsAdapter.VIEW_TYPE_ICON) return false
        val item = mAdapterItems.removeAt(from)
        mAdapterItems.add(to, item)
        return true
    }

    // Persists the current app sequence into drawerAppOrder after a drag completes.
    fun onDragEnd() {
        if (!isOwnOrderDragEnabled()) return
        val newOrder = mAdapterItems
            .filter { it.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON }
            .mapNotNull { it.itemInfo?.toComponentKey()?.toString() }
            .filter { it.isNotEmpty() }
        prefs.drawerAppOrder.set(newOrder.joinToString(","))
    }

    override fun onIdpChanged(modelPropertiesChanged: Boolean) {
        onAppsUpdated()
    }

    companion object {
        const val SORT_MODE_ALPHABETICAL = "alphabetical"
        const val SORT_MODE_OWN_ORDER = "ownOrder"
    }
}
