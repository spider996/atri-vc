# 变声器 ↔ QQ 麦克风：可行性调研与最终方案

> 原始需求：「让变声器和 QQ 的麦克风直接接通，使 QQ 的语音可以发送变声器已经转换好的语音」
>
> 追加约束（用户明确要求）：
> - **必须在不 root 的情况下工作**
> - **必须在不同设备上通用**（用户将会开源这个项目）
>
> 调研 + 真机实测：2026-10-01 ～ 2026-10-02
> 真机：小米 25060RK16C（天玑 MT6991 / Android 16 / HyperOS OS3.0.301.0.WONCNXM）

---

## 一、结论先行（已按实测修订）

| 用户诉求 | 非 root 能否做到 | 结论 |
|---|---|---|
| ① 不用手机扬声器 | ⚠️ **建议放弃这条** | 见第三节，耳机路线实测证伪 |
| ② 把音频「送进」QQ 的麦克风 | ✅ **能，但走的是外放回采** | 见第四节 |
| ③ 播完自动结束 QQ 录音 | ❌ **做不到** | 需 `INJECT_EVENTS`（签名级） |
| ④ 自动检测 QQ 是否在录音 | ❌ **在这版 QQ 上做不到** | 录音面板是自绘 View，不进无障碍树 |

**一句话结论**：**非 root 下不存在「虚拟麦克风」API**（本次已在真机上核实设备列表，
见 2.1），**也不存在「蓝牙耳机声学回环」这条路**（实测证伪，见第三节）。
唯一可行的是**「扬声器外放 → 手机自带麦克风 → QQ」**，而这条路早就在项目里实现了
（`Route.SPEAKER`）。

### 1.1 关于「橘雪梨语音盒能做到」——已逆向拆包，结论明确

用户反馈「橘雪梨语音盒在小窗模式下，QQ/微信录音不用外放就能变声」。
**为验证这个前提，把该 App 的 APK 从设备上拉下来拆解**（`com.bluegogo.bluegogo.qw.jxl` v1.04，30.11 MB），
结论：**它不具备任何变声能力，其声音必然经过空气（或耳机）传播。**

铁证如下：

| 检查项 | 结果 | 说明 |
|---|---|---|
| `RECORD_AUDIO` 权限 | **✗ 没有** | 它**连读麦克风的能力都没有** |
| `MODIFY_AUDIO_SETTINGS` 权限 | **✗ 没有** | 无法改音频路由 |
| `CAPTURE_AUDIO_OUTPUT` 权限 | **✗ 没有** | 无法抓取音频输出 |
| 原生 SO 库 | 全是广告 SDK | pangle/穿山甲、octopus、sgcore、windmill、kwad、sigmob |
| DSP / 神经网络变声库 | **一个都没有** | 无 SoundTouch / rubberband / WSOLA / TFLite |
| APK 内置音频文件 | **0 个**（按魔数扫描）| 语音素材是**联网下载**的 |
| 运行时录音客户端 | `- No active record clients` | 启动后系统里**没有任何录音会话** |
| 主界面实现 | `com.webviewapp.pro.main` | **WebView 壳** |

**它实际做的事**：从服务端拉一批**预录语音包**，你点一下就**从扬声器大声播出来**。
所谓「原声/胖猫/花栗鼠」是它素材库里的**分类标签**，不是你说话被实时改音色。
「小窗模式」的作用只是**让你能一边看 QQ 一边点播放按钮**——与"不用外放"无关。

**所以要诚实地说**：那个「不用外放也能进 QQ 麦克风」的前提，在证据层面不成立。
如果确实观察到完全静音也能录入，那是值得深挖的新线索；
但基于当前证据，唯一成立的物理通路仍是**声学回采**。

---

## 二、为什么「真正注入麦克风」在非 root 下不可能（已核实）

### 2.1 音频架构层面没有「虚拟输入设备」（真机实测证据）

Android 采集链路：`App → AudioRecord → AudioFlinger → AudioPolicyManager → HAL → 物理麦`。
`AudioPolicyManager` 从 `audio_policy_configuration.xml` 的**静态 HAL 设备枚举**里挑输入设备。

真机取证（`adb shell dumpsys media.audio_policy`）：

```
Available input devices (5):
  1. Port ID: 18;    "Built-In Mic";        {AUDIO_DEVICE_IN_BUILTIN_MIC, @:bottom}
  2. Port ID: 14378; "BT SCO Headset Mic";  {AUDIO_DEVICE_IN_BLUETOOTH_SCO_HEADSET, @:B0:F0:0C:67:DD:CC}
  3. Port ID: 21;    "Voice Call In";       {AUDIO_DEVICE_IN_TELEPHONY_RX, @:}
  4. Port ID: 19;    "Built-In Back Mic";   {AUDIO_DEVICE_IN_BACK_MIC, @:back}
  5. Port ID: 26;    "Remote Submix In";    {AUDIO_DEVICE_IN_REMOTE_SUBMIX, @:0}
```

**全部是物理设备或系统内部通路，没有任何虚拟麦克风设备。**
三方 App 既无法往这个列表注册新设备，也无法改变别的 App 的采集来源。
（`Remote Submix In` 是系统给 `AudioPlaybackCapture` / 屏幕录制用的内部通路，
普通 App 既不能往里写，也不能读它。）

### 2.2 关键权限是 signature 级

`CAPTURE_AUDIO_OUTPUT` 保护级为 `signature|privileged`。官方文档原文：

> "requires the `CAPTURE_AUDIO_OUTPUT` permission. This permission is reserved for use by
> system components and **is not available to third-party applications**."

`adb shell pm grant` 也授不了（shell 不在 signature 白名单）。

### 2.3 `AudioPlaybackCapture` 方向相反且排除通话

`AudioPlaybackCaptureConfiguration`（API 29）是抓**别的 App 的播放输出**，
与「我的输出 → 别人的输入」方向相反。且它：
- 只能抓 `USAGE_MEDIA` / `USAGE_GAME` / `USAGE_UNKNOWN`；
- **明确排除 `USAGE_VOICE_COMMUNICATION`**（QQ 语音通话正是这一类）。

### 2.4 想自动点 QQ 的「按住说话」需要 `INJECT_EVENTS`

`INJECT_EVENTS` 同样是 `signature` 级。所以「自动按住 / 自动松手结束录音」做不到。

### 2.5 自动检测 QQ 录音态：这版 QQ 不可行（真机实测）

`QqWatchService`（AccessibilityService）轮询 `rootInActiveWindow`，实测结论：

- **按下录音键之后，QQ 的无障碍树完全没有变化** —— 没有新文本、没有新窗口。
  录音面板是自定义绘制的 `View`，不暴露节点。
- 「按住说话」这个文本在**按下之前**就存在，所以不能拿来当触发条件。

> 保留 `QqWatchService`：如果将来某个 QQ 版本把录音态暴露成文本节点，它就能自动触发。

---

## 三、❌ 已证伪：蓝牙耳机声学回环（2026-10-02 实测）

### 3.1 曾经的想法

```
手机 ──A2DP/SCO──▶ 蓝牙耳机喇叭 ──空气──▶ 蓝牙耳机麦克风 ──SCO──▶ QQ
```

推理是：只要录音一方在通话模式，Android 的输入路由会自动选
`AUDIO_DEVICE_IN_BLUETOOTH_SCO_HEADSET`，于是耳机喇叭放的声音被耳机麦收走。

### 3.2 实测结果：**回环增益为负，方案作废**

用 `HeadsetLoopTest`（`LOOPTEST` 调试通路）跑了两次，含主动建立 SCO 的对照实验：

| 项目 | 第 1 轮（A2DP） | 第 2 轮（含 SCO 尝试） |
|---|---|---|
| 静音基线 | −112.7 dBFS | −99.2 dBFS |
| 播放+录音 | −114.5 dBFS | −116.0 dBFS |
| **回环增益** | **−1.8 dB** | **−16.8 dB** |
| 实际输出设备 | iKF-King Pro⁺ ✅ | iKF-King Pro⁺ ✅ |
| 实际输入设备 | iKF-King Pro⁺ ✅ | iKF-King Pro⁺ ✅ |
| `startBluetoothSco()` | — | **false**（链路都没建起来）|
| 录音 source | 7（VOICE_RECOGNITION）| 7 |

**设备层面全对，但增益是负的 —— 播放时比静音还安静。声音完全传不过去。**

### 3.3 根因：A2DP 与 HFP/SCO 是**两条独立链路**

- `A2dpStateMachine state=Connected`（放音，高音质，**不走 SCO**）
- `HeadsetStateMachine state=Connected`（HFP，麦克风在这条路上）
- 耳机硬件**不会**把「喇叭放出来的」和「麦克风收到的」内部接通 ——
  蓝牙协议层就是分离的。
- 唯一可能的声学路径是「耳机喇叭 → **空气** → 耳机麦」，但入耳式耳机喇叭冲耳道、
  麦克风朝外，被耳壳隔开，声压极低。
- 旁证：`AudioRecord` 请求 `VOICE_COMMUNICATION(3)` 却回落到 `VOICE_RECOGNITION(7)`，
  且 `startBluetoothSco()=false` ⇒ SCO 输入从未真正激活。

### 3.4 这次实验的价值

`HeadsetLoopTest` + `LOOPTEST` 触发文件是**判别任何「声学回环」方案成立与否的标准工具**：
比较「静音基线」与「播放时能量」的 dBFS 差值，**差值 < 3 dB 就是没有回环**。
比「用耳朵听 QQ 里有没有声音」快得多、也可靠得多。
现已扩展为**同时测量外放通路**（第 3 组）。

---

## 四、实际采用的方案：**扬声器外放 → 手机麦**（已实现）

### 4.1 为什么这条能行

同样的声学回环思路，换成手机自己的扬声器和麦克风 —— **距离只有几厘米，且没有耳壳遮挡**。
关键差别只有一个：**声源离麦克风近**。

```
手机扬声器 ──空气（约 10 cm）──▶ 手机自带麦克风 ──▶ QQ
```

### 4.2 一个反被纠正的「旁证」（橘雪莉语音盒）

最初把这款 App 的官方教程当作「外放方案成立」的旁证，**后来拆包发现这个旁证本身是错的**——
它是个 WebView 壳 + 预录语音包，没有任何变声与录音能力（完整证据见 1.1）。

其官方教程原文：

> 想在 QQ 里顺利开启变音整活吗？……**必须依托系统自带的小窗口或者应用双开小屏功能**
> 才可以完美激活；要是直接切进大型网络游戏里往往是不管用的，主要是因为游戏官方
> 从底层做了权限屏蔽，导致麦克风对面的队友根本听不到声音。
>
> 依旧是以主流的小米机型作为操作示范，该玩法**仅在系统支持分屏或悬浮小屏的模式下**
> 才能派上用场。

**这段话唯一有价值的信息是**：「小窗/分屏」确实是这类玩法的关键操作姿势 ——
让对方的 App 保持前台、麦克风持续采集。但它**不能**用来证明「不用外放也能进麦克风」，
因为该 App 的声音必然从扬声器播出后经空气回采。

### 4.3 实现要点

| 项 | 值 | 理由 |
|---|---|---|
| AudioAttributes | `USAGE_MEDIA` + `CONTENT_TYPE_MUSIC` | 走媒体通路，**避开** `VOICE_COMMUNICATION` 的通话链路（那条会触发耳返/降噪，反而压制外放） |
| 音量 | `STREAM_MUSIC` 拉满 | 提高信噪比，对抗 QQ 的 AEC |
| AEC/NS/AGC | 不主动处理 | 那是 QQ 录音侧的事，我们关不掉 |
| 交互 | 「先播再按」，循环 20 秒 | 因为检测不到 QQ 录音态，用循环覆盖「按住多久」的不确定量 |

### 4.4 ✅ 外放通路实测成立（2026-10-02，硬数据）

`HeadsetLoopTest` 第 3 组，**并已确认蓝牙断开**（输出设备必须是手机本体）：

| 项目 | 值 |
|---|---|
| 静音基线 | **−49.0 dBFS** |
| 播放时能量 | **−20.9 dBFS** |
| **外放增益** | **+28.1 dB** ✅ |
| 实际输出设备 | `25060RK16C`（= 手机本体，**不是**耳机）|
| 实际输入设备 | 手机麦 |
| 录音 source | 7（VOICE_RECOGNITION）|

**+28.1 dB，与耳机组的 −16.8 dB 形成鲜明对比。** 这条通路确认可用。

> ⚠️ **测外放组前必须断开蓝牙**。第一次测时耳机还连着，
> 结果输出设备是 `iKF-King Pro⁺`、静音与播放都读到 −140 dBFS（空值）——
> 因为声音全走耳机了，根本没进空气。

判据：**≥ 10 dB 算成立，5～10 dB 偏弱需调大音量，< 5 dB 视为不通**。

---

## 五、必须让用户知道的两件事（开源文档应保留）

### 5.1 外放方案的固有代价

1. **会外泄** —— 旁边的人能听到放出来的内容
2. **可能被 QQ 的 AEC 压制** —— QQ 的回声消除是为「扬声器→麦克风」设计的，
   它**可能把我们放的声音当成自己的回声而压掉一部分**。
   实测中我们自己的 session 一侧可以关掉 AEC/NS/AGC（见第六节），
   但**关不掉 QQ 录音通路上 QQ 自己挂的效果**。

### 5.2 必须遵守的使用姿势

1. **用系统小窗/分屏让 QQ 保持前台** —— 这是成败关键（橘雪莉官方教程的核心提示）
2. 不要用返回键切到变声器；变声器应该以**小窗/悬浮**形式出现
3. 在**安静环境**下使用，环境噪声会被一起录进去

### 5.3 更稳的替代：物理回环线（建议用户自选）

如果对外泄和 AEC 压制都不能接受，**有线回环**是最稳的：
`手机耳机孔/USB 声卡输出 → 分线/回环线 → 手机麦克风输入`。
这是真电信号而非空气传播，不受 AEC 影响，也完全静音。
代价：需要一根几块钱的 TRRS 分线器，且要告诉用户怎么接。
**开源项目建议把它做成「可选增强」，默认仍走外放。**

---

## 六、一个必须记住的 API 坑：`AudioEffect` 是 @hide 裁剪的

`android.jar` 里的 `android.media.audiofx.AudioEffect` 是**公开桩**，只有：

```
public static AudioEffect.Descriptor[] queryEffects();
public AudioEffect.Descriptor getDescriptor();
public int  setEnabled(boolean);
public int  getId();
public boolean getEnabled();
public void release();
```

而下面这些**都是 @hide，编译期直接 `Unresolved reference`**：

```
AudioEffect.create(UUID, UUID, int, int)     ❌
AudioEffect.EFFECT_TYPE_NULL                 ❌
Equalizer.numberOfEffects / getEffect(int)   ❌
```

**正确写法** —— 用三个公开工厂类（都在 `android.jar` 里，且 `create(sessionId)` 是公开的）：

```kotlin
AcousticEchoCanceler.create(sessionId)?.setEnabled(false)
NoiseSuppressor.create(sessionId)?.setEnabled(false)
AutomaticGainControl.create(sessionId)?.setEnabled(false)
```

三个类都 `extends AudioEffect`，所以能调基类公开的 `setEnabled` / `release`。

> ⚠️ **语义边界（别误解）**：`create(sessionId)` 只对**自己拥有**的 session 生效，
> 传别人的 session 会返回 `null`。所以这只能保证**我们播放的这条通路干净**，
> **关不掉 QQ 录音通路上 QQ 自己挂的 AEC**。

另一条相关经验：`MediaPlayer.getRoutedDevice()` 在 `start()` 之后**立刻**调用常常返回 `null`
（音频通路还没建好）。需要短轮询（本项目用 150ms × 最多 12 次）才能拿到设备名。

---

## 七、排除的路线汇总

| 路线 | 可行性 | 排除原因 |
|---|---|---|
| LSPosed Hook `AudioRecord` | ✅ 技术可行 | **要 root + LSPosed** → 违反「跨设备通用 + 开源」约束；且本机 HyperOS 3 解锁需社区 5 段 + 答题 + 实名 + 14 天等待 + 每年 3 台，**会清空数据、失去 OTA** → 用户已明确放弃 root |
| Shizuku | ❌ | 实测 `cmd audio` 只有音量/surround 子命令，**无 SCO/蓝牙路由命令**；`cmd bluetooth_manager` 只有 enable/disable/wait-for-state ⇒ shell 权限拿不到音频路由开关 |
| 官方虚拟麦克风 API | ❌ | **不存在**（2.1 真机取证） |
| `AudioPlaybackCapture` | ❌ | 方向相反 + 排除 `USAGE_VOICE_COMMUNICATION` |
| 自动点击 QQ 录音键 | ❌ | 需 `INJECT_EVENTS`（签名级） |
| **蓝牙耳机声学回环** | ❌ | **实测证伪**，回环增益 −16.8 dB |
| **扬声器外放 + 本机采集** | ✅ **采纳** | 已实现；需配「小窗保前台」姿势 |
| 物理回环线（TRRS/USB） | ✅ 可选增强 | 最稳，但需外接硬件 |

---

## 八、使用方式：**悬浮小窗 + 点一次播一次**

### 8.1 交互形态（学同类产品，只学形态不学原理）

同类产品（橘雪梨语音盒）有一个真正好用的东西：**悬浮球**。
它的原理是假的（见 4.2），但**交互形态是对的** ——
用户不需要在两个 App 之间来回切，浮在 QQ 上面点一下就行。

我们照抄这个形态，但走**实测成立的那条通路**（外放回采）。

### 8.2 三步操作

1. 在 App 里合成或转换出音频 → 自动装载为「待发送」
2. ⭐ 打开**悬浮小窗**（设置页开关，首次需授予「显示在其他应用上层」）
3. 到 QQ 聊天里切到语音输入 → **按住「说话」之前点一下悬浮球** →
   音频播一遍 → 播完前松手发送

悬浮球可拖动（位置会记住）。播放中再点一次 = 停止。
播放完按钮自动变回 ▶，不需要手动复位。

### 8.3 为什么**不做**循环播放（这是被用户否掉的设计）

早期版本让用户点「开始代发」，然后循环外放 20 秒，让用户自己在这段时间里
"按住说话"。实测下来这是**错的设计**：

| 问题 | 说明 |
|---|---|
| 对不齐 | QQ 只取「按下 → 松开」那一段，循环多放的全是白放 |
| 串音 | 松手后音频还在响，会被 QQ 的下一次录音录进去 |
| 吵 | 20 秒的连续外放，而且盖过了用户自己的说话声 |

现在改成**点一次播一遍、播完即停**：用户自己掐点 —— 先点悬浮球，再按住说话。
`QQ_LOOP_SEC`（12 秒）**只留给无障碍自动触发**那一条路径：
那种情况下用户还没切到 QQ，需要多留一点时间才来得及按住。

### 8.4 音量策略：**不拉满、淡入、播完恢复**

用户反馈原话：「外放时别把音量一下子挑满，很容易吓人一跳啊」。

旧实现是 `setStreamVolume(STREAM_MUSIC, maxVol, 0)` —— 一把拉满，
第一帧就是满幅，听感上就是"突然蹦一声"。现在改成三条：

| 措施 | 参数 | 理由 |
|---|---|---|
| 目标音量 | max 的 **60%**，且只在「当前更小」时才抬 | 拉满会炸耳朵；离得近反而更容易让麦克风前级限幅 |
| 淡入 | `MediaPlayer.setVolume()`，350 ms，25 ms 一步（14 步） | 消除"突然蹦一声" |
| 播完恢复 | 记下原值，播完/停止/异常三条路径都恢复 | 不动用户自己设的音量 |

⚠️ 三个恢复点缺一不可：正常播完、用户手动停、播放异常。
漏掉任何一个都会让用户的系统音量被我们永久改掉。

### 8.5 验证链路的两个入口

- 设置页「在这里播一遍」—— 不依赖悬浮窗，用来确认链路通
- 日志里的「上次实际输出设备」—— 确认真的走了扬声器而不是耳机

---

## 九、明确的「做不到」清单（诚实告知）

1. **无法把音频直接注入 QQ 的麦克风**（无 API，非 root 不可能）
2. **无法自动结束 QQ 的录音**（需 `INJECT_EVENTS`，签名级）
3. **无法自动检测 QQ 正在录音**（这版 QQ 的录音面板不进无障碍树）
4. **无法在非 root 下做「真静音」虚拟麦克风**（必须有物理声学路径）
5. **无法保证外放的声音不被 QQ 的 AEC 压制**（那属于 QQ 的 session）

本方案用**扬声器外放**绕过了 1 和 4，用**悬浮球手动一点**绕过了 2 和 3，
第 5 条是残留风险，只能靠「QQ 保前台 + 安静环境 + 音量 60%」缓解。

> 第 3 条补充一句：悬浮窗是**手动**按钮，不是自动检测。
> 只有无障碍服务能在某些 QQ 版本上做自动触发；
> 这版 QQ 的录音面板不进无障碍树，所以自动那条路当前基本不通，悬浮球才是主力。
