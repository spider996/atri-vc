# Android 端移植（AtriVC）

## App 结构

`:core`（纯 Kotlin：Wav/Fft/Resampler/Mel/Rmvpe/FaissIvfIndex/RvcPipeline）|
`:cli`（JVM 逐级对齐）|
`:app`（Compose UI + ORT Android）

- JDK17 / Gradle 8.7 / AGP 8.5.2 / Kotlin 2.0.21 / Compose BOM 2024.09.03 / **onnxruntime-android 1.19.2**
- abiFilters = arm64-v8a + x86_64；APK 46.95MB（未含模型）；minSdk 26 / compileSdk 34
- 模型走外部私有目录（`adb push` 可写，App 可读）或内部目录；`ModelStore.candidateDirs()` 双目录探测
- 索引：自研 `ATRIIDX1`（30.09MB）+ `FaissIvfIndex.kt`
- TTS：`TtsEngine.kt`（AR 循环 + vits）· `TtsLibrary.kt`（**LFU 收藏库**，上限 10 条，
  索引 `tts_library/index.tsv` = `文件名\t计数\t最近使用\t情绪\t文本`；试听计数 +1，超限淘汰
  「计数最小、同计数则最久未用」）
- 图标（2026-10-02）：`mipmap-anydpi-v26/ic_launcher.xml` 自适应图标，
  **background = 亚托莉立绘出血铺满**（套任何遮罩都不缺脸）；界内头像 `drawable-nodpi/atri_avatar.png`
  （源图版权素材，仅本地个人自用）

## ⚠️ ORT `intra_op_num_threads` 会让 INT8 结果「分档」

同一文件同一输入只改该参数：默认/16/20/22/24/32 → `hubert_feat` maxAbs **0.000e+00**；
4/8/12 → cos 0.99409469。

机制：MLAS INT8 QGEMM 按线程数决定 K 维是否切分 → 量化累积分组不同。
**核心数 <16 的设备必然落在 B 档。** 对策：`VcEngine.load(threads=0)` 跟随默认；`threads.txt` 可覆盖。

→ **教训：判定「是不是平台差异」前，先在同一平台只改可疑参数尝试复现。**

## 真机：小米 25060RK16C（天玑 MT6991 / 8 核 / Android 16 / arm64 asimddp+i8mm+sve2+bf16）

- 侧载被 HyperOS 拦（`INSTALL_FAILED_USER_RESTRICTED`）→ 手机侧开「开发者选项 → 通过 USB 安装」
- 正确性 PASS：mel 0.99998 / rmvpe 0.99984 / f0 0.99944；
  `hubert cos 0.99313` = arm64 NEON 与 x86 AVX2 累加顺序不同，跨架构不可能逐位一致
- 性能（20s 音频）：CPU 串行 15621ms(0.784) / **块并行 4 = 13426ms(0.671) ← 最优** /
  XNNPACK 14641ms / **NNAPI 崩溃**（`AddNnapiSplit dimension 1 [192]`）
- 已实现 **net_g 块级并行**（顺序准备输入保 rnd 序列 → 并发 run 同一 Session → 顺序 overlap-add）：
  **-16.6%**，par=4 vs par=1 数值逐位等价
- 算子剖析（net_g 12510ms / 10188 node）：
  QLinearConv 49.5% / ConvTranspose 14.5% / **Transpose 11.9%（1179 次）** / Q+DQ 9.2%
  → Transpose+Q/DQ 21% 是白给；CPU 利用率仅 ~3.5/8 核
- ⚠️ **做不到低延迟实时**：net_g 固定 1024 帧 = 单次最小延迟 10s，与 50–200ms 差两个数量级；
  缩小块长不线性变快。**实时化前提 = 可变块长导出**（卡在 `T was inferred to be a constant`）

## 调试通路

`real_run.ps1 -InputMode real|raw16k|raw16k_nornd -Ep cpu|xnnpack|nnapi -Parallel n [-Profile]`；
外部开关 `AUTORUN` / `threads.txt` / `ep.txt` / `parallel.txt`；构建 `build-real.ps1 -Serial <sn>`

⚠️ **adb 多设备**：不带 `-s` 会静默跑偏
⚠️ `enableProfiling(path)` 参数是**文件名前缀不是目录**

交付：`ANDROID_PORT_REPORT.md`、`REAL_DEVICE_REPORT.md`、`deliverables\`、`tools/shots\`
