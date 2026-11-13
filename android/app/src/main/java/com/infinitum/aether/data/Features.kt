package com.infinitum.aether.data

import android.content.Context

/**
 * The stores behind ÆTHER Maps™, ÆTHER Field™ and ÆTHER eidetic MIL™. Each keeps its own
 * encrypted vault under the device's hardware-wrapped key; eidetic templates live in a
 * separate sealed vault from its reviews and audit log.
 */
class Features(context: Context, store: Store) {
    private val app = context.applicationContext
    val maps: MapsStore by lazy { MapsStore(app, store.openVault("maps")) }
    val field: FieldStore by lazy { FieldStore(store.openVault("field")) }
    val eidetic: EideticStore by lazy { EideticStore(store.openVault("eidetic"), store.openVault("eidetic-templates")) }
}
