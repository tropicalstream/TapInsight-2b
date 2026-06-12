@file:Suppress("DEPRECATION")
package com.rayneo.visionclaw.core.media

/**
 * MediaLibraryService has moved to the tapbrowser module
 * (com.TapLink.app.media.MediaLibraryService) so both the on-glasses
 * JavascriptInterface bridge AND the companion HTTP server can depend on it
 * without crossing the app → tapbrowser dependency direction.
 *
 * This typealias is a transitional shim for any stragglers that still
 * import the old path. New code should import
 * com.TapLink.app.media.MediaLibraryService directly.
 */
@Deprecated(
    message = "Moved to com.TapLink.app.media.MediaLibraryService",
    replaceWith = ReplaceWith(
        "MediaLibraryService",
        imports = ["com.TapLink.app.media.MediaLibraryService"]
    )
)
typealias MediaLibraryService = com.TapLink.app.media.MediaLibraryService
