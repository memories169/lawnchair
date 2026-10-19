package app.lawnchair.allapps

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ItemTouchHelper.DOWN
import androidx.recyclerview.widget.ItemTouchHelper.UP
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.Launcher
import com.android.launcher3.allapps.AllAppsRecyclerView

private val DRAG_DIRS = UP or DOWN

// [xperia-port] Attaches long-press drag-to-reorder to the app drawer grid.
// Only active while the drawer is in OWN_ORDER mode (see LawnchairAlphabeticalAppsList).
fun attachDrawerDragReorder(launcher: Launcher) {
    val container = launcher.appsView ?: return
    container.post {
        val rv = container.activeRecyclerView ?: return@post
        val apps = (rv as? AllAppsRecyclerView)?.apps as? LawnchairAlphabeticalAppsList<*>
            ?: return@post
        val callback = object : ItemTouchHelper.SimpleCallback(DRAG_DIRS, 0) {
            override fun isLongPressDragEnabled(): Boolean = apps.isOwnOrderDragEnabled()

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
        ItemTouchHelper(callback).attachToRecyclerView(rv)
    }
}
