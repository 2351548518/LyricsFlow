param(
    [switch]$InitializeSigning,
    [string]$SigningDirectory = (Join-Path $env:USERPROFILE '.android\lyricsflow-release'),
    [string]$SdkPath = (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$signingVariables = @('LYRICSFLOW_KEYSTORE_FILE', 'LYRICSFLOW_STORE_PASSWORD', 'LYRICSFLOW_KEY_ALIAS', 'LYRICSFLOW_KEY_PASSWORD')
$previousValues = @{}
foreach ($name in $signingVariables) { $previousValues[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
Push-Location $projectDirectory
try {
    $keyPath = Join-Path $SigningDirectory 'lyricsflow-release.p12'
    $credentialPath = Join-Path $SigningDirectory 'signing.credentials.clixml'
    if ($InitializeSigning) {
        if ((Test-Path -LiteralPath $keyPath) -or (Test-Path -LiteralPath $credentialPath)) {
            throw 'Signing material already exists. Reuse it without -InitializeSigning; never replace the published signing key.'
        }
        New-Item -ItemType Directory -Force -Path $SigningDirectory | Out-Null
        $password = [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        $credential = [PSCredential]::new('lyricsflow-release', (ConvertTo-SecureString $password -AsPlainText -Force))
        # Export-Clixml encrypts the password with Windows DPAPI for the current account.
        $credential | Export-Clixml -LiteralPath $credentialPath
        $env:LYRICSFLOW_STORE_PASSWORD = $password
        $env:LYRICSFLOW_KEY_PASSWORD = $password
        & keytool -genkeypair -storetype PKCS12 -keystore $keyPath -alias lyricsflow-release -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=LyricsFlow,OU=Android,O=LyricsFlow' -storepass:env LYRICSFLOW_STORE_PASSWORD -keypass:env LYRICSFLOW_KEY_PASSWORD -noprompt
        if ($LASTEXITCODE -ne 0) { throw 'Release key generation failed. Preserve any generated signing files and check the error.' }
        Write-Output "Created private signing key outside the repository: $keyPath"
    }

    $missing = @($signingVariables | Where-Object { -not [Environment]::GetEnvironmentVariable($_, 'Process') })
    if ($missing.Count -gt 0) {
        if (-not (Test-Path -LiteralPath $keyPath) -or -not (Test-Path -LiteralPath $credentialPath)) {
            throw 'No complete release signing configuration. On Windows, initialize once with -InitializeSigning or provide all four documented environment variables.'
        }
        $credential = Import-Clixml -LiteralPath $credentialPath
        $env:LYRICSFLOW_KEYSTORE_FILE = $keyPath
        $env:LYRICSFLOW_STORE_PASSWORD = $credential.GetNetworkCredential().Password
        $env:LYRICSFLOW_KEY_ALIAS = $credential.UserName
        $env:LYRICSFLOW_KEY_PASSWORD = $env:LYRICSFLOW_STORE_PASSWORD
    }

    # Do not serialize signing credentials into the Gradle configuration cache.
    android build :app:assembleRelease -- :app:testDebugUnitTest :app:lintRelease --no-configuration-cache --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Release build, unit tests or lint failed.' }
    $metadataPath = Join-Path $projectDirectory 'app\build\outputs\apk\release\output-metadata.json'
    $metadata = Get-Content -Raw -LiteralPath $metadataPath | ConvertFrom-Json
    if ($metadata.applicationId -ne 'com.example.lyricsflow' -or $metadata.elements.Count -ne 1) {
        throw 'Unexpected release package or split output.'
    }
    $version = $metadata.elements[0].versionName
    $outputDirectory = Join-Path $projectDirectory "dist\v$version"
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
    $apkName = "LyricsFlow-v$version.apk"
    $apkPath = Join-Path $outputDirectory $apkName
    Copy-Item -LiteralPath (Join-Path (Split-Path $metadataPath) $metadata.elements[0].outputFile) -Destination $apkPath
    $buildTools = Get-ChildItem -LiteralPath (Join-Path $SdkPath 'build-tools') -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if (-not $buildTools) { throw 'Android SDK build-tools not found.' }
    $signature = & (Join-Path $buildTools.FullName 'apksigner.bat') verify --verbose --print-certs $apkPath
    if ($LASTEXITCODE -ne 0) { throw 'APK signing verification failed.' }
    $signature | Set-Content -Encoding utf8 -LiteralPath (Join-Path $outputDirectory 'signature.txt')
    $badging = & (Join-Path $buildTools.FullName 'aapt.exe') dump badging $apkPath
    if ($LASTEXITCODE -ne 0 -or ($badging -join "`n") -match 'application-debuggable') { throw 'APK metadata invalid or release is debuggable.' }
    $badging | Set-Content -Encoding utf8 -LiteralPath (Join-Path $outputDirectory 'apk-metadata.txt')
    & keytool -exportcert -keystore $env:LYRICSFLOW_KEYSTORE_FILE -alias $env:LYRICSFLOW_KEY_ALIAS -storepass:env LYRICSFLOW_STORE_PASSWORD -file (Join-Path $outputDirectory 'LyricsFlow-signing-certificate.cer')
    if ($LASTEXITCODE -ne 0) { throw 'Public certificate export failed.' }
    $hash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $apkName" | Set-Content -Encoding ascii -LiteralPath (Join-Path $outputDirectory 'SHA256SUMS.txt')
    Write-Output "Verified signed release: $apkPath"
    Write-Output "SHA-256: $hash"
} finally {
    foreach ($name in $signingVariables) { [Environment]::SetEnvironmentVariable($name, $previousValues[$name], 'Process') }
    $password = $null
    $credential = $null
    Pop-Location
}
