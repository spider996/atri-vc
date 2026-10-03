# 在 PC 上跑 Kotlin 管线，与 Python 基准逐级对齐
$ErrorActionPreference = "Continue"

$jdk    = "D:\AndroidDev\jdk17"
$gradle = "D:\AndroidDev\gradle\gradle-8.7\bin\gradle.bat"
$proj   = Split-Path -Parent $MyInvocation.MyCommand.Path

$env:JAVA_HOME        = $jdk
$env:GRADLE_USER_HOME = "D:\AndroidDev\gradle-home"
$env:PATH             = "$jdk\bin;$env:PATH"

$extra = if ($args.Count -gt 0) { $args -join " " } else { "" }

& $gradle -p $proj :cli:run --args="$extra" --console=plain -q
