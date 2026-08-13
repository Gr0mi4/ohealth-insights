package dev.gr0mi4.ohealthinsights.drive

import android.app.Activity
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dev.gr0mi4.ohealthinsights.BuildConfig
import kotlinx.coroutines.tasks.await

class DriveAuth(private val activity: Activity) {
    private val authorizationClient = Identity.getAuthorizationClient(activity)

    val isConfigured: Boolean
        get() = BuildConfig.DRIVE_OAUTH_CLIENT_ID.isNotBlank()

    suspend fun authorizeSilently(): AuthorizationResult? {
        if (!isConfigured) return null
        val result = runCatching { authorizationClient.authorize(buildRequest()).await() }.getOrNull()
            ?: return null
        return if (result.hasResolution()) null else result
    }

    suspend fun authorizeInteractive(): AuthorizationResult {
        require(isConfigured) {
            "Drive OAuth client ID is not configured. Add DRIVE_OAUTH_CLIENT_ID to local.properties."
        }
        val initial = authorizationClient.authorize(buildRequest()).await()
        if (!initial.hasResolution()) return initial
        error("Interactive authorization must be launched from the hosting activity.")
    }

    fun extractAccessToken(result: AuthorizationResult): String =
        result.accessToken ?: error("Google Drive authorization did not return an access token.")

    fun extractAccountEmail(result: AuthorizationResult): String? = result.toGoogleSignInAccount()?.email

    private fun buildRequest(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }
}
