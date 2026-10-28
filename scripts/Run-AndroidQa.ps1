param(
    [string]$Device = 'emulator-5554',
    [string]$SdkPath = (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
    [switch]$IncludeNetwork,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$adbExecutable = Join-Path $SdkPath 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adbExecutable)) { throw "adb not found: $adbExecutable" }
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
    # Required by the controlled MediaSession test. This changes the selected QA device only.
    & $adbExecutable -s $Device shell cmd notification allow_listener com.example.lyricsflow/com.example.lyricsflow.playback.MusicNotificationListener
    $testClasses = 'com.example.lyricsflow.PlaybackIntegrationTest,com.example.lyricsflow.PlayerMetadataTest,com.example.lyricsflow.UiFlowTest,com.example.lyricsflow.ImportSecurityTest,com.example.lyricsflow.CalibrationOffsetTest,com.example.lyricsflow.FlowAppearanceTest,com.example.lyricsflow.TranslationPolicyTest,com.example.lyricsflow.ControlReadabilityTest'
    $testClasses += ',com.example.lyricsflow.GitHubSyncTest,com.example.lyricsflow.GitHubUiTest,com.example.lyricsflow.GitHubNetworkPolicyTest'
    $testClasses += ',com.example.lyricsflow.ReaderPolishTest'
    $testClasses += ',com.example.lyricsflow.LrcLibClientTest,com.example.lyricsflow.LyricScrollTest'
    $testClasses += ',com.example.lyricsflow.AmllClientTest,com.example.lyricsflow.MeshUiTest'
    $testClasses += ',com.example.lyricsflow.ArtworkBlendTest'
    $testClasses += ',com.example.lyricsflow.LrcOriginalCacheTest'
    $testClasses += ',com.example.lyricsflow.SongSelectionTest,com.example.lyricsflow.SongSelectionUiTest'
    $testClasses += ',com.example.lyricsflow.SearchLayoutTest'
    $testClasses += ',com.example.lyricsflow.AuditStateTest,com.example.lyricsflow.AuditUiTest'
    $expectedCount = 70
    if ($IncludeNetwork) {
        $testClasses += ',com.example.lyricsflow.ProviderIntegrationTest,com.example.lyricsflow.QqDiscoveryTest,com.example.lyricsflow.SearchUiTest,com.example.lyricsflow.AutomaticMatchingTest'
        $testClasses += ',com.example.lyricsflow.LrcLibLiveTest'
        $testClasses += ',com.example.lyricsflow.AmllLiveTest'
        $expectedCount = 87
    }
    $testOutput = & $adbExecutable -s $Device shell am instrument -w -r -e disableAnalytics true -e class $testClasses com.example.lyricsflow.test/androidx.test.runner.AndroidJUnitRunner
    $outputDirectory = Join-Path $projectDirectory 'docs\android-qa'
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
    $testOutput | Set-Content -Encoding utf8 -LiteralPath (Join-Path $outputDirectory "instrumentation-$Device.log")
    $textOutput = $testOutput -join "`n"
    Write-Output $textOutput
    # am instrument can exit 0 even when JUnit reports a failure.
    if ($textOutput -notmatch "OK \($expectedCount tests\)" -or $textOutput -match 'FAILURES!!!|INSTRUMENTATION_FAILED') {
        throw "Instrumented tests did not pass on $Device. See docs/android-qa/instrumentation-$Device.log."
    }
} finally {
    Pop-Location
}
