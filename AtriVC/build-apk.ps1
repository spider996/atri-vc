# 本地构建辅助（Windows / PowerShell）
#
# 用法：
#   .\build-apk.ps1                   构建轻量包（63MB，不含模型）
#   .\build-apk.ps1 -Full             构建全量包（950MB，含全部模型）
#   .\build-apk.ps1 -Full -Install    构建全量包并安装到已连接的设备
#
# 需要先按 README 装好 JDK17 与 Android SDK，并改好下面的路径。

param(
    [string]$Serial = "",
    [string]$Task   = ":app:assembleDebug",
    [switch]$Full,
    [switch]$Install
)

$ErrorActionPreference = "Continue"

# ---------------------------------------------------------------- 按本机情况修改
$jdk    = "D:\AndroidDev\jdk17"
$sdk    = "D:\AndroidDev\Sdk"
$gradle = "D:\AndroidDev\gradle\gradle-8.7\bin\gradle.bat"
$gradleHome = "D:\AndroidDev\gradle-home"
# ----------------------------------------------------------------

$proj = $PSScriptRoot
$log  = Join-Path $proj "build.log"

if (-not (Test-Path $jdk))    { Write-Host "找不到 JDK: $jdk，请修改脚本里的路径" -ForegroundColor Red; exit 1 }
if (-not (Test-Path $gradle)) { Write-Host "找不到 gradle: $gradle，请修改脚本里的路径" -ForegroundColor Red; exit 1 }

$env:JAVA_HOME        = $jdk
$env:ANDROID_HOME     = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:PATH             = "$jdk\bin;$env:PATH"
if (Test-Path $gradleHome) { $env:GRADLE_USER_HOME = $gradleHome }
# 全量包要拷贝 900MB 资产，堆给小了容易 OOM
$env:GRADLE_OPTS      = "-Xmx4096m"

if ($Full) {
    $stage = Join-Path (Split-Path $proj -Parent) "tools\stage_model_assets.py"
    if (-not (Test-Path $stage)) {
        # 仓库根目录才是项目根时，脚本就在 tools/ 下
        $stage = Join-Path $proj "tools\stage_model_assets.py"
    }
    Write-Host "==> 汇总模型到 model_assets/ ..." -ForegroundColor Cyan
    & python $stage
    if ($LASTEXITCODE -ne 0) { Write-Host "模型汇总失败" -ForegroundColor Red; exit 1 }
}

$gradleArgs = @("-p", $proj, $Task, "--console=plain")
if ($Full) { $gradleArgs += "-PbundleModels=true" }

Write-Host "==> 构建中（日志: $log）" -ForegroundColor Cyan
Remove-Item $log -Force -ErrorAction SilentlyContinue
& $gradle @gradleArgs *> $log
$code = $LASTEXITCODE

if ($code -ne 0) {
    Write-Host "构建失败，末尾日志：" -ForegroundColor Red
    Get-Content $log -Tail 40
    exit $code
}

$apk = Join-Path $proj "app\build\outputs\apk\debug\app-debug.apk"
$mb  = [math]::Round((Get-Item $apk).Length / 1MB, 2)
Write-Host "构建成功: $apk ($mb MB)" -ForegroundColor Green

if ($Install) {
    $adb = Join-Path $sdk "platform-tools\adb.exe"
    if (-not (Test-Path $adb)) { Write-Host "找不到 adb: $adb" -ForegroundColor Red; exit 1 }
    $devArgs = @()
    if ($Serial) { $devArgs = @("-s", $Serial) }
    & $adb @devArgs install -r -t $apk
    if ($LASTEXITCODE -ne 0) {
        # 首次调用时 adb 守护进程可能刚启动，这一枪会落空，重试一次
        Write-Host "==> 安装失败，重试一次" -ForegroundColor Yellow
        Start-Sleep -Seconds 2
        & $adb @devArgs install -r -t $apk
    }
}
