package app.lawnchair.allapps

// [xperia-port] Transient "edit order" state for the app drawer, toggled by the pencil button in
// the search-bar row. When armed, a long-press starts an in-drawer reorder drag (icons + folders).
// When disarmed, a long-press falls through to Launcher's normal drag-to-home. Kept in memory only:
// every cold start begins disarmed, and Manual (own-order) sort mode is still required for arming.
object DrawerEditMode {
    var armed: Boolean = false
}
