package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ConnectivityObserver — process-wide online/offline signal for the local-first
 * message pipeline (Task 24).
 *
 * The app is local-first: chats open from Room, sends stage locally, uploads
 * happen in the background. This observer is the "internet came back" edge
 * that triggers the automatic retry of the offline outgoing queue (stuck
 * SENDING / FAILED messages) instead of requiring the user to tap retry.
 *
 * Usage: [start] is idempotent and safe to call from any ViewModel init;
 * collectors subscribe to [isOnline].
 */
object ConnectivityObserver {

    private const val TAG = "ConnectivityObserver"

    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline

    @Volatile
    private var started = false

    /** Registers a default-network callback once per process. */
    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            try {
                val cm = context.applicationContext
                    .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

                // Seed the current state (a registered callback only fires on
                // changes — the initial value must reflect reality).
                _isOnline.value = isNetworkAvailable(cm)

                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.d(TAG, "network available")
                        _isOnline.value = true
                    }

                    override fun onLost(network: Network) {
                        // Only go offline when NO network remains — Wi-Fi and
                        // cellular can hand over without ever dropping.
                        _isOnline.value = isNetworkAvailable(cm)
                        Log.d(TAG, "network lost — online=$_isOnline")
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities
                    ) {
                        val hasInternet = capabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_VALIDATED
                        )
                        _isOnline.value = hasInternet
                    }
                }

                cm.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    callback
                )
                started = true
            } catch (e: Exception) {
                // Callbacks unavailable (rare OEM behavior) — leave the seeded
                // value; the retry paths also run on app start and chat open.
                Log.w(TAG, "registerNetworkCallback failed: ${e.message}")
            }
        }
    }

    private fun isNetworkAvailable(cm: ConnectivityManager): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
