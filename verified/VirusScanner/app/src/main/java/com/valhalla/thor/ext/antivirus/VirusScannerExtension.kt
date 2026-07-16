package com.valhalla.thor.ext.antivirus

import com.valhalla.thor.extension.api.ThorExtension

/**
 * Metadata surface Thor loads (by `thor.extension.class`) to list this extension + offer Configure.
 * Thor never runs extension code now — the extension calls Thor's ExtensionOpsProvider instead,
 * so this exposes no runtime trigger hooks. Kept as a plain [ThorExtension] (metadata only).
 */
@Suppress("unused")
class VirusScannerExtension : ThorExtension {
    override val name: String = "S.H.I.E.L.D."
    override val description: String = "Strategic Hazard Intervention Espionage Logistics Directorate. Premium APK & App security scanner. Scans installed apps and storage files for malicious heuristics, certificate changes, and signature spoofing."
    override val version: String = "1.00.0"
    override val author: String = "Thor"
}
