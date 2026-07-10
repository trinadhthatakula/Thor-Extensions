/*
 * Stormbringer — CorePatch signature/digest bypass hooks (lifted & rewired).
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
 * V (API 35 / 36). CorePatch's only V-specific hook was an alternate
 * checkDowngrade signature (downgrade) — stripped. Inherits U's kept hooks.
 */
package com.valhalla.thor.ext.stormbringer.corepatch;

public class CorePatchForV extends CorePatchForU {
    // No V-specific hooks are kept; inherit CorePatchForU#handleLoadPackage as-is.
}
