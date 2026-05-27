package com.aura.music.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Checks whether a backend call is allowed given the current network and user settings.
 *
 * Governs AND-009: online_search_enabled and online_search_network_policy are the
 * normative gate for all backend calls initiated from Android (search, resolve, enrichment).
 *
 * Policy values (from docs/android/room-schema.md):
 *   - "disabled"     : no backend call allowed
 *   - "wifi_only"    : allowed only on unmetered (Wi-Fi) connections
 *   - "any_network"  : allowed on any connected network
 */
object NetworkPolicyChecker {

    /**
     * Returns true if a backend call is allowed given the provided settings and live network.
     *
     * @param onlineSearchEnabled  Value of user_settings.online_search_enabled
     * @param policy               Value of user_settings.online_search_network_policy
     * @param context              Application context for ConnectivityManager lookup
     */
    fun isAllowed(
        onlineSearchEnabled: Boolean,
        policy: String?,
        context: Context,
    ): Boolean {
        if (!onlineSearchEnabled) return false
        return when (policy) {
            "disabled" -> false
            "wifi_only" -> isUnmetered(context)
            "any_network" -> isConnected(context)
            else -> false // unknown policy defaults to blocked
        }
    }

    private fun isConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
