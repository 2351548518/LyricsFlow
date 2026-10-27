param(
    [Parameter(Mandatory = $true)][string]$ApiConfigPath,
    [string]$Device = 'emulator-5554',
    [string]$SdkPath = (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
    [string]$Model = 'deepseek-flash',
    [switch]$SkipBuild,
    [switch]$CaptureWindow
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$adbExecutable = Join-Path $SdkPath 'platform-tools\adb.exe'
$deepseekKey = $null
Push-Location $projectDirectory
try {
    $apiText = Get-Content -LiteralPath $ApiConfigPath -Raw -Encoding utf8
    $deepseekKey = [regex]::Match($apiText, '(?im)^Key\s*[:：]\s*(\S+)').Groups[1].Value.Trim('`','"',"'")
    $baseUrl = [regex]::Match($apiText, '(?im)^URL\s*[:：]\s*(https://\S+)').Groups[1].Value.TrimEnd('/')
    if (-not $deepseekKey -or $baseUrl -ne 'https://api.deepseek.com') { throw 'Expected a DeepSeek HTTPS URL and a nonempty Key field.' }
    if (-not $SkipBuild) {
        android build :app:assembleDebug -- :app:assembleDebugAndroidTest :app:testDebugUnitTest --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
    }
    android install "--device=$Device" --apks=app/build/outputs/apk/debug/app-debug.apk
    if ($LASTEXITCODE -ne 0) { throw 'App installation failed.' }
    android install "--device=$Device" --apks=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'Test installation failed.' }

    # No secret in shell arguments, environment variables, repository files, or APKs.
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $adbExecutable
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardInputEncoding = [System.Text.UTF8Encoding]::new($false)
    foreach ($argument in @('-s',$Device,'shell','-T','run-as','com.example.lyricsflow','dd','of=files/translation-qa.json')) { $start.ArgumentList.Add($argument) }
    $process = [System.Diagnostics.Process]::Start($start)
    $runtimeConfiguration = @{baseUrl=$baseUrl; model=$Model; key=$deepseekKey} | ConvertTo-Json -Compress
    $process.StandardInput.Write($runtimeConfiguration)
    $process.StandardInput.Close()
    $null = $process.StandardOutput.ReadToEnd()
    $null = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw 'Unable to deliver the private runtime configuration.' }
    $process.Dispose()
    $runtimeConfiguration = $null
    $capture = if ($CaptureWindow) {'true'} else {'false'}
    $result = & $adbExecutable -s $Device shell am instrument -w -r -e disableAnalytics true -e allowPaidTranslation true -e captureWindow $capture -e class com.example.lyricsflow.TranslationProtocolTest,com.example.lyricsflow.DeepSeekTranslationIntegrationTest com.example.lyricsflow.test/androidx.test.runner.AndroidJUnitRunner
    $safeResult = ($result -join "`n").Replace($deepseekKey, '[REDACTED]')
    $outputDirectory = Join-Path $projectDirectory 'docs\android-qa'
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
    Set-Content -LiteralPath (Join-Path $outputDirectory "deepseek-$Device.log") -Encoding utf8 -Value $safeResult
    Write-Output $safeResult
    if ($safeResult -notmatch 'OK \(4 tests\)' -or $safeResult -match 'FAILURES!!!|INSTRUMENTATION_FAILED') { throw 'DeepSeek translation QA did not pass; see the redacted QA log.' }
} finally {
    if (Test-Path -LiteralPath $adbExecutable) { $null = & $adbExecutable -s $Device shell run-as com.example.lyricsflow rm -f files/translation-qa.json }
    $deepseekKey = $null
    $apiText = $null
    $runtimeConfiguration = $null
    Pop-Location
}
