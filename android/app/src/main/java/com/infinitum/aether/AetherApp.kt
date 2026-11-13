package com.infinitum.aether

import android.app.Application
import com.infinitum.aether.data.Features
import com.infinitum.aether.data.Store
import com.infinitum.aether.net.AetherNode

class AetherApp : Application() {
    val store: Store by lazy { Store(this) }
    val features: Features by lazy { Features(this, store) }

    @Volatile
    var node: AetherNode? = null
        private set

    /** Starts the node once a profile exists. Idempotent. */
    @Synchronized
    fun ensureNode(): AetherNode? {
        node?.let { return it }
        val profile = store.loadProfile() ?: return null
        return AetherNode(this, store, profile).also { it.start(); node = it; NodeService.start(this) }
    }

    @Synchronized
    fun createProfile(name: String): AetherNode {
        store.createProfile(name)
        return ensureNode()!!
    }
}
