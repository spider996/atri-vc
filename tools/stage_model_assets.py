# -*- coding: utf-8 -*-
"""
把所有模型文件汇总到 AtriVC/model_assets/ —— 即打包进全量 APK 的资源树。

产出布局（= assets 根）：
    models/  RVC 变声三件套 + 检索库
    tts/     GPT-SoVITS 六模型 + shared.data

用硬链接优先（同卷瞬时且不占额外空间），跨卷自动退化为复制。
结束后打印清单与总大小，并对每个文件做「目标字节数 == 源字节数」校验。

【为什么不含文本前端 txt 与 ref/】
它们已经在 AtriVC/app/src/main/assets/ 里了。若此处再放一份，
Gradle 的 mergeDebugAssets 会因为同名资源冲突直接失败：
    Execution failed for task ':app:mergeDebugAssets'
    [tts/bert_vocab.txt] ... duplicate resources

【怎么用】
脚本从仓库根目录相对定位，直接 `python tools/stage_model_assets.py` 即可。
如果你的模型不在默认位置，改 PLAN 里的相对路径，或用环境变量指定：
    ATRI_MODEL_DIR    RVC 三件套与检索库所在目录（默认 tools/onnx_q8 + tools/android_ref）
    ATRI_TTS_DIR      GPT-SoVITS ONNX 所在目录（默认 tools/gsv_onnx_final_c）
"""
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DST = os.path.join(ROOT, "AtriVC", "model_assets")

RVC_DIR = os.environ.get("ATRI_MODEL_DIR", os.path.join("tools", "onnx_q8"))
IDX_DIR = os.environ.get("ATRI_MODEL_DIR", os.path.join("tools", "android_ref"))
TTS_DIR = os.environ.get("ATRI_TTS_DIR", os.path.join("tools", "gsv_onnx_final_c"))

# (源路径, assets 内相对路径) —— 一律用 os.path.join，不要写字面反斜杠
PLAN = [
    # ---------------- RVC 变声三件套 + 检索库 ----------------
    (os.path.join(RVC_DIR, "atri_net_g_t1024_q8.onnx"), "models/atri_net_g_t1024_q8.onnx"),
    (os.path.join(RVC_DIR, "rmvpe_q8.onnx"),            "models/rmvpe_q8.onnx"),
    (os.path.join(RVC_DIR, "hubert_dyn8.onnx"),         "models/hubert_dyn8.onnx"),
    (os.path.join(IDX_DIR, "index_mobile.bin"),         "models/index_mobile.bin"),
    # ---------------- GPT-SoVITS 六个 ONNX + 共享权重 ----------------
    (os.path.join(TTS_DIR, "bert.onnx"),           "tts/bert.onnx"),
    (os.path.join(TTS_DIR, "t2s_encoder.onnx"),    "tts/t2s_encoder.onnx"),
    (os.path.join(TTS_DIR, "t2s_prefill.onnx"),    "tts/t2s_prefill.onnx"),
    (os.path.join(TTS_DIR, "t2s_decode.onnx"),     "tts/t2s_decode.onnx"),
    (os.path.join(TTS_DIR, "t2s_step_embed.onnx"), "tts/t2s_step_embed.onnx"),
    (os.path.join(TTS_DIR, "t2s_shared.data"),     "tts/t2s_shared.data"),
    (os.path.join(TTS_DIR, "vits.onnx"),           "tts/vits.onnx"),
]


def link_or_copy(src: str, dst: str) -> str:
    """优先硬链接（同卷免费）；跨卷/不支持则复制。返回方式名。"""
    if os.path.exists(dst):
        os.remove(dst)
    try:
        os.link(src, dst)
        return "link"
    except OSError:
        shutil.copy2(src, dst)
        return "copy"


def main() -> int:
    total = 0
    rows = []
    fails = []

    print("仓库根目录: %s" % ROOT)
    print("目标目录  : %s\n" % DST)

    for rel_src, rel_dst in PLAN:
        src = os.path.join(ROOT, rel_src.replace("/", os.sep))
        dst = os.path.join(DST, rel_dst.replace("/", os.sep))
        if not os.path.exists(src):
            fails.append("MISSING SOURCE: %s" % src)
            continue
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        how = link_or_copy(src, dst)
        a, b = os.path.getsize(src), os.path.getsize(dst)
        if a != b:
            fails.append("SIZE MISMATCH %s: src=%d dst=%d" % (rel_dst, a, b))
            continue
        total += b
        rows.append((rel_dst, b, how))

    for name, size, how in sorted(rows):
        print("  %-42s %10.2f MB  (%s)" % (name, size / 1048576.0, how))
    print("-" * 72)
    print("文件数 = %d" % len(rows))
    print("合计   = %.2f MB (%.3f GB)" % (total / 1048576.0, total / 1073741824.0))

    if fails:
        print("\n!! 失败 %d 项:" % len(fails))
        for f in fails:
            print("   " + f)
        print("\n提示：用 ATRI_MODEL_DIR / ATRI_TTS_DIR 指向你的模型目录后重试。")
        return 1
    print("\nOK: 全部文件已就位并校验通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
