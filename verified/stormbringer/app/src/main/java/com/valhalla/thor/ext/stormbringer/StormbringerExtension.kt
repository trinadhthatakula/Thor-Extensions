@file:Suppress("unused")

package com.valhalla.thor.ext.stormbringer

import com.valhalla.thor.extension.api.ThorExtension

/**
 * Thor-side metadata surface for Stormbringer. Thor loads THIS class in its own process (via
 * PathClassLoader) only to read the name/description/version/author shown in the Extension Manager,
 * so it must stay trivial — no Compose, no Asgard, no privileged calls. Anything that would run in
 * Thor's process against Thor's minified runtime is forbidden here (that coupling is exactly what
 * broke the old in-host config screen).
 *
 * The actual configuration UI lives in [ConfigActivity], which Thor launches by Intent
 * (action `com.valhalla.thor.extension.action.CONFIGURE`) so it runs in Stormbringer's OWN process
 * with its own full Compose/Asgard — no cross-boundary ABI. The signature-bypass work itself runs
 * in `system_server` via the LSPosed hooks ([XposedEntry]); this class does not.
 */
class StormbringerExtension : ThorExtension {
    override val name = "Stormbringer"
    override val description = "Auto-unfreeze suspended apps on launch, and CorePatch signature-bypass " +
            "(danger zone). Requires LSPosed. Tap to configure."
    override val version = "1.00.0"
    override val author = "Thor"
}
