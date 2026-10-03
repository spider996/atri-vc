# QQ 语音代发（最终采用：**扬声器外放 → 手机麦**）

> **2026-10-02 方案已改**。完整调研：`AtriVC/AUDIO_ROUTING_FEASIBILITY.md`
> 真机：小米 25060RK16C（Android 16 / HyperOS OS3.0.301.0.WONCNXM）+ iKF-King Pro⁺ 蓝牙耳机

## 用户约束（决定方案选型）

1. **不 root**（HyperOS 3 解锁要社区 5 段 + 答题 + 实名 + 14 天 + 每年 3 台，
   **清空数据**且**失去 OTA** → 用户明确「放弃 root 这个方案」）
2. **不同设备通用**（用户将开源）
3. 尽量不用手机扬声器 ← 但**这条已被现实否决**，见下

→ 排除 LSPosed / Magisk / Shizuku（设备相关）；排除一切 root 方案。
→ 非 root 下**不存在**虚拟麦克风 API（真机取证：只有 5 个物理输入设备，无虚拟设备）。

## ⭐ 转折：用户指出「橘雪梨变声器能做到」

这推翻了「非 root 不可能」的过悲观结论。查证后发现**真正的通路是外放回采**：

**同类产品官方教程原文**（橘雪莉语音盒）：

> 想在 QQ 里顺利开启变音整活吗？……**必须依托系统自带的小窗口或者应用双开小屏功能**
> 才可以完美激活；切进游戏里往往不管用，因为游戏官方从底层做了权限屏蔽。
> 依旧以**小米机型**作示范，仅在系统支持**分屏或悬浮小屏**的模式下才能派上用场。

**关键洞察**：成败不在「注入音频」，而在 **QQ 是否还保持前台、麦克风是否还在采集**。
用返回键切到变声器 → QQ 退后台 → 采集被掐 → 「录音里没有任何声音」。
这正是本项目最初遇到的现象，而不是耳机回环的问题。

## ✅✅ 外放通路实测成立（2026-10-02，最终数据）

`HeadsetLoopTest` 第 3 组，**并先确认蓝牙已断开**（`svc bluetooth disable` → `state: OFF`）：

| 项目 | 值 |
|---|---|
| 静音基线 | **−49.0 dBFS** |
| 播放时能量 | **−20.9 dBFS** |
| **外放增益** | **+28.1 dB** ✅ |
| 外放输出设备 | **`25060RK16C`**（手机本体，不是耳机）|
| 实际输入设备 | 手机麦 |

同轮耳机组：**−42.9 dB**。**+28.1 vs −42.9**，一眼定生死。

⚠️ **测外放组前必须断开蓝牙。** 第一次测时耳机还连着 → 输出设备是 `iKF-King Pro⁺`、
静音与播放都是 **−140.0 dBFS**（空值），因为声音全走耳机、根本没进空气。
**这个现象极易被误判成「外放也不行」，务必先断蓝牙。**

## ❌ 实测证伪：蓝牙耳机声学回环**不成立**（2026-10-02）

**结论：方案作废。** 用 `HeadsetLoopTest`（`LOOPTEST` 调试通路）实测：

| 项目 | 值 |
|---|---|
| 静音基线 | **-112.7 dBFS** |
| 播放+录音 | **-114.5 dBFS** |
| 回环增益 | **-1.8 dB**（负数 = 播放时比静音还安静） |
| 实际输出设备 | iKF-King Pro⁺ ✅ |
| 实际输入设备 | iKF-King Pro⁺ ✅ |

**设备层面全对（输出/输入都是耳机名），但声音完全传不过去。**

### 根因：A2DP 与 HFP/SCO 是**两条独立的蓝牙链路**

- `A2dpStateMachine state=Connected`（放音，高音质，**不走 SCO**）
- `HeadsetStateMachine state=Connected`（HFP，麦克风在这条路上）
- 耳机硬件**不会**把「喇叭放出来的」和「麦克风收到的」内部接通 ——
  它们在蓝牙协议层就是分离的，不存在物理声学回环。
- 唯一可能的声学路径是「耳机喇叭 → **空气** → 耳机麦」，
  但入耳式耳机喇叭冲着耳道、麦克风朝外，两者被耳壳隔开，声压极低。
- 旁证：`录音source = 7`（VOICE_RECOGNITION），说明 `VOICE_COMMUNICATION(3)`
  都没拿到 → SCO 输入根本没被真正激活。

### 这个实验方法值得保留

`HeadsetLoopTest.kt` + `LOOPTEST` 触发文件是**判别任何「声学回环」方案成立与否的标准工具**：
测「静音基线」与「播放时能量」的差值。差值 < 3 dB 就是没回环。
比「用耳朵听 QQ 里有没有声音」快得多、也可靠得多。

## ⚠️ API 坑：`AudioEffect` 在 android.jar 里是 @hide 裁剪桩

**有**：`queryEffects()` / `getDescriptor()` / `setEnabled(boolean)` / `release()` / `getId()`

**没有**（编译期 `Unresolved reference`）：
```
AudioEffect.create(UUID, UUID, int, int)
AudioEffect.EFFECT_TYPE_NULL
Equalizer.numberOfEffects / getEffect(int)
```

**正确写法** —— 三个**公开工厂类**（都在 android.jar 里）：
```kotlin
AcousticEchoCanceler.create(sessionId)?.setEnabled(false)
NoiseSuppressor.create(sessionId)?.setEnabled(false)
AutomaticGainControl.create(sessionId)?.setEnabled(false)
```
三个类都 `extends AudioEffect`，可调基类公开的 `setEnabled` / `release`。

⚠️ **语义边界**：`create(sessionId)` **只对自己拥有的 session 生效**（别人的返回 null）。
所以只能保证**我们播放这条通路干净**，关不掉 QQ 录音通路上 QQ 自己的 AEC。

另：`MediaPlayer.getRoutedDevice()` 在 `start()` 后**立刻**调常返回 null（通路未建好）
→ 需短轮询（本项目 150ms × 最多 12 次）。

## ⚠️ 非 root 做不到的三件事（开源文档需诚实保留）

1. **无法把音频直接注入别的 App 的麦克风** —— 无此 API
   （`CAPTURE_AUDIO_OUTPUT` 是 `signature|privileged`；
   `AudioPlaybackCapture` 明确排除 `USAGE_VOICE_COMMUNICATION`）
2. **无法自动结束 QQ 录音** —— 需 `INJECT_EVENTS`，signature 级
3. **无法自动检测 QQ 正在录音** —— 这版 QQ 录音面板是自绘 View，不进无障碍树
   （按下录音键后无障碍树**零变化**；「按住说话」在按下**之前**就存在，不能当判据）

### 交互形态（2026-10-02 定稿 —— 被用户否掉两次后的结论）

**❌ 已废弃：循环播放 20 秒。** 三个致命问题：

| 问题 | 说明 |
|---|---|
| 对不齐 | QQ 只取「按下 → 松开」那一段，循环多放的全是白放 |
| 串音 | 松手后音频还在响，会被下一次录音录进去 |
| 吵 | 长时间连续外放，还盖过用户自己的说话声 |

**✅ 现行：悬浮球 + 点一次播一遍。**

```
QQ 在前台 → 悬浮球浮在上面 → 点一下 → 播一遍 → 播完即停
```

用户自己掐点：先点悬浮球，再按住说话。这正是同类产品（橘雪梨）的**交互形态**——
我们照抄形态，但走自己实测成立的**外放回采**原理。

- 悬浮球：`FloatingPlayService`，`SYSTEM_ALERT_WINDOW` 前台服务，58dp，可拖动（位置落盘）
- 点一次播一遍：`VoiceSender.playNow()`；`QQ_LOOP_SEC`（12s）只留给无障碍自动触发那条路
- 为什么循环还留着：无障碍触发时用户还没切到 QQ，需要多留一点时间
`QqWatchService` 保留（将来 QQ 版本若暴露录音态即可全自动）。

## 实现

| 文件 | 内容 |
|---|---|
| `VoiceSender.kt` | `Route{HEADSET,SPEAKER}`；`applyRoute`/`hasHeadset`/`headsetName`/`inputName`/`disableEffects`/`isHeadsetOut`；`playNow`/`playLoop(秒)`/`arm`/`bind`+落盘/`onState` |
| `HeadsetLoopTest.kt` | `run(ctx,wav)` → 三组对比：**① A2DP 耳机回环 ② 主动 SCO ③ 外放→手机麦**；`LOOPTEST` 文件触发 |
| `QqWatchService.kt` | **轮询**为主；`RECORD_HINTS`/`IDLE_HINTS`；`qq_dump.txt` 转储诊断 |
| `res/xml/qq_watch_config.xml` + Manifest | 只订阅 `com.tencent.mobileqq` |
| 设置页 | 开关 + 耳机/外放切换 + 耳机状态行 + 待发文件 + 开始代发/停止/试播一次 + 「上次实际输出设备」 |

### 外放模式的实现要点（**这才是能用的那条**）

| 项 | 值 | 理由 |
|---|---|---|
| AudioAttributes | `USAGE_MEDIA` + `CONTENT_TYPE_MUSIC` | 走媒体通路，**避开** `VOICE_COMMUNICATION` 的通话链路（那条会触发耳返/降噪，反而压制外放） |
| 音量 | 抬到 max 的 **60%** + 350ms 淡入，播完恢复 | 拉满会「吓人一跳」；离得近反而让麦克风前级限幅 |

### 音量的三条措施（用户原话：「别把音量一下子挑满，很容易吓人一跳」）

```kotlin
// ① 只抬到 60%，且仅在「当前更小」时才抬（尊重用户自己调过的音量）
val target = (maxVol * 0.60f).toInt().coerceIn(1, maxVol)
if (before < target) { savedVolume = before; am.setStreamVolume(STREAM_MUSIC, target, 0) }

// ② setVolume 淡入：start() 前先归零，之后 25ms 一步推 14 步
player.setVolume(0f, 0f); player.start(); fadeIn(player)

// ③ 三个恢复点缺一不可
//    onCompletion（正常播完）/ stopInternal（手动停）/ catch（异常）
```

⚠️ 漏掉任何一个恢复点，都会把用户的系统音量永久改掉。

### ⚠️ 最隐蔽的坑：蓝牙没断时「外放」是假外放

耳机连着时 `STREAM_MUSIC` 会被路由到 `bt_a2dp` —— 声音进耳机、**不进空气**，
手机麦收不到，QQ 录成静音。实测确实撞到过（`Devices: bt_a2dp(80)`）。

用户看到的现象是「点了播，但 QQ 里还是没声音」，**几乎不可能自己联想到是蓝牙没断**。
所以 `VoiceSender.lastBlockedByHeadset` 会把这个状态暴露出来，设置页弹红色警告卡片。

### 三处必须记住的 Kotlin/Android 坑（都踩过）

- **`var route` 自动生成 `setRoute()`，与 `fun setRoute(r)` JVM 签名冲突**
  （`var armed` + `fun setArmed` 同款）→ 函数改名 `applyRoute` / `setArmedState`
- **定时自动停必须回调 ViewModel**，否则 UI 停在「正在外放中」，按钮一直禁用

⚠️ 待发文件/开关**必须落盘**（`qq_pending.txt`，3 行：armed / 路径 / route）：
无障碍服务可能是系统单独拉起的新进程，内存变量全丢。

## 诊断配方（换 QQ 版本 / 换机时先跑）

```
adb shell dumpsys media.audio_policy | grep -A2 "STRATEGY_PHONE|STRATEGY_MEDIA" | grep "Selected Device"
adb shell dumpsys media.audio_policy | grep 'AUDIO_DEVICE_IN'   # 确认有没有虚拟输入设备
adb logcat -s AtriVC:* | grep -E 'VoiceSender|SpeakerLoop|HeadsetLoopTest'
```

**跑外放组自测前，先 `adb shell svc bluetooth disable`** —— 否则声音全走耳机，
测出来全是 −140 dBFS 空值，会被误判成「外放也不行」。

## ⚠️ 必须写进开源文档的用户须知

1. **必须用系统小窗/分屏让 QQ 保持前台** —— 否则 QQ 退后台会暂停采集，
   表现就是「录音里没有任何声音」（橘雪莉官方教程的核心提示）
2. **会外泄** —— 旁边人听得到
3. **QQ 的 AEC 可能压制一部分** —— 那条 session 我们关不掉
4. 更稳的替代：**物理回环线**（TRRS 分线 / USB 声卡），真电信号、不受 AEC 影响、静音，
   但需外接硬件 → 建议做成「可选增强」
