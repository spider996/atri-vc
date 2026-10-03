# 亚托莉变声器（ATRI Voice Changer）

把手机变成一个**完全离线**的亚托莉变声器：麦克风收音 → 端侧推理 → 亚托莉的声音。
不需要 root，不需要联网，音频不出手机。

附带一套 **GPT-SoVITS 离线语音合成**（输入文字，用亚托莉的声音念出来），
以及一个「**把这句语音发到 QQ**」的小窗播放器。

---

## 它能做什么

| 功能 | 说明 |
|---|---|
| **变声** | 录音或选一段音频，转换成亚托莉声线。基于 RVC（检索式音色转换） |
| **合成** | 输入文字 → 亚托莉念出来。四种情绪：高兴 / 悲伤 / 生气 / 平淡 |
| **QQ 语音代发** | 把合成结果从扬声器放出来，让 QQ 采走当成你说的话发出去 |
| **收藏库** | 自动保留最常用的 10 条合成结果（LFU 淘汰） |

四个页面：**变声 / 合成 / 模型 / 设置**。

---

## 关于"实时变声"的一个重要说明

**这个 App 做不到通话级的实时变声，请不要期待它像变声器外设那样用。**

原因是模型结构决定的：RVC 的 `net_g` 声码器导出的固定输入长度是 1024 帧，
单次推理最少要吃 10 秒音频。要实时化必须先解决"可变块长导出"的问题，目前还没有攻克。

所以它的正确用法是**"离线转换"**：录一段 → 转 → 播放/发送。

---

## 两个版本

| | 全量包 | 轻量包 |
|---|---|---|
| APK 体积 | **约 941 MB** | 约 63 MB |
| 模型 | 全部打包在 APK 内 | 需自行准备 |
| 首次启动 | **自动导入，装完即用** | 手动推送到设备目录 |
| 适合 | 分享给普通用户 | 开发调试 |

全量包首次启动会把约 910 MB 的模型解包到 App 私有目录（几秒到几十秒，
界面上有进度条和文件名）。**只需一次**，之后启动就是秒进。

模型在 APK 里是 **STORED（不压缩）** 存的，所以安装后能直接按真实字节数
判断"已导入 / 未导入"，重复启动不会重解包。这也是为什么全量包体积接近模型原始大小。

---

## 仓库结构

```
.
├── AtriVC/                       Android 工程（Gradle 三模块）
│   ├── app/                      Compose UI + ORT Android
│   ├── core/                     纯 Kotlin 算法（FFT / Mel / faiss / RVC 管线）
│   ├── cli/                      JVM 逐级对齐工具
│   ├── build-apk.ps1             一键打包（-Full 出全量包，-Install 顺带装机）
│   └── *.md                      端侧移植 / 音频路由可行性 / 真机实测报告
├── tools/
│   └── stage_model_assets.py     汇总模型 → 打包资源树
├── README.md
└── LICENSE
```

模型权重、实验中间产物、真机样音都不在仓库里（见 `.gitignore`）。

---

## 从源码构建

### 环境要求

- JDK 17
- Android SDK（compileSdk 34）
- Gradle 8.7（或用 wrapper）
- 一个 arm64-v8a 的 Android 手机（Android 8.0 / API 26 以上）

### 构建轻量包（开发用）

```bash
gradle :app:assembleDebug
```

产物 `app/build/outputs/apk/debug/app-debug.apk`，约 63 MB，
不含模型 —— 模型要自己推（见下）。

### 构建全量包（分享用）

```bash
# 1) 先把原始 ONNX 汇总到 AtriVC/model_assets/
python tools/stage_model_assets.py

# 2) 带 -PbundleModels=true 构建
gradle :app:assembleDebug -PbundleModels=true
```

产物约 941 MB，装到手机上首次启动会自动把模型解包出来。

PowerShell 下可以直接用封装好的脚本：

```powershell
cd AtriVC
.\build-apk.ps1 -Full        # 出全量包
.\build-apk.ps1 -Full -Install   # 出全量包并安装到设备
```

### 手动推送模型（轻量包）

模型要放到 App 的外部私有目录。三件套走 `models/`，TTS 走 `tts/`：

```bash
PKG=com.atri.vc
DST=/sdcard/Android/data/$PKG/files

# RVC 变声三件套 + 检索库
adb push atri_net_g_t1024_q8.onnx  $DST/models/
adb push hubert_dyn8.onnx          $DST/models/
adb push rmvpe_q8.onnx             $DST/models/
adb push index_mobile.bin          $DST/models/

# GPT-SoVITS 合成
adb push bert.onnx                 $DST/tts/
adb push t2s_encoder.onnx          $DST/tts/
adb push t2s_prefill.onnx          $DST/tts/
adb push t2s_decode.onnx           $DST/tts/
adb push t2s_step_embed.onnx       $DST/tts/
adb push t2s_shared.data           $DST/tts/
adb push vits.onnx                 $DST/tts/
adb push ref_joy.refbin ref_sad.refbin ref_angry.refbin ref_calm.refbin $DST/tts/ref/
```

文本前端的数据表（`symbols.txt` / `charmap.txt` / `poly.txt` …）和参考特征
`refbin` 已经在 APK 的 assets 里，**首次启动会自动解包**，不用手动推。

> ⚠️ Android 11+ 用 `adb push` 推到 `/sdcard/Android/data/` 有时会因属主问题
> 读不到。更稳的做法是 `adb push` 到 `/data/local/tmp/` 再 `run-as` 拷进内部目录。

---

## 下载现成的 APK

GitHub 单文件限制 100 MB，**941 MB 的全量包无法直接放在仓库里**，走 Release 附件：

→ 到本仓库的 **Releases** 页面下载 `atri-vc-v1.0-full.apk`。

装的时候系统可能提示"未知来源"，允许即可。首次启动等进度条走完就能用。

> 如果你的手机是 MIUI / HyperOS，安装大 APK 可能被"安装拦截"挡下，
> 需要在开发者选项里关掉「MIUI 优化」或允许「通过 USB 安装」。

---

## 模型是怎么来的

仓库里**不含**训练好的模型权重，因为体积太大。模型需要自己准备：

### RVC 声线三件套

用 [RVC](https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI)
训练一个目标声线的模型（得到 `.pth` + `.index`），然后导出 ONNX 并做 INT8 量化：

| 文件 | 来源 | 体积 |
|---|---|---|
| `atri_net_g_t1024_q8.onnx` | net_g（声码器）静态 INT8 | 45 MB |
| `hubert_dyn8.onnx` | HuBERT（内容特征）动态 INT8 | 117 MB |
| `rmvpe_q8.onnx` | RMVPE（音高）静态 INT8 | 94 MB |
| `index_mobile.bin` | .index 转移动端 faiss | 30 MB |

> 导出与量化的完整脚本没有随仓库发布（体积与依赖都很重，且与个人环境强绑定）。
> 量化分档的原则写在下面的「关键工程决策」里，照着复现即可。

### GPT-SoVITS 合成

基于 [GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS) v2ProPlus，
拆成 6 个 ONNX + 1 个共享权重：

| 文件 | 作用 | 体积 |
|---|---|---|
| `bert.onnx` | 文本理解（字级 RoBERTa，int8） | 288 MB |
| `t2s_encoder.onnx` | 序列编码器 | 11 MB |
| `t2s_prefill.onnx` / `t2s_decode.onnx` | AR 首帧 / 逐帧 | 各 0.25 MB |
| `t2s_step_embed.onnx` | token 位置嵌入 | 9 MB |
| `t2s_shared.data` | prefill/decode 共享权重 | 145 MB |
| `vits.onnx` | SoVITS 声码器 | 162 MB |

参考音频特征（4 条情绪）是**离线预计算**好的 `.refbin`，
这样端侧就不需要 `ssl` / `sv` / `prompt` / `spec` 四个模型（省掉 543 MB）。

### 模型文件怎么放

`tools/stage_model_assets.py` 会把散落在各处的原始模型汇总成打包用的资源树
（同卷用硬链接，不额外占空间）：

```bash
python tools/stage_model_assets.py
# => AtriVC/model_assets/models/*.onnx    （RVC 三件套 + 检索库）
#    AtriVC/model_assets/tts/*            （TTS 六个模型 + 共享权重）
```

它按脚本顶部的 `PLAN` 表找文件，所以换机器时改一下那几张路径即可。

---

## 技术栈

- **Kotlin** + **Jetpack Compose**（UI）
- **ONNX Runtime Android 1.19.2**（端侧推理，CPU EP）
- 纯 Kotlin 实现的 FFT / Mel / 重采样 / 自研 faiss IVF 检索

三个 Gradle 模块：

- `:core` —— 纯 Kotlin 算法（Wav / Fft / Resampler / Mel / Rmvpe / FaissIvfIndex / RvcPipeline）
- `:cli` —— JVM 逐级对齐工具（把端侧每一步的中间结果和 PC 基准比对）
- `:app` —— Compose UI + ORT Android

### 一些关键的工程决策

- **模型量化按算子构成分档**：纯 MatMul 的部分可以放心动态 INT8；
  Conv 为主的部分（声码器）全量 INT8 会崩（相似度掉到 0.34），必须用 fp16 权重存储。
- **固定 `intra_op_num_threads`**：ORT 的多线程归约不可逐位复现，
  不钉死的话每次跑出来的 INT8 结果会分档。
- **参考特征离线预计算**：这是把 TTS 从 2612 MB 压到 615 MB 的关键一步。
- **音频回环走扬声器**：见下。

---

## QQ 语音代发是怎么回事

这是整个项目里最"绕"的部分，值得单独说清楚。

**免 root 的第三方 App 拿不到别的 App 的麦克风。** 这不是没找到 API，
而是 Android 的设计：`dumpsys media.audio_policy` 里只有 5 个物理输入设备，
**没有任何虚拟麦克风**，第三方 App 无法新增输入设备。

所以唯一可行的路径是**声学回环**：把语音从**手机扬声器**放出来，
由**手机自己的麦克风**收回去，QQ 从麦克风拿到就是"你在说话"。

这条路径有两个硬性前提：

1. **必须让 QQ 保持在前台**（用系统的小窗 / 分屏）。
   按返回键把 QQ 切到后台，它就不采集了，录出来是一段静音。
2. **必须断开蓝牙耳机**。耳机连着的时候 `STREAM_MUSIC` 会被路由到 `bt_a2dp`，
   声音进了耳机不进空气，手机麦克风收不到 —— 现象是"播了但 QQ 里没声音"，
   这个坑非常隐蔽，App 里专门做了红色警告。

我们实测过蓝牙耳机的声学回环：**回环增益 −42.9 dB**（不通）。
扬声器外放：**+28.1 dB**（通）。差距一目了然。

App 里的实现：一个悬浮球（`SYSTEM_ALERT_WINDOW` 前台服务）浮在 QQ 上面，
按"按住说话"之前点一下它就播一遍。播放时音量只抬到系统音量的 **60%**
并做 350ms 淡入，播完恢复原值 —— 拉满会吓人一跳。

> 顺带一提：市面上某些"免 root 变声器"其实是联网下载预录语音包再播出来，
> 并没有变声能力。判断方法很直接：拉下 APK 看有没有 `RECORD_AUDIO` 权限。

---

## 已知限制

- **做不到实时变声**（原因见上）
- 端侧合成较慢：3 秒语音大约 **36–86 秒**（全在手机上算）
- QQ 代发需要手动开小窗 + 手动点悬浮球，无法全自动
- 只用 CPU 推理（未接 NNAPI / GPU）
- 变声支持 arm64-v8a；x86_64 仅供模拟器

---

## 作者

- **B站**：[不太高性能萝卜籽](https://space.bilibili.com/402371919)（UID 402371919）
- **QQ 交流群**：590952998

---

## 版权声明

本项目代码以 **MIT License** 开源（见 `LICENSE`）。

**但请注意，仓库与 APK 中包含的模型权重和角色素材是另外一回事：**

- 亚托莉（ATRI）的角色形象、立绘、语音素材，
  版权属于 **ANIPLEX.EXE / Frontwing**（《ATRI -My Dear Moments-》）。
- RVC 与 GPT-SoVITS 的基座模型遵循各自的原始许可。

本项目**仅供个人学习与技术交流，禁止任何商业用途**。
如果版权方有异议，请提 issue，我会立即移除相关素材。

**下载即代表你理解：请勿将角色语音用于任何违法、侵权或骚扰他人的用途。**
