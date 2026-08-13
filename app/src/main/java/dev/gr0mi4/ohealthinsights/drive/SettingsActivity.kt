package dev.gr0mi4.ohealthinsights.drive

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {
    private lateinit var settingsStore: DriveSettingsStore
    private lateinit var statusText: TextView
    private lateinit var autoUploadCheck: CheckBox
    private lateinit var updateLatestCheck: CheckBox
    private lateinit var rootFolderInput: EditText
    private lateinit var reportsFolderInput: EditText
    private lateinit var archiveFolderInput: EditText
    private lateinit var reportTemplateInput: EditText
    private lateinit var csvTemplateInput: EditText
    private lateinit var rawTemplateInput: EditText
    private lateinit var latestReportInput: EditText
    private lateinit var latestCsvInput: EditText

    private val authorizationClient by lazy { Identity.getAuthorizationClient(this) }

    private val authLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            statusText.text = "Google sign-in cancelled."
            return@registerForActivityResult
        }
        val data = result.data ?: run {
            statusText.text = "Google sign-in returned no data."
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            runCatching {
                val authResult = authorizationClient.getAuthorizationResultFromIntent(data)
                handleAuthorizationSuccess(authResult)
            }.onFailure {
                statusText.text = "Authorization failed: ${it.message}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = DriveSettingsStore(this)
        createUi()
        renderSettings(settingsStore.load())
    }

    private fun createUi() {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()

        statusText = TextView(this).apply { textSize = 15f }
        autoUploadCheck = CheckBox(this).apply { text = "Auto-upload after sync" }
        updateLatestCheck = CheckBox(this).apply { text = "Maintain latest report + CSV files" }
        rootFolderInput = labeledInput("Root folder name")
        reportsFolderInput = labeledInput("Reports subfolder")
        archiveFolderInput = labeledInput("Archive subfolder")
        reportTemplateInput = labeledInput("Report filename template")
        csvTemplateInput = labeledInput("CSV filename template")
        rawTemplateInput = labeledInput("Raw export filename template")
        latestReportInput = labeledInput("Latest report filename")
        latestCsvInput = labeledInput("Latest CSV filename")

        val placeholders = TextView(this).apply {
            text = "Template placeholders: {date}, {timestamp}, {syncMode}, {version}"
            textSize = 13f
        }

        val connectButton = Button(this).apply {
            text = "Connect Google account"
            setOnClickListener { connectGoogleAccount() }
        }
        val testButton = Button(this).apply {
            text = "Test connection"
            setOnClickListener { testConnection() }
        }
        val disconnectButton = Button(this).apply {
            text = "Disconnect Google account"
            setOnClickListener { disconnectAccount() }
        }
        val saveButton = Button(this).apply {
            text = "Save settings"
            setOnClickListener { saveSettings() }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(this@SettingsActivity).apply {
                text = "Google Drive upload"
                textSize = 22f
                gravity = Gravity.CENTER_HORIZONTAL
            })
            addView(statusText, wrap(top = 8))
            addView(autoUploadCheck, wrap(top = 12))
            addView(updateLatestCheck, wrap(top = 4))
            addView(rootFolderInput, wrap(top = 12))
            addView(reportsFolderInput, wrap(top = 8))
            addView(archiveFolderInput, wrap(top = 8))
            addView(reportTemplateInput, wrap(top = 12))
            addView(csvTemplateInput, wrap(top = 8))
            addView(rawTemplateInput, wrap(top = 8))
            addView(latestReportInput, wrap(top = 8))
            addView(latestCsvInput, wrap(top = 8))
            addView(placeholders, wrap(top = 8))
            addView(connectButton, wrap(top = 16))
            addView(testButton, wrap(top = 8))
            addView(disconnectButton, wrap(top = 8))
            addView(saveButton, wrap(top = 16))
        }

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun labeledInput(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        setSingleLine()
    }

    private fun wrap(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = (top * resources.displayMetrics.density).toInt()
    }

    private fun renderSettings(settings: DriveSettings) {
        if (!settingsStore.isConfigured()) {
            statusText.text = "Drive OAuth client ID is missing. See docs/GOOGLE_DRIVE_SETUP.md."
            return
        }
        autoUploadCheck.isChecked = settings.autoUploadEnabled
        updateLatestCheck.isChecked = settings.updateLatestReport
        rootFolderInput.setText(settings.rootFolderName)
        reportsFolderInput.setText(settings.reportsFolderName)
        archiveFolderInput.setText(settings.archiveFolderName)
        reportTemplateInput.setText(settings.reportFileTemplate)
        csvTemplateInput.setText(settings.csvFileTemplate)
        rawTemplateInput.setText(settings.rawFileTemplate)
        latestReportInput.setText(settings.latestReportName)
        latestCsvInput.setText(settings.latestCsvName)
        statusText.text = when {
            settings.driveAuthorizationGranted && !settings.googleAccountEmail.isNullOrBlank() ->
                "Connected as ${settings.googleAccountEmail}"
            settings.driveAuthorizationGranted -> "Connected to Google Drive."
            else -> "Not connected. Tap Connect Google account."
        }
    }

    private fun currentSettings(): DriveSettings {
        val existing = settingsStore.load()
        return existing.copy(
            autoUploadEnabled = autoUploadCheck.isChecked,
            updateLatestReport = updateLatestCheck.isChecked,
            rootFolderName = rootFolderInput.text.toString().ifBlank { existing.rootFolderName },
            reportsFolderName = reportsFolderInput.text.toString().ifBlank { existing.reportsFolderName },
            archiveFolderName = archiveFolderInput.text.toString().ifBlank { existing.archiveFolderName },
            reportFileTemplate = reportTemplateInput.text.toString().ifBlank { existing.reportFileTemplate },
            csvFileTemplate = csvTemplateInput.text.toString().ifBlank { existing.csvFileTemplate },
            rawFileTemplate = rawTemplateInput.text.toString().ifBlank { existing.rawFileTemplate },
            latestReportName = latestReportInput.text.toString().ifBlank { existing.latestReportName },
            latestCsvName = latestCsvInput.text.toString().ifBlank { existing.latestCsvName },
        )
    }

    private fun saveSettings() {
        settingsStore.save(currentSettings())
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        renderSettings(settingsStore.load())
    }

    private fun connectGoogleAccount() {
        if (!settingsStore.isConfigured()) {
            Toast.makeText(this, "Configure DRIVE_OAUTH_CLIENT_ID first", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            statusText.text = "Opening Google sign-in…"
            runCatching {
                val request = AuthorizationRequest.builder()
                    .setRequestedScopes(listOf(Scope(DriveAuth.DRIVE_FILE_SCOPE)))
                    .build()
                val initial = authorizationClient.authorize(request).await()
                if (initial.hasResolution()) {
                    val pending = initial.pendingIntent
                        ?: error("Google authorization returned no pending intent.")
                    authLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    handleAuthorizationSuccess(initial)
                }
            }.onFailure {
                statusText.text = "Connect failed: ${it.message}"
            }
        }
    }

    private suspend fun handleAuthorizationSuccess(result: AuthorizationResult) {
        require(!result.accessToken.isNullOrBlank()) {
            "Google authorization returned no Drive access token."
        }
        val email = result.toGoogleSignInAccount()?.email
        settingsStore.save(
            currentSettings().copy(
                googleAccountEmail = email,
                driveAuthorizationGranted = true,
            ),
        )
        withContext(Dispatchers.Main) {
            statusText.text = email?.let { "Connected as $it" } ?: "Connected to Google Drive."
            Toast.makeText(this@SettingsActivity, "Google account connected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun testConnection() {
        lifecycleScope.launch {
            statusText.text = "Testing Drive connection…"
            runCatching {
                val settings = settingsStore.load()
                val token = authorizeForUpload() ?: error("Connect a Google account first.")
                withContext(Dispatchers.IO) {
                    DriveClient(token).testConnection(settings)
                }
            }.onSuccess { check ->
                settingsStore.save(currentSettings().copy(driveAuthorizationGranted = true))
                settingsStore.updateFolderIds(
                    rootFolderId = check.rootFolderId,
                    reportsFolderId = check.reportsFolderId,
                    archiveFolderId = check.archiveFolderId,
                )
                statusText.text = check.message
            }.onFailure {
                statusText.text = "Test failed: ${it.message}"
            }
        }
    }

    private fun disconnectAccount() {
        settingsStore.clearAccount()
        settingsStore.save(
            currentSettings().copy(
                googleAccountEmail = null,
                driveAuthorizationGranted = false,
            ),
        )
        statusText.text = "Disconnected locally. Reconnect anytime from this screen."
        Toast.makeText(this, "Google account disconnected", Toast.LENGTH_SHORT).show()
    }

    private suspend fun authorizeForUpload(): String? {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DriveAuth.DRIVE_FILE_SCOPE)))
            .build()
        val result = authorizationClient.authorize(request).await()
        if (result.hasResolution()) return null
        return result.accessToken
    }
}
