package app.lawnchair.allapps

import android.graphics.Rect
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
        // [xperia-port] Live-shift + drop-on-top model (Xperia Home style): neighbours reflow in real
        // time as you drag (onMove swaps them), and on release we look at how far the dragged icon
        // overlaps the icon under its centre. A heavy overlap means "drop on top" -> stack into a folder
        // / add to folder / eject a child; a light overlap is just a reorder, whose order we persist.
        val dragRect = Rect()
        val childRect = Rect()
        val loc = IntArray(2)
        var bestTargetPos = RecyclerView.NO_POSITION
        var bestOverlap = 0f
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

            override fun onSelectedChanged(
                viewHolder: RecyclerView.ViewHolder?,
                actionState: Int,
            ) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    bestTargetPos = RecyclerView.NO_POSITION
                    bestOverlap = 0f
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                // Live shift: mutate the backing list, notify the adapter so RecyclerView animates the
                // neighbours into place in real time, and let ItemTouchHelper continue the drag.
                if (apps.onDragMove(from, to)) {
                    recyclerView.adapter?.notifyItemMoved(from, to)
                    return true
                }
                return false
            }

            override fun onChildDraw(
                c: android.graphics.Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean,
            ) {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                if (!isCurrentlyActive) return
                val dragged = viewHolder.itemView
                dragged.getLocationInWindow(loc)
                dragRect.set(loc[0], loc[1], loc[0] + dragged.width, loc[1] + dragged.height)
                val dragArea = dragRect.width().coerceAtLeast(1) * dragRect.height().coerceAtLeast(1)
                bestTargetPos = RecyclerView.NO_POSITION
                bestOverlap = 0f
                for (i in 0 until recyclerView.childCount) {
                    val child = recyclerView.getChildAt(i) ?: continue
                    if (child === dragged) continue
                    child.getLocationInWindow(loc)
                    childRect.set(loc[0], loc[1], loc[0] + child.width, loc[1] + child.height)
                    if (!childRect.intersect(dragRect)) continue
                    val interArea = childRect.width().coerceAtLeast(0) * childRect.height().coerceAtLeast(0)
                    val ratio = interArea.toFloat() / dragArea.toFloat()
                    if (ratio > bestOverlap) {
                        bestOverlap = ratio
                        bestTargetPos = recyclerView.getChildAdapterPosition(child)
                    }
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                val from = viewHolder.bindingAdapterPosition
                val target = bestTargetPos
                val overlap = bestOverlap
                super.clearView(recyclerView, viewHolder)
                bestTargetPos = RecyclerView.NO_POSITION
                bestOverlap = 0f
                if (from == RecyclerView.NO_POSITION) return
                // Structural drops (merge / add / eject) rebuild from the DB; a plain reorder keeps the
                // live-shifted adapter state, so we persist the new grid order.
                val structural = target != RecyclerView.NO_POSITION && apps.handleDrop(from, target, overlap)
                if (!structural) apps.onDragEnd()
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
    // The long-pressed view may be a descendant of the RecyclerView child (e.g. a folder's FolderIcon
    // inside its container), so resolve the containing ViewHolder rather than requiring a direct child.
    val viewHolder = rv.findContainingViewHolder(iconView) ?: return false
    if (!apps.isAppAtPosition(viewHolder.bindingAdapterPosition)) return false
    itemTouchHelper.startDrag(viewHolder)
    return true
}
