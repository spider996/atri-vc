package com.atri.vc

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atri.vc.ui.AtriAmber
import com.atri.vc.ui.AtriBg
import com.atri.vc.ui.AtriCyan
import com.atri.vc.ui.AtriPink
import com.atri.vc.ui.AtriSurface
import com.atri.vc.ui.AtriSurface2
import com.atri.vc.ui.AtriText
import com.atri.vc.ui.AtriTextDim
import com.atri.vc.ui.AtriTheme
import com.atri.vc.ui.AtriWarn
import java.io.File
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var vmRef: MainViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: MainViewModel = viewModel()
            vmRef = vm
            AtriTheme(dark = true) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    AppRoot(vm)
                }
            }
        }
    }

    // 从「系统无障碍设置」返回时刷新一次开关状态，否则界面还显示未开启
    override fun onResume() {
        super.onResume()
        vmRef?.refreshQqWatchState()
        // 从「显示在其他应用上层」授权页或无障碍设置返回时都要刷新
        vmRef?.refreshFloatingState()
    }
}

private enum class Tab(val label: String) {
    Convert("变声"), Synth("合成"), Models("模型"), Settings("设置")
}

@Composable
private fun AppRoot(vm: MainViewModel = viewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.Convert) }
    val ctx = LocalContext.current

    LaunchedEffect(st.toast) {
        st.toast?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
            vm.clearToast()
        }
    }
    LaunchedEffect(st.error) {
        st.error?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
            vm.clearError()
        }
    }

    Scaffold(
        containerColor = AtriBg,
        bottomBar = {
            NavigationBar(containerColor = AtriSurface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.Convert -> Icons.Default.PlayArrow
                                    Tab.Synth -> Icons.Default.Create
                                    Tab.Models -> Icons.Default.Info
                                    Tab.Settings -> Icons.Default.Settings
                                },
                                contentDescription = t.label,
                            )
                        },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AtriBg,
                            selectedTextColor = AtriCyan,
                            indicatorColor = AtriCyan,
                            unselectedIconColor = AtriTextDim,
                            unselectedTextColor = AtriTextDim,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                Tab.Convert -> ConvertScreen(vm, st)
                Tab.Synth -> SynthScreen(vm, st)
                Tab.Models -> ModelsScreen(vm, st)
                Tab.Settings -> SettingsScreen(vm, st)
            }
            // 全量包首次启动：模型正在解包，盖一层进度，避免用户以为卡死
            if (st.importing && st.importTotal > 0) {
                ImportOverlay(st)
            }
        }
    }
}

// ------------------------------------------------------------------ 变声页

@Composable
private fun ConvertScreen(vm: MainViewModel, st: UiState) {
    val ctx = LocalContext.current
    val clip = remember { ClipPlayer() }

    DisposableEffect(Unit) {
        onDispose { clip.stop() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            vm.pickAudio(uri)
        }
    }

    var showRecList by remember { mutableStateOf(false) }
    var recList by remember { mutableStateOf<List<File>>(emptyList()) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(
                painter = painterResource(R.drawable.atri_avatar),
                contentDescription = null,
                modifier = Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)),
            )
            Column {
                Text("亚托莉变声器", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AtriText)
                Text("离线变声 · 全程在手机本地跑 · 不上传任何音频",
                    fontSize = 12.sp, color = AtriTextDim)
            }
        }

        Banner(st.modelsReady, st.engineLoaded)

        Card(colors = CardDefaults.cardColors(containerColor = AtriSurface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1 · 选择音频", fontWeight = FontWeight.SemiBold, color = AtriCyan)
                if (st.srcName != null) {
                    Text(st.srcName!!, color = AtriText, fontSize = 16.sp)
                    Text(st.srcInfo ?: "", color = AtriTextDim, fontSize = 12.sp)
                } else {
                    Text("支持 wav / mp3 / m4a / flac / ogg", color = AtriTextDim, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { picker.launch(arrayOf("audio/*")) },
                        enabled = !st.busy && !st.recording,
                        colors = ButtonDefaults.buttonColors(containerColor = AtriCyan, contentColor = AtriBg),
                    ) { Text(if (st.srcName == null) "选择音频文件" else "换一个") }

                    OutlinedButton(
                        onClick = { vm.importFromDir() },
                        enabled = !st.busy && !st.recording,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AtriCyan),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("App 目录")
                    }
                }

                ThinDivider()

                // ---------------- 录音 ----------------
                Text("或者直接录一段自己的声音", fontWeight = FontWeight.SemiBold, color = AtriCyan)
                RecordBlock(vm, st, clip)

                if (st.recCount > 0) {
                    TextButton(onClick = {
                        recList = vm.listRecordings()
                        showRecList = true
                    }) { Text("查看历史录音（${st.recCount} 条）", color = AtriCyan, fontSize = 12.sp) }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = AtriSurface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("2 · 开始转换", fontWeight = FontWeight.SemiBold, color = AtriCyan)
                val canRun = st.srcName != null && st.engineLoaded && !st.busy && !st.recording
                Button(
                    onClick = { vm.convert() },
                    enabled = canRun,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AtriAmber, contentColor = Color(0xFF2A1F00)),
                ) {
                    Text(
                        when {
                            st.recording -> "录音中…先停止录音"
                            st.busy -> "转换中…"
                            !st.engineLoaded -> "请先加载模型（到模型页）"
                            st.srcName == null -> "请先选择音频或录音"
                            else -> "开始变声"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (st.busy) {
                    Text(
                        "${st.stage}   ${(st.progress * 100).roundToInt()}%",
                        color = AtriText, fontSize = 13.sp,
                    )
                    LinearProgressIndicator(
                        progress = { st.progress },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = AtriCyan,
                        trackColor = AtriSurface2,
                    )
                }
            }
        }

        val rf = st.resultFile
        if (rf != null) {
            Card(colors = CardDefaults.cardColors(containerColor = AtriSurface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("3 · 结果", fontWeight = FontWeight.SemiBold, color = AtriCyan)
                    Text(
                        "时长 %.1f 秒 · 40 kHz · 用时 %.1f 秒".format(st.resultSeconds, st.elapsedMs / 1000.0),
                        color = AtriText, fontSize = 14.sp,
                    )
                    Text(st.resultDisplay ?: rf.absolutePath, color = AtriTextDim, fontSize = 11.sp)
                    Button(
                        onClick = { clip.toggle(ctx, "result", rf) },
                        colors = ButtonDefaults.buttonColors(containerColor = AtriCyan, contentColor = AtriBg),
                    ) {
                        Icon(
                            if (clip.isPlaying("result")) Icons.Default.Clear else Icons.Default.PlayArrow,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (clip.isPlaying("result")) "停止" else "试听")
                    }
                }
            }
        }
    }

    if (showRecList) {
        AlertDialog(
            onDismissRequest = { showRecList = false },
            containerColor = AtriSurface,
            title = { Text("历史录音", color = AtriText) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (recList.isEmpty()) {
                        Text("还没有录音", color = AtriTextDim, fontSize = 13.sp)
                    }
                    recList.forEach { f ->
                        TextButton(onClick = {
                            vm.useRecordingAsInput(f)
                            showRecList = false
                        }) {
                            Text(f.name, color = AtriCyan, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRecList = false }) { Text("关闭", color = AtriCyan) }
            },
        )
    }
}

/** 录音控件：录制 / 停止 / 电平 / 试听 / 用作输入 */
@Composable
private fun RecordBlock(vm: MainViewModel, st: UiState, clip: ClipPlayer) {
    val ctx = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.startRecording()
        } else {
            Toast.makeText(ctx, "没有麦克风权限，无法录音（可在系统设置里开启）", Toast.LENGTH_LONG).show()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    when {
                        st.recording -> vm.stopRecording()
                        vm.hasMicPermission() -> vm.startRecording()
                        else -> permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = !st.busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (st.recording) AtriPink else AtriCyan,
                    contentColor = if (st.recording) Color(0xFF3A0010) else AtriBg,
                ),
            ) {
                Icon(if (st.recording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (st.recording) "停止" else "录音")
            }
            if (st.recording) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(AtriPink))
                Text(
                    fmtMs(st.recElapsedMs),
                    color = AtriPink, fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                )
                Text("${st.recSampleRate / 1000.0} kHz", color = AtriTextDim, fontSize = 11.sp)
            }
        }

        if (st.recording) {
            LevelBar(st.recLevel)
            Text(
                when {
                    st.recLevel > 0.95f -> "音量偏大，可能削波"
                    st.recLevel < 0.12f -> "声音太小，靠近麦克风一点"
                    else -> "电平正常，保持这个距离"
                },
                color = if (st.recLevel > 0.95f) AtriPink else AtriTextDim,
                fontSize = 11.sp,
            )
        }

        val rec = st.recFile
        if (!st.recording && rec != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("已录制 %.1f 秒".format(st.recSeconds), color = AtriText, fontSize = 13.sp)
                OutlinedButton(
                    onClick = { clip.toggle(ctx, "rec", rec) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AtriCyan),
                ) { Text(if (clip.isPlaying("rec")) "停止" else "试听") }
            }
            Text(rec.name, color = AtriTextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }

        if (!st.recording && st.recCount == 0) {
            Text("建议 5 ~ 20 秒、安静环境、正常语速说话；录完会自动作为变声输入。",
                color = AtriTextDim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun LevelBar(level: Float) {
    val pct = level.coerceIn(0f, 1f)
    val color = when {
        pct > 0.95f -> AtriPink
        pct > 0.78f -> AtriAmber
        else -> AtriCyan
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(AtriSurface2)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(pct.coerceAtLeast(0.02f))
                .clip(RoundedCornerShape(5.dp))
                .background(color)
        )
    }
}

@Composable
private fun ThinDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(AtriSurface2))
}

private fun fmtMs(ms: Long): String = "%02d:%02d".format((ms / 60000) % 100, (ms / 1000) % 60)

@Composable
private fun Banner(ready: Boolean, engineOk: Boolean) {
    val bg: Color
    val fg: Color
    val text: String
    val icon: androidx.compose.ui.graphics.vector.ImageVector
    if (engineOk) {
        bg = AtriCyan.copy(alpha = 0.16f); fg = AtriCyan
        text = "模型已载入内存，可以开始变声"; icon = Icons.Default.CheckCircle
    } else if (ready) {
        bg = AtriAmber.copy(alpha = 0.16f); fg = AtriAmber
        text = "三件套已就位，去「模型」页点「加载模型」"; icon = Icons.Default.Info
    } else {
        bg = AtriPink.copy(alpha = 0.16f); fg = AtriPink
        text = "缺少模型文件，请到「模型」页查看路径"; icon = Icons.Default.Warning
    }

    Card(colors = CardDefaults.cardColors(containerColor = bg), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = fg)
            Spacer(Modifier.width(10.dp))
            Text(text, color = fg, fontSize = 13.sp)
        }
    }
}

// ------------------------------------------------------------------ 合成页（GPT-SoVITS）

@Composable
private fun SynthScreen(vm: MainViewModel, st: UiState) {
    val ctx = LocalContext.current
    val clip = remember { ClipPlayer() }

    DisposableEffect(Unit) {
        onDispose { clip.stop() }
    }

    var showAdvanced by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("语音合成", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AtriText)
        Text("输入文字，用亚托莉的声音读出来。全程在手机本地完成。", fontSize = 12.sp, color = AtriTextDim)

        // ---- 模型没就位时的唯一提示（不再列文件清单，避免刷屏）----
        if (!st.ttsReady) {
            Card(
                colors = CardDefaults.cardColors(containerColor = AtriPink.copy(alpha = 0.14f)),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = AtriPink)
                        Spacer(Modifier.width(10.dp))
                        Text("合成模型还没准备好", color = AtriPink, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "还缺 " + st.ttsMissing.size + " 个文件。放到「模型」页显示的合成模型目录里，再回来即可。",
                        color = AtriTextDim, fontSize = 11.sp,
                    )
                }
            }
        }

        // ---- 1 · 输入文字 ----
        Card(colors = CardDefaults.cardColors(containerColor = AtriSurface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1 · 输入文字", fontWeight = FontWeight.SemiBold, color = AtriCyan)
                OutlinedTextField(
                    value = st.ttsText,
                    onValueChange = { vm.setTtsText(it) },
                    placeholder = { Text("在这里输入要读的文字…", color = AtriTextDim) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 10,
                    colors = atriTextFieldColors(),
                )
                Text(
                    "${st.ttsText.count { !it.isWhitespace() }} 字 · 目前只支持中文，英文和数字会被忽略",
                    color = AtriTextDim, fontSize = 11.sp,
                )
            }
        }

        // ---- 2 · 选择语气 ----
        Card(colors = CardDefaults.cardColors(containerColor = AtriSurface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("2 · 选择语气", fontWeight = FontWeight.SemiBold, color = AtriCyan)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RefFeatureStore.EMOTIONS.forEach { emo ->
                        val ok = emo in st.ttsRefsReady
                        FilterChip(
                            selected = st.ttsEmotion == emo,
                            onClick = { if (ok) vm.setTtsEmotion(emo) },
                            enabled = ok,
                            label = {
                                Text(
                                    (RefFeatureStore.LABELS[emo] ?: emo) + if (ok) "" else "（缺）",
                                    fontSize = 12.sp,
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = AtriSurface2,
                                labelColor = AtriTextDim,
                                selectedContainerColor = AtriAmber,
                                selectedLabelColor = Color(0xFF2A1F00),
                                disabledContainerColor = AtriSurface2.copy(alpha = 0.4f),
                                disabledLabelColor = AtriTextDim.copy(alpha = 0.4f),
                            ),
                        )
                    }
                }
                Text(
                    "每种语气都配了一段亚托莉的原声当参考，合成时会模仿这段的语气起伏与节奏；"
                        + "换语气只改语气，音色不变。",
                    color = AtriTextDim, fontSize = 11.sp,
                )
                if (st.ttsRefsReady.size < RefFeatureStore.EMOTIONS.size) {
                    Text("灰掉的是缺参考文件（ref/ref_xxx.refbin）。", color = AtriTextDim, fontSize = 10.sp)
                }
                ThinDivider()
                ParamSlider(
                    label = "语速  %.2fx".format(st.ttsSpeed),
                    hint = "小于 1 变慢，大于 1 变快。",
                    value = st.ttsSpeed, range = 0.5f..2.0f, steps = 29,
                    onChange = { vm.setTtsSpeed(it) },
                )
            }
        }

        // ---- 高级参数（默认收起）----
        Card(colors = CardDefaults.cardColors(containerColor = AtriSurface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("高级参数", fontWeight = FontWeight.SemiBold, color = AtriCyan,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { showAdvanced = !showAdvanced }) {
                        Text(if (showAdvanced) "收起" else "展开", color = AtriTextDim, fontSize = 12.sp)
                    }
                }
                Text("不熟的话保持默认就好，默认值是最稳的那组。",
                    color = AtriTextDim, fontSize = 11.sp)
                if (showAdvanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ParamSlider(
                            label = "采样温度  %.2f".format(st.ttsTemperature),
                            hint = "越低越稳但语气平，越高起伏越大也越容易跑调。",
                            value = st.ttsTemperature, range = 0.1f..2.0f, steps = 37,
                            onChange = { vm.setTtsTemperature(it) },
                        )
                        ParamSlider(
                            label = "Top-K  ${st.ttsTopK}",
                            hint = "每步只从概率最高的 K 个里挑。",
                            value = st.ttsTopK.toFloat(), range = 1f..50f, steps = 48,
                            onChange = { vm.setTtsTopK(it.roundToInt()) },
                        )
                        ParamSlider(
                            label = "Top-P  %.2f".format(st.ttsTopP),
                            hint = "和 Top-K 一起控制随机程度。",
                            value = st.ttsTopP, range = 0.1f..1.0f, steps = 17,
                            onChange = { vm.setTtsTopP(it) },
                        )
                        ParamSlider(
                            label = "重复惩罚  %.2f".format(st.ttsRepetition),
                            hint = "越大越不容易拖音、重复吐字。",
                            value = st.ttsRepetition, range = 1.0f..2.0f, steps = 19,
                            onChange = { vm.setTtsRepetition(it) },
                        )
                    }
                }
            }
        }

        // ---- 3 · 开始合成 ----
        Card(colors = CardDefaults.cardColors(containerColor = AtriSurface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("3 · 开始合成", fontWeight = FontWeight.SemiBold, color = AtriCyan)
                Text(
                    "用法：输入文字 → 选语气 → 点下面这个按钮。"
                        + "合成一次要几十秒（全在手机上算），请耐心等一下。",
                    color = AtriTextDim, fontSize = 11.sp,
                )
                Button(
                    onClick = { vm.synthesizeTts() },
                    enabled = !st.ttsBusy && st.ttsText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AtriAmber, contentColor = Color(0xFF2A1F00)),
                ) {
                    Text(
                        when {
                            st.ttsBusy -> "合成中…"
                            !st.ttsReady -> "合成模型未就位"
                            st.ttsText.isBlank() -> "请先输入文字"
                            else -> "开始合成"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (st.ttsBusy) {
                    Text("${st.ttsStage}   ${(st.ttsProgress * 100).roundToInt()}%", color = AtriText, fontSize = 13.sp)
                    LinearProgressIndicator(
                        progress = { st.ttsProgress },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = AtriCyan, trackColor = AtriSurface2,
                    )
                }
            }
        }

        // ---- 结果 ----
        val trf = st.ttsResultFile
        if (trf != null) {
            Card(colors = CardDefaults.cardColors(containerColor = AtriSurface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AtriCyan)
                        Spacer(Modifier.width(10.dp))
                        Text("合成完成", fontWeight = FontWeight.SemiBold, color = AtriCyan)
                    }
                    Text(
                        "%s · %.2f 秒 · 用时 %.1f 秒".format(
                            RefFeatureStore.LABELS[st.ttsEmotion] ?: st.ttsEmotion,
                            st.ttsResultSeconds, st.ttsElapsedMs / 1000.0,
                        ),
                        color = AtriText, fontSize = 14.sp,
                    )
                    Text("已自动设为「待发送」，QQ 那边点悬浮球就能播。",
                        color = AtriTextDim, fontSize = 11.sp)
                    Button(
                        onClick = { clip.toggle(ctx, "ttsres", trf) },
                        colors = ButtonDefaults.buttonColors(containerColor = AtriCyan, contentColor = AtriBg),
                    ) {
                        Icon(if (clip.isPlaying("ttsres")) Icons.Default.Clear else Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (clip.isPlaying("ttsres")) "停止" else "试听")
                    }
                }
            }
        }

        // ---- 常用语音（LFU 收藏，最多 10 条）----
        LibraryCard(vm, st, clip)
    }
}

/**
 * 「常用语音」收藏卡片。
 *
 * LFU 计数在这里产生：**试听**（不是点「停止」那下）和**设为待发送**时各 +1。
 * 存满 10 条后，计数最小的先被淘汰（同计数则最久没用过的先走）。
 */
@Composable
private fun LibraryCard(vm: MainViewModel, st: UiState, clip: ClipPlayer) {
    val ctx = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = AtriSurface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("常用语音", fontWeight = FontWeight.SemiBold, color = AtriCyan,
                    modifier = Modifier.weight(1f))
                Text("${st.library.size} / ${TtsLibrary.MAX}", color = AtriTextDim, fontSize = 11.sp)
            }
            Text(
                "合成一次要几十秒，所以这里帮你留下最常用的 10 条，点一下就能再听、再发。"
                    + "用得多的会一直留着，用得少的自动清掉。",
                color = AtriTextDim, fontSize = 11.sp,
            )
            if (st.library.isEmpty()) {
                Text("还没有内容 —— 合成一条就会出现在这里。", color = AtriTextDim, fontSize = 11.sp)
            }
            st.library.forEach { e ->
                val key = "lib:" + e.file.name
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.preview, color = AtriText, fontSize = 13.sp)
                        Text(
                            "%s · %.1f 秒 · 用过 %d 次".format(
                                RefFeatureStore.LABELS[e.emotion] ?: e.emotion, e.seconds, e.count,
                            ),
                            color = AtriTextDim, fontSize = 10.sp,
                        )
                    }
                    TextButton(onClick = {
                        val wasPlaying = clip.isPlaying(key)
                        clip.toggle(ctx, key, e.file)
                        // 只在「开始播放」那一下计数，点「停止」不算一次使用
                        if (!wasPlaying) vm.touchLibrary(e)
                    }) {
                        Text(if (clip.isPlaying(key)) "停止" else "试听", color = AtriCyan, fontSize = 11.sp)
                    }
                    TextButton(onClick = { vm.useLibraryAsPending(e) }) {
                        Text("发送", color = AtriAmber, fontSize = 11.sp)
                    }
                    TextButton(onClick = { vm.deleteLibrary(e) }) {
                        Text("删除", color = AtriTextDim, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    hint: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = AtriCyan, fontSize = 13.sp)
        Text(hint, color = AtriTextDim, fontSize = 10.sp)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun atriTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AtriText,
    unfocusedTextColor = AtriText,
    focusedBorderColor = AtriCyan,
    unfocusedBorderColor = AtriSurface2,
    cursorColor = AtriCyan,
    focusedLabelColor = AtriCyan,
    unfocusedLabelColor = AtriTextDim,
    focusedPlaceholderColor = AtriTextDim,
    unfocusedPlaceholderColor = AtriTextDim,
)

// ------------------------------------------------------------------ 模型页

@Composable
private fun ModelsScreen(vm: MainViewModel, st: UiState) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("模型", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AtriText)
        Text("模型文件太大，不随安装包分发。放进下面显示的目录即可。",
            fontSize = 12.sp, color = AtriTextDim)

        Card(colors = CardDefaults.cardColors(containerColor = AtriSurface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("变声模型目录", fontWeight = FontWeight.SemiBold, color = AtriCyan)
                Text(st.modelDir, color = AtriText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("把下面这三个文件放进这个目录，然后点「重新扫描」。",
                    color = AtriTextDim, fontSize = 11.sp)
                OutlinedButton(
                    onClick = { vm.refreshModels() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AtriCyan),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("重新扫描")
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = AtriSurface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("变声三件套", fontWeight = FontWeight.SemiBold, color = AtriCyan)
                st.modelItems.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (item.present) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (item.present) AtriCyan else AtriPink,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.name + if (item.required) "" else "（可选）",
                                color = AtriText, fontSize = 13.sp,
                            )
                            Text(
                                if (!item.present) "缺失" else "%.1f MB".format(item.sizeBytes / 1048576.0),
                                color = AtriTextDim, fontSize = 11.sp,
                            )
                        }
                    }
                }
                Text("三件套各管一段：一个提取「说了什么」，一个提取「音有多高」，"
                    + "一个负责「把声音合成出来」。",
                    color = AtriTextDim, fontSize = 11.sp)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = AtriSurface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("运行状态", fontWeight = FontWeight.SemiBold, color = AtriCyan)
                Button(
                    onClick = { vm.loadEngine() },
                    enabled = st.modelsReady && !st.loadingEngine,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AtriCyan, contentColor = AtriBg),
                ) {
                    Text(
                        when {
                            st.loadingEngine -> "加载中…"
                            st.engineLoaded -> "重新加载模型"
                            else -> "加载模型"
                        }
                    )
                }
                Text(
                    if (st.engineLoaded) "模型已在内存里，回「变声」页就能用。"
                    else "加载一次后会一直留在内存里，不用每次重来。",
                    color = AtriTextDim, fontSize = 11.sp,
                )
                if (st.engineLog.isNotBlank()) {
                    Text(st.engineLog, color = AtriTextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = AtriSurface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("合成模型目录", fontWeight = FontWeight.SemiBold, color = AtriCyan)
                Text(st.ttsDir, color = AtriText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("合成用的模型放这里，参考特征放它下面的 ref/ 里。缺什么「合成」页会提示。",
                    color = AtriTextDim, fontSize = 11.sp)
                OutlinedButton(
                    onClick = { vm.refreshTtsModels() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AtriCyan),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("重新扫描")
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: MainViewModel, st: UiState) {
    val ctx = LocalContext.current
    var showAdvanced by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("设置", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AtriText)

        Card(colors = CardDefaults.cardColors(containerColor = AtriSurface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "变调  ${if (st.pitchShift > 0) "+" else ""}${st.pitchShift} 半音",
                    color = AtriCyan, fontWeight = FontWeight.SemiBold,
                )
                Text("正值升调（更像女声）。男声转亚托莉一般 +8 ~ +12。",
                    color = AtriTextDim, fontSize = 11.sp)
                Slider(
                    value = st.pitchShift.toFloat(),
                    onValueChange = { vm.setPitch(it.roundToInt()) },
                    valueRange = -24f..24f,
                    steps = 47,
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = AtriSurface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("使用音色检索", color = AtriCyan, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    Switch(checked = st.useIndex, onCheckedChange = { vm.setUseIndex(it) })
                }
                Text("开启后音色更像亚托莉，代价是慢一点。需要匹配模型一起放进来的检索文件。",
                    color = AtriTextDim, fontSize = 11.sp)
                Text("检索混合比例  %.2f".format(st.indexRate), color = AtriCyan, fontSize = 13.sp)
                Slider(value = st.indexRate, onValueChange = { vm.setIndexRate(it) }, valueRange = 0f..1f)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = AtriSurface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("无声段保护  %.2f".format(st.protect), color = AtriCyan, fontWeight = FontWeight.SemiBold)
                Text("越小越贴近检索音色；太小的话静音段容易冒出杂音。默认 0.33。",
                    color = AtriTextDim, fontSize = 11.sp)
                Slider(value = st.protect, onValueChange = { vm.setProtect(it) }, valueRange = 0f..0.5f)
            }
        }

        // ---------------------------------------------------------- QQ 语音代发
        Card(colors = CardDefaults.cardColors(containerColor = AtriSurface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("QQ 语音代发", color = AtriCyan, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    // ⚠️ 这里是**悬浮窗**开关，不是无障碍开关。
                    // 悬浮窗是主交互（唯一永远可用的入口），不能藏在别的开关后面 ——
                    // 早先整张卡片都被 if (qqAutoSend) 包着，导致入口不可达。
                    Switch(checked = st.qqFloating, onCheckedChange = { vm.setFloating(it) })
                }
                Text("把合成好的语音播给 QQ 听，让它当成你说的话发出去。",
                    color = AtriTextDim, fontSize = 11.sp)

                val pending = st.qqPendingName
                Text(
                    if (pending != null) "待发送：$pending"
                    else "还没有待发送语音（去「合成」或「变声」页生成一条）",
                    color = if (pending != null) AtriText else AtriTextDim,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )

                // ---- 悬浮小窗：主交互 ----
                Card(
                    colors = CardDefaults.cardColors(containerColor = AtriCyan.copy(alpha = 0.08f)),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("悬浮小窗（推荐用法）",
                                    color = AtriCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "打开后屏幕边上会浮一个小球，浮在 QQ 上面。"
                                        + "按住说话之前点一下它，就播一遍 —— 不用来回切界面。",
                                    color = AtriTextDim, fontSize = 10.sp,
                                )
                            }
                            Switch(checked = st.qqFloating,
                                onCheckedChange = { vm.setFloating(it) })
                        }
                        if (st.qqFloating) {
                            Text("✓ 已开启，可以拖到顺手的位置。不想要时去通知里点「关闭悬浮窗」。",
                                color = AtriCyan, fontSize = 10.sp)
                        } else {
                            OutlinedButton(
                                onClick = { vm.requestFloatPermission() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AtriTextDim),
                            ) { Text("授予「显示在其他应用上层」权限", fontSize = 11.sp) }
                        }
                    }
                }

                // ---- 最关键的一条：必须小窗/分屏保前台 ----
                Card(
                    colors = CardDefaults.cardColors(containerColor = AtriWarn.copy(alpha = 0.16f)),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("⚠️ 注意：必须用「小窗 / 分屏」让 QQ 留在前台",
                            color = AtriWarn, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "如果按返回键切走，QQ 退到后台就不录音了，录出来是一段静音。"
                                + "长按任务卡片选「小窗」，让 QQ 和本 App 同时可见。",
                            color = AtriWarn, fontSize = 10.sp,
                        )
                    }
                }

                Text(
                    "用法：① 切到 QQ 的语音输入 ② 按住「说话」之前点一下悬浮球 "
                        + "③ 音频播完前松手发送。",
                    color = AtriTextDim, fontSize = 11.sp,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { vm.playPendingNow() },
                        enabled = pending != null && !st.qqPlaying,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AtriCyan, contentColor = AtriBg),
                    ) { Text("在这里播一遍", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = { vm.stopQqLoop() },
                        enabled = st.qqPlaying,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AtriPink),
                    ) { Text("停止", fontSize = 12.sp) }
                }
                if (st.qqPlaying) Text("正在播放…", color = AtriCyan, fontSize = 11.sp)

                // ---- 最致命的坑：蓝牙没断 → 外放的声音进了耳机，QQ 录成静音 ----
                if (st.qqBlockedByHeadset) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AtriWarn.copy(alpha = 0.20f)),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("⚠️ 上次没能真正外放：耳机还连着",
                                color = AtriWarn, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "声音被送到了耳机（${st.qqHeadsetName}），没从扬声器出来 —— "
                                    + "手机麦克风收不到，QQ 里会是一段静音。先断开蓝牙 / 拔掉耳机再试。",
                                color = AtriWarn, fontSize = 10.sp,
                            )
                        }
                    }
                }

                // ---- 注意事项③：靠扬声器外放（最需要让用户一眼看到的那条）----
                Card(
                    colors = CardDefaults.cardColors(containerColor = AtriWarn.copy(alpha = 0.16f)),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("⚠️ 注意：变声 / 合成的声音会从「扬声器外放」出来",
                            color = AtriWarn, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "手机扬声器把这段语音放出来，再由手机自己的麦克风收回去给 QQ。"
                                + "没有 root 权限的话，这是唯一可行的办法。",
                            color = AtriWarn, fontSize = 10.sp,
                        )
                        Text(
                            "① 声音会外泄，旁边的人听得到　② QQ 的回声消除可能压掉一点音量，属正常现象。",
                            color = AtriWarn, fontSize = 10.sp,
                        )
                    }
                }
                Text(
                    "音量已自动处理：只抬到系统音量的 60% 并淡入，播完恢复原值，不会突然炸一声。",
                    color = AtriTextDim, fontSize = 10.sp,
                )

                ThinDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("检测到 QQ 录音时自动播（可选）", color = AtriText, fontSize = 13.sp)
                        Text(
                            "需要在系统「无障碍」里手动开启。实测这版 QQ 的录音界面检测不到，"
                                + "多半不管用 —— 不影响上面的悬浮球。",
                            color = AtriTextDim, fontSize = 10.sp,
                        )
                    }
                    Switch(checked = st.qqAutoSend, onCheckedChange = { vm.setQqAutoSend(it) })
                }
                Text(
                    if (st.qqWatchEnabled) "✓ 无障碍服务已在系统中开启"
                    else "✗ 无障碍服务尚未在系统中开启",
                    color = if (st.qqWatchEnabled) AtriCyan else AtriTextDim,
                    fontSize = 10.sp,
                )
                OutlinedButton(
                    onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AtriTextDim),
                ) { Text("去开启无障碍（可选）", fontSize = 12.sp) }
            }
        }

        // ---------------------------------------------------------- 高级设置
        Card(colors = CardDefaults.cardColors(containerColor = AtriSurface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("高级设置", color = AtriCyan, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { showAdvanced = !showAdvanced }) {
                        Text(if (showAdvanced) "收起" else "展开", color = AtriTextDim, fontSize = 12.sp)
                    }
                }
                Text("不熟的项保持默认即可。这里改的是「推理」相关的参数，改了要重新加载模型才生效。",
                    color = AtriTextDim, fontSize = 11.sp)
                if (showAdvanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ParamSlider(
                            label = "说话人 ID  ${st.sid}",
                            hint = "单人模型保持 0 就行。",
                            value = st.sid.toFloat(), range = 0f..108f, steps = 107,
                            onChange = { vm.setSid(it.roundToInt()) },
                        )
                        ParamSlider(
                            label = "拼接重叠  ${st.overlap} 帧",
                            hint = "长音频分块拼接时接缝的平滑程度，一般不用改。",
                            value = st.overlap.toFloat(), range = 0f..256f, steps = 15,
                            onChange = { vm.setOverlap(it.roundToInt()) },
                        )
                        ParamSlider(
                            label = "推理线程  " + if (st.threads == 0) "自动" else st.threads.toString(),
                            hint = "本机核心数 ${Runtime.getRuntime().availableProcessors()}。0 = 自动（推荐）。",
                            value = st.threads.toFloat(), range = 0f..8f, steps = 7,
                            onChange = { vm.setThreads(it.roundToInt()) },
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------- 作者声明
        AuthorCard(ctx)
    }
}

/**
 * 作者声明。纯展示 + 可点击跳转 / 复制，不参与任何推理逻辑。
 */
@Composable
private fun AuthorCard(ctx: Context) {
    fun copyToClip(label: String, value: String) {
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, value))
            Toast.makeText(ctx, "已复制 $label：$value", Toast.LENGTH_SHORT).show()
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = AtriSurface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, contentDescription = null,
                    tint = AtriCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("作者声明", color = AtriCyan, fontWeight = FontWeight.SemiBold)
            }
            Text("本 App 由「不太高性能萝卜籽」个人制作，免费分享，仅供学习交流。",
                color = AtriTextDim, fontSize = 11.sp)
            Text("语音模型与角色立绘版权归原作者所有，请勿用于商业用途。",
                color = AtriTextDim, fontSize = 11.sp)

            ThinDivider()

            Text("B站 · 不太高性能萝卜籽", color = AtriText, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold)
            Text("UID 402371919", color = AtriTextDim, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://space.bilibili.com/402371919")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AtriPink),
                ) { Text("打开B站主页", fontSize = 11.sp) }
                OutlinedButton(
                    onClick = { copyToClip("B站UID", "402371919") },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AtriTextDim),
                ) { Text("复制 UID", fontSize = 11.sp) }
            }

            Text("QQ 交流群：590952998", color = AtriText, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { copyToClip("QQ群号", "590952998") },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AtriCyan),
                ) { Text("复制 QQ 群号", fontSize = 11.sp) }
            }
            Text("进群前先看群公告，常见问题都在里面。", color = AtriTextDim, fontSize = 10.sp)
        }
    }
}

/**
 * 单实例音频播放器。
 *
 * 页面上同时存在多个「试听」（变声结果 / 录音 / TTS 结果），共用一个 MediaPlayer
 * 可以保证同时只有一条在响，切换时自动掐掉上一条。
 */
private class ClipPlayer {
    var playingKey: String? by mutableStateOf(null)
        private set
    private var mp: MediaPlayer? = null

    fun toggle(ctx: Context, key: String, file: File) {
        if (playingKey == key) {
            stop()
            return
        }
        stop()
        try {
            val p = MediaPlayer()
            p.setDataSource(file.absolutePath)
            p.prepare()
            p.setOnCompletionListener { if (playingKey == key) playingKey = null }
            p.start()
            mp = p
            playingKey = key
        } catch (t: Throwable) {
            Toast.makeText(ctx, "播放失败：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun isPlaying(key: String): Boolean = playingKey == key

    fun stop() {
        try { mp?.release() } catch (_: Exception) {}
        mp = null
        playingKey = null
    }
}


/**
 * 首启导入遮罩。
 *
 * 910 MB 从 APK 解包出来要几十秒到几分钟，这期间模型页/合成页都还是「未就绪」，
 * 不解释清楚的话用户会以为装坏了。给个明确的进度 + 一句「只需一次」。
 */
@Composable
private fun ImportOverlay(st: UiState) {
    val frac = if (st.importTotal > 0) st.importDone.toFloat() / st.importTotal else 0f
    Box(
        Modifier
            .fillMaxSize()
            .background(AtriBg.copy(alpha = 0.94f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null,
                tint = AtriCyan, modifier = Modifier.size(40.dp))
            Text("正在导入模型", color = AtriText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("首次启动只需一次，之后打开就是秒进。",
                color = AtriTextDim, fontSize = 11.sp)
            LinearProgressIndicator(
                progress = { frac },
                modifier = Modifier.fillMaxWidth(0.75f),
                color = AtriCyan,
                trackColor = AtriSurface2,
            )
            Text("${st.importDone} / ${st.importTotal}　${(frac * 100).roundToInt()}%",
                color = AtriCyan, fontSize = 13.sp)
            if (st.importName.isNotBlank()) {
                Text(st.importName, color = AtriTextDim, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
            }
            Text("请保持 App 在前台，不要锁屏或切走。",
                color = AtriAmber, fontSize = 11.sp)
        }
    }
}
