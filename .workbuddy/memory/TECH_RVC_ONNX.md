# RVC ONNX 三件套（详见专题）

## 三、RVC ONNX 三件套（已跑通，INT8）

| 模型 | fp32 | INT8 | 压缩 | 方法 |
|---|---|---|---|---|
| net_g | 122.42 MB | **47.61 MB** | 2.57x | 静态 QOperator（Conv/ConvTranspose/MatMul）|
| HuBERT | 377.82 MB | **122.47 MB** | 3.08x | 动态量化（仅 MatMul）|
| RMVPE | 361.69 MB | **98.58 MB** | 3.67x | 静态 QOperator |
| **合计** | 861.93 MB | **268.66 MB** | **3.21x** | |

- net_g：`tools/onnx/atri_net_g_t1024.onnx`（**dynamo**，固定块长 1024 帧 = 10.24s）
- HuBERT：`tools/onnx/hubert_tracing.onnx`（**tracing + dynamic_axes，真动态** T=8000~160000）
- 工具：`quantize_static_rvc.py --kind netg|hubert|rmvpe [--dynamic]`、`bench_netg.py`、`rmvpe_onnx_check.py`

### 关键坑

1. 导出 net_g：`SynthesizerTrnMsNSFsidM(*cpt["config"], version="v2", is_half=False)`，导出前 `remove_weight_norm()`；**rnd 必须 ×0.66666**（官方 onnx_inference.py 漏了）
2. 后端：tracing cos 0.8627（错）→ **dynamo cos 0.9997**；dynamo 需 `onnxscript` + 两处进程内 patch（`tools/attn_dyn.py`：SineGen 去 in-place、相对位置注意力动态版）
3. HuBERT 动态形状：`pad_to_multiple` 里纯 Python 分支（`m.is_integer()`）会崩 → patch 成 no-op（`tools/hubert_dyn.py`）。安全性：multiple=2，补零位被 padding_mask 屏蔽，实测 8 种长度 max_abs ≤ 1.0e-05
4. 动态量化对 Conv 不可用（ConvInteger CPU EP 未实现）→ net_g/RMVPE 必须静态 QOperator
5. transformer 不能静态量化激活（HuBERT cos 1.0 → 0.67）→ HuBERT 用动态量化（0.985~0.992）
6. 量化器「孤儿张量」报错：masked_fill 降级成 Where → `quant_pre_process` 修不好 → 用 `--op-types` 限定算子
7. 量化器在输入模型旁写中间文件 → 中文路径必失败

### ⚠️ 两条方法论铁律

- **端到端「波形余弦」在 NSF 声码器上无效**：ONNX vs PyTorch 波形 cos 仅 0.48，但频谱级 0.9641 ≈ 「同一实现跑两次」天花板 0.9686 → 等价。原因：net_g 内有随机激励噪声 + 40k 相位极敏感。**判等一律用 `compare_spectra.py`（mel）；逐模块用固定随机源。**
- **跨时段比较耗时 = 自欺欺人**：同一机器不同时段能差 3 倍（曾误判「INT8 慢 2.6 倍」，交替 A/B 实测 INT8 其实快 12%）。**只能同时段交错测。**

### 耗时结构（PC CPU，20s 音频）

net_g **66%** / RMVPE 22% / index 6% / HuBERT **仅 5%**。
→ **参数量 ≠ 耗时**：HuBERT 95M 只在 16k 跑 999 帧；net_g 27.5M 却要生成 40k 波形。

---

