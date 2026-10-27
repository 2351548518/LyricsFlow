param(
    [Parameter(Mandatory = $true)][string]$Device,
    [Parameter(Mandatory = $true)][ValidateSet('Spotify', 'QQ', 'NetEase')][string]$Player,
    [string]$SdkPath = (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
    [switch]$RequireLyrics,
    [switch]$CaptureWindow,
    [switch]$SkipBuild
)

# Explicitly invoked only: this test plays, pauses and seeks the selected real music app.
# Select a song in that app first. It restores the original position/play state when possible.
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$adbExecutable = Join-Path $SdkPath 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adbExecutable)) { throw "adb not found: $adbExecutable" }
$packages = @{ Spotify = 'com.spotify.music'; QQ = 'com.tencent.qqmusic'; NetEase = 'com.netease.cloudmusic' }
Push-Location $projectDirectory
try {
    if (-not $SkipBuild) {
        android build :app:assembleDebug -- :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Build, unit tests, or lint failed.' }
    }
    android install "--device=$Device" --apks=app/build/outputs/apk/debug/app-debug.apk
    if ($LASTEXITCODE -ne 0) { throw 'App installation failed.' }
    android install "--device=$Device" --apks=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'Test installation failed.' }
    & $adbExecutable -s $Device shell cmd notification allow_listener com.example.lyricsflow/com.example.lyricsflow.playback.MusicNotificationListener
    if ($LASTEXITCODE -ne 0) { throw 'Cannot grant playback-state access.' }
    $testClasses = 'com.example.lyricsflow.PlayerMetadataTest,com.example.lyricsflow.RealPlayerIntegrationTest'
    $arguments = @('-s', $Device, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'disableAnalytics', 'true',
        '-e', 'allowRealPlayer', 'true', '-e', 'playerPackage', $packages[$Player],
        '-e', 'class', $testClasses)
    if ($RequireLyrics) { $arguments += @('-e', 'requireLyrics', 'true') }
    if ($CaptureWindow) { $arguments += @('-e', 'captureWindow', 'true') }
    $arguments += 'com.example.lyricsflow.test/androidx.test.runner.AndroidJUnitRunner'
    $outputDirectory = Join-Path $projectDirectory 'docs\android-qa'
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
    $outputPath = Join-Path $outputDirectory "real-$($Player.ToLowerInvariant())-instrumentation.log"
    $testOutput = & $adbExecutable @arguments | Tee-Object -FilePath $outputPath
    $textOutput = $testOutput -join "`n"
    Write-Output $textOutput
    if ($textOutput -notmatch 'OK \(4 tests\)' -or $textOutput -match 'FAILURES!!!|INSTRUMENTATION_FAILED' -or $textOutput -notmatch 'result=REAL_PLAYER') {
        throw "Real $Player test failed. See $outputPath"
    }
} finally {
    Pop-Location
}
