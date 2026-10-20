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

// [xperia-port] How long a dragged icon must dwell over a neighbouring icon (no target change) before
// releasing creates a folder instead of a reorder. Tuned for a deliberate "stack" gesture.
private const val MERGE_DWELL_MS = 300L

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
        // [xperia-port] Decide-at-drop model: the drawer is a packed, flowing grid, so live shifting
        // can't be told apart from stacking. We therefore never reorder mid-drag; the dragged icon just
        // follows the finger. On release we either merge it into the icon it dwelled on (folder) or move
        // it to the slot it is hovering over (reorder).
        var lastTargetPos = RecyclerView.NO_POSITION
        var mergeArmed = false
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var mergeTimer: Runnable? = null
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
                    lastTargetPos = RecyclerView.NO_POSITION
                    mergeArmed = false
                    mergeTimer?.let { handler.removeCallbacks(it) }
                    mergeTimer = null
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
                if (to == lastTargetPos) return false
                // A new neighbour is now under the finger: restart the dwell timer. onMove only fires on
                // ACTION_MOVE, so we arm the merge with a Handler postDelayed instead of a time check.
                lastTargetPos = to
                mergeArmed = false
                mergeTimer?.let { handler.removeCallbacks(it) }
                if (apps.canMergeAt(from, to)) {
                    val timer = Runnable { mergeArmed = true }
                    mergeTimer = timer
                    handler.postDelayed(timer, MERGE_DWELL_MS)
                } else {
                    mergeTimer = null
                }
                // Never mutate the list here; the decision is made on drop in clearView().
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                mergeTimer?.let { handler.removeCallbacks(it) }
                mergeTimer = null
                val from = viewHolder.bindingAdapterPosition
                when {
                    mergeArmed && lastTargetPos != RecyclerView.NO_POSITION ->
                        apps.mergeAppsIntoFolder(from, lastTargetPos)
                    lastTargetPos != RecyclerView.NO_POSITION && lastTargetPos != from -> {
                        if (apps.onDragMove(from, lastTargetPos)) {
                            recyclerView.adapter?.notifyItemMoved(from, lastTargetPos)
                            apps.onDragEnd()
                        }
                    }
                }
                lastTargetPos = RecyclerView.NO_POSITION
                mergeArmed = false
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
