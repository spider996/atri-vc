# -*- coding: utf-8 -*-
"""
把所有模型文件汇总到 AtriVC/model_assets/ —— 即打包进全量 APK 的资源树。

产出布局（= assets 根）：
    models/  RVC 变声三件套 + 检索库
    tts/     GPT-SoVITS 六模型 + shared.data + 文本前端 + ref/

用硬链接优先（同卷瞬时且不占额外空间），跨卷自动退化为复制。
结束后打印清单与总大小，并对每个文件做「目标字节数 == 源字节数」校验。
"""
import os
import shutil
import sys

ROOT = r"C:\Users\21264\WorkBuddy\2026-09-28-10-31-09"
DST = os.path.join(ROOT, "AtriVC", "model_assets")

# (源路径, assets 内相对路径)
PLAN = [
    # ---------------- RVC 变声三件套 ----------------
    (r"tools\onnx_q8\atri_net_g_t1024_q8.onnx", r"models\atri_net_g_t1024_q8.onnx"),
    (r"tools\onnx_q8\rmvpe_q8.onnx",            r"models\rmvpe_q8.onnx"),
    (r"tools\onnx_q8\hubert_dyn8.onnx",         r"models\hubert_dyn8.onnx"),
    (r"tools\android_ref\index_mobile.bin",     r"models\index_mobile.bin"),
    # ---------------- GPT-SoVITS 档 C ----------------
    (r"tools\gsv_onnx_final_c\bert.onnx",           r"tts\bert.onnx"),
    (r"tools\gsv_onnx_final_c\t2s_encoder.onnx",    r"tts\t2s_encoder.onnx"),
    (r"tools\gsv_onnx_final_c\t2s_prefill.onnx",    r"tts\t2s_prefill.onnx"),
    (r"tools\gsv_onnx_final_c\t2s_decode.onnx",     r"tts\t2s_decode.onnx"),
    (r"tools\gsv_onnx_final_c\t2s_step_embed.onnx", r"tts\t2s_step_embed.onnx"),
    (r"tools\gsv_onnx_final_c\t2s_shared.data",     r"tts\t2s_shared.data"),
    (r"tools\gsv_onnx_final_c\vits.onnx",           r"tts\vits.onnx"),
    # ---------------- 文本前端（已是 assets 内容，直接搬） ----------------
    (r"AtriVC\app\src\main\assets\tts\symbols.txt",    r"tts\symbols.txt"),
    (r"AtriVC\app\src\main\assets\tts\syl.txt",        r"tts\syl.txt"),
    (r"AtriVC\app\src\main\assets\tts\charmap.txt",    r"tts\charmap.txt"),
    (r"AtriVC\app\src\main\assets\tts\poly.txt",       r"tts\poly.txt"),
    (r"AtriVC\app\src\main\assets\tts\phrases.txt",    r"tts\phrases.txt"),
    (r"AtriVC\app\src\main\assets\tts\p2s.txt",        r"tts\p2s.txt"),
    (r"AtriVC\app\src\main\assets\tts\bert_vocab.txt", r"tts\bert_vocab.txt"),
    # ---------------- 四条情绪参考特征 ----------------
    (r"AtriVC\app\src\main\assets\tts\ref\ref_joy.refbin",   r"tts\ref\ref_joy.refbin"),
    (r"AtriVC\app\src\main\assets\tts\ref\ref_sad.refbin",   r"tts\ref\ref_sad.refbin"),
    (r"AtriVC\app\src\main\assets\tts\ref\ref_angry.refbin", r"tts\ref\ref_angry.refbin"),
    (r"AtriVC\app\src\main\assets\tts\ref\ref_calm.refbin",  r"tts\ref\ref_calm.refbin"),
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

    for rel_src, rel_dst in PLAN:
        src = os.path.join(ROOT, rel_src)
        dst = os.path.join(DST, rel_dst)
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
        return 1
    print("\nOK: 全部文件已就位并校验通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
