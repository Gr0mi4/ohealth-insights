package dev.gr0mi4.ohealthinsights.drive

import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class DriveClient(
    private val accessToken: String,
    private val httpClient: OkHttpClient = sharedClient,
) {
    suspend fun uploadSyncBundle(
        settings: DriveSettings,
        names: ResolvedDriveNames,
        rawFile: File,
        reportMarkdown: String,
        csvContent: String,
        changeLogCsv: String,
        onProgress: (String) -> Unit = {},
    ): DriveUploadResult {
        onProgress("Ensuring Drive folders")
        val rootId = ensureFolder(settings.rootFolderName, settings.rootFolderId, parentId = null)
        val reportsId = ensureFolder(settings.reportsFolderName, settings.reportsFolderId, parentId = rootId)
        val archiveId = ensureFolder(settings.archiveFolderName, settings.archiveFolderId, parentId = rootId)

        val uploaded = mutableListOf<String>()

        onProgress("Uploading raw export to Archive")
        uploadFile(
            name = names.rawFileName,
            mimeType = "application/gzip",
            parentId = archiveId,
            source = rawFile,
            resumable = rawFile.length() > MULTIPART_LIMIT_BYTES,
        ).also { uploaded += names.rawFileName }

        // Nothing ever removed an export, so the folder grew without limit in the user's own Drive.
        // Trimmed after the upload, never before, so a failed upload cannot cost an older export.
        onProgress("Trimming Archive")
        runCatching { trimFolder(archiveId, keep = ARCHIVE_KEEP) }

        // One cumulative file rather than a dated copy per sync. The dated copies each held the
        // same ninety-day window, overlapping by eighty-nine days, so a year of them was 32,850
        // rows describing 365 days and a year-over-year comparison meant stitching them by hand.
        onProgress("Uploading metrics")
        val csvId = upsertTextFile(
            name = names.latestCsvName,
            mimeType = "text/csv",
            parentId = reportsId,
            content = csvContent,
            existingFileId = settings.latestCsvFileId,
        ).also { uploaded += names.latestCsvName }

        onProgress("Uploading report")
        val reportId = upsertTextFile(
            name = names.latestReportName,
            mimeType = "text/markdown",
            parentId = reportsId,
            content = reportMarkdown,
            existingFileId = settings.latestReportFileId,
        ).also { uploaded += names.latestReportName }

        if (changeLogCsv.isNotBlank()) {
            onProgress("Uploading change log")
            upsertTextFile(
                name = names.changeLogName,
                mimeType = "text/csv",
                parentId = reportsId,
                content = changeLogCsv,
                existingFileId = null,
            ).also { uploaded += names.changeLogName }
        }

        onProgress("Rotating metrics snapshots")
        runCatching { rotateSnapshots(archiveId, csvContent) }

        return DriveUploadResult(
            rootFolderId = rootId,
            reportsFolderId = reportsId,
            archiveFolderId = archiveId,
            uploadedFiles = uploaded,
            latestReportFileId = reportId,
            latestCsvFileId = csvId,
        )
    }

    // Creating a folder only proves metadata access, so the probe also writes a real
    // file: that is the operation that actually fails when uploads are misconfigured.
    fun testConnection(settings: DriveSettings): DriveConnectionCheck {
        val rootId = ensureFolder(settings.rootFolderName, settings.rootFolderId, parentId = null)
        val reportsId = ensureFolder(settings.reportsFolderName, settings.reportsFolderId, parentId = rootId)
        val archiveId = ensureFolder(settings.archiveFolderName, settings.archiveFolderId, parentId = rootId)
        upsertTextFile(
            name = CONNECTION_PROBE_NAME,
            mimeType = "text/plain",
            parentId = rootId,
            content = "OHealth Insights write check at ${java.time.Instant.now()}\n",
            existingFileId = null,
        )
        return DriveConnectionCheck(
            rootFolderId = rootId,
            reportsFolderId = reportsId,
            archiveFolderId = archiveId,
            message = "Connected. Folders ready and $CONNECTION_PROBE_NAME written to " +
                "${settings.rootFolderName}.",
        )
    }

    private fun ensureFolder(name: String, cachedId: String?, parentId: String?): String {
        if (!cachedId.isNullOrBlank()) {
            if (fileExists(cachedId)) return cachedId
        }
        val parentClause = parentId?.let { " and '$it' in parents" } ?: " and 'root' in parents"
        val query = "name='${escapeQuery(name)}' and mimeType='application/vnd.google-apps.folder' " +
            "and trashed=false$parentClause"
        listFiles(query).firstOrNull()?.let { return it.getString("id") }

        val metadata = JSONObject().apply {
            put("name", name)
            put("mimeType", "application/vnd.google-apps.folder")
            parentId?.let { put("parents", JSONArray().put(it)) }
        }
        return createFile(metadata).getString("id")
    }

    private fun upsertTextFile(
        name: String,
        mimeType: String,
        parentId: String,
        content: String,
        existingFileId: String?,
    ): String {
        if (!existingFileId.isNullOrBlank() && fileExists(existingFileId)) {
            return updateTextFile(existingFileId, mimeType, content)
        }
        val existing = findFileByName(name, parentId)
        if (existing != null) {
            return updateTextFile(existing.getString("id"), mimeType, content)
        }
        return uploadTextFile(name, mimeType, parentId, content)
    }

    private fun uploadTextFile(
        name: String,
        mimeType: String,
        parentId: String,
        content: String,
    ): String {
        val metadata = JSONObject().apply {
            put("name", name)
            put("parents", JSONArray().put(parentId))
        }
        return uploadMultipart(metadata, mimeType, content.toByteArray(Charsets.UTF_8))
    }

    private fun updateTextFile(fileId: String, mimeType: String, content: String): String {
        // Replacing file content must go through the upload endpoint; the plain
        // /drive/v3 endpoint only accepts JSON metadata and rejects the body.
        val request = Request.Builder()
            .url("$UPLOAD/$fileId?uploadType=media")
            .patch(content.toRequestBody(mimeType.toMediaType()))
            .header("Authorization", authHeader())
            .build()
        execute(request)
        return fileId
    }

    private fun uploadFile(
        name: String,
        mimeType: String,
        parentId: String,
        source: File,
        resumable: Boolean,
    ): String {
        val metadata = JSONObject().apply {
            put("name", name)
            put("parents", JSONArray().put(parentId))
        }
        return if (resumable) {
            uploadResumable(metadata, mimeType, source)
        } else {
            uploadMultipart(metadata, mimeType, source.readBytes())
        }
    }

    private fun uploadMultipart(metadata: JSONObject, mimeType: String, bytes: ByteArray): String {
        val boundary = "ohealth_${System.currentTimeMillis()}"
        val body = buildMultipartBody(boundary, metadata, mimeType, bytes)
        val request = Request.Builder()
            .url("$UPLOAD?uploadType=multipart")
            .post(body.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
            .header("Authorization", authHeader())
            .build()
        return JSONObject(execute(request)).getString("id")
    }

    private fun uploadResumable(metadata: JSONObject, mimeType: String, source: File): String {
        val startRequest = Request.Builder()
            .url("$UPLOAD?uploadType=resumable")
            .post(metadata.toString().toRequestBody(JSON_MEDIA))
            .header("Authorization", authHeader())
            .header("X-Upload-Content-Type", mimeType)
            .header("X-Upload-Content-Length", source.length().toString())
            .build()
        val startResponse = httpClient.newCall(startRequest).execute()
        startResponse.use { response ->
            if (!response.isSuccessful) throw driveError("resumable start", response)
            val uploadUrl = response.header("Location")
                ?: error("Drive resumable upload did not return a Location header.")
            val uploadRequest = Request.Builder()
                .url(uploadUrl)
                .put(source.asRequestBody(mimeType.toMediaType()))
                .header("Authorization", authHeader())
                .build()
            return JSONObject(execute(uploadRequest)).getString("id")
        }
    }

    private fun createFile(metadata: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(BASE + "/files")
            .post(metadata.toString().toRequestBody(JSON_MEDIA))
            .header("Authorization", authHeader())
            .build()
        return JSONObject(execute(request))
    }

    /**
     * Keeps three generations of the metrics file: about a day, a week and a month old.
     *
     * Refreshed by age rather than on every sync, which is the whole point. Snapshotting each time
     * would copy a bad write into all three within three syncs; ageing them out means the monthly
     * copy predates anything noticed within a month. Promotion runs oldest first so no generation
     * is skipped, and each copy is made before the older one is removed.
     */
    private fun rotateSnapshots(archiveId: String, currentCsv: String) {
        val present = listFiles(
            query = "'${escapeQuery(archiveId)}' in parents and trashed=false",
            fields = "files(id,name,modifiedTime)",
        ).associateBy { it.optString("name") }

        val now = Instant.now()
        fun ageDays(name: String): Long? = present[name]?.optString("modifiedTime")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Duration.between(Instant.parse(it), now).toDays() }.getOrNull() }

        fun due(name: String, days: Long): Boolean = (ageDays(name) ?: Long.MAX_VALUE) >= days

        if (due(SNAPSHOT_MONTHLY, 30)) {
            present[SNAPSHOT_WEEKLY]?.let { source ->
                copyFile(source.getString("id"), SNAPSHOT_MONTHLY, archiveId)
                present[SNAPSHOT_MONTHLY]?.let { runCatching { deleteFile(it.getString("id")) } }
            }
        }
        if (due(SNAPSHOT_WEEKLY, 7)) {
            present[SNAPSHOT_DAILY]?.let { source ->
                copyFile(source.getString("id"), SNAPSHOT_WEEKLY, archiveId)
                present[SNAPSHOT_WEEKLY]?.let { runCatching { deleteFile(it.getString("id")) } }
            }
        }
        if (due(SNAPSHOT_DAILY, 1)) {
            upsertTextFile(
                name = SNAPSHOT_DAILY,
                mimeType = "text/csv",
                parentId = archiveId,
                content = currentCsv,
                existingFileId = present[SNAPSHOT_DAILY]?.getString("id"),
            )
        }
    }

    private fun copyFile(fileId: String, newName: String, parentId: String): String {
        val metadata = JSONObject().apply {
            put("name", newName)
            put("parents", JSONArray().put(parentId))
        }
        val request = Request.Builder()
            .url("$BASE/files/$fileId/copy?fields=id")
            .post(metadata.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", authHeader())
            .build()
        return JSONObject(execute(request)).getString("id")
    }

    /**
     * Rotates the incremental exports, keeping the [keep] most recent, and never touches a full one.
     *
     * The files in here are not equivalent. An incremental export holds only what changed since the
     * last checkpoint - a day's worth, and the next full sync reproduces it from Health Connect. An
     * initial or diagnostic export is a complete snapshot of everything at that moment, which is the
     * only record of what the data looked like before a source app revised or removed it. Rotating
     * by age alone would evict exactly those first.
     */
    private fun trimFolder(parentId: String, keep: Int) {
        val files = listFiles(
            query = "'${escapeQuery(parentId)}' in parents and trashed=false",
            fields = "files(id,name,createdTime)",
            orderBy = "createdTime desc",
        )
        files.filterNot { isFullExport(it.optString("name")) || isSnapshot(it.optString("name")) }
            .drop(keep)
            .forEach { file -> runCatching { deleteFile(file.getString("id")) } }
    }

    private fun isFullExport(name: String): Boolean =
        FULL_EXPORT_MARKERS.any { name.contains(it) }

    private fun isSnapshot(name: String): Boolean =
        name == SNAPSHOT_DAILY || name == SNAPSHOT_WEEKLY || name == SNAPSHOT_MONTHLY

    private fun deleteFile(fileId: String) {
        val request = Request.Builder()
            .url("$BASE/files/$fileId")
            .delete()
            .header("Authorization", authHeader())
            .build()
        execute(request)
    }

    private fun findFileByName(name: String, parentId: String): JSONObject? {
        val query = "name='${escapeQuery(name)}' and '$parentId' in parents and trashed=false"
        return listFiles(query).firstOrNull()
    }

    private fun listFiles(
        query: String,
        fields: String = "files(id,name,mimeType)",
        orderBy: String? = null,
    ): List<JSONObject> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val encodedFields = URLEncoder.encode(fields, StandardCharsets.UTF_8)
        val order = orderBy?.let { "&orderBy=" + URLEncoder.encode(it, StandardCharsets.UTF_8) }.orEmpty()
        val request = Request.Builder()
            .url("$BASE/files?q=$encoded&spaces=drive&fields=$encodedFields&pageSize=1000$order")
            .get()
            .header("Authorization", authHeader())
            .build()
        val response = JSONObject(execute(request))
        val files = response.optJSONArray("files") ?: JSONArray()
        return buildList {
            for (index in 0 until files.length()) add(files.getJSONObject(index))
        }
    }

    private fun fileExists(fileId: String): Boolean {
        val request = Request.Builder()
            .url("$BASE/files/$fileId?fields=id,trashed")
            .get()
            .header("Authorization", authHeader())
            .build()
        return runCatching {
            val json = JSONObject(execute(request))
            !json.optBoolean("trashed", false)
        }.getOrDefault(false)
    }

    private fun authHeader(): String = "Bearer $accessToken"

    private fun execute(request: Request): String {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Drive API ${request.method} ${request.url} failed: HTTP ${response.code} $body")
            }
            return body
        }
    }

    private fun driveError(stage: String, response: okhttp3.Response): IOException {
        val body = response.body?.string().orEmpty()
        return IOException("Drive $stage failed: HTTP ${response.code} $body")
    }

    private fun buildMultipartBody(
        boundary: String,
        metadata: JSONObject,
        mimeType: String,
        bytes: ByteArray,
    ): ByteArray {
        val lineEnd = "\r\n"
        val builder = StringBuilder()
        builder.append("--").append(boundary).append(lineEnd)
        builder.append("Content-Type: application/json; charset=UTF-8").append(lineEnd).append(lineEnd)
        builder.append(metadata.toString()).append(lineEnd)
        builder.append("--").append(boundary).append(lineEnd)
        builder.append("Content-Type: ").append(mimeType).append(lineEnd).append(lineEnd)
        val prefix = builder.toString().toByteArray(Charsets.UTF_8)
        val suffix = ("$lineEnd--$boundary--$lineEnd").toByteArray(Charsets.UTF_8)
        return prefix + bytes + suffix
    }

    private fun escapeQuery(value: String): String = value.replace("'", "\\'")

    companion object {
        private const val BASE = "https://www.googleapis.com/drive/v3"

        /** Roughly two months of incremental exports; a full sync reproduces anything older. */
        private const val ARCHIVE_KEEP = 60

        /** Sync modes whose export is a complete snapshot, and so is never rotated away. */
        private val FULL_EXPORT_MARKERS = listOf("initial_compact", "full_diagnostic")

        private const val SNAPSHOT_DAILY = "ohealth-metrics-daily.csv"
        private const val SNAPSHOT_WEEKLY = "ohealth-metrics-weekly.csv"
        private const val SNAPSHOT_MONTHLY = "ohealth-metrics-monthly.csv"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val MULTIPART_LIMIT_BYTES = 5L * 1024 * 1024
        private const val CONNECTION_PROBE_NAME = "ohealth-connection-test.txt"

        // Uploads run over mobile networks and can carry several megabytes, so the
        // stock ten-second OkHttp timeouts are far too aggressive here.
        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()
    }
}
