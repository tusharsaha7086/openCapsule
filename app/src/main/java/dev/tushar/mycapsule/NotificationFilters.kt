package dev.tushar.mycapsule

/**
 * What happens to an app's notifications when it has no choice of its own on the Notification filters screen:
 * every such app is allowed, or every such app is blocked. Together with the per-app switches this is both a block
 * list ("allow all", then switch a few off) and an allow list ("block all", then switch a few on).
 */
enum class AppPolicy(val label: String) {
    ALLOW_ALL("Allow all"),
    BLOCK_ALL("Block all"),
}
