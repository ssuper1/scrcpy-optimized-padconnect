param(
    [switch]$SkipBuild,
    [string]$SdkDirectory = '',
    [string]$SigningStore = (Join-Path $env:USERPROFILE '.codex\app-signing\padconnect-repair.p12')
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'resolve-android-sdk.ps1')
$SdkDirectory = Resolve-PadconnectAndroidSdk -SdkDirectory $SdkDirectory -ProjectDirectory $projectDirectory
$buildTools = Join-Path $SdkDirectory 'build-tools\36.1.0'
$artifactDirectory = Join-Path $projectDirectory 'artifacts'
New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null
$previousAndroidHome = $env:ANDROID_HOME

Push-Location $projectDirectory
try {
    $env:ANDROID_HOME = $SdkDirectory
    if (!$SkipBuild) {
        & .\gradlew.bat :remote:assembleRelease :remote:testDebugUnitTest :adb:testDebugUnitTest :remote:lintRelease --console=plain --no-configuration-cache
        if ($LASTEXITCODE -ne 0) { throw 'Build or verification failed' }
    }
    $apkDirectory = Join-Path $projectDirectory 'scrcpy-android\app\build\outputs\apk\release'
    $metadata = Get-Content -LiteralPath (Join-Path $apkDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
    $unsigned = Join-Path $apkDirectory $metadata.elements[0].outputFile
    $aligned = Join-Path $artifactDirectory 'phone-aligned.apk'
    $signed = Join-Path $artifactDirectory ('scrcpy-' + $metadata.elements[0].versionName + '.apk')
    if (!(Test-Path -LiteralPath $SigningStore)) { throw 'The signing key for the previous repair build is missing' }
    & (Join-Path $buildTools 'zipalign.exe') -P 16 -f 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed' }
    & (Join-Path $buildTools 'apksigner.bat') sign --ks $SigningStore --ks-key-alias scrcpy-repair --ks-pass pass:android --out $signed $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
    & (Join-Path $buildTools 'apksigner.bat') verify --verbose $signed
    if ($LASTEXITCODE -ne 0) { throw 'Signature verification failed' }
    & (Join-Path $buildTools 'zipalign.exe') -c -P 16 4 $signed
    if ($LASTEXITCODE -ne 0) { throw '16 KiB alignment verification failed' }
    Remove-Item -LiteralPath $aligned
    Get-FileHash -Algorithm SHA256 -LiteralPath $signed
} finally {
    $env:ANDROID_HOME = $previousAndroidHome
    Pop-Location
}
