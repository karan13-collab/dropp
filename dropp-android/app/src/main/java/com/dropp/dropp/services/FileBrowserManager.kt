package com.dropp.dropp.services

import android.content.Context
import android.os.Environment
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

data class RemoteBrowserItem(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val type: String = if (isDirectory) "FOLDER" else "FILE",
    val modified: Long = 0L,
    val itemCount: Int = 0
) {
    val formattedSize: String
        get() {
            if (isDirectory) return if (itemCount > 0) "$itemCount item${if (itemCount > 1) "s" else ""}" else "Folder"
            if (size < 1024) return "$size B"
            if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0)
            if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024.0))
            return String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
        }
}

object FileBrowserManager {
    private const val TAG = "FileBrowserManager"

    private val _currentPath = MutableStateFlow("ROOT")
    val currentPath = _currentPath.asStateFlow()

    private val _parentPath = MutableStateFlow<String?>(null)
    val parentPath = _parentPath.asStateFlow()

    private val _currentItems = MutableStateFlow<List<RemoteBrowserItem>>(emptyList())
    val currentItems = _currentItems.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _browserError = MutableStateFlow<String?>(null)
    val browserError = _browserError.asStateFlow()

    private var currentRequestId: String? = null

    fun browseRemote(context: Context, path: String = "ROOT"): String {
        val requestId = UUID.randomUUID().toString()
        currentRequestId = requestId
        _isLoading.value = true
        _browserError.value = null

        val req = JSONObject().apply {
            put("type", "BROWSE_REQ")
            put("Type", "BROWSE_REQ")
            put("path", path)
            put("Path", path)
            put("requestId", requestId)
            put("RequestId", requestId)
        }

        Log.d(TAG, "Dispatching BROWSE_REQ to Windows PC for path='$path', requestId='$requestId'")

        CoroutineScope(Dispatchers.IO).launch {
            val sent = CommandTransferManager.sendCommandSync(context, req.toString())
            if (!sent) {
                Log.w(TAG, "Failed to send BROWSE_REQ to Windows (unreachable).")
                _isLoading.value = false
                _browserError.value = "Windows PC unreachable. Ensure PC is running Dropp on the same network."
                return@launch
            }

            // Launch 8-second timeout guard
            delay(8000)
            if (_isLoading.value && currentRequestId == requestId) {
                Log.w(TAG, "Browse request $requestId timed out without response from Windows PC.")
                _isLoading.value = false
                _browserError.value = "Directory request timed out. No response received from PC."
            }
        }

        return requestId
    }

    fun handleBrowseRequest(context: Context, json: JSONObject) {
        val path = json.optString("path").ifEmpty { json.optString("Path") }
        val requestId = json.optString("requestId").ifEmpty { json.optString("RequestId") }

        Log.d(TAG, "Handling incoming BROWSE_REQ: path='$path', requestId='$requestId'")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val itemsArray = JSONArray()
                var currentPathResolved = path
                var parentPathResolved: String? = null

                if (path.isEmpty() || path.equals("ROOT", ignoreCase = true)) {
                    currentPathResolved = "ROOT"
                    parentPathResolved = null

                    // Return Virtual Android Library Roots
                    val libraryFolders = listOf(
                        Triple("Downloads", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FOLDER"),
                        Triple("Camera & DCIM", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "FOLDER"),
                        Triple("Pictures", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "FOLDER"),
                        Triple("Documents", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "FOLDER"),
                        Triple("Music", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "FOLDER"),
                        Triple("Movies & Videos", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "FOLDER"),
                        Triple("Internal Storage", Environment.getExternalStorageDirectory(), "FOLDER")
                    )

                    for ((label, dir, type) in libraryFolders) {
                        if (dir != null && (dir.exists() || dir.mkdirs())) {
                            val count = dir.listFiles()?.count { !it.name.startsWith(".") } ?: 0
                            val obj = JSONObject().apply {
                                put("id", dir.absolutePath)
                                put("name", label)
                                put("isDirectory", true)
                                put("size", 0L)
                                put("type", type)
                                put("modified", dir.lastModified())
                                put("itemCount", count)
                            }
                            itemsArray.put(obj)
                        }
                    }
                } else {
                    val resolvedDir = when (path.trim().uppercase()) {
                        "DOWNLOADS" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        "DCIM", "CAMERA" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                        "PICTURES" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        "DOCUMENTS" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                        "MUSIC" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                        "MOVIES", "VIDEOS" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                        "INTERNAL" -> Environment.getExternalStorageDirectory()
                        else -> File(path)
                    }
                    val folder = resolvedDir ?: File(path)
                    if (folder.exists() && folder.isDirectory) {
                        currentPathResolved = folder.absolutePath
                        val parentFile = folder.parentFile
                        parentPathResolved = if (parentFile != null && parentFile.canRead() && parentFile.absolutePath.length >= 8) {
                            parentFile.absolutePath
                        } else {
                            "ROOT"
                        }

                        val children = folder.listFiles() ?: emptyArray()

                        // Sort folders first alphabetically, then files alphabetically
                        val sorted = children.filter { !it.name.startsWith(".") }
                            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

                        for (file in sorted.take(200)) {
                            val isDir = file.isDirectory
                            val count = if (isDir) (file.listFiles()?.count { !it.name.startsWith(".") } ?: 0) else 0
                            val cat = if (isDir) "FOLDER" else SearchManager.getCategory(file.name, "")

                            val obj = JSONObject().apply {
                                put("id", file.absolutePath)
                                put("name", file.name)
                                put("isDirectory", isDir)
                                put("size", if (isDir) 0L else file.length())
                                put("type", cat)
                                put("modified", file.lastModified())
                                put("itemCount", count)
                            }
                            itemsArray.put(obj)
                        }
                    } else {
                        Log.w(TAG, "Requested folder does not exist or is not a directory: $path")
                        currentPathResolved = "ROOT"
                        parentPathResolved = null
                    }
                }

                val res = JSONObject().apply {
                    put("type", "BROWSE_RES")
                    put("Type", "BROWSE_RES")
                    put("requestId", requestId)
                    put("RequestId", requestId)
                    put("currentPath", currentPathResolved)
                    put("CurrentPath", currentPathResolved)
                    put("parentPath", parentPathResolved ?: "")
                    put("ParentPath", parentPathResolved ?: "")
                    put("items", itemsArray)
                }

                Log.d(TAG, "Dispatching BROWSE_RES to Windows (${itemsArray.length()} items) for requestId='$requestId'")
                CommandTransferManager.sendCommand(context, res.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Error handling BROWSE_REQ: ${e.message}", e)
            }
        }
    }

    fun handleBrowseResponse(json: JSONObject) {
        try {
            val requestId = json.optString("requestId").ifEmpty { json.optString("RequestId") }
            val currentPath = json.optString("currentPath").ifEmpty { json.optString("CurrentPath") }
            val parentPathRaw = json.optString("parentPath").ifEmpty { json.optString("ParentPath") }
            val parentPath = if (parentPathRaw.isBlank() || parentPathRaw.equals("null", ignoreCase = true)) null else parentPathRaw
            val itemsArray = json.optJSONArray("items") ?: json.optJSONArray("Items")

            val list = mutableListOf<RemoteBrowserItem>()
            if (itemsArray != null) {
                for (i in 0 until itemsArray.length()) {
                    val item = itemsArray.optJSONObject(i) ?: continue
                    val id = item.optString("id").ifEmpty { item.optString("Id") }
                    val name = item.optString("name").ifEmpty { item.optString("Name") }
                    val isDir = item.optBoolean("isDirectory", item.optBoolean("IsDirectory", false))
                    val size = item.optLong("size", item.optLong("Size", 0L))
                    val type = (item.optString("type").ifEmpty { item.optString("Type") }).uppercase().ifEmpty { if (isDir) "FOLDER" else "FILE" }
                    val modified = item.optLong("modified", item.optLong("Modified", 0L))
                    val count = item.optInt("itemCount", item.optInt("ItemCount", 0))

                    list.add(
                        RemoteBrowserItem(
                            id = id,
                            name = name,
                            isDirectory = isDir,
                            size = size,
                            type = type,
                            modified = modified,
                            itemCount = count
                        )
                    )
                }
            }

            Log.d(TAG, "Received BROWSE_RES from Windows: ${list.size} items for path='$currentPath'")
            currentRequestId = null
            _currentPath.value = currentPath
            _parentPath.value = parentPath
            _currentItems.value = list
            _isLoading.value = false
            _browserError.value = null
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing BROWSE_RES: ${e.message}", e)
            _isLoading.value = false
        }
    }
}
