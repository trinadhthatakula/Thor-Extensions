/*
 * Strombringer — CorePatch signature/digest bypass hooks (lifted & rewired).
 *
 * Derived from CorePatch (https://github.com/LSPosed/CorePatch) by the CorePatch
 * authors (weishu, yujincheng08 / canyie and the LSPosed contributors).
 * Original hook logic Copyright (C) the CorePatch authors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the Free
 * Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * S / S_V2 (API 31-32): CorePatch's only S-specific hook was
 * doesSignatureMatchForPermissions (a permission hook) — stripped. This class
 * therefore only inherits R's kept sig/digest hooks unchanged.
 */
package com.valhalla.thor.ext.strombringer.corepatch;

public class CorePatchForS extends CorePatchForR {
    // No S-specific hooks are kept; inherit CorePatchForR#handleLoadPackage as-is.
}
