package com.dropp.dropp.services

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.dropp.dropp.core.networking.CommandTransferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class RemoteSearchResult(
    val id: String,
    val name: String,
    val size: Long,
    val type: String
) {
    val formattedSize: String
        get() {
            if (size < 1024) return "$size B"
            if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0)
            if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024.0))
            return String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
        }
}

object SearchManager {
    private const val TAG = "SearchManager"

    private val _remoteResults = MutableStateFlow<List<RemoteSearchResult>>(emptyList())
    val remoteResults = _remoteResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError = _searchError.asStateFlow()

    private val _lastSearchQuery = MutableStateFlow("")
    val lastSearchQuery = _lastSearchQuery.asStateFlow()

    private var currentRequestId: String? = null

    fun searchRemote(context: Context, query: String, fileType: String = "ALL"): String {
        val requestId = UUID.randomUUID().toString()
        currentRequestId = requestId
        _lastSearchQuery.value = query
        _isSearching.value = true
        _searchError.value = null

        val req = JSONObject().apply {
            put("type", "SEARCH_REQ")
            put("Type", "SEARCH_REQ")
            put("query", query)
            put("Query", query)
            put("fileType", fileType)
            put("FileType", fileType)
            put("requestId", requestId)
            put("RequestId", requestId)
        }

        Log.d(TAG, "Dispatching SEARCH_REQ to Windows: query='$query', fileType='$fileType', id='$requestId'")

        CoroutineScope(Dispatchers.IO).launch {
            val sent = CommandTransferManager.sendCommandSync(context, req.toString())
            if (!sent) {
                Log.w(TAG, "Failed to send SEARCH_REQ to Windows PC (unreachable).")
                _isSearching.value = false
                _searchError.value = "Windows PC unreachable. Ensure PC is running Dropp on the same network."
                return@launch
            }

            // Launch 8-second timeout guard
            delay(8000)
            if (_isSearching.value && currentRequestId == requestId) {
                Log.w(TAG, "Search request $requestId timed out without response from Windows PC.")
                _isSearching.value = false
                _searchError.value = "Search timed out. No response received from PC."
            }
        }

        return requestId
    }

    fun requestRemoteStream(context: Context, fileId: String, isPreview: Boolean) {
        val streamReq = JSONObject().apply {
            put("type", "STREAM_REQ")
            put("Type", "STREAM_REQ")
            put("id", fileId)
            put("Id", fileId)
            put("isPreview", isPreview)
            put("IsPreview", isPreview)
        }

        Log.d(TAG, "Dispatching STREAM_REQ to Windows: id='$fileId', isPreview=$isPreview")
        CommandTransferManager.sendCommand(context, streamReq.toString())
    }

    fun handleSearchRequest(context: Context, json: JSONObject) {
        val query = json.optString("query").ifEmpty { json.optString("Query") }
        val fileType = (json.optString("fileType").ifEmpty { json.optString("FileType") }).uppercase().ifEmpty { "ALL" }
        val requestId = json.optString("requestId").ifEmpty { json.optString("RequestId") }

        Log.d(TAG, "Handling incoming SEARCH_REQ: query='$query', fileType='$fileType', id='$requestId'")
        if (query.isBlank()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val matches = searchMediaStore(context, query, fileType)
                val resultsArray = JSONArray()

                for (item in matches) {
                    val obj = JSONObject().apply {
                        put("id", item.id)
                        put("name", item.name)
                        put("size", item.size)
                        put("type", item.type)
                    }
                    resultsArray.put(obj)
                }

                val res = JSONObject().apply {
                    put("type", "SEARCH_RES")
                    put("Type", "SEARCH_RES")
                    put("requestId", requestId)
                    put("RequestId", requestId)
                    put("results", resultsArray)
                    put("Results", resultsArray)
                }

                Log.d(TAG, "Dispatching SEARCH_RES to Windows (${matches.size} items) for id='$requestId'")
                CommandTransferManager.sendCommand(context, res.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Error executing searchMediaStore: ${e.message}", e)
            }
        }
    }

    fun handleSearchResponse(json: JSONObject) {
        try {
            val requestId = json.optString("requestId").ifEmpty { json.optString("RequestId") }
            val resultsArray = json.optJSONArray("results") ?: json.optJSONArray("Results")

            val list = mutableListOf<RemoteSearchResult>()
            if (resultsArray != null) {
                for (i in 0 until resultsArray.length()) {
                    val item = resultsArray.optJSONObject(i) ?: continue
                    val id = item.optString("id").ifEmpty { item.optString("Id") }
                    val name = item.optString("name").ifEmpty { item.optString("Name") }
                    val size = item.optLong("size", item.optLong("Size", 0L))
                    val type = (item.optString("type").ifEmpty { item.optString("Type") }).uppercase().ifEmpty { "FILE" }

                    list.add(RemoteSearchResult(id = id, name = name, size = size, type = type))
                }
            }

            Log.d(TAG, "Received SEARCH_RES from Windows: ${list.size} items for id='$requestId'")
            currentRequestId = null
            _remoteResults.value = list
            _isSearching.value = false
            _searchError.value = null
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing SEARCH_RES: ${e.message}", e)
            _isSearching.value = false
        }
    }

    private fun searchMediaStore(context: Context, query: String, filterType: String): List<RemoteSearchResult> {
        val matches = mutableListOf<RemoteSearchResult>()
        val seenIds = mutableSetOf<String>()
        val maxResults = 50

        // 1. Query MediaStore.Files (comprehensive content resolver)
        val collections = mutableListOf<Uri>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            collections.add(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL))
            collections.add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            collections.add(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
            collections.add(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
            collections.add(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
        } else {
            collections.add(MediaStore.Files.getContentUri("external"))
            collections.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            collections.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            collections.add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        }

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE
        )

        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        for (collectionUri in collections) {
            if (matches.size >= maxResults) break
            try {
                context.contentResolver.query(
                    collectionUri,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                    val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                    val mimeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)

                    while (cursor.moveToNext() && matches.size < maxResults) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: continue
                        val size = cursor.getLong(sizeCol)
                        val mime = cursor.getString(mimeCol) ?: ""

                        val cat = getCategory(name, mime)
                        if (matchesFilter(cat, filterType)) {
                            val contentUri = ContentUris.withAppendedId(collectionUri, id).toString()
                            if (seenIds.add(contentUri) && seenIds.add(name)) {
                                matches.add(RemoteSearchResult(id = contentUri, name = name, size = size, type = cat))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Query error for $collectionUri: ${e.message}")
            }
        }

        // 2. Fallback: Search common public storage directories directly for non-indexed files
        try {
            val publicDirs = listOfNotNull(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            )

            for (dir in publicDirs) {
                if (matches.size >= maxResults) break
                if (dir.exists() && dir.isDirectory) {
                    searchDirectFolder(dir, query, filterType, matches, seenIds, maxResults, depth = 0)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct directory search error: ${e.message}")
        }

        return matches
    }

    private fun searchDirectFolder(
        folder: File,
        query: String,
        filterType: String,
        matches: MutableList<RemoteSearchResult>,
        seenIds: MutableSet<String>,
        maxResults: Int,
        depth: Int
    ) {
        if (depth > 2 || matches.size >= maxResults) return
        try {
            val files = folder.listFiles() ?: return
            for (file in files) {
                if (matches.size >= maxResults) break
                if (file.isDirectory) {
                    if (!file.name.startsWith(".")) {
                        searchDirectFolder(file, query, filterType, matches, seenIds, maxResults, depth + 1)
                    }
                } else if (file.isFile) {
                    if (file.name.contains(query, ignoreCase = true) && !file.name.startsWith(".")) {
                        val cat = getCategory(file.name, "")
                        if (matchesFilter(cat, filterType)) {
                            val fileUri = Uri.fromFile(file).toString()
                            if (seenIds.add(fileUri) && seenIds.add(file.name)) {
                                matches.add(RemoteSearchResult(id = fileUri, name = file.name, size = file.length(), type = cat))
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun matchesFilter(category: String, filter: String): Boolean {
        if (filter.equals("ALL", ignoreCase = true)) return true
        return category.equals(filter, ignoreCase = true)
    }

    fun getCategory(fileName: String, mimeType: String): String {
        val lowerMime = mimeType.lowercase()
        val ext = fileName.substringAfterLast('.', "").lowercase()

        return when {
            lowerMime.startsWith("image/") || ext in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg") -> "IMAGE"
            lowerMime.startsWith("video/") || ext in listOf("mp4", "mkv", "mov", "avi", "webm", "3gp") -> "VIDEO"
            lowerMime.startsWith("audio/") || ext in listOf("mp3", "wav", "m4a", "flac", "aac", "ogg") -> "AUDIO"
            lowerMime == "application/pdf" || ext == "pdf" -> "PDF"
            lowerMime.contains("document") || lowerMime.contains("text/") || ext in listOf("doc", "docx", "txt", "rtf", "odt", "xls", "xlsx", "ppt", "pptx", "csv", "md") -> "DOC"
            else -> "FILE"
        }
    }
}
