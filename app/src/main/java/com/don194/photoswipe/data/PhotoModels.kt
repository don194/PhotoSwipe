package com.don194.photoswipe.data

import android.net.Uri

/** 定义整理会话中照片的展示顺序。 */
enum class PhotoLoadOrder { OldestFirst, NewestFirst }

/** 相册列表中展示的摘要信息，也用于标识照片来源目录。 */
data class PhotoAlbum(
    val id: String,
    val name: String,
    val count: Int,
    val sizeBytes: Long,
    val coverUri: Uri?,
    val isScreenshots: Boolean = false
)

/** 界面、整理逻辑和删除流程所需的 MediaStore 字段。 */
data class PhotoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val dateMillis: Long,
    val bucketId: String,
    val bucketName: String,
    val relativePath: String?
)

/** 设备图片库的快照，供首页和相册页面使用。 */
data class MediaLibrary(
    val albums: List<PhotoAlbum>,
    val screenshotAlbum: PhotoAlbum?
)
