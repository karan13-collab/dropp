package com.dropp.dropp.services

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import com.dropp.dropp.core.networking.CommandTransferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

data class GalleryAlbum(
    val id: String,
    val name: String,
    val count: Int
)

data class GalleryMediaItem(
    val id: String,
    val name: String,
    val date: Long,
    val size: Long,
    val type: String,
    val width: Int = 0,
    val height: Int = 0,
    val thumbnailB64: String = ""
)

object GalleryManager {
    private const val TAG = "GalleryManager"

    fun handleAlbumsRequest(context: Context, json: JSONObject) {
        val requestId = json.optString("requestId").ifEmpty { json.optString("RequestId") }
        Log.d(TAG, "Handling GALLERY_ALBUMS_REQ: requestId='$requestId'")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val albums = queryAlbums(context)
                val albumsArray = JSONArray()
                for (album in albums) {
                    val obj = JSONObject().apply {
                        put("id", album.id)
                        put("name", album.name)
                        put("count", album.count)
                    }
                    albumsArray.put(obj)
                }

                val res = JSONObject().apply {
                    put("type", "GALLERY_ALBUMS_RES")
                    put("Type", "GALLERY_ALBUMS_RES")
                    put("requestId", requestId)
                    put("RequestId", requestId)
                    put("albums", albumsArray)
                }

                Log.d(TAG, "Dispatching GALLERY_ALBUMS_RES to Windows (${albums.size} albums) for requestId='$requestId'")
                CommandTransferManager.sendCommand(context, res.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Error executing handleAlbumsRequest: ${e.message}", e)
            }
        }
    }

    fun handleGalleryRequest(context: Context, json: JSONObject) {
        val requestId = json.optString("requestId").ifEmpty { json.optString("RequestId") }
        val bucket = (json.optString("bucket").ifEmpty { json.optString("Bucket") }).ifEmpty { "ALL" }
        val limit = if (json.has("limit")) json.optInt("limit", 60) else json.optInt("Limit", 60)
        val offset = if (json.has("offset")) json.optInt("offset", 0) else json.optInt("Offset", 0)

        Log.d(TAG, "Handling GALLERY_REQ: bucket='$bucket', limit=$limit, offset=$offset, requestId='$requestId'")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val items = queryGallery(context, bucket, limit, offset)
                val itemsArray = JSONArray()

                for (item in items) {
                    val obj = JSONObject().apply {
                        put("id", item.id)
                        put("name", item.name)
                        put("date", item.date)
                        put("size", item.size)
                        put("type", item.type)
                        put("width", item.width)
                        put("height", item.height)
                        put("thumbnail", item.thumbnailB64)
                    }
                    itemsArray.put(obj)
                }

                val res = JSONObject().apply {
                    put("type", "GALLERY_RES")
                    put("Type", "GALLERY_RES")
                    put("requestId", requestId)
                    put("RequestId", requestId)
                    put("bucket", bucket)
                    put("Bucket", bucket)
                    put("items", itemsArray)
                }

                Log.d(TAG, "Dispatching GALLERY_RES to Windows (${items.size} media items) for requestId='$requestId'")
                CommandTransferManager.sendCommand(context, res.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Error executing queryGallery: ${e.message}", e)
            }
        }
    }

    private fun queryAlbums(context: Context): List<GalleryAlbum> {
        val albumMap = linkedMapOf<String, Int>()
        var totalCount = 0

        // 1. Query distinct Image buckets
        try {
            val imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val projection = arrayOf(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.BUCKET_DISPLAY_NAME else MediaStore.Images.Media.DATA
            )

            context.contentResolver.query(imageUri, projection, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(projection[0])
                while (cursor.moveToNext()) {
                    val raw = if (col != -1) cursor.getString(col) else null
                    val bucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        raw ?: "Other"
                    } else {
                        raw?.let { File(it).parentFile?.name } ?: "Other"
                    }

                    if (bucket.isNotBlank()) {
                        albumMap[bucket] = (albumMap[bucket] ?: 0) + 1
                        totalCount++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning image albums: ${e.message}", e)
        }

        // 2. Query distinct Video buckets
        try {
            val videoUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val projection = arrayOf(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.BUCKET_DISPLAY_NAME else MediaStore.Video.Media.DATA
            )

            context.contentResolver.query(videoUri, projection, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(projection[0])
                while (cursor.moveToNext()) {
                    val raw = if (col != -1) cursor.getString(col) else null
                    val bucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        raw ?: "Videos"
                    } else {
                        raw?.let { File(it).parentFile?.name } ?: "Videos"
                    }

                    if (bucket.isNotBlank()) {
                        albumMap[bucket] = (albumMap[bucket] ?: 0) + 1
                        totalCount++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning video albums: ${e.message}", e)
        }

        val result = mutableListOf<GalleryAlbum>()
        // Always place "All Media" first
        result.add(GalleryAlbum(id = "ALL", name = "All Media", count = totalCount))

        // Sort: Camera first, Screenshots second, WhatsApp third, Downloads fourth, then remainder by count desc
        val sorted = albumMap.entries.sortedWith(
            Comparator { a, b ->
                fun priority(name: String): Int = when {
                    name.equals("Camera", ignoreCase = true) || name.equals("DCIM", ignoreCase = true) -> 1
                    name.contains("Screenshot", ignoreCase = true) -> 2
                    name.contains("WhatsApp", ignoreCase = true) -> 3
                    name.contains("Download", ignoreCase = true) -> 4
                    name.contains("Instagram", ignoreCase = true) -> 5
                    name.contains("Picture", ignoreCase = true) -> 6
                    else -> 10
                }
                val pA = priority(a.key)
                val pB = priority(b.key)
                if (pA != pB) pA.compareTo(pB)
                else b.value.compareTo(a.value)
            }
        )

        for (entry in sorted) {
            result.add(GalleryAlbum(id = entry.key, name = entry.key, count = entry.value))
        }

        return result
    }

    private fun queryGallery(context: Context, bucket: String, limit: Int, offset: Int): List<GalleryMediaItem> {
        val results = mutableListOf<GalleryMediaItem>()

        val isVideosBucket = bucket.equals("VIDEOS", ignoreCase = true)
        val isAllBucket = bucket.equals("ALL", ignoreCase = true)

        val queryImages = !isVideosBucket
        val queryVideos = isAllBucket || isVideosBucket || bucket.contains("Video", ignoreCase = true)

        var imageSelection: String? = null
        var imageSelectionArgs: Array<String>? = null

        if (!isAllBucket && !isVideosBucket) {
            imageSelection = "${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} = ? OR ${MediaStore.MediaColumns.DATA} LIKE ?"
            imageSelectionArgs = arrayOf(bucket, "%/$bucket/%")
        }

        // 1. Query Images
        if (queryImages) {
            val imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
            )

            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            try {
                context.contentResolver.query(
                    imageUri,
                    projection,
                    imageSelection,
                    imageSelectionArgs,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                    val widthCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                    val heightCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)

                    var count = 0
                    while (cursor.moveToNext() && count < limit) {
                        val mediaId = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "Photo_$mediaId.jpg"
                        val dateSec = cursor.getLong(dateCol)
                        val size = cursor.getLong(sizeCol)
                        val width = if (widthCol != -1) cursor.getInt(widthCol) else 0
                        val height = if (heightCol != -1) cursor.getInt(heightCol) else 0

                        val contentUri = ContentUris.withAppendedId(imageUri, mediaId)
                        val thumbB64 = generateThumbnailBase64(context, contentUri, isVideo = false)

                        results.add(
                            GalleryMediaItem(
                                id = contentUri.toString(),
                                name = name,
                                date = dateSec * 1000L,
                                size = size,
                                type = "IMAGE",
                                width = width,
                                height = height,
                                thumbnailB64 = thumbB64
                            )
                        )
                        count++
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying Images: ${e.message}", e)
            }
        }

        // 2. Query Videos (if requested or in matching bucket)
        if (queryVideos && results.size < limit) {
            val videoUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT
            )

            var videoSelection: String? = null
            var videoSelectionArgs: Array<String>? = null

            if (!isAllBucket && !isVideosBucket) {
                videoSelection = "${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} = ? OR ${MediaStore.MediaColumns.DATA} LIKE ?"
                videoSelectionArgs = arrayOf(bucket, "%/$bucket/%")
            }

            val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

            try {
                context.contentResolver.query(
                    videoUri,
                    projection,
                    videoSelection,
                    videoSelectionArgs,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                    val dateCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                    val widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                    val heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)

                    val remaining = limit - results.size
                    var count = 0
                    while (cursor.moveToNext() && count < remaining) {
                        val mediaId = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "Video_$mediaId.mp4"
                        val dateSec = cursor.getLong(dateCol)
                        val size = cursor.getLong(sizeCol)
                        val width = if (widthCol != -1) cursor.getInt(widthCol) else 0
                        val height = if (heightCol != -1) cursor.getInt(heightCol) else 0

                        val contentUri = ContentUris.withAppendedId(videoUri, mediaId)
                        val thumbB64 = generateThumbnailBase64(context, contentUri, isVideo = true)

                        results.add(
                            GalleryMediaItem(
                                id = contentUri.toString(),
                                name = name,
                                date = dateSec * 1000L,
                                size = size,
                                type = "VIDEO",
                                width = width,
                                height = height,
                                thumbnailB64 = thumbB64
                            )
                        )
                        count++
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying Videos: ${e.message}", e)
            }
        }

        return results.sortedByDescending { it.date }.take(limit)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun generateThumbnailBase64(context: Context, uri: Uri, isVideo: Boolean): String {
        return try {
            var bitmap: Bitmap? = null

            // 1. Primary: loadThumbnail on API 29+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    bitmap = context.contentResolver.loadThumbnail(uri, android.util.Size(200, 200), null)
                } catch (_: Exception) {}
            }

            // 2. Fallback for Images: Open stream and decode with downsampling
            if (bitmap == null && !isVideo) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { stream1 ->
                        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(stream1, null, boundsOpts)
                        val sample = calculateInSampleSize(boundsOpts, 200, 200)

                        context.contentResolver.openInputStream(uri)?.use { stream2 ->
                            val decodeOpts = BitmapFactory.Options().apply {
                                inSampleSize = sample
                                inPreferredConfig = Bitmap.Config.RGB_565
                            }
                            bitmap = BitmapFactory.decodeStream(stream2, null, decodeOpts)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Image stream decode fallback failed for $uri: ${e.message}")
                }
            }

            // 3. Fallback for Videos: Retrieve frame with MediaMetadataRetriever
            if (bitmap == null && isVideo) {
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(context, uri)
                    bitmap = retriever.getFrameAtTime(1000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    retriever.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Video frame fallback failed for $uri: ${e.message}")
                }
            }

            if (bitmap != null) {
                val orig = bitmap!!
                val scaled = if (orig.width > 200 || orig.height > 200) {
                    val ratio = Math.min(200f / orig.width, 200f / orig.height)
                    val targetW = Math.max(1, (orig.width * ratio).toInt())
                    val targetH = Math.max(1, (orig.height * ratio).toInt())
                    Bitmap.createScaledBitmap(orig, targetW, targetH, true)
                } else {
                    orig
                }

                val stream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                val bytes = stream.toByteArray()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } else {
                Log.w(TAG, "Could not produce thumbnail for $uri")
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "generateThumbnailBase64 error for $uri: ${e.message}", e)
            ""
        }
    }
}
