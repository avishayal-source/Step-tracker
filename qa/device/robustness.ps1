<#
.SYNOPSIS
  Device-level robustness checks for Y Walk over adb (crash hunting, permissions,
  display scaling, power management). Every setting the script changes is restored.

.EXAMPLE
  .\qa\device\robustness.ps1                       # all suites, first device
  .\qa\device\robustness.ps1 -Suite Monkey -Events 20000 -Seed 7
  .\qa\device\robustness.ps1 -Suite Display -Serial 00170157N001216
#>
param(
    [ValidateSet('All', 'Monkey', 'Permissions', 'Display', 'Power', 'Lifecycle')]
    [string]$Suite = 'All',
    [string]$Serial = '',
    [string]$Package = 'com.ywalk.app',
    [int]$Events = 5000,
    [int]$Seed = 1
)

$ErrorActionPreference = 'Stop'
$adbExe = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adbExe)) { $adbExe = 'adb' }
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$out = Join-Path $PSScriptRoot "out\$stamp"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$results = [System.Collections.Generic.List[object]]::new()

function Adb { param([Parameter(ValueFromRemainingArguments)] $a)
    if ($Serial) { & $adbExe -s $Serial @a } else { & $adbExe @a } }
function Sh([string]$cmd) { Adb shell $cmd }
function Launch { Sh "monkey -p $Package -c android.intent.category.LAUNCHER 1" | Out-Null; Start-Sleep 3 }
function Shot([string]$name) {
    Sh "screencap -p /sdcard/qa_shot.png" | Out-Null
    Adb pull /sdcard/qa_shot.png (Join-Path $out "$name.png") | Out-Null
}
function ClearCrashes { Sh 'logcat -b crash -c' | Out-Null; Sh 'logcat -b events -c' | Out-Null }
# An ANR is a failure just like a crash: the system logs it as am_anr in the events buffer.
function Record([string]$check, [string]$note = '', [bool]$anrSeen = $false) {
    $crash = (Sh 'logcat -b crash -d') -join "`n"
    $mine = $crash -match [regex]::Escape($Package)
    $events = (Sh 'logcat -b events -d -s am_anr') -join "`n"
    $anr = $anrSeen -or ($events -match [regex]::Escape($Package))
    $tag = $check -replace '\W','_'
    if ($mine) { $crash | Set-Content (Join-Path $out "crash_$tag.txt") }
    if ($anr) { $events | Set-Content (Join-Path $out "anr_$tag.txt") }
    $result = if ($mine) { 'CRASH' } elseif ($anr) { 'ANR' } else { 'ok' }
    $results.Add([pscustomobject]@{ Check = $check; Result = $result; Note = $note })
    ClearCrashes
}

if (-not ((Adb get-state) -match 'device')) { throw 'No adb device. Connect the phone (or pass -Serial).' }
Write-Host "Device: $((Sh 'getprop ro.product.model').Trim())  Android $((Sh 'getprop ro.build.version.release').Trim())  → $out"
ClearCrashes

# ── Monkey: random taps/swipes/keys, the "random user" in its purest form ───────
if ($Suite -in 'All', 'Monkey') {
    Write-Host "Monkey: $Events events, seed $Seed"
    $log = Sh "monkey -p $Package -s $Seed --throttle 120 --pct-syskeys 0 --pct-appswitch 5 --ignore-security-exceptions -v $Events"
    $log | Set-Content (Join-Path $out 'monkey.txt')
    $note = ($log | Select-String -Pattern 'CRASH|ANR|Monkey aborted' | Select-Object -First 3) -join ' | '
    $monkeyAnr = [bool]($log | Select-String -Pattern "ANR in $([regex]::Escape($Package))|NOT RESPONDING: $([regex]::Escape($Package))")
    Record "monkey seed=$Seed events=$Events" $note $monkeyAnr
    Sh "am force-stop $Package" | Out-Null
}

# ── Permissions: each runtime permission denied on its own ──────────────────────
if ($Suite -in 'All', 'Permissions') {
    $perms = 'android.permission.ACCESS_FINE_LOCATION', 'android.permission.ACCESS_COARSE_LOCATION',
             'android.permission.ACTIVITY_RECOGNITION', 'android.permission.POST_NOTIFICATIONS',
             'android.permission.BODY_SENSORS'
    $dump = (Sh "dumpsys package $Package") -join "`n"
    foreach ($p in $perms) {
        $granted = $dump -match [regex]::Escape("$p`: granted=true")
        Sh "pm revoke $Package $p" 2>$null | Out-Null
        Launch
        Shot "perm_denied_$($p.Split('.')[-1])"
        Record "launch with $($p.Split('.')[-1]) denied" 'open the screenshot: is there a clear explanation + way to grant?'
        if ($granted) { Sh "pm grant $Package $p" 2>$null | Out-Null }
    }
    Sh "am force-stop $Package" | Out-Null
}

# ── Display: huge fonts, big display size, dark mode, forced RTL ────────────────
if ($Suite -in 'All', 'Display') {
    $font = (Sh 'settings get system font_scale').Trim()
    $night = (Sh 'cmd uimode night').Trim()
    try {
        foreach ($scale in '1.3', '2.0') {
            Sh "settings put system font_scale $scale" | Out-Null
            Launch; Shot "font_$scale"; Record "font scale $scale" 'check clipped text / unreachable buttons'
        }
        Sh "settings put system font_scale $font" | Out-Null
        Sh 'wm density 600' | Out-Null
        Launch; Shot 'density_600'; Record 'display size large (density 600)'
        Sh 'wm density reset' | Out-Null
        Sh 'cmd uimode night yes' | Out-Null
        Launch; Shot 'dark_mode'; Record 'dark mode' 'check contrast of every card/chart'
        Sh 'settings put global debug.force_rtl 1' | Out-Null
        Sh "am force-stop $Package" | Out-Null
        Launch; Shot 'rtl'; Record 'forced RTL layout (Hebrew/Arabic users)'
    } finally {
        Sh "settings put system font_scale $font" | Out-Null
        Sh 'wm density reset' | Out-Null
        Sh 'settings put global debug.force_rtl 0' | Out-Null
        if ($night -match 'no') { Sh 'cmd uimode night no' | Out-Null }
        Sh "am force-stop $Package" | Out-Null
    }
}

# ── Lifecycle: process death and memory pressure while backgrounded ─────────────
if ($Suite -in 'All', 'Lifecycle') {
    Launch
    Read-Host 'Start a workout on the phone (Track tab), then press Enter'
    Sh 'input keyevent KEYCODE_HOME' | Out-Null; Start-Sleep 2
    Sh "am send-trim-memory $Package RUNNING_CRITICAL" | Out-Null; Start-Sleep 2
    Record 'trim-memory RUNNING_CRITICAL during workout'
    Sh "am kill $Package" | Out-Null; Start-Sleep 5
    $alive = (Sh "pidof $Package").Trim()
    Launch; Shot 'after_process_kill'
    Record 'am kill while backgrounded mid-workout' "process after kill: '$alive' — is the workout still running with correct time/distance?"
    for ($i = 0; $i -lt 6; $i++) { Sh 'settings put system user_rotation 1'; Start-Sleep 1; Sh 'settings put system user_rotation 0'; Start-Sleep 1 }
    Record 'rotation x6 during workout'
}

# ── Power: Doze and app-standby, the #1 cause of "it stopped tracking" ──────────
if ($Suite -in 'All', 'Power') {
    Launch
    Read-Host 'Start a workout, lock the screen, then press Enter (screen must stay off)'
    Sh 'dumpsys battery unplug' | Out-Null
    try {
        Sh 'dumpsys deviceidle force-idle' | Out-Null
        Start-Sleep 60
        $svc = (Sh "dumpsys activity services $Package") -join "`n"
        $running = $svc -match 'StepTrackerService'
        Record 'Doze force-idle 60 s during workout' "StepTrackerService alive: $running"
        Sh "am set-standby-bucket $Package rare" | Out-Null
        Start-Sleep 30
        Record 'standby bucket RARE during workout'
    } finally {
        Sh 'dumpsys deviceidle unforce' | Out-Null
        Sh "am set-standby-bucket $Package active" | Out-Null
        Sh 'dumpsys battery reset' | Out-Null
    }
}

$results | Format-Table -AutoSize | Out-String | Tee-Object (Join-Path $out 'summary.txt')
if ($results.Result -contains 'CRASH' -or $results.Result -contains 'ANR') {
    Write-Host 'CRASHES/ANRs FOUND - see crash_*.txt / anr_*.txt' -ForegroundColor Red; exit 1
}
