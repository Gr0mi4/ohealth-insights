package dev.gr0mi4.ohealthinsights.drive

object DriveScopes {
    /**
     * Grants access only to files this app creates. The rest of the user's Drive stays invisible to
     * it, which is why reconnecting cannot reach documents the app did not write.
     */
    const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
}
