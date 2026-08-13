package dev.gr0mi4.ohealthinsights.drive

import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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

        onProgress("Uploading dated report")
        uploadTextFile(
            name = names.reportFileName,
            mimeType = "text/markdown",
            parentId = reportsId,
            content = reportMarkdown,
        ).also { uploaded += names.reportFileName }

        onProgress("Uploading dated metrics CSV")
        uploadTextFile(
            name = names.csvFileName,
            mimeType = "text/csv",
            parentId = reportsId,
            content = csvContent,
        ).also { uploaded += names.csvFileName }

        var latestReportId: String? = null
        var latestCsvId: String? = null
        if (settings.updateLatestReport) {
            onProgress("Updating latest report files")
            latestReportId = upsertTextFile(
                name = names.latestReportName,
                mimeType = "text/markdown",
                parentId = reportsId,
                content = reportMarkdown,
                existingFileId = settings.latestReportFileId,
            ).also { uploaded += names.latestReportName }

            latestCsvId = upsertTextFile(
                name = names.latestCsvName,
                mimeType = "text/csv",
                parentId = reportsId,
                content = csvContent,
                existingFileId = settings.latestCsvFileId,
            ).also { uploaded += names.latestCsvName }
        }

        return DriveUploadResult(
            rootFolderId = rootId,
            reportsFolderId = reportsId,
            archiveFolderId = archiveId,
            uploadedFiles = uploaded,
            latestReportFileId = latestReportId,
            latestCsvFileId = latestCsvId,
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

    private fun findFileByName(name: String, parentId: String): JSONObject? {
        val query = "name='${escapeQuery(name)}' and '$parentId' in parents and trashed=false"
        return listFiles(query).firstOrNull()
    }

    private fun listFiles(query: String): List<JSONObject> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val request = Request.Builder()
            .url("$BASE/files?q=$encoded&spaces=drive&fields=files(id,name,mimeType)")
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
