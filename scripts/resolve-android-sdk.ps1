function Resolve-PadconnectAndroidSdk {
    param(
        [string]$SdkDirectory,
        [Parameter(Mandatory = $true)][string]$ProjectDirectory
    )

    $candidates = @($SdkDirectory, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    $propertiesPath = Join-Path $ProjectDirectory 'local.properties'
    if (Test-Path -LiteralPath $propertiesPath) {
        $sdkLine = Get-Content -LiteralPath $propertiesPath |
            Where-Object { $_ -match '^\s*sdk\.dir\s*=' } |
            Select-Object -Last 1
        if ($sdkLine) {
            $sdkValue = ($sdkLine -replace '^\s*sdk\.dir\s*=\s*', '').Trim()
            $candidates += $sdkValue.Replace('\:', ':').Replace('\\', '\')
        }
    }
    if ($env:LOCALAPPDATA) {
        $candidates += Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    }
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'platforms'))) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw 'Android SDK not found. Set ANDROID_HOME, configure local.properties, or pass -SdkDirectory.'
}
