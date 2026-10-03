#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用 git 已缓存的 GitHub 凭据创建 Release 并上传附件。

取自 `git credential fill`（GCM 缓存的 OAuth token），不落盘、不回显。
用法:
    python tools/gh_release.py check           # 验证 token + 列出 release
    python tools/gh_release.py create          # 创建 v1.0 release
    python tools/gh_release.py upload          # 上传 APK 附件（大文件，耗时长）
    python tools/gh_release.py status          # 查上传进度
"""
import json
import os
import subprocess
import sys

REPO = os.environ.get("GH_REPO", "spider996/atri-vc")
REPO_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TAG = os.environ.get("GH_TAG", "v1.0")
TITLE = os.environ.get("GH_TITLE", "亚托莉变声器 v1.0（全量包，含模型）")
API = "https://api.github.com"

# 待上传的 APK：按顺序找第一个存在的。可用 GH_APK 环境变量直接指定。
# 附件名单独用 ASSET_NAME（ASCII），避免下载链接里出现中文。
ASSET_NAME = os.environ.get("GH_ASSET_NAME", "atri-vc-v1.0-full.apk")
_APK_ENV = os.environ.get("GH_APK")
APK_CANDIDATES = ([_APK_ENV] if _APK_ENV else []) + [
    r"C:\Users\21264\Desktop\atri-vc-v1.0-full.apk",
    r"C:\Users\21264\Desktop\亚托莉变声器.apk",
    os.path.join(REPO_DIR, r"AtriVC\app\build\outputs\apk\debug\app-debug.apk"),
]


def find_apk():
    for p in APK_CANDIDATES:
        if os.path.isfile(p):
            return p
    raise SystemExit("找不到 APK，检查过：\n  " + "\n  ".join(APK_CANDIDATES))

BODY = """端侧 RVC 变声 + GPT-SoVITS 离线合成，完全离线、不需要 root，音频不出手机。

本附件为 **全量包（941 MB）**，已内置全部模型（15 个文件 / 908 MB）。
安装后首次启动自动导入到 App 私有目录，**装完即用、零配置**。

- 系统要求：Android 8.0 (API 26) 以上，arm64-v8a
- 首次启动解包约需 1–3 分钟（界面上有进度条），只需一次，之后秒进
- 轻量包请自行从源码构建（见 README）

### 功能
- **变声**：RVC 检索式音色转换，INT8 量化三件套 + faiss 检索库
- **合成**：GPT-SoVITS v2ProPlus 全 ONNX 端侧推理，四种情绪
- **QQ 语音代发**：悬浮小窗点按播放 + 扬声器外放回环（免 root 唯一可行路径）
- **收藏库**：LFU 保留最常用的 10 条合成结果

### 已知限制
做不到通话级实时变声（net_g 固定 1024 帧，单次最小延迟 10s）。

### 版权
角色素材（声线模型、立绘）版权属 ANIPLEX.EXE / Frontwing，仅供个人学习交流，禁止商用。
"""


def get_token():
    p = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        capture_output=True, text=True, cwd=REPO_DIR,
    )
    for line in p.stdout.splitlines():
        if line.startswith("password="):
            return line[len("password="):]
    raise SystemExit("取不到凭据：%s" % p.stdout)


def api(method, url, token, data=None, content_type="application/json"):
    import urllib.error
    import urllib.request
    body = None
    if data is not None:
        body = json.dumps(data).encode("utf-8") if isinstance(data, (dict, list)) else data
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "atri-vc-release-tool")
    if body is not None:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        msg = e.read().decode("utf-8", "replace")
        raise SystemExit("HTTP %d %s\n%s" % (e.code, e.reason, msg[:600]))


def cmd_check(token):
    me = api("GET", API + "/user", token)
    print("token 属于  : %s (id %s)" % (me.get("login"), me.get("id")))
    rel = api("GET", "%s/repos/%s/releases" % (API, REPO), token)
    print("现有 release: %d 个" % len(rel))
    for r in rel:
        print("  - %s  assets=%d  upload_url=%s" % (r["tag_name"], len(r["assets"]), r["upload_url"]))


def cmd_create(token):
    refs = api("GET", "%s/repos/%s/git/refs/tags/%s" % (API, REPO, TAG), token) \
        if False else None
    payload = {
        "tag_name": TAG,
        "name": TITLE,
        "body": BODY,
        "draft": False,
        "prerelease": False,
    }
    rel = api("POST", "%s/repos/%s/releases" % (API, REPO), token, payload)
    print("RELEASE_ID=%s" % rel["id"])
    print("TAG       =%s" % rel["tag_name"])
    print("UPLOAD_URL=%s" % rel["upload_url"])
    print("HTML_URL  =%s" % rel["html_url"])


def cmd_upload(token):
    apk = find_apk()
    rels = api("GET", "%s/repos/%s/releases" % (API, REPO), token)
    rel = None
    for r in rels:
        if r["tag_name"] == TAG:
            rel = r
    if rel is None:
        raise SystemExit("release %s 不存在，先跑 create" % TAG)

    name = ASSET_NAME
    for a in rel.get("assets", []):
        if a["name"] == name:
            print("附件已存在：%s  %.2f MB  下载 %d 次" % (a["name"], a["size"] / 1048576, a["download_count"]))
            return

    size = os.path.getsize(apk)
    print("源文件：%s" % apk, flush=True)
    print("开始上传 %s  (%.2f MB) ..." % (name, size / 1048576), flush=True)
    url = "%s/repos/%s/releases/%s/assets?name=%s" % (
        "https://uploads.github.com", REPO, rel["id"], name)
    # 用 curl 上传：自动带 Content-Length、支持大文件流式
    cmd = [
        "curl", "-sS", "-X", "POST",
        "-H", "Authorization: Bearer " + token,
        "-H", "Content-Type: application/octet-stream",
        "--data-binary", "@" + apk,
        url,
    ]
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0:
        raise SystemExit("curl 失败 rc=%s\n%s" % (p.returncode, p.stderr[:800]))
    try:
        res = json.loads(p.stdout)
    except Exception:
        raise SystemExit("返回非 JSON：%s" % p.stdout[:500])
    if "id" not in res:
        raise SystemExit("上传失败：%s" % p.stdout[:500])
    print("上传成功：%s  %.2f MB" % (res["name"], res["size"] / 1048576))
    print("下载地址：%s" % res["browser_download_url"])


def cmd_status(token):
    rels = api("GET", "%s/repos/%s/releases" % (API, REPO), token)
    if not rels:
        print("(还没有 release)")
        return
    for r in rels:
        print("release %s  %s" % (r["tag_name"], r["html_url"]))
        if not r["assets"]:
            print("   (无附件)")
        for a in r["assets"]:
            print("   - %s  %.2f MB  下载 %d 次  %s" % (
                a["name"], a["size"] / 1048576, a["download_count"], a["browser_download_url"]))


def main():
    action = sys.argv[1] if len(sys.argv) > 1 else "status"
    token = get_token()
    {"check": cmd_check, "create": cmd_create,
     "upload": cmd_upload, "status": cmd_status}[action](token)


if __name__ == "__main__":
    main()
