param(
    [string]$SdkDirectory = ''
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'resolve-android-sdk.ps1')
$SdkDirectory = Resolve-PadconnectAndroidSdk -SdkDirectory $SdkDirectory -ProjectDirectory $projectDirectory
$serverProject = Join-Path $projectDirectory 'scrcpy-android\vendor\scrcpy'
$previousAndroidHome = $env:ANDROID_HOME
Push-Location $projectDirectory
try {
    $env:ANDROID_HOME = $SdkDirectory
    & .\gradlew.bat -p $serverProject :server:assembleRelease :server:testDebugUnitTest :server:lintRelease --console=plain --no-configuration-cache
    if ($LASTEXITCODE -ne 0) { throw 'Server build or tests failed' }
    $sourceApk = Join-Path $serverProject 'server\build\outputs\apk\release\server-release-unsigned.apk'
    $assetDirectory = Join-Path $projectDirectory 'scrcpy-android\app\src\main\assets'
    $destination = Join-Path $assetDirectory 'scrcpy-server.jar'
    Copy-Item -LiteralPath $sourceApk -Destination $destination -Force
    $checksum = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText((Join-Path $assetDirectory 'scrcpy-server.sha256'), "$checksum`n")
    Get-FileHash -LiteralPath $destination -Algorithm SHA256
} finally {
    $env:ANDROID_HOME = $previousAndroidHome
    Pop-Location
}
