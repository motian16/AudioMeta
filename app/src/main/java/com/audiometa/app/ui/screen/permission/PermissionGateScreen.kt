package com.audiometa.app.ui.screen.permission

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.audiometa.app.di.ServiceLocator
import com.audiometa.app.ui.component.SectionTitle
import com.audiometa.app.ui.component.StatusLine
import com.audiometa.app.util.StoragePermissions
import kotlinx.coroutines.launch

@Composable
fun PermissionGateScreen(onContinue: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var allFiles by remember { mutableStateOf(StoragePermissions.hasAllFilesAccess()) }
    var media by remember { mutableStateOf(StoragePermissions.hasMediaAccess(context)) }

    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        media = StoragePermissions.hasMediaAccess(context)
    }

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            allFiles = StoragePermissions.hasAllFilesAccess()
            media = StoragePermissions.hasMediaAccess(context)
        }
    }

    // 边到边模式下窗口不再自动避让状态栏，必须自己加 padding，
    // 否则顶部标题会被状态栏压住。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("需要文件访问权限", style = MaterialTheme.typography.headlineSmall)
        Text("修改音频内嵌标签需要所有文件访问权限。拒绝后会退回 SAF 模式，部分功能不可用。")
        SectionTitle("当前状态")
        StatusLine("所有文件访问", allFiles)
        StatusLine("媒体读取", media)
        Button(onClick = {
            /*
             * 统一走 [StoragePermissions]：它自带"这个 ROM 没有该页面"的兜底。
             *
             * 以前这里自己拼 Intent 直接 startActivity，部分 ROM（把
             * ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION 裁掉的定制系统）
             * 会抛 ActivityNotFoundException —— 引导页第一屏就闪退。
             */
            StoragePermissions.openAllFilesAccessSettings(context)
        }) { Text("前往授权") }
        if (Build.VERSION.SDK_INT >= 33) {
            Button(onClick = { mediaLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO) }) {
                Text("授予媒体权限")
            }
        }
        Button(onClick = onContinue) { Text("继续") }
    }
}
