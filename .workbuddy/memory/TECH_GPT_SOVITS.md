# GPT-SoVITS 全 ONNX + 端侧移植（详见专题）

## 六、GPT-SoVITS v2ProPlus 全 ONNX + 端侧移植（四情绪端到端已通 ✅）

### 验证方法论（务必沿用）

导出脚本里的 cos 只证明「手写封装 torch vs ORT 一致」，**证明不了与官方语义一致**。补三个：
- `gsv_verify_t2s.py`：官方 `process_prompt/decode_next_token` 对照 → attn_mask 逐位一致、logits relL2 ~1e-6、5 步 argmax 全对
- `gsv_verify_s2.py`：官方 `SynthesizerTrn` 对照 → prompt 逐位相同、vits relL2 2.0e-5（技巧：monkeypatch `torch.randn_like` 注入固定噪声）
- `gsv_verify_ar_e2e.py`：**决定性检验** —— 官方 `infer_panel` 与 ONNX 循环设同一 seed，必须吐出相同 token 序列
- **判端侧移植对错不要用绝对相等**（AR 是随机采样）：先测 PC 自身种子噪声本底
  （`AR_SEED=11/12/13` → cos 0.9600/0.9208/0.9489），再看端侧 vs PC（0.9171）
  → 差距 1.27~1.46 倍落在噪声范围内 = 移植正确。工具 `gsv_device_compare2.py`（DTW 对齐）

### AR 循环三个必须照抄的细节 + 四个坑

1. EOS 禁用步数：官方 idx 从 0 起 `if idx<11`；我的 idx 从 1 起 → 等价 `idx<=minSteps`
2. token 位置 = `yLen + idx - 1`
3. vits 的 noise 外部传入 = `randn(1,192,P*2)*0.5`
4. 官方 `decode_next_token` **原地改 k/v cache** → 对照实验必须传 clone（否则「单步 cos 1.0、5 步掉 0.9967」的假异常）
5. `sample()` 的 `top_p` 是 `Optional[int]` → 传 `1` 不能传 `1.0`
6. **参考音频三条前端路径官方本来就不同，别统一**：ssl = librosa 16k **尾部接 9600 个零**；
   refer = torchaudio→32k→`spectrogram_torch(center=False)`；sv = 再重采样回 16k（无零填充）
7. **ORT CPU EP 多线程归约不可逐位复现** → 回归要么钉死 `intra_op_num_threads`，要么用 ~1e-5 容差

⚠️⚠️ **上面第 1 条真机踩爆过（2026-10-02）**：Kotlin 曾把条件写成「前 minSteps 步**允许** EOS」，
且 `break` 被开关挡住 → **EOS(1024) 被写进 `sem`** → vits 的 `codes` Gather 报
`idx=1024 must be within [-1024,1023]`，表现为「某些文本合成到一半直接失败」（试 3 次全失败）。
**铁律：EOS 只用于判停 break，绝不进交给 vits 的序列**；再加越界 token 替 0 + 步数上限截断兜底。
修法详见技能 `android-onnx-port-verify` 第 9.3 节。

**质量旋钮**：生成长度乱跳是官方模型固有行为（30 种子 token 13~89、中位 44，**27% 出半句话**）
→ 「强制禁用 EOS 步数」11→**45**，20 种子全部 45~84 token（1.8~3.4s）。`AR_MIN_STEPS`/`AR_SEED`。

### 体积：2612.9 MB → **档 C 615.1 MB**（采用）

1. ⚠️ **动态 INT8 必须按算子构成分档**：纯 MatMul 安全（t2s logits cos 0.9997、bert 传导到 AR logits 0.9999952、argmax 不变）；
   **Conv 为主的必崩**（ssl 0.8223 / vits 0.3403 / sv **0.2491**）。全量 INT8 到 694.6MB 但 rms 0.0864→0.0048
2. **fp16 权重存储 + fp32 计算**（`gsv_fp16.py`，**不要用 ORT 的 `convert_float_to_float16`**）：
   initializer 存 fp16 + 权重前插 `Cast→fp32`。bert/t2s cos 1.0 逐位相同、vits 0.9999974
3. **参考特征离线预计算**：ssl+sv+prompt+spec 只依赖参考音频 → npz **1.69MB**，无损
4. **t2s_prefill/decode 共享权重**（external data）：fp16 省 145.3MB。
   ⚠️ **必须按内容键**（dtype+dims+md5(raw)）匹配：torch 把常量折叠的 MatMul 自动编号，
   按名字只有 192/193 命中，**按内容键 483/483 全中**；`set_external_data` 要求 `raw_data` 存在
   → 量化模型 `*_scale`/`*_zero_point` 须先物化（483 里 194 个）；**先 set 后 ClearField**

**档 C**（`tools/gsv_onnx_final_c/`）：bert int8 288.16 + prefill/decode fp16 共享（各 0.19 + 池 145.29）
+ encoder/step_embed fp16 19.35 + **vits fp16 e8** 161.84
**档 D**（545.5MB）prefill/decode 换 int8 → AR **快约 30%**，rms 低 4~8%
全 fp16（1315.6MB）是唯一 AR token 逐位一致的方案

**e8**（`atri_e8_s744.pth`）：`ssl_proj`/`quantizer` 6 张量逐位相同 → `prompt.onnx` 可复用；
vits 重导。指纹 `dec.conv_pre.weight` = `-347.737518`/md5 `a96a2bed`（e4 = `8a3d6b45`，cos(e8,e4)=0.173）。
⚠️ 坑：`gsv_export_s2.py` 第 82 行**硬编码 e4_s372**，第一次「重导」其实还是 e4。
**四情绪参考音频**（`tools/tts_baseline/ref_map.json`，lang 全 `ja`）：
joy=ATR_b102_017 / sad=ATR_b207_040 / angry=ATR_b701_013 / calm=ATR_b303_024。
**日文参考不需要 BERT**（官方 `get_bert_inf()` 对非中文返回全零）。

### 真机端到端结果（小米 25060RK16C，CPU 4 线程）

| 情绪 | ref phones | prompt | cat | AR 步数 | 时长 | PC 基线 |
|---|---|---|---|---|---|---|
| joy | 22 | 102 | 153 | 78 | 3.12s | 3.36s |
| sad | 73 | 147 | 249 | 85 | 3.40s | 3.60s |
| angry | 53 | 146 | 228 | 72 | 2.88s | 3.36s |
| calm | 59 | 97 | 185 | 82 | 3.28s | 3.72s |

`cat = ref_phones + text_phones(29) + prompt`，与 PC `xy_pos (1,153,512)` 逐位一致。
性能：G2P 1ms / BERT ~90ms / encoder 2ms / prefill ~550ms / **AR 416–966 ms 每步** / vits 2.8–3.5s
→ **合计 36–86 s**。CPU 比 GPU 慢约 25 倍，AR 是 O(n²)，⚠️ 远达不到实时。

### 真机修的 5 个 bug

1. `OnnxJavaType` 未 import（在 `ai.onnxruntime` 包下）
2. **AR 循环越界**：EOS 停止时 `ySeq.removeAt` 已丢最后一步 → **单独用 `semList` 收集**
3. **日文参考 phone 为空**（端侧只做中文 G2P）→ 离线预计算塞进 refbin
4. `unpackTextAssets` 跳过已存在文件 → **按魔数判版本**覆盖
5. **采样器算子顺序**：官方 `rep_pen → top_p(logit空间) → /temp → top_k(pivot 保留 >=) → softmax`
   → 逐行照抄后 AR 步数 95→84。校验 `gsv_sampler_check.py`（概率 max_abs ≤ 4e-8）

### refbin 格式 v02（**v01 已废弃**）

```
magic 8B "ATRREF02" | n_ssl i32 | n_prompt i32 | n_refer i32 | n_phone i32
ssl f32[n_ssl*768] | sv f32[20480] | prompt i32[n_prompt] | refer f32[1025*n_refer] | phones i32[n_phone]
```
生成链：`gsv_export_refphones.py` → `gsv_pack_refbin.py` + `gsv_len_check.py`

### ORT Java 端侧 API 踩坑

1. `Result.get(i).value` 返回类型**看 shape**：含 `?` → `OnnxTensor`；全静态 → **裸数组**
   → 必须写 `asF`(扁平化) / `asShape`(沿第一维下钻) 两种都兜
2. **`attn_mask` 是 bool 张量**（`boolean[][][][]`）；`OnnxTensor.createTensor(env, ByteBuffer, shape, OnnxJavaType.BOOL)`
3. **encoder → prefill 原样直传**（`toTensor`），不能转 float
4. **bert 输出已是展开后的 `[S,1024]`**（repeat_interleave 在图内），`S=sum(word2ph)=len(phones)`
5. **日文参考走全零 BERT** → 端侧不用真跑 bert，按 `refPhones.size` 铺零

### 端侧 G2P（纯查表+规则，精度 96.27%）

官方 `clean_text(zh)` 依赖 pypinyin/jieba/g2pw/cn2an → 端侧全用不了。改用：
主词典 = g2pw 消歧后的 `polyphonic-fix.rep`（45046 词）→ `poly.txt`；
精度 96.27%、整句一致 4/15，但**端到端与官方逐位一致** → 残差对音频无影响。
表：`symbols.txt`(732)、`syl.txt`(1550)、`charmap.txt`(41651)、`phrases.txt`(45474)、`bert_vocab.txt`(21128)。
⚠️ **官方 p2s 表无调号后缀**（键是 `ni`/`hao` 不是 `ni3`）→ Kotlin 查表须用 tone-stripped 拼音。
⚠️ `gsv_export_assets.py` 曾静默丢词 → 改逐字兜底 `char_best`。**BERT 是字级词表** → 不需要 WordPiece。

### 档 C ONNX 签名

| 模型 | 输入 → 输出 |
|---|---|
| `bert.onnx`(int8,288MB) | `input_ids[1,L]`/`attention_mask`/`token_type_ids`/`word2ph[L2]` → `bert_feat[S,1024]` |
| `t2s_encoder.onnx` | `ref_seq`/`text_seq`/`ref_bert`/`text_bert`/`prompts` → `xy_pos[1,l,512]`/`attn_mask[1,1,l,l]`(bool) |
| `t2s_prefill.onnx` | `xy_pos`/`attn_mask` → `logits[?,1025]`/`k[24,1,l,512]`/`v` |
| `t2s_decode.onnx` | `xy_pos[1,1,512]`/`k_cache`/`v_cache` → `logits`/`k_cache_out`/`v_cache_out` |
| `t2s_step_embed.onnx` | `token[1,1]`/`pos_idx[]`(标量) → `xy_pos[1,1,512]` |
| `vits.onnx`(fp16,162MB) | `codes[1,1,p]`/`text_seq`/`refer[1,1025,w]`/`sv_emb[1,20480]`/`noise[1,192,q]` → `audio[1,1,n]`(32kHz) |

`t2s_shared.data` 145.3MB = prefill/decode 共享 external data。

### Kotlin 文件（`AtriVC/app/src/main/kotlin/com/atri/vc/`）

| 文件 | 内容 |
|---|---|
| `TextFrontend.kt` | 端侧 G2P：`normalize()`/`segment()`(FMM)/`preMerge()`/五种 sandhi/`erhua`/`g2p()` |
| `TtsEngine.kt` | ONNX 合成：`load`/`synthesize`/`bertFeat`/`sample`(照抄官方顺序)/`asF`/`asShape`/`toTensor` |
| `RefFeatureStore.kt` | 读 v02 `.refbin`；`MAGIC="ATRREF02"`；`EMOTIONS`/`LABELS`/`REF_TEXTS`/`load()` |
| `TtsAssetStore.kt` | 目录管理 + `MODELS`/`SHARED_DATA`/`ASSETS`/`unpackTextAssets()`（按魔数覆盖） |

自动化测试：推 `TTSAUTORUN` + `tts_text.txt` + `tts_emo.txt` + `tts_seed.txt` + `tts_threads.txt`
（+ `tts_uipath.txt=1` 走 UI 路径）到 `/sdcard/Android/data/com.atri.vc/files/`，启动 App 即跑。

