package com.don194.photoswipe.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.don194.photoswipe.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaStoreRepository(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    // Android Q 引入了具名外部存储卷，旧设备仍需使用兼容 URI。
    private val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    suspend fun loadLibrary(): MediaLibrary = withContext(Dispatchers.IO) {
        val photos = queryPhotos()
        val screenshots = photos.filter(::isScreenshot)
        // bucket_id 是 MediaStore 稳定的相册标识。
        // 截图可能分散在多个目录，因此截图相册是一个虚拟相册。
        val albums = photos
            .groupBy { it.bucketId }
            .map { (bucketId, items) ->
                PhotoAlbum(
                    id = bucketId,
                    name = items.first().bucketName
                        .takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.unnamed_album),
                    count = items.size,
                    sizeBytes = items.sumOf { it.sizeBytes },
                    coverUri = items.firstOrNull()?.uri
                )
            }
            .sortedByDescending { it.count }

        MediaLibrary(
            albums = albums,
            screenshotAlbum = screenshots.takeIf { it.isNotEmpty() }?.let { items ->
                PhotoAlbum(
                    id = SCREENSHOT_ALBUM_ID,
                    name = context.getString(R.string.screenshots),
                    count = items.size,
                    sizeBytes = items.sumOf { it.sizeBytes },
                    coverUri = items.first().uri,
                    isScreenshots = true
                )
            }
        )
    }

    suspend fun loadPhotos(album: PhotoAlbum, order: PhotoLoadOrder): List<PhotoItem> = withContext(Dispatchers.IO) {
        val photos = queryPhotos()
        val albumPhotos = if (album.isScreenshots) {
            photos.filter(::isScreenshot)
        } else {
            photos.filter { it.bucketId == album.id }
        }
        albumPhotos.withLoadOrder(order)
    }

    suspend fun loadPhotosByIds(photoIds: Set<Long>, order: PhotoLoadOrder): List<PhotoItem> = withContext(Dispatchers.IO) {
        if (photoIds.isEmpty()) emptyList() else queryPhotos().filter { it.id in photoIds }.withLoadOrder(order)
    }

    suspend fun existingPhotoIds(photos: List<PhotoItem>): Set<Long> = withContext(Dispatchers.IO) {
        photos.mapNotNullTo(mutableSetOf()) { photo ->
            val exists = resolver.query(
                photo.uri,
                arrayOf(MediaStore.Images.Media._ID),
                null,
                null,
                null
            )?.use { it.moveToFirst() } == true
            photo.id.takeIf { exists }
        }
    }

    private fun queryPhotos(): List<PhotoItem> {
        // 显式声明查询字段，只读取应用实际使用的数据，减少 Cursor 工作量，
        // 也方便检查不同 API 版本的字段差异。
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Images.Media.RELATIVE_PATH)
        }.toTypedArray()
        // 忽略空文件和仍处于待处理状态的图片，避免未完成的媒体进入界面。
        val selection = buildString {
            append("${MediaStore.Images.Media.SIZE} > 0")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                append(" AND ${MediaStore.Images.Media.IS_PENDING} = 0")
            }
        }
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC"

        return resolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val widthIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val dateTakenIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val dateModifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val dateAddedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val bucketIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val pathIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                -1
            }
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    add(
                        PhotoItem(
                            id = id,
                            uri = Uri.withAppendedPath(collection, id.toString()),
                            displayName = cursor.getString(nameIndex) ?: context.getString(R.string.unnamed_photo),
                            width = cursor.getInt(widthIndex),
                            height = cursor.getInt(heightIndex),
                            sizeBytes = cursor.getLong(sizeIndex),
                            // 并非每张导入图片都有 DATE_TAKEN，辅助函数会回退到
                            // 修改时间和添加时间。
                            dateMillis = resolvePhotoDateMillis(
                                dateTakenMillis = cursor.getLong(dateTakenIndex),
                                dateModifiedSeconds = cursor.getLong(dateModifiedIndex),
                                dateAddedSeconds = cursor.getLong(dateAddedIndex)
                            ),
                            bucketId = cursor.getString(bucketIndex) ?: "",
                            bucketName = cursor.getString(bucketNameIndex) ?: "",
                            relativePath = if (pathIndex >= 0) cursor.getString(pathIndex) else null
                        )
                    )
                }
            }.sortedWith(compareBy<PhotoItem> { it.dateMillis }.thenBy { it.id })
        }.orEmpty()
    }

    private fun isScreenshot(photo: PhotoItem): Boolean {
        // 不同厂商的文件名和相对路径可能不同，因此同时检查两者，
        // 并兼容常见的下划线写法。
        val name = photo.displayName.lowercase()
        val path = photo.relativePath?.lowercase().orEmpty()
        return "screenshot" in name || "screen_shot" in name || "screenshot" in path || "screenshots" in path
    }

    private fun List<PhotoItem>.withLoadOrder(order: PhotoLoadOrder): List<PhotoItem> = when (order) {
        PhotoLoadOrder.OldestFirst -> this
        PhotoLoadOrder.NewestFirst -> asReversed()
    }

    private companion object {
        const val SCREENSHOT_ALBUM_ID = "__screenshots__"
    }
}

internal fun resolvePhotoDateMillis(
    dateTakenMillis: Long,
    dateModifiedSeconds: Long,
    dateAddedSeconds: Long
): Long = dateTakenMillis.takeIf { it > 0 }
    // MediaStore 的 DATE_TAKEN 使用毫秒，另外两个字段使用秒，需要统一单位。
    ?: dateModifiedSeconds.takeIf { it > 0 }?.times(1_000L)
    ?: dateAddedSeconds.takeIf { it > 0 }?.times(1_000L)
    ?: 0L
