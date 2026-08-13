package dev.gr0mi4.ohealthinsights.drive

import android.content.Context
import android.content.SharedPreferences
import dev.gr0mi4.ohealthinsights.BuildConfig

data class DriveSettings(
    val autoUploadEnabled: Boolean = false,
    val driveAuthorizationGranted: Boolean = false,
    val rootFolderName: String = "OHealth Insights",
    val reportsFolderName: String = "Reports",
    val archiveFolderName: String = "Archive",
    val reportFileTemplate: String = "ohealth-report-{date}.md",
    val csvFileTemplate: String = "ohealth-metrics-{date}.csv",
    val rawFileTemplate: String = "ohealth-raw-{syncMode}-{timestamp}.ndjson.gz",
    val updateLatestReport: Boolean = true,
    val latestReportName: String = "ohealth-latest-report.md",
    val latestCsvName: String = "ohealth-latest-metrics.csv",
    val googleAccountEmail: String? = null,
    val rootFolderId: String? = null,
    val reportsFolderId: String? = null,
    val archiveFolderId: String? = null,
    val latestReportFileId: String? = null,
    val latestCsvFileId: String? = null,
)

class DriveSettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): DriveSettings = DriveSettings(
        autoUploadEnabled = prefs.getBoolean(KEY_AUTO_UPLOAD, false),
        driveAuthorizationGranted = prefs.getBoolean(
            KEY_DRIVE_AUTHORIZATION_GRANTED,
            !prefs.getString(KEY_ACCOUNT_EMAIL, null).isNullOrBlank(),
        ),
        rootFolderName = prefs.getString(KEY_ROOT_FOLDER, "OHealth Insights") ?: "OHealth Insights",
        reportsFolderName = prefs.getString(KEY_REPORTS_FOLDER, "Reports") ?: "Reports",
        archiveFolderName = prefs.getString(KEY_ARCHIVE_FOLDER, "Archive") ?: "Archive",
        reportFileTemplate = prefs.getString(KEY_REPORT_TEMPLATE, "ohealth-report-{date}.md")
            ?: "ohealth-report-{date}.md",
        csvFileTemplate = prefs.getString(KEY_CSV_TEMPLATE, "ohealth-metrics-{date}.csv")
            ?: "ohealth-metrics-{date}.csv",
        rawFileTemplate = prefs.getString(
            KEY_RAW_TEMPLATE,
            "ohealth-raw-{syncMode}-{timestamp}.ndjson.gz",
        ) ?: "ohealth-raw-{syncMode}-{timestamp}.ndjson.gz",
        updateLatestReport = prefs.getBoolean(KEY_UPDATE_LATEST, true),
        latestReportName = prefs.getString(KEY_LATEST_REPORT, "ohealth-latest-report.md")
            ?: "ohealth-latest-report.md",
        latestCsvName = prefs.getString(KEY_LATEST_CSV, "ohealth-latest-metrics.csv")
            ?: "ohealth-latest-metrics.csv",
        googleAccountEmail = prefs.getString(KEY_ACCOUNT_EMAIL, null),
        rootFolderId = prefs.getString(KEY_ROOT_FOLDER_ID, null),
        reportsFolderId = prefs.getString(KEY_REPORTS_FOLDER_ID, null),
        archiveFolderId = prefs.getString(KEY_ARCHIVE_FOLDER_ID, null),
        latestReportFileId = prefs.getString(KEY_LATEST_REPORT_ID, null),
        latestCsvFileId = prefs.getString(KEY_LATEST_CSV_ID, null),
    )

    fun save(settings: DriveSettings) {
        prefs.edit()
            .putBoolean(KEY_AUTO_UPLOAD, settings.autoUploadEnabled)
            .putBoolean(KEY_DRIVE_AUTHORIZATION_GRANTED, settings.driveAuthorizationGranted)
            .putString(KEY_ROOT_FOLDER, settings.rootFolderName)
            .putString(KEY_REPORTS_FOLDER, settings.reportsFolderName)
            .putString(KEY_ARCHIVE_FOLDER, settings.archiveFolderName)
            .putString(KEY_REPORT_TEMPLATE, settings.reportFileTemplate)
            .putString(KEY_CSV_TEMPLATE, settings.csvFileTemplate)
            .putString(KEY_RAW_TEMPLATE, settings.rawFileTemplate)
            .putBoolean(KEY_UPDATE_LATEST, settings.updateLatestReport)
            .putString(KEY_LATEST_REPORT, settings.latestReportName)
            .putString(KEY_LATEST_CSV, settings.latestCsvName)
            .putString(KEY_ACCOUNT_EMAIL, settings.googleAccountEmail)
            .putString(KEY_ROOT_FOLDER_ID, settings.rootFolderId)
            .putString(KEY_REPORTS_FOLDER_ID, settings.reportsFolderId)
            .putString(KEY_ARCHIVE_FOLDER_ID, settings.archiveFolderId)
            .putString(KEY_LATEST_REPORT_ID, settings.latestReportFileId)
            .putString(KEY_LATEST_CSV_ID, settings.latestCsvFileId)
            .apply()
    }

    fun updateFolderIds(
        rootFolderId: String?,
        reportsFolderId: String?,
        archiveFolderId: String?,
    ) {
        prefs.edit()
            .putString(KEY_ROOT_FOLDER_ID, rootFolderId)
            .putString(KEY_REPORTS_FOLDER_ID, reportsFolderId)
            .putString(KEY_ARCHIVE_FOLDER_ID, archiveFolderId)
            .apply()
    }

    fun updateLatestFileIds(reportFileId: String?, csvFileId: String?) {
        prefs.edit()
            .putString(KEY_LATEST_REPORT_ID, reportFileId)
            .putString(KEY_LATEST_CSV_ID, csvFileId)
            .apply()
    }

    fun clearAccount() {
        prefs.edit()
            .remove(KEY_ACCOUNT_EMAIL)
            .remove(KEY_DRIVE_AUTHORIZATION_GRANTED)
            .remove(KEY_ROOT_FOLDER_ID)
            .remove(KEY_REPORTS_FOLDER_ID)
            .remove(KEY_ARCHIVE_FOLDER_ID)
            .remove(KEY_LATEST_REPORT_ID)
            .remove(KEY_LATEST_CSV_ID)
            .apply()
    }

    fun isConfigured(): Boolean = BuildConfig.DRIVE_OAUTH_CLIENT_ID.isNotBlank()

    companion object {
        private const val PREFS_NAME = "ohealth_drive_settings"

        private const val KEY_AUTO_UPLOAD = "auto_upload"
        private const val KEY_DRIVE_AUTHORIZATION_GRANTED = "drive_authorization_granted"
        private const val KEY_ROOT_FOLDER = "root_folder_name"
        private const val KEY_REPORTS_FOLDER = "reports_folder_name"
        private const val KEY_ARCHIVE_FOLDER = "archive_folder_name"
        private const val KEY_REPORT_TEMPLATE = "report_template"
        private const val KEY_CSV_TEMPLATE = "csv_template"
        private const val KEY_RAW_TEMPLATE = "raw_template"
        private const val KEY_UPDATE_LATEST = "update_latest"
        private const val KEY_LATEST_REPORT = "latest_report_name"
        private const val KEY_LATEST_CSV = "latest_csv_name"
        private const val KEY_ACCOUNT_EMAIL = "account_email"
        private const val KEY_ROOT_FOLDER_ID = "root_folder_id"
        private const val KEY_REPORTS_FOLDER_ID = "reports_folder_id"
        private const val KEY_ARCHIVE_FOLDER_ID = "archive_folder_id"
        private const val KEY_LATEST_REPORT_ID = "latest_report_file_id"
        private const val KEY_LATEST_CSV_ID = "latest_csv_file_id"
    }
}

data class NamingContext(
    val syncMode: String,
    val exportedAt: java.time.Instant,
    val appVersion: String,
) {
    fun apply(template: String): String {
        val zone = java.time.ZoneOffset.UTC
        val date = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone)
            .format(exportedAt)
        val timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(zone)
            .format(exportedAt)
        return template
            .replace("{date}", date)
            .replace("{timestamp}", timestamp)
            .replace("{syncMode}", syncMode)
            .replace("{version}", appVersion)
    }
}

fun DriveSettings.withResolvedNames(context: NamingContext): ResolvedDriveNames = ResolvedDriveNames(
    reportFileName = context.apply(reportFileTemplate),
    csvFileName = context.apply(csvFileTemplate),
    rawFileName = context.apply(rawFileTemplate),
    latestReportName = latestReportName,
    latestCsvName = latestCsvName,
)

data class ResolvedDriveNames(
    val reportFileName: String,
    val csvFileName: String,
    val rawFileName: String,
    val latestReportName: String,
    val latestCsvName: String,
)

data class DriveUploadResult(
    val rootFolderId: String,
    val reportsFolderId: String,
    val archiveFolderId: String,
    val uploadedFiles: List<String>,
    val latestReportFileId: String?,
    val latestCsvFileId: String?,
)
