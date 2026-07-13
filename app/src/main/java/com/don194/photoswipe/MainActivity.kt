package com.don194.photoswipe

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.imageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.don194.photoswipe.data.MediaLibrary
import com.don194.photoswipe.data.MediaStoreRepository
import com.don194.photoswipe.data.PhotoAlbum
import com.don194.photoswipe.data.PhotoItem
import com.don194.photoswipe.data.PhotoLoadOrder
import com.don194.photoswipe.ui.theme.PhotoSwipeTheme
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Compose 负责管理界面树，Activity 只提供应用入口。
        enableEdgeToEdge()
        setContent {
            PhotoSwipeTheme { PhotoSwipeApp() }
        }
    }
}

private enum class AppScreen { PermissionIntro, Home, Settings, KeptPhotos, Albums, Loading, Swipe, Review, Result }
private enum class SwipeChoice { Delete, Keep }
private enum class SwipeHint { Delete, Keep, Neutral }
private enum class DeleteStatus { Success, Cancelled, Failed, Partial }

private data class SwipeHistory(
    val photo: PhotoItem,
    val choice: SwipeChoice,
    val addedToKeptHistory: Boolean = false
)
private data class DeleteResult(val status: DeleteStatus, val deletedCount: Int, val remainingCount: Int)

@Composable
private fun PhotoSwipeApp() {
    val context = LocalContext.current
    val repository = remember { MediaStoreRepository(context.applicationContext) }
    val preferences = remember { PhotoPreferences(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val hasInitialPhotoAccess = remember { hasPhotoAccess(context) }
    // 使用统一的状态机管理页面和当前整理会话，
    // 从子页面返回时不会丢失整理进度。
    var screen by remember {
        mutableStateOf(if (hasInitialPhotoAccess) AppScreen.Loading else AppScreen.PermissionIntro)
    }
    var library by remember { mutableStateOf(MediaLibrary(emptyList(), null)) }
    var selectedAlbum by remember { mutableStateOf<PhotoAlbum?>(null) }
    var photos by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var deleteCandidates by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var history by remember { mutableStateOf<List<SwipeHistory>>(emptyList()) }
    var showExitDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var deleteResult by remember { mutableStateOf<DeleteResult?>(null) }
    var hideKeptPhotos by remember { mutableStateOf(preferences.hideKeptPhotos) }
    var keptPhotoDays by remember { mutableIntStateOf(preferences.keptPhotoDays) }
    var photoLoadOrder by remember { mutableStateOf(preferences.photoLoadOrder) }
    var keptPhotoCount by remember { mutableIntStateOf(preferences.hiddenKeptPhotoIds.size) }
    var keptPhotos by remember { mutableStateOf<List<PhotoItem>?>(null) }
    var selectedKeptAlbumId by remember { mutableStateOf<String?>(null) }
    var cleanedPhotoCount by remember { mutableStateOf(preferences.cleanedPhotoCount) }
    var cleanedBytes by remember { mutableStateOf(preferences.cleanedBytes) }

    LaunchedEffect(hasInitialPhotoAccess) {
        if (hasInitialPhotoAccess) {
            // MediaStore 访问属于阻塞式 I/O，Repository 会切换到 I/O 调度器，
            // 完成查询后再把结果应用到界面状态。
            library = repository.loadLibrary()
            screen = AppScreen.Home
        }
    }

    fun refreshLibrary(nextScreen: AppScreen) {
        scope.launch {
            screen = AppScreen.Loading
            // 删除照片或权限发生变化后重新加载，使相册数量和封面
            // 与 MediaStore 中的实际内容保持一致。
            library = repository.loadLibrary()
            screen = nextScreen
        }
    }

    fun startAlbum(album: PhotoAlbum) {
        selectedAlbum = album
        currentIndex = 0
        deleteCandidates = emptyList()
        history = emptyList()
        scope.launch {
            screen = AppScreen.Loading
            val loadedPhotos = repository.loadPhotos(album, photoLoadOrder)
            // 只有开启设置时才过滤已保留照片；完整列表仍可在已保留照片页面查看。
            photos = if (hideKeptPhotos) {
                val keptIds = preferences.hiddenKeptPhotoIds
                loadedPhotos.filterNot { it.id in keptIds }
            } else {
                loadedPhotos
            }
            screen = AppScreen.Swipe
        }
    }

    fun undoLastChoice() {
        val last = history.lastOrNull() ?: return
        history = history.dropLast(1)
        // 撤销操作需要同时恢复界面状态和已持久化的保留照片状态。
        if (last.choice == SwipeChoice.Delete) {
            deleteCandidates = deleteCandidates.filterNot { it.id == last.photo.id }
        } else if (last.addedToKeptHistory && preferences.removeKeptPhoto(last.photo.id)) {
            keptPhotoCount = (keptPhotoCount - 1).coerceAtLeast(0)
        }
        currentIndex = (currentIndex - 1).coerceAtLeast(0)
    }

    fun recordCleanup(deletedPhotos: List<PhotoItem>) {
        if (deletedPhotos.isEmpty()) return
        val deletedBytes = deletedPhotos.sumOf { it.sizeBytes }
        preferences.recordCleanup(deletedPhotos.size, deletedBytes)
        cleanedPhotoCount += deletedPhotos.size
        cleanedBytes += deletedBytes
    }

    fun finishDeleteRequest(resultCode: Int) {
        val requested = pendingDelete
        pendingDelete = emptyList()
        if (resultCode != Activity.RESULT_OK) {
            // Android 11 及以上版本会显示系统确认对话框。
            // 非 OK 结果表示用户取消了操作，而不是删除发生了部分失败。
            deleteResult = DeleteResult(DeleteStatus.Cancelled, 0, requested.size)
            screen = AppScreen.Result
            return
        }
        scope.launch {
            // 系统确认后重新查询这些 URI，避免媒体提供者保留照片或照片已提前消失时
            // 仍将其计入删除成功数量。
            val existingIds = repository.existingPhotoIds(requested)
            val deletedPhotos = requested.filterNot { it.id in existingIds }
            val deleted = deletedPhotos.size
            val remaining = requested.size - deleted
            recordCleanup(deletedPhotos)
            deleteResult = when {
                remaining == 0 -> DeleteResult(DeleteStatus.Success, deleted, 0)
                deleted == 0 -> DeleteResult(DeleteStatus.Failed, 0, remaining)
                else -> DeleteResult(DeleteStatus.Partial, deleted, remaining)
            }
            screen = AppScreen.Result
        }
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
        onResult = { finishDeleteRequest(it.resultCode) }
    )
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { refreshLibrary(AppScreen.Home) }
    )
    val requestPermissions = { permissionLauncher.launch(photoPermissions()) }
    val requestDelete: () -> Unit = {
        pendingDelete = deleteCandidates
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                // Android 11 及以上版本删除非应用自身创建的媒体时，
                // 必须通过 MediaStore API 请求用户确认。
                val request = MediaStore.createDeleteRequest(context.contentResolver, pendingDelete.map { it.uri })
                deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } catch (_: SecurityException) {
                pendingDelete = emptyList()
                deleteResult = DeleteResult(DeleteStatus.Failed, 0, deleteCandidates.size)
                screen = AppScreen.Result
            }
        } else {
            scope.launch {
                val deletedPhotos = mutableListOf<PhotoItem>()
                // 较早版本的 Android 没有 createDeleteRequest，
                // 因此逐个删除 URI，并将失败情况报告为部分成功。
                pendingDelete.forEach { photo ->
                    try {
                        if (context.contentResolver.delete(photo.uri, null, null) > 0) {
                            deletedPhotos += photo
                        }
                    } catch (_: SecurityException) {
                        // 下面的结果状态会报告系统无法删除的照片。
                    }
                }
                val deleted = deletedPhotos.size
                val remaining = pendingDelete.size - deleted
                pendingDelete = emptyList()
                recordCleanup(deletedPhotos)
                deleteResult = when {
                    remaining == 0 -> DeleteResult(DeleteStatus.Success, deleted, 0)
                    deleted == 0 -> DeleteResult(DeleteStatus.Failed, 0, remaining)
                    else -> DeleteResult(DeleteStatus.Partial, deleted, remaining)
                }
                screen = AppScreen.Result
            }
        }
    }

    BackHandler(
        enabled = !showExitDialog && screen != AppScreen.Home && screen != AppScreen.PermissionIntro
    ) {
        // 返回键在当前流程内导航；正在进行滑动整理时先请求确认，
        // 因为当前会话中可能包含尚未执行的删除决定。
        when (screen) {
            AppScreen.Settings, AppScreen.Albums -> screen = AppScreen.Home
            AppScreen.KeptPhotos -> {
                if (selectedKeptAlbumId == null) screen = AppScreen.Settings else selectedKeptAlbumId = null
            }
            AppScreen.Swipe -> showExitDialog = true
            AppScreen.Review -> {
                if (currentIndex >= photos.size) undoLastChoice()
                screen = AppScreen.Swipe
            }
            AppScreen.Result -> refreshLibrary(AppScreen.Home)
            AppScreen.Loading -> Unit
            AppScreen.Home, AppScreen.PermissionIntro -> Unit
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (screen) {
            AppScreen.PermissionIntro -> PermissionIntroScreen(onAllow = requestPermissions, onSkip = { screen = AppScreen.Home })
            AppScreen.Home -> HomeScreen(
                screenshotAlbum = library.screenshotAlbum,
                hasAccess = hasPhotoAccess(context),
                cleanedPhotoCount = cleanedPhotoCount,
                cleanedBytes = cleanedBytes,
                onRequestPermission = requestPermissions,
                onStartScreenshots = { library.screenshotAlbum?.let(::startAlbum) },
                onChooseAlbum = { if (hasPhotoAccess(context)) refreshLibrary(AppScreen.Albums) else requestPermissions() },
                onSettings = {
                    keptPhotoCount = preferences.hiddenKeptPhotoIds.size
                    screen = AppScreen.Settings
                }
            )
            AppScreen.Settings -> SettingsScreen(
                hideKeptPhotos = hideKeptPhotos,
                keptPhotoDays = keptPhotoDays,
                keptPhotoCount = keptPhotoCount,
                photoLoadOrder = photoLoadOrder,
                onHideKeptPhotosChange = {
                    hideKeptPhotos = it
                    preferences.hideKeptPhotos = it
                },
                onKeptPhotoDaysChange = {
                    keptPhotoDays = it
                    preferences.keptPhotoDays = it
                    keptPhotoCount = preferences.hiddenKeptPhotoIds.size
                },
                onPhotoLoadOrderChange = {
                    photoLoadOrder = it
                    preferences.photoLoadOrder = it
                },
                onViewKeptPhotos = {
                    screen = AppScreen.KeptPhotos
                    keptPhotos = null
                    selectedKeptAlbumId = null
                    scope.launch {
                        keptPhotos = runCatching {
                            repository.loadPhotosByIds(preferences.hiddenKeptPhotoIds, photoLoadOrder)
                        }.getOrDefault(emptyList())
                    }
                },
                onBack = { screen = AppScreen.Home }
            )
            AppScreen.KeptPhotos -> KeptPhotosScreen(
                keptPhotos = keptPhotos,
                selectedAlbumId = selectedKeptAlbumId,
                onSelectAlbum = { selectedKeptAlbumId = it },
                onClearKeptPhotos = {
                    preferences.clearKeptPhotos()
                    keptPhotoCount = 0
                    keptPhotos = emptyList()
                    selectedKeptAlbumId = null
                },
                onRemoveKeptPhoto = { photo ->
                    if (preferences.removeKeptPhoto(photo.id)) {
                        keptPhotoCount = (keptPhotoCount - 1).coerceAtLeast(0)
                        keptPhotos = keptPhotos?.filterNot { it.id == photo.id }
                    }
                },
                onBack = {
                    if (selectedKeptAlbumId == null) screen = AppScreen.Settings else selectedKeptAlbumId = null
                }
            )
            AppScreen.Albums -> AlbumListScreen(library.albums, onBack = { screen = AppScreen.Home }, onAlbumClick = ::startAlbum)
            AppScreen.Loading -> LoadingScreen(selectedAlbum?.name)
            AppScreen.Swipe -> selectedAlbum?.let { album ->
                SwipeScreen(
                    album = album,
                    photos = photos,
                    currentIndex = currentIndex,
                    deleteCount = deleteCandidates.size,
                    deleteSizeBytes = deleteCandidates.sumOf { it.sizeBytes },
                    canUndo = history.isNotEmpty(),
                    lastSwipe = history.lastOrNull(),
                    onSwipe = { photo, choice ->
                        // 待删除照片在确认页面前只保存在内存中；
                        // 保留照片会立即持久化，以便后续整理会话将其隐藏。
                        if (choice == SwipeChoice.Delete) {
                            history = history + SwipeHistory(photo, choice)
                            deleteCandidates = deleteCandidates + photo
                        } else {
                            val addedToKeptHistory = preferences.addKeptPhoto(photo.id)
                            history = history + SwipeHistory(photo, choice, addedToKeptHistory)
                            if (addedToKeptHistory) keptPhotoCount += 1
                        }
                        currentIndex += 1
                        if (currentIndex >= photos.size) screen = AppScreen.Review
                    },
                    onUndo = ::undoLastChoice,
                    onExit = { showExitDialog = true },
                    onFinish = { screen = AppScreen.Review }
                )
            }
            AppScreen.Review -> selectedAlbum?.let { album ->
                ReviewDeleteScreen(
                    album = album, candidates = deleteCandidates,
                    onBackToSwipe = {
                        if (currentIndex >= photos.size) undoLastChoice()
                        screen = AppScreen.Swipe
                    },
                    onRemoveCandidate = { photo -> deleteCandidates = deleteCandidates.filterNot { it.id == photo.id } },
                    onDelete = requestDelete,
                    onCancel = { screen = AppScreen.Home }
                )
            }
            AppScreen.Result -> DeleteResultScreen(
                result = deleteResult ?: DeleteResult(DeleteStatus.Failed, 0, 0),
                onRetry = requestDelete,
                onHome = { refreshLibrary(AppScreen.Home) },
                onAnotherAlbum = { refreshLibrary(AppScreen.Albums) }
            )
        }

        if (showExitDialog) {
            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                title = { Text(stringResource(R.string.exit_session)) },
                text = { Text(stringResource(R.string.exit_session_message)) },
                confirmButton = { TextButton(onClick = { showExitDialog = false; screen = AppScreen.Home }) { Text(stringResource(R.string.exit)) } },
                dismissButton = { TextButton(onClick = { showExitDialog = false }) { Text(stringResource(R.string.continue_sorting)) } }
            )
        }
    }
}

@Composable
private fun PermissionIntroScreen(onAllow: () -> Unit, onSkip: () -> Unit) {
    PhotoSwipeScaffold {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                AppHeader(stringResource(R.string.app_name), stringResource(R.string.local_photo_cleanup))
                StatusIllustration(stringResource(R.string.permission_title))
                Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.permission_message), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAllow, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 16.dp)) { Text(stringResource(R.string.allow_photo_access)) }
                TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.not_now)) }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    screenshotAlbum: PhotoAlbum?,
    hasAccess: Boolean,
    cleanedPhotoCount: Long,
    cleanedBytes: Long,
    onRequestPermission: () -> Unit,
    onStartScreenshots: () -> Unit,
    onChooseAlbum: () -> Unit,
    onSettings: () -> Unit
) {
    PhotoSwipeScaffold {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    AppHeader(
                        stringResource(R.string.app_name),
                        stringResource(R.string.swipe_instruction),
                        Modifier.weight(1f).padding(top = 16.dp)
                    )
                    TextButton(onClick = onSettings) { Text(stringResource(R.string.settings)) }
                }
                Text(stringResource(R.string.cleanup_screenshots), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.cleanup_message), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hasAccess) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard(stringResource(R.string.screenshot_count), screenshotAlbum?.count?.toString() ?: "0", Modifier.weight(1f))
                        StatCard(stringResource(R.string.storage_used), formatBytes(screenshotAlbum?.sizeBytes ?: 0), Modifier.weight(1f))
                    }
                } else {
                    StatusMessage(stringResource(R.string.permission_required), stringResource(R.string.permission_required_message))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(stringResource(R.string.photos_cleaned), cleanedPhotoCount.toString(), Modifier.weight(1f))
                    StatCard(stringResource(R.string.space_reclaimed), formatBytes(cleanedBytes), Modifier.weight(1f))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!hasAccess) {
                    Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 16.dp)) { Text(stringResource(R.string.allow_photo_access)) }
                } else {
                    Button(onClick = onStartScreenshots, modifier = Modifier.fillMaxWidth(), enabled = screenshotAlbum != null, contentPadding = PaddingValues(vertical = 16.dp)) { Text(stringResource(R.string.start_screenshot_cleanup)) }
                    FilledTonalButton(onClick = onChooseAlbum, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 16.dp)) { Text(stringResource(R.string.choose_another_album)) }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    hideKeptPhotos: Boolean,
    keptPhotoDays: Int,
    keptPhotoCount: Int,
    photoLoadOrder: PhotoLoadOrder,
    onHideKeptPhotosChange: (Boolean) -> Unit,
    onKeptPhotoDaysChange: (Int) -> Unit,
    onPhotoLoadOrderChange: (PhotoLoadOrder) -> Unit,
    onViewKeptPhotos: () -> Unit,
    onBack: () -> Unit
) {
    var showDurationMenu by remember { mutableStateOf(false) }
    var showLoadOrderMenu by remember { mutableStateOf(false) }
    PhotoSwipeScaffold {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TopBar(stringResource(R.string.settings), stringResource(R.string.back), onLeadingClick = onBack)
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.photo_load_order),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Box {
                OutlinedButton(onClick = { showLoadOrderMenu = true }) {
                    Text(photoLoadOrderLabel(photoLoadOrder))
                }
                DropdownMenu(expanded = showLoadOrderMenu, onDismissRequest = { showLoadOrderMenu = false }) {
                    PhotoLoadOrder.entries.forEach { order ->
                        DropdownMenuItem(
                            text = { Text(photoLoadOrderLabel(order)) },
                            onClick = {
                                onPhotoLoadOrderChange(order)
                                showLoadOrderMenu = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.hide_kept_photos_temporarily), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.hide_kept_photos_temporarily_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(16.dp))
                Switch(checked = hideKeptPhotos, onCheckedChange = onHideKeptPhotosChange)
            }
            if (hideKeptPhotos) {
                Spacer(Modifier.height(28.dp))
                Text(
                    stringResource(R.string.kept_photo_duration),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Box {
                    OutlinedButton(onClick = { showDurationMenu = true }) {
                        Text(keptDurationLabel(keptPhotoDays))
                    }
                    DropdownMenu(expanded = showDurationMenu, onDismissRequest = { showDurationMenu = false }) {
                        KEPT_DAY_OPTIONS.forEach { days ->
                            DropdownMenuItem(
                                text = { Text(keptDurationLabel(days)) },
                                onClick = {
                                    onKeptPhotoDaysChange(days)
                                    showDurationMenu = false
                                }
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.kept_photo_count, keptPhotoCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = onViewKeptPhotos, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.view_hidden_kept_photos))
                }
            }
        }
    }
}

@Composable
private fun KeptPhotosScreen(
    keptPhotos: List<PhotoItem>?,
    selectedAlbumId: String?,
    onSelectAlbum: (String) -> Unit,
    onClearKeptPhotos: () -> Unit,
    onRemoveKeptPhoto: (PhotoItem) -> Unit,
    onBack: () -> Unit
) {
    var selectedPhoto by remember { mutableStateOf<PhotoItem?>(null) }
    val unnamedAlbumName = stringResource(R.string.unnamed_album)
    val albums = keptPhotos
        ?.groupBy { it.bucketId }
        ?.map { (id, photos) ->
            KeptPhotoAlbum(
                id = id,
                name = photos.first().bucketName.ifBlank { unnamedAlbumName },
                photos = photos
            )
        }
        ?.sortedBy { it.name.lowercase() }
        .orEmpty()
    val selectedAlbum = albums.firstOrNull { it.id == selectedAlbumId }

    PhotoSwipeScaffold {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                selectedAlbum?.name ?: stringResource(R.string.hidden_kept_photos),
                stringResource(R.string.back),
                onLeadingClick = onBack
            )
            Spacer(Modifier.height(14.dp))
            when {
                keptPhotos == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                keptPhotos.isEmpty() -> Text(
                    stringResource(R.string.no_kept_photos),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                selectedAlbum == null -> {
                    OutlinedButton(onClick = onClearKeptPhotos) { Text(stringResource(R.string.clear_kept_history)) }
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(albums, key = { it.id }) { album ->
                            KeptPhotoAlbumRow(album) { onSelectAlbum(album.id) }
                        }
                    }
                }
                else -> PagedPhotoGrid(
                    photos = selectedAlbum.photos,
                    modifier = Modifier.weight(1f),
                    onPhotoClick = { selectedPhoto = it }
                )
            }
        }
    }

    selectedPhoto?.let { photo ->
        PhotoActionDialog(
            photo = photo,
            actionLabel = stringResource(R.string.remove_from_kept),
            onAction = {
                onRemoveKeptPhoto(photo)
                selectedPhoto = null
            },
            onDismiss = { selectedPhoto = null }
        )
    }
}

@Composable
private fun keptDurationLabel(days: Int): String = when (days) {
    1 -> stringResource(R.string.duration_1_day)
    3 -> stringResource(R.string.duration_3_days)
    7 -> stringResource(R.string.duration_7_days)
    15 -> stringResource(R.string.duration_15_days)
    30 -> stringResource(R.string.duration_30_days)
    else -> stringResource(R.string.duration_forever)
}

@Composable
private fun AlbumListScreen(albums: List<PhotoAlbum>, onBack: () -> Unit, onAlbumClick: (PhotoAlbum) -> Unit) {
    PhotoSwipeScaffold {
        Column(Modifier.fillMaxSize()) {
            TopBar(stringResource(R.string.choose_album), stringResource(R.string.back), onLeadingClick = onBack)
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(albums, key = { it.id }) { AlbumRow(it) { onAlbumClick(it) } }
            }
        }
    }
}

@Composable
private fun LoadingScreen(albumName: String?) {
    PhotoSwipeScaffold {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.loading_album, albumName ?: stringResource(R.string.app_name)))
            Text(stringResource(R.string.loading_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwipeScreen(
    album: PhotoAlbum,
    photos: List<PhotoItem>,
    currentIndex: Int,
    deleteCount: Int,
    deleteSizeBytes: Long,
    canUndo: Boolean,
    lastSwipe: SwipeHistory?,
    onSwipe: (PhotoItem, SwipeChoice) -> Unit,
    onUndo: () -> Unit,
    onExit: () -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val currentPhoto = photos.getOrNull(currentIndex)
    val nextPhoto = photos.getOrNull(currentIndex + 1)
    val progress = if (photos.isEmpty()) 0f else currentIndex.toFloat() / photos.size
    val imageWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val imageHeightPx = with(density) { (configuration.screenHeightDp.dp * 0.72f).roundToPx() }
    val dismissDistancePx = imageWidthPx * 1.6f
    var swipeHint by remember(currentPhoto?.id) { mutableStateOf(SwipeHint.Neutral) }
    var isImageZoomed by remember(currentPhoto?.id) { mutableStateOf(false) }
    var exitSequence by remember { mutableIntStateOf(0) }
    var exitingCards by remember { mutableStateOf<List<ExitingCard>>(emptyList()) }
    var undoEntry by remember { mutableStateOf<UndoEntry?>(null) }
    var furthestPrefetchedIndex by remember(photos) {
        mutableIntStateOf(minOf(PREFETCH_COUNT, photos.lastIndex))
    }

    // 提前解码前几张卡片，减少快速滑动时的空白画面；
    // 后续照片会在下面的逻辑中逐步加入预加载队列。
    LaunchedEffect(photos, imageWidthPx, imageHeightPx) {
        photos.drop(1).take(PREFETCH_COUNT).forEach { photo ->
            context.imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(photo.uri)
                    .size(imageWidthPx, imageHeightPx)
                    .build()
            )
        }
    }

    LaunchedEffect(photos, currentIndex, imageWidthPx, imageHeightPx) {
        val targetIndex = minOf(currentIndex + PREFETCH_COUNT, photos.lastIndex)
        if (currentIndex > 0 && targetIndex > furthestPrefetchedIndex) {
            (furthestPrefetchedIndex + 1..targetIndex).forEach { index ->
                context.imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(photos[index].uri)
                        .size(imageWidthPx, imageHeightPx)
                        .build()
                )
            }
            furthestPrefetchedIndex = targetIndex
        }
    }

    PhotoSwipeScaffold {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TopBar(album.name, stringResource(R.string.close), "${(currentIndex + 1).coerceAtMost(photos.size)}/${photos.size}", onExit)
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
            AssistPill(
                stringResource(R.string.to_delete_with_size, deleteCount, formatBytes(deleteSizeBytes)),
                Modifier.fillMaxWidth().height(SWIPE_INFO_ROW_HEIGHT)
            )
            if (currentPhoto == null) {
                EmptyCard(onFinish)
            } else {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    nextPhoto?.let {
                        PhotoCard(it, Modifier.fillMaxSize(), imageWidthPx, imageHeightPx)
                    }
                    SwipeablePhotoCard(
                        photo = currentPhoto,
                        modifier = Modifier.fillMaxSize(),
                        imageWidthPx = imageWidthPx,
                        imageHeightPx = imageHeightPx,
                        initialOffsetPx = undoEntry
                            ?.takeIf { it.photoId == currentPhoto.id }
                            ?.startOffsetPx
                            ?: 0f,
                        onSwipeHintChange = { swipeHint = it },
                        onZoomStateChange = { isImageZoomed = it }
                    ) { choice, releaseOffset ->
                        exitSequence += 1
                        exitingCards = exitingCards + ExitingCard(
                            token = exitSequence,
                            photo = currentPhoto,
                            initialOffsetPx = releaseOffset,
                            targetOffsetPx = if (choice == SwipeChoice.Delete) -dismissDistancePx else dismissDistancePx
                        )
                        onSwipe(currentPhoto, choice)
                    }
                    exitingCards.forEach { exiting ->
                        ExitingPhotoCard(
                            exiting = exiting,
                            imageWidthPx = imageWidthPx,
                            imageHeightPx = imageHeightPx,
                            onFinished = {
                                exitingCards = exitingCards.filterNot { it.token == exiting.token }
                            }
                        )
                    }
                    SwipeHintPill(
                        hint = swipeHint,
                        isImageZoomed = isImageZoomed,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        lastSwipe?.let { last ->
                            val startOffset = if (last.choice == SwipeChoice.Delete) -dismissDistancePx else dismissDistancePx
                            undoEntry = UndoEntry(last.photo.id, startOffset)
                            exitingCards = exitingCards.filterNot { it.photo.id == last.photo.id }
                            onUndo()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = canUndo
                ) { Text(stringResource(R.string.undo)) }
                FilledTonalButton(onClick = onFinish, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.finish)) }
            }
        }
    }
}

@Composable
private fun SwipeablePhotoCard(
    photo: PhotoItem,
    modifier: Modifier = Modifier,
    imageWidthPx: Int,
    imageHeightPx: Int,
    initialOffsetPx: Float,
    onSwipeHintChange: (SwipeHint) -> Unit,
    onZoomStateChange: (Boolean) -> Unit,
    onSwipe: (SwipeChoice, Float) -> Unit
) {
    val scope = rememberCoroutineScope()
    var offsetX by remember(photo.id) { mutableFloatStateOf(initialOffsetPx) }
    var entryAnimationEnabled by remember(photo.id) { mutableStateOf(initialOffsetPx != 0f) }
    var imageScale by remember(photo.id) { mutableFloatStateOf(1f) }
    var imagePanX by remember(photo.id) { mutableFloatStateOf(0f) }
    var imagePanY by remember(photo.id) { mutableFloatStateOf(0f) }
    fun applyTransform(zoomChange: Float, panChange: Offset) {
        // 平移范围根据当前缩放比例计算，保证图片边缘留在卡片内，
        // 避免用户将图片拖到空白区域。
        val newScale = (imageScale * zoomChange).coerceIn(MIN_IMAGE_SCALE, MAX_IMAGE_SCALE)
        val maxPanX = imageWidthPx * (newScale - 1f) / 2f
        val maxPanY = imageHeightPx * (newScale - 1f) / 2f
        imageScale = newScale
        imagePanX = (imagePanX + panChange.x).coerceIn(-maxPanX, maxPanX)
        imagePanY = (imagePanY + panChange.y).coerceIn(-maxPanY, maxPanY)
        if (newScale == MIN_IMAGE_SCALE) {
            imagePanX = 0f
            imagePanY = 0f
        }
    }

    fun finishSwipe() {
        // 短距离拖动会回弹；只有水平移动超过阈值才记录选择，
        // 避免因轻微移动误触删除。
        val choice = when {
            offsetX <= -SWIPE_THRESHOLD_PX -> SwipeChoice.Delete
            offsetX >= SWIPE_THRESHOLD_PX -> SwipeChoice.Keep
            else -> null
        }
        if (choice == null) {
            scope.launch {
                animate(offsetX, 0f, animationSpec = spring()) { value, _ -> offsetX = value }
            }
        } else {
            onSwipe(choice, offsetX)
        }
    }

    LaunchedEffect(photo.id) {
        if (initialOffsetPx != 0f) {
            animate(initialOffsetPx, 0f, animationSpec = spring()) { value, _ ->
                if (entryAnimationEnabled) offsetX = value
            }
            entryAnimationEnabled = false
        }
    }
    val swipeHint by remember(photo.id) {
        derivedStateOf {
            when {
                offsetX < -80f -> SwipeHint.Delete
                offsetX > 80f -> SwipeHint.Keep
                else -> SwipeHint.Neutral
            }
        }
    }
    LaunchedEffect(swipeHint) {
        onSwipeHintChange(swipeHint)
    }
    LaunchedEffect(imageScale > MIN_IMAGE_SCALE) {
        onZoomStateChange(imageScale > MIN_IMAGE_SCALE)
    }
    PhotoCard(
        photo = photo,
        imageWidthPx = imageWidthPx,
        imageHeightPx = imageHeightPx,
        imageScale = imageScale,
        imagePan = Offset(imagePanX, imagePanY),
        modifier = modifier.graphicsLayer {
            translationX = offsetX
            rotationZ = offsetX / 42f
        }.pointerInput(photo.id, imageWidthPx, imageHeightPx) {
            // 单指控制卡片滑动；出现第二根手指后切换到图片变换模式，
            // 此时停止卡片移动。
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                entryAnimationEnabled = false
                var accumulatedDrag = Offset.Zero
                var isSwiping = false
                var isTransforming = imageScale > MIN_IMAGE_SCALE
                var event = awaitPointerEvent()

                do {
                    val pointerCount = event.changes.count { it.pressed || it.previousPressed }
                    if (!isTransforming && pointerCount >= 2) {
                        isTransforming = true
                        isSwiping = false
                        accumulatedDrag = Offset.Zero
                        offsetX = 0f
                    }

                    val pressedChanges = event.changes.filter { it.pressed }
                    if (isTransforming) {
                        when {
                            pressedChanges.size >= 2 -> applyTransform(
                                zoomChange = event.calculateZoom(),
                                panChange = event.calculatePan()
                            )
                            pressedChanges.size == 1 && imageScale > MIN_IMAGE_SCALE -> applyTransform(
                                zoomChange = 1f,
                                panChange = pressedChanges.first().positionChange()
                            )
                        }
                        event.changes.forEach { it.consume() }
                    } else if (pressedChanges.size == 1) {
                        val change = pressedChanges.first()
                        val drag = change.positionChange()
                        accumulatedDrag += drag
                        if (!isSwiping &&
                            abs(accumulatedDrag.x) > viewConfiguration.touchSlop &&
                            abs(accumulatedDrag.x) > abs(accumulatedDrag.y)
                        ) {
                            isSwiping = true
                        }
                        if (isSwiping) {
                            change.consume()
                            offsetX += drag.x
                        }
                    }

                    if (event.changes.any { it.pressed }) {
                        event = awaitPointerEvent()
                    }
                } while (event.changes.any { it.pressed })

                if (!isTransforming && isSwiping) {
                    finishSwipe()
                }
            }
        }.pointerInput(photo.id) {
            detectTapGestures(
                onDoubleTap = { tapPosition ->
                    // 双击以触摸点为中心放大；再次双击会重置缩放和平移，
                    // 确保下一次滑动从初始状态开始。
                    if (imageScale > MIN_IMAGE_SCALE) {
                        imageScale = MIN_IMAGE_SCALE
                        imagePanX = 0f
                        imagePanY = 0f
                    } else {
                        imageScale = DOUBLE_TAP_SCALE
                        val maxPanX = imageWidthPx * (DOUBLE_TAP_SCALE - 1f) / 2f
                        val maxPanY = imageHeightPx * (DOUBLE_TAP_SCALE - 1f) / 2f
                        imagePanX = ((imageWidthPx / 2f - tapPosition.x) * (DOUBLE_TAP_SCALE - 1f))
                            .coerceIn(-maxPanX, maxPanX)
                        imagePanY = ((imageHeightPx / 2f - tapPosition.y) * (DOUBLE_TAP_SCALE - 1f))
                            .coerceIn(-maxPanY, maxPanY)
                    }
                }
            )
        }
    )
}

@Composable
private fun ExitingPhotoCard(
    exiting: ExitingCard,
    imageWidthPx: Int,
    imageHeightPx: Int,
    onFinished: () -> Unit
) {
    var offsetX by remember(exiting.token) { mutableFloatStateOf(exiting.initialOffsetPx) }
    LaunchedEffect(exiting.token) {
        animate(
            initialValue = exiting.initialOffsetPx,
            targetValue = exiting.targetOffsetPx,
            animationSpec = tween(durationMillis = DISMISS_DURATION_MS, easing = FastOutLinearInEasing)
        ) { value, _ -> offsetX = value }
        onFinished()
    }
    PhotoCard(
        photo = exiting.photo,
        modifier = Modifier.fillMaxSize().graphicsLayer {
            translationX = offsetX
            rotationZ = offsetX / 42f
        },
        imageWidthPx = imageWidthPx,
        imageHeightPx = imageHeightPx
    )
}

@Composable
private fun PhotoCard(
    photo: PhotoItem,
    modifier: Modifier = Modifier,
    imageWidthPx: Int,
    imageHeightPx: Int,
    imageScale: Float = MIN_IMAGE_SCALE,
    imagePan: Offset = Offset.Zero
) {
    val context = LocalContext.current
    val previewRequest = remember(photo.uri, imageWidthPx, imageHeightPx) {
        ImageRequest.Builder(context)
            .data(photo.uri)
            .size(imageWidthPx, imageHeightPx)
            .build()
    }
    // 普通请求按照卡片尺寸加载；只有放大时才加载更大的图片，
    // 以控制长时间整理过程中的内存占用。
    val highResolutionTier = if (imageScale > MIN_IMAGE_SCALE) {
        ceil(imageScale).toInt().coerceIn(2, MAX_IMAGE_SCALE.toInt())
    } else {
        null
    }
    val shorterEdge = minOf(photo.width, photo.height)
    val longerEdge = maxOf(photo.width, photo.height)
    // 超长截图即使宽度不大也可能超过普通解码限制，
    // 因此为它们设置更大的、但仍有上限的解码尺寸。
    val decodeLimitPx = if (
        shorterEdge > 0 && longerEdge.toFloat() / shorterEdge >= LONG_SCREEN_ASPECT_RATIO_THRESHOLD
    ) {
        LONG_SCREEN_DECODE_LIMIT_PX
    } else {
        HIGH_RESOLUTION_DECODE_LIMIT_PX
    }
    val highResolutionRequest = remember(
        photo.uri,
        photo.width,
        photo.height,
        imageWidthPx,
        imageHeightPx,
        highResolutionTier,
        decodeLimitPx
    ) {
        highResolutionTier?.let { tier ->
            val targetWidth = minOf(
                imageWidthPx.toLong() * tier,
                decodeLimitPx.toLong(),
                photo.width.takeIf { it > 0 }?.toLong() ?: Long.MAX_VALUE
            ).toInt().coerceAtLeast(1)
            val targetHeight = minOf(
                imageHeightPx.toLong() * tier,
                decodeLimitPx.toLong(),
                photo.height.takeIf { it > 0 }?.toLong() ?: Long.MAX_VALUE
            ).toInt().coerceAtLeast(1)
            ImageRequest.Builder(context)
                .data(photo.uri)
                .size(targetWidth, targetHeight)
                .crossfade(150)
                .build()
        }
    }
    val imageTransformModifier = Modifier.fillMaxSize().graphicsLayer {
        scaleX = imageScale
        scaleY = imageScale
        translationX = imagePan.x
        translationY = imagePan.y
    }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp), elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = previewRequest,
                contentDescription = photo.displayName,
                modifier = imageTransformModifier,
                contentScale = ContentScale.Fit
            )
            highResolutionRequest?.let { request ->
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    modifier = imageTransformModifier,
                    contentScale = ContentScale.Fit
                )
            }
            Surface(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp), color = Color.Black.copy(alpha = 0.68f), contentColor = Color.White, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(photo.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(
                            R.string.photo_detail,
                            photo.width,
                            photo.height,
                            formatBytes(photo.sizeBytes),
                            formatPhotoDate(photo.dateMillis)
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun formatPhotoDate(dateMillis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(dateMillis))

@Composable
private fun photoLoadOrderLabel(order: PhotoLoadOrder): String = when (order) {
    PhotoLoadOrder.OldestFirst -> stringResource(R.string.oldest_photos_first)
    PhotoLoadOrder.NewestFirst -> stringResource(R.string.newest_photos_first)
}

private const val PREFETCH_COUNT = 5
private const val SWIPE_THRESHOLD_PX = 260f
private const val DISMISS_DURATION_MS = 170
private const val MIN_IMAGE_SCALE = 1f
private const val MAX_IMAGE_SCALE = 8f
private const val DOUBLE_TAP_SCALE = 2f
private const val HIGH_RESOLUTION_DECODE_LIMIT_PX = 4096
private const val LONG_SCREEN_DECODE_LIMIT_PX = 15000
private const val LONG_SCREEN_ASPECT_RATIO_THRESHOLD = 7f
private const val KEEP_FOREVER = 0
private val KEPT_DAY_OPTIONS = listOf(1, 3, 7, 15, 30, KEEP_FOREVER)
private val SWIPE_HINT_WIDTH = 164.dp
private val SWIPE_INFO_ROW_HEIGHT = 32.dp

private data class ExitingCard(
    val token: Int,
    val photo: PhotoItem,
    val initialOffsetPx: Float,
    val targetOffsetPx: Float
)

private data class UndoEntry(val photoId: Long, val startOffsetPx: Float)

@Composable
private fun ReviewDeleteScreen(album: PhotoAlbum, candidates: List<PhotoItem>, onBackToSwipe: () -> Unit, onRemoveCandidate: (PhotoItem) -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    var selectedPhoto by remember { mutableStateOf<PhotoItem?>(null) }
    PhotoSwipeScaffold {
        Column(Modifier.fillMaxSize()) {
            TopBar(stringResource(R.string.review), stringResource(R.string.back), onLeadingClick = onBackToSwipe)
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.ready_to_delete, candidates.size), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.review_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            if (candidates.isEmpty()) {
                EmptyReview(album.name)
            } else {
                PagedPhotoGrid(
                    photos = candidates,
                    modifier = Modifier.weight(1f),
                    onPhotoClick = { selectedPhoto = it }
                )
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = onDelete, modifier = Modifier.fillMaxWidth(), enabled = candidates.isNotEmpty(), contentPadding = PaddingValues(vertical = 16.dp)) { Text(stringResource(R.string.confirm_delete, candidates.size)) }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.back_home)) }
        }
    }
    selectedPhoto?.let { photo ->
        PhotoActionDialog(
            photo = photo,
            actionLabel = stringResource(R.string.remove),
            onAction = {
                onRemoveCandidate(photo)
                selectedPhoto = null
            },
            onDismiss = { selectedPhoto = null }
        )
    }
}

@Composable
private fun DeleteResultScreen(result: DeleteResult, onRetry: () -> Unit, onHome: () -> Unit, onAnotherAlbum: () -> Unit) {
    val (title, message) = when (result.status) {
        DeleteStatus.Success -> stringResource(R.string.delete_success, result.deletedCount) to stringResource(R.string.delete_success_message)
        DeleteStatus.Cancelled -> stringResource(R.string.delete_cancelled) to stringResource(R.string.delete_cancelled_message)
        DeleteStatus.Failed -> stringResource(R.string.delete_failed) to stringResource(R.string.delete_failed_message)
        DeleteStatus.Partial -> stringResource(R.string.delete_partial, result.deletedCount, result.remainingCount) to stringResource(R.string.delete_partial_message)
    }
    PhotoSwipeScaffold {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Column(
                Modifier.fillMaxWidth().padding(top = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DeleteStatusBadge(result.status)
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (result.status == DeleteStatus.Cancelled) {
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 16.dp)) {
                        Text(stringResource(R.string.retry_delete))
                    }
                    FilledTonalButton(onClick = onHome, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 16.dp)) {
                        Text(stringResource(R.string.back_home))
                    }
                    TextButton(onClick = onAnotherAlbum, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.clean_another_album))
                    }
                } else {
                    Button(onClick = onHome, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 16.dp)) { Text(stringResource(R.string.back_home)) }
                    FilledTonalButton(onClick = onAnotherAlbum, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 16.dp)) { Text(stringResource(R.string.clean_another_album)) }
                }
            }
        }
    }
}

@Composable
private fun DeleteStatusBadge(status: DeleteStatus) {
    val containerColor = when (status) {
        DeleteStatus.Success -> MaterialTheme.colorScheme.primaryContainer
        DeleteStatus.Partial -> MaterialTheme.colorScheme.tertiaryContainer
        DeleteStatus.Failed -> MaterialTheme.colorScheme.errorContainer
        DeleteStatus.Cancelled -> MaterialTheme.colorScheme.surfaceVariant
    }
    val iconColor = when (status) {
        DeleteStatus.Success -> MaterialTheme.colorScheme.onPrimaryContainer
        DeleteStatus.Partial -> MaterialTheme.colorScheme.onTertiaryContainer
        DeleteStatus.Failed -> MaterialTheme.colorScheme.onErrorContainer
        DeleteStatus.Cancelled -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier.size(72.dp),
        shape = CircleShape,
        color = containerColor,
        contentColor = iconColor
    ) {
        Canvas(Modifier.padding(20.dp)) {
            val strokeWidth = 3.5.dp.toPx()
            when (status) {
                DeleteStatus.Success -> {
                    drawLine(iconColor, Offset(size.width * 0.12f, size.height * 0.53f), Offset(size.width * 0.4f, size.height * 0.8f), strokeWidth, StrokeCap.Round)
                    drawLine(iconColor, Offset(size.width * 0.4f, size.height * 0.8f), Offset(size.width * 0.9f, size.height * 0.22f), strokeWidth, StrokeCap.Round)
                }
                DeleteStatus.Partial -> {
                    drawLine(iconColor, Offset(size.width * 0.5f, size.height * 0.14f), Offset(size.width * 0.5f, size.height * 0.62f), strokeWidth, StrokeCap.Round)
                    drawCircle(iconColor, radius = strokeWidth / 2, center = Offset(size.width * 0.5f, size.height * 0.85f))
                }
                DeleteStatus.Failed -> {
                    drawLine(iconColor, Offset(size.width * 0.2f, size.height * 0.2f), Offset(size.width * 0.8f, size.height * 0.8f), strokeWidth, StrokeCap.Round)
                    drawLine(iconColor, Offset(size.width * 0.8f, size.height * 0.2f), Offset(size.width * 0.2f, size.height * 0.8f), strokeWidth, StrokeCap.Round)
                }
                DeleteStatus.Cancelled -> drawLine(
                    iconColor,
                    Offset(size.width * 0.2f, size.height * 0.5f),
                    Offset(size.width * 0.8f, size.height * 0.5f),
                    strokeWidth,
                    StrokeCap.Round
                )
            }
        }
    }
}

@Composable private fun PhotoSwipeScaffold(content: @Composable () -> Unit) { Scaffold { innerPadding -> Box(Modifier.fillMaxSize().padding(innerPadding).padding(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 12.dp)) { content() } } }

@Composable
private fun AppHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TopBar(title: String, leading: String, trailing: String = "", onLeadingClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onLeadingClick) { Text(leading) }
        Text(text = title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text = trailing, modifier = Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp)) { Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(6.dp)); Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) } }
}

@Composable
private fun AlbumRow(album: PhotoAlbum, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                if (album.coverUri == null) Text(album.name.take(1), color = MaterialTheme.colorScheme.primary) else AsyncImage(album.coverUri, album.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(album.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(stringResource(R.string.photo_count_and_size, album.count, formatBytes(album.sizeBytes)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(stringResource(R.string.open), color = MaterialTheme.colorScheme.primary)
        }
    }
}

private const val PHOTO_GRID_COLUMNS = 4
private const val PHOTO_GRID_PAGE_SIZE = 24
private const val PHOTO_GRID_LOAD_AHEAD = 8

private data class KeptPhotoAlbum(
    val id: String,
    val name: String,
    val photos: List<PhotoItem>
)

@Composable
private fun KeptPhotoAlbumRow(album: KeptPhotoAlbum, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = album.photos.first().uri,
                    contentDescription = album.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(album.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(
                        R.string.photo_count_and_size,
                        album.photos.size,
                        formatBytes(album.photos.sumOf { it.sizeBytes })
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(stringResource(R.string.open), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PagedPhotoGrid(
    photos: List<PhotoItem>,
    modifier: Modifier = Modifier,
    onPhotoClick: (PhotoItem) -> Unit
) {
    val gridState = rememberLazyGridState()
    var visiblePhotoCount by remember(photos) {
        mutableIntStateOf(minOf(PHOTO_GRID_PAGE_SIZE, photos.size))
    }
    val lastVisibleItemIndex by remember {
        derivedStateOf { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
    }

    LaunchedEffect(lastVisibleItemIndex, visiblePhotoCount, photos.size) {
        if (
            lastVisibleItemIndex >= visiblePhotoCount - PHOTO_GRID_LOAD_AHEAD &&
            visiblePhotoCount < photos.size
        ) {
            visiblePhotoCount = (visiblePhotoCount + PHOTO_GRID_PAGE_SIZE).coerceAtMost(photos.size)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(PHOTO_GRID_COLUMNS),
        state = gridState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        gridItems(photos.take(visiblePhotoCount), key = { it.id }) { photo ->
            PhotoGridCard(photo = photo) { onPhotoClick(photo) }
        }
    }
}

@Composable
private fun PhotoGridCard(photo: PhotoItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.aspectRatio(1f),
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        shape = RoundedCornerShape(8.dp)
    ) {
        AsyncImage(
            model = photo.uri,
            contentDescription = photo.displayName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun PhotoActionDialog(
    photo: PhotoItem,
    actionLabel: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(photo.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AsyncImage(
                    model = photo.uri,
                    contentDescription = photo.displayName,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit
                )
                Text(
                    formatBytes(photo.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onAction) { Text(actionLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.back)) } }
    )
}

@Composable private fun AssistPill(text: String, modifier: Modifier = Modifier) { Surface(modifier, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), contentColor = MaterialTheme.colorScheme.onSurface, shape = CircleShape) { Box(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.Center) { Text(text = text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) } } }

@Composable
private fun SwipeHintPill(hint: SwipeHint, isImageZoomed: Boolean, modifier: Modifier = Modifier) {
    val displayHint = if (isImageZoomed) SwipeHint.Neutral else hint
    val targetBackground = when (displayHint) {
        SwipeHint.Delete -> MaterialTheme.colorScheme.error
        SwipeHint.Keep -> Color(0xFF2E7D32)
        SwipeHint.Neutral -> MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    }
    val targetBorder = when (displayHint) {
        SwipeHint.Delete -> MaterialTheme.colorScheme.error
        SwipeHint.Keep -> Color(0xFF2E7D32)
        SwipeHint.Neutral -> MaterialTheme.colorScheme.outline
    }
    val targetContent = if (displayHint == SwipeHint.Neutral) MaterialTheme.colorScheme.onSurface else Color.White
    val text = if (isImageZoomed) {
        stringResource(R.string.double_tap_to_reset)
    } else when (hint) {
        SwipeHint.Delete -> stringResource(R.string.add_to_delete)
        SwipeHint.Keep -> stringResource(R.string.keep)
        SwipeHint.Neutral -> stringResource(R.string.swipe_delete_hint)
    }
    Surface(
        modifier = modifier.width(SWIPE_HINT_WIDTH).border(1.dp, targetBorder, CircleShape),
        color = targetBackground,
        contentColor = targetContent,
        shape = CircleShape
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center
        )
    }
}

@Composable private fun StatusIllustration(label: String) { Box(Modifier.fillMaxWidth().aspectRatio(1.8f).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)) { Text(text = label, modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) } } }

@Composable private fun StatusMessage(title: String, message: String) { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }

@Composable private fun EmptyCard(onFinish: () -> Unit) { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(28.dp)) { Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(stringResource(R.string.album_done)); Button(onClick = onFinish) { Text(stringResource(R.string.review_delete_list)) } } } }

@Composable private fun EmptyReview(albumName: String) { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(24.dp)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(stringResource(R.string.no_photos_selected), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(stringResource(R.string.no_photos_selected_message, albumName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }

private fun photoPermissions(): Array<String> = when {
    // Android 14 可能授予全部图片访问权限，也可能只授予用户选择的媒体权限。
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun hasPhotoAccess(context: Context): Boolean = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
    else -> ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}

private class PhotoPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        val storedRecords = preferences.getStringSet(KEY_KEPT_PHOTO_RECORDS, emptySet()).orEmpty()
        if (storedRecords.any { RECORD_SEPARATOR !in it }) {
            // 旧版本只保存照片 ID。补充当前时间后，新的过期设置才能统一处理这些记录。
            val migratedAt = System.currentTimeMillis()
            val migratedRecords = storedRecords.mapTo(mutableSetOf()) { record ->
                if (RECORD_SEPARATOR in record) record else "$record$RECORD_SEPARATOR$migratedAt"
            }
            preferences.edit().putStringSet(KEY_KEPT_PHOTO_RECORDS, migratedRecords).apply()
        }
    }

    var hideKeptPhotos: Boolean
        get() = preferences.getBoolean(KEY_HIDE_KEPT_PHOTOS, true)
        set(value) = preferences.edit().putBoolean(KEY_HIDE_KEPT_PHOTOS, value).apply()

    var keptPhotoDays: Int
        get() = preferences.getInt(KEY_KEPT_PHOTO_DAYS, DEFAULT_KEPT_DAYS)
            .takeIf { it in KEPT_DAY_OPTIONS }
            ?: DEFAULT_KEPT_DAYS
        set(value) = preferences.edit()
            .putInt(KEY_KEPT_PHOTO_DAYS, value.takeIf { it in KEPT_DAY_OPTIONS } ?: DEFAULT_KEPT_DAYS)
            .apply()

    var photoLoadOrder: PhotoLoadOrder
        get() = preferences.getString(KEY_PHOTO_LOAD_ORDER, null)
            ?.let { storedValue -> PhotoLoadOrder.entries.firstOrNull { it.name == storedValue } }
            ?: PhotoLoadOrder.NewestFirst
        set(value) = preferences.edit().putString(KEY_PHOTO_LOAD_ORDER, value.name).apply()

    val hiddenKeptPhotoIds: Set<Long>
        get() {
            // 已过期的保留记录仍可留在存储中，但不会再影响后续会话中的照片显示。
            val cutoff = keptPhotoCutoff(System.currentTimeMillis())
            return keptPhotoRecords().filterValues { keptAt -> keptAt >= cutoff }.keys
        }

    fun addKeptPhoto(photoId: Long): Boolean {
        val records = keptPhotoRecords().toMutableMap()
        val now = System.currentTimeMillis()
        val cutoff = keptPhotoCutoff(now)
        val wasAlreadyHidden = records[photoId]?.let { it >= cutoff } == true
        records[photoId] = now
        saveKeptPhotoRecords(records)
        return !wasAlreadyHidden
    }

    fun removeKeptPhoto(photoId: Long): Boolean {
        val records = keptPhotoRecords().toMutableMap()
        if (records.remove(photoId) == null) return false
        saveKeptPhotoRecords(records)
        return true
    }

    fun clearKeptPhotos() {
        preferences.edit().remove(KEY_KEPT_PHOTO_RECORDS).apply()
    }

    val cleanedPhotoCount: Long
        get() = preferences.getLong(KEY_CLEANED_PHOTO_COUNT, 0L)

    val cleanedBytes: Long
        get() = preferences.getLong(KEY_CLEANED_BYTES, 0L)

    fun recordCleanup(photoCount: Int, bytes: Long) {
        preferences.edit()
            .putLong(KEY_CLEANED_PHOTO_COUNT, cleanedPhotoCount + photoCount)
            .putLong(KEY_CLEANED_BYTES, cleanedBytes + bytes)
            .apply()
    }

    private fun keptPhotoRecords(): Map<Long, Long> = buildMap {
        preferences.getStringSet(KEY_KEPT_PHOTO_RECORDS, emptySet()).orEmpty().forEach { record ->
            val separatorIndex = record.indexOf(RECORD_SEPARATOR)
            if (separatorIndex > 0) {
                val photoId = record.substring(0, separatorIndex).toLongOrNull()
                val keptAt = record.substring(separatorIndex + 1).toLongOrNull()
                if (photoId != null && keptAt != null) put(photoId, keptAt)
            }
        }
    }

    private fun saveKeptPhotoRecords(records: Map<Long, Long>) {
        val storedRecords = records.mapTo(mutableSetOf()) { (photoId, keptAt) ->
            "$photoId$RECORD_SEPARATOR$keptAt"
        }
        preferences.edit().putStringSet(KEY_KEPT_PHOTO_RECORDS, storedRecords).apply()
    }

    private fun keptPhotoCutoff(now: Long): Long =
        if (keptPhotoDays == KEEP_FOREVER) Long.MIN_VALUE else now - keptPhotoDays * MILLIS_PER_DAY

    private companion object {
        const val PREFERENCES_NAME = "photo_swipe_preferences"
        const val KEY_HIDE_KEPT_PHOTOS = "hide_kept_photos"
        const val KEY_KEPT_PHOTO_DAYS = "kept_photo_days"
        const val KEY_PHOTO_LOAD_ORDER = "photo_load_order"
        const val KEY_KEPT_PHOTO_RECORDS = "kept_photo_ids"
        const val KEY_CLEANED_PHOTO_COUNT = "cleaned_photo_count"
        const val KEY_CLEANED_BYTES = "cleaned_bytes"
        const val DEFAULT_KEPT_DAYS = 30
        const val RECORD_SEPARATOR = ':'
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

@Preview(showBackground = true)
@Composable
private fun PhotoSwipePreview() { PhotoSwipeTheme(dynamicColor = false) { PhotoSwipeApp() } }
