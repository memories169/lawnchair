package app.lawnchair.allapps

import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ItemTouchHelper.DOWN
import androidx.recyclerview.widget.ItemTouchHelper.LEFT
import androidx.recyclerview.widget.ItemTouchHelper.RIGHT
import androidx.recyclerview.widget.ItemTouchHelper.UP
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.Launcher
import com.android.launcher3.allapps.AllAppsRecyclerView
import java.util.WeakHashMap

// Grid supports free movement in all four directions (matches Xperia Home's drag flags = 0xf).
private val DRAG_DIRS = UP or DOWN or LEFT or RIGHT

// One ItemTouchHelper per drawer RecyclerView; kept weakly so it never leaks the view.
private val dragHelpers = WeakHashMap<RecyclerView, ItemTouchHelper>()

// [xperia-port] Wires long-press drag-to-reorder into the app drawer grid.
// The ItemTouchHelper's own long-press is disabled; drags are started deterministically from
// LawnchairLauncher.getAllAppsItemLongClickListener via startDrawerReorderDrag(), so Manual mode
// never races with Launcher's drag-to-workspace.
fun attachDrawerDragReorder(launcher: Launcher) {
    val container = launcher.appsView ?: return
    container.post {
        val rv = container.activeRecyclerView ?: return@post
        val apps = (rv as? AllAppsRecyclerView)?.apps as? LawnchairAlphabeticalAppsList<*>
            ?: return@post
        if (dragHelpers.containsKey(rv)) return@post
        val callback = object : ItemTouchHelper.SimpleCallback(DRAG_DIRS, 0) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int {
                if (!apps.isOwnOrderDragEnabled()) return 0
                if (!apps.isAppAtPosition(viewHolder.bindingAdapterPosition)) return 0
                return makeMovementFlags(DRAG_DIRS, 0)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                if (!apps.onDragMove(from, to)) return false
                recyclerView.adapter?.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                apps.onDragEnd()
            }
        }
        val itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(rv)
        dragHelpers[rv] = itemTouchHelper
    }
}

// [xperia-port] Handles an AllApps icon long-press. Returns true when a reorder drag was started
// (Manual mode + app item), consuming the gesture so Launcher's drag-to-home never fires.
// Returns false to fall back to the normal long-press behaviour.
fun startDrawerReorderDrag(launcher: Launcher, iconView: View): Boolean {
    val rv = launcher.appsView?.activeRecyclerView ?: return false
    val apps = (rv as? AllAppsRecyclerView)?.apps as? LawnchairAlphabeticalAppsList<*> ?: return false
    if (!apps.isOwnOrderDragEnabled()) return false
    val itemTouchHelper = dragHelpers[rv] ?: return false
    val viewHolder = rv.getChildViewHolder(iconView) ?: return false
    if (!apps.isAppAtPosition(viewHolder.bindingAdapterPosition)) return false
    itemTouchHelper.startDrag(viewHolder)
    return true
}
