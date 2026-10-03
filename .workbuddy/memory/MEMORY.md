# 项目长期记忆：亚托莉变声器（Android 端离线变声 App）

**目标**：手机端 RVC 变声（麦进 → 亚托莉声音出）+ GPT-SoVITS 离线 TTS，全部端侧推理。
**栈**：Kotlin/Compose + ONNX Runtime Mobile + INT8/FP16 + AAudio。
**已否掉**：TTS「合成模式」参数；实时通话路由（用户明确不做）；**循环播放代发**。

> 专题细节在独立文件，本文件只留索引 + 铁律：
> `TECH_RVC_ONNX.md`（三件套导出/量化/对齐）· `TECH_GPT_SOVITS.md`（TTS 全 ONNX）·
> `TECH_ANDROID_PORT.md`（App 结构/真机性能/调试通路）· `TECH_QQ_VOICE.md`（代发方案）

---

## 一、环境事实（别再重查）

- **RVC 环境**：`D:\AI翻唱技术\RVC20240604Nvidia50x0\RVC20240604Nvidia50x0`
  - `runtime\python.exe`(3.9.13) = torch 2.8.0+cu128 / fairseq / onnx 1.19.1 / **ORT 1.19.2** / librosa；GPU RTX 5060 Laptop
  - **量化一律用这个 runtime**（GPT-SoVITS 的 onnx 1.14 与 ORT 1.19.2 不兼容，报 `AttributeError: INT4`）
  - v2 的 HuBERT = `assets/hubert/hubert_base.pt`（**fairseq，不是 ContentVec**），output_layer=12 不加 proj → 768 维
- **GPT-SoVITS 环境**：`D:\GPT-SoVITS-v2pro-20250604\...\runtime\python.exe`
- **managed python 3.13 无 numpy** → npz 脚本必须用上面两个 runtime
- **faiss 打不开中文路径**（C++ fopen）→ 已复制到 `D:/tmp_atri/`
- **亚托莉游戏原声** 2222 条：`D:\atri_voice\wav\`
- **Android 构建**：JDK17 `D:\AndroidDev\jdk17` / SDK `D:\AndroidDev\Sdk` / Gradle `D:\AndroidDev\gradle\gradle-8.7`；设备 `AI9X5P5HORLR5HB6`
- ⚠️ **工具链缺陷**：Bash 缺 ls/head/tail/grep（**必须用 PowerShell**）；PowerShell 5.1 中文乱码（用 Python 写盘再读）；Gradle 日志是 **UTF-16**（须 `decode('utf-16')`）
- ⚠️⚠️ **Edit 工具在含中文的 Kotlin 文件上会「假成功」** → **Kotlin/大文件改动一律用 Python 补丁脚本 + `assert` 计数校验**
- ⚠️ **构建前先 `Remove-Item tools\build_real.log`**，并用 mtime 交叉验证，否则会读到陈旧日志误判"补丁没生效"
- ⚠️ `build-real.ps1` 在 **`AtriVC\`** 下（不在 `tools\`）；日志固定写 `tools\build_real.log`
- ⚠️ **`build-real.ps1` 的 adb 会抢跑**：脚本里 `adb install` 是第一条 adb 命令，
  若守护进程没在跑，它启动守护进程后**本次调用仍返回 `device '<serial>' not found`** → 安装失败（构建本身成功）。
  **手动重跑一次 `adb install -r` 即 Success**。判据：日志尾部有 `* daemon not running; starting now at tcp:5037`
- ⚠️ **日志是混合编码**：gradle 段 UTF-16LE（`*>` 重定向）+ 追加段 UTF-8（`Out-File -Encoding UTF8`）。
  整体按 utf-16 解码会把追加段变乱码；**尾部改用 `open(p,'rb').read()[-900:].decode('utf-8','replace')`**
- ⚠️ **`input swipe` 在这台 ROM 上会误切 Tab**（实测两次把「设置」切到「合成」/「变声」）→
  单次慢速滑（duration ≥ 800ms），且每次滑动后 **dump UI 确认当前 Tab**，别连滑后直接截图
- ⚠️ **Compose 的 `Switch` 不进无障碍树**（`uiautomator dump` 找不到）→ 只能按布局推坐标
- ⚠️ `adb shell` 里 `run-as <pkg> sh -c 'cat > file'` **重定向会失败**（PID namespace）；改 prefs 走 UI 或 `appops`

## 二、RVC：素材与基线

- **声线模型**：`C:\Users\21264\Desktop\ATRI语音模型\ATRI_model_e150_s136800.pth`（v2 / 40k / f0=1 / 150ep，全 fp16，55.1MB）
- **基线素材（用户指定）**：`杨丞琳 - 雨爱` 去混响干音 → `tools/rvc_baseline/src_yuai.wav`（162.5s 起 20s）
  - 用户否掉「宇宙冷漠」：AI 歌 + 男声，跨女声会破音。数据证实：F0 八度跳变率 2.26% vs **0.23%**
- **回归门槛**（含 index=0.3）：F0 Pearson **0.928/0.929**、mel 余弦 **0.684/0.664**、静音漏音 **0.014/0.011**
- ⚠️ **NSF 声码器上「波形 cos」是无效判据**（含随机激励噪声），只看频谱级指标与 F0/mel

## 三、RVC ONNX 三件套（INT8，已跑通）

| 模型 | fp32 | INT8 | 压缩 |
|---|---|---|---|
| net_g | 122.42 MB | **47.61 MB** | 2.57x |
| HuBERT | 377.82 MB | **122.47 MB** | 3.08x |
| RMVPE | 361.69 MB | **98.58 MB** | 3.67x |
| **合计** | 861.93 MB | **268.66 MB** | **3.21x** |

耗时（PC CPU，20s）：net_g **66%** / RMVPE 22% / index 6% / HuBERT **仅 5%** → **参数量 ≠ 耗时**。细节见 `TECH_RVC_ONNX.md`。

## 四、Android App（Gradle 三模块）

`:core`（纯 Kotlin 算法）| `:cli`（JVM 逐级对齐）| `:app`（Compose + ORT Android）。
4 个 Tab：变声 / 合成 / 模型 / 设置。细节见 `TECH_ANDROID_PORT.md`。

⚠️ **做不到低延迟实时**：net_g 固定 1024 帧 = 单次最小延迟 10s。实时化前提 = 可变块长导出（卡在 `T was inferred to be a constant`）。

## 五、GPT-SoVITS v2ProPlus 全 ONNX + 端侧（四情绪端到端已通 ✅）

细节与提速方向见 `TECH_GPT_SOVITS.md`。

⚠️⚠️ **EOS 门控 off-by-one（2026-10-02 修复，必看）**：AR 循环里「前 minSteps 步**禁用** EOS」的条件**必须写对**。
写反 → EOS(1024) 被写进 `sem` → vits 的 `codes` Gather 越界崩溃
（`idx=1024 must be within [-1024,1023]`），表现为「某些文本合成到一半直接失败」。
铁律：**EOS 是哨兵，永远不准进 vits**（vits codes 词表只有 0..1023）。
基准对齐 `tools/gsv_onnx_pipeline.py:235`；修法见技能 `android-onnx-port-verify` 第 9.3 节。

## 六、QQ 语音代发 → **扬声器外放 → 手机自带麦**（唯一可行）

细节见 `TECH_QQ_VOICE.md`。

| 路线 | 结论 | 依据 |
|---|---|---|
| 蓝牙耳机声学回环 | ❌ 实测证伪 | 回环增益 **−1.8 / −16.8 dB**；`startBluetoothSco()=false` |
| root / LSPosed 注入 | ❌ 用户放弃 | HyperOS 3 解锁要 5 段+答题+实名+14 天，清数据 + 失 OTA |
| **扬声器外放 → 手机麦** | ✅ **采用** | 回环增益 **+28.1 dB**；输出设备 `25060RK16C` |

- **非 root 铁律**：`dumpsys media.audio_policy` 只有 5 个物理输入设备，**无任何虚拟麦克风**。
- **致命前提**：**QQ 必须保持前台**（退后台暂停采集 → 录音是静音）。
- ⚠️ **最隐蔽的坑**：**蓝牙没断时外放无效** —— `STREAM_MUSIC` 被路由到 `bt_a2dp`，
  声音进耳机不进空气，手机麦收不到。现象是"播了但 QQ 里没声音"，用户极难自己想到。

### 橘雪梨语音盒（`com.bluegogo.bluegogo.qw.jxl`）—— 已拆包证伪

**无 `RECORD_AUDIO`、无变声 DSP/NN 库、APK 内 0 个音频文件、
运行时 `No active record clients`、主界面是 WebView 壳。**
它只是播放联网下载的预录语音包 → **「不用外放」的前提取证不成立**。
**但它的小窗交互形态是对的**，我们照抄形态、走自己实测成立的原理。

**方法论**：某 App 宣称能做到我们做不到的事时，**直接拉 APK 查权限清单**，比搜索/推理都硬。
详见技能 `android-audio-loopback-verify`。

## 七、交互形态（2026-10-02 定稿，用户三次反馈后的结论）

1. **悬浮小窗**（`FloatingPlayService`，`SYSTEM_ALERT_WINDOW` 前台服务）——
   浮在 QQ 上面点一下就播，不用来回切界面。**这是主交互。**
2. **点一次播一次，不循环** —— 循环对不齐（QQ 只取按下到松开）、会串音、很吵。
   循环只留给无障碍自动触发那条路（`QQ_LOOP_SEC=12`）。
3. **音量不拉满** —— 抬到 max 的 **60%** + `setVolume` 350ms 淡入 + 播完/停止/异常**三个点都要恢复原值**。
   （最初定 70%，用户实测仍偏响 → 降到 60%）

⚠️ 交互层级教训：**主交互不能被别的开关挡住**。曾把整张 QQ 卡片包在
`if (qqAutoSend)` 里，而它默认 false → 悬浮窗入口不可达。
⚠️ 老用户迁移：`qqHeadsetMode=true` 是已证伪时代的默认值，必须有一次性迁移
（`routeMigrated` 版本键），否则升级后仍停在耳机模式。

## 八、UI 合成「失败/闪退」排查（2026-10-01）

真因三条（**都不是 OOM**）：① 缺 `onStep` 回调 → 进度条卡 30% 约 80s；② 语言选择器是空操作（端侧 G2P 只支持中文）；③ G2P 报错不可执行。均已修。

## 九、UI 简化 + 亚托莉图标 + LFU 收藏库（2026-10-02）

- **音量 70% → 60%**：`VoiceSender.TARGET_VOLUME_RATIO = 0.60f`（用户反馈 70% 仍偏响）。
- **文案精简**：删掉所有面向开发者的内部说明（`t2s_shared.data`、adb 路径、文件清单、faiss 等）；
  「参考音频」控件**从未生效**（引擎只读预设 4 条情绪参考特征）→ 已删除，其说明并入「情绪选择」。
- **LFU 收藏库**（`TtsLibrary.kt`）：最多 10 条，索引 `tts_library/index.tsv` =
  `文件名\t计数\t最近使用\t情绪\t文本`；试听 `touch()` 计数 +1；超 10 条淘汰
  「计数最小、同计数则最久未用」（LFU + LRU 兜底）。合成成功自动入库；`wav` 副本同目录。
- **亚托莉图标**：源图 `C:\Users\21264\Desktop\mod\亚托莉图片素材\大图\image.png`（700×700 RGBA）；
  `mipmap-anydpi-v26/ic_launcher.xml` 自适应图标，**background = 整幅立绘出血铺满**（套任何遮罩都不缺脸）；
  界内头像 `drawable-nodpi/atri_avatar.png`（512，裁 `110,10,650,550`）。
  **原作版权素材，仅本地个人自用，不对外分发。**
- **作者声明**（设置页最底部 `AuthorCard`）：B站「不太高性能萝卜籽」`UID 402371919`
  （可点开主页 / 复制），QQ 交流群 `590952998`（可复制）。
- **注意事项一律用红**：主题新增 `AtriWarn = 0xFFFF5252`（深蓝底对比度 ~5:1，达 AA）。
  三处警示卡（小窗/分屏保前台、耳机没断、**扬声器外放**）全部红底红字；
  「扬声器外放」这条从灰色小字升级为红卡（用户点名要醒目）。纯说明性文字仍用灰。

## 十、待办

1. 端侧合成提速（当前 36–86 s / 3 秒语音）：prefill/AR attention、vits 加速
2. 导出可变块长 net_g（**实时化前提**）
3. 图优化消除冗余 Transpose + Q/DQ（预期 ~21%）
4. TTS 模型分发（615MB 不能进 APK，需首启下载 + SHA 校验）
5. 可选再压：bert INT4（288→~150MB）、vits 静态量化
6. 主观听感验收（四情绪真机音频在 `tools/tts_device/*.wav`）
7. **端到端 QQ 实测**：需用户开系统小窗 + 按住说话（自动化做不了：检测不到 QQ 录音态、无法注入手势）
8. **真机主观验收**：亚托莉图标视觉 / 精简后文案 / 收藏卡片（试听·发送·删除）/ 音量 60% 实际听感
   （`input` 注入在此 ROM 不稳定，需用户手动过一遍）
