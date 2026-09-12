# PC harness: Botty hybrid model (mirrors RunPlanCoach.kt v2)
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File botty-eval\botty-eval.ps1
param([string]$OutDir = $PSScriptRoot)

$ErrorActionPreference = 'Stop'

$MAX_WEEKLY_GROWTH = 0.10
$BEGINNER_WEEKLY_GROWTH = 0.15
$SAFE_WEEKLY_GROWTH = 0.07
$C25K_WEEKS = 9
$C25K_HIGH_RISK_WEEKS = 12
$C25K_MAX_TARGET_KM = 5.0
$C210K_WEEKS = 16
$C210K_HIGH_RISK_WEEKS = 20
$C210K_MAX_TARGET_KM = 10.0

function Round1([double]$v) { [math]::Round($v, 1) }

function Evaluate-PlanWeeks {
    param([hashtable]$P, [hashtable]$G)

    $heightM = [math]::Max($P.heightCm / 100.0, 0.5)
    $bmi = $P.weightKg / ($heightM * $heightM)
    $highRisk = ($bmi -ge 30) -or ($P.ageYears -ge 65)
    $beginner = $P.currentLongestRunKm -lt 1.0
    $start = if ($beginner) { 1.0 } else { $P.currentLongestRunKm }
    $target = $G.targetDistanceKm
    $horizon = [math]::Max(1, $G.horizonWeeks)

    if ($beginner -and $target -le $C25K_MAX_TARGET_KM) {
        $safeWeeks = if ($highRisk) { $C25K_HIGH_RISK_WEEKS } else { $C25K_WEEKS }
        if ($horizon -ge [math]::Ceiling($safeWeeks * 1.15)) { $verdict = 'REALISTIC' }
        elseif ($horizon -ge $safeWeeks) { $verdict = 'AMBITIOUS' }
        else { $verdict = 'NOT_REALISTIC' }
        return [pscustomobject]@{ bmi = (Round1 $bmi); path = 'c25k'; verdict = $verdict; planWeeks = $safeWeeks }
    }
    if ($beginner -and $target -le $C210K_MAX_TARGET_KM) {
        $safeWeeks = if ($highRisk) { $C210K_HIGH_RISK_WEEKS } else { $C210K_WEEKS }
        if ($horizon -ge [math]::Ceiling($safeWeeks * 1.15)) { $verdict = 'REALISTIC' }
        elseif ($horizon -ge $safeWeeks) { $verdict = 'AMBITIOUS' }
        else { $verdict = 'NOT_REALISTIC' }
        return [pscustomobject]@{ bmi = (Round1 $bmi); path = 'c210k'; verdict = $verdict; planWeeks = $safeWeeks }
    }

    # Distance ramp for beginners >10 km / intermediate / advanced
    if ($beginner -and -not $highRisk) { $growthCap = $BEGINNER_WEEKLY_GROWTH }
    elseif ($beginner -and $highRisk) { $growthCap = $MAX_WEEKLY_GROWTH }
    elseif ($highRisk) { $growthCap = $SAFE_WEEKLY_GROWTH }
    else { $growthCap = $MAX_WEEKLY_GROWTH }

    $cutbackEvery = if ($beginner) { 5 } else { 4 }
    $taper = if ($target -ge 5.0) { 1 } else { 0 }
    $adaptationFloor = if ($beginner) { 8 } else { 4 }
    if ($target -le $start) { $progressNeeded = 0 }
    else {
        $progressNeeded = [math]::Ceiling([math]::Log($target / $start) / [math]::Log(1.0 + $growthCap))
    }
    $cutDiv = [math]::Max(1, $cutbackEvery - 1)
    $cutbacksNeeded = [math]::Floor($progressNeeded / $cutDiv)
    $safeWeeks = [math]::Max($progressNeeded + $cutbacksNeeded + $taper, $adaptationFloor)

    if ($target -le $start) { $verdict = 'REALISTIC' }
    elseif ($horizon -ge [math]::Ceiling($safeWeeks * 1.15)) { $verdict = 'REALISTIC' }
    elseif ($horizon -ge $safeWeeks) { $verdict = 'AMBITIOUS' }
    else { $verdict = 'NOT_REALISTIC' }

    $planWeeks = if ($verdict -eq 'NOT_REALISTIC') { $safeWeeks } else { $horizon }
    $path = if ($beginner) { 'beginner_ramp' } else { 'ramp' }

    [pscustomobject]@{
        bmi = (Round1 $bmi); path = $path; verdict = $verdict; planWeeks = $planWeeks
    }
}

function New-Scenario {
    param(
        [string]$Id, [string]$Category, [string]$Goal,
        [int]$Age, [string]$Sex, [double]$WeightKg, [double]$HeightCm,
        [double]$CurrentKm, [int]$Days, [double]$TargetKm, [int]$AskWeeks
    )
    [pscustomobject]@{
        id = $Id; category = $Category; goal = $Goal
        age = $Age; sex = $Sex; weightKg = $WeightKg; heightCm = $HeightCm
        currentKm = $CurrentKm; days = $Days; targetKm = $TargetKm; askWeeks = $AskWeeks
    }
}

$scenarios = @(
    (New-Scenario 'B01' 'beginner' '0 to 3km'  22 'F' 58  162 0 3 3  8)
    (New-Scenario 'B02' 'beginner' '0 to 5km'  28 'M' 78  180 0 3 5  8)
    (New-Scenario 'B03' 'beginner' '0 to 5km'  34 'F' 65  168 0 3 5 12)
    (New-Scenario 'B04' 'beginner' '0 to 5km'  41 'M' 88  175 0 3 5 16)
    (New-Scenario 'B05' 'beginner' '0 to 5km'  29 'other' 70 170 0 4 5 23)
    (New-Scenario 'B06' 'beginner' '0 to 10km' 25 'F' 55  158 0 3 10 12)
    (New-Scenario 'B07' 'beginner' '0 to 5km'  31 'F' 72  165 0 3 5  8)
    (New-Scenario 'B08' 'beginner' '0 to 5km BMI30+' 38 'M' 98 175 0 3 5 12)
    (New-Scenario 'B09' 'beginner' '0 to 5km'  55 'F' 68  160 0 3 5 12)
    (New-Scenario 'B10' 'beginner' '0 to 5km'  68 'M' 82  172 0 3 5 12)
    (New-Scenario 'B11' 'beginner' '0 to 5km'  19 'M' 68  185 0 5 5  8)
    (New-Scenario 'B12' 'beginner' '0 to 5km'  46 'F' 90  163 0 2 5 10)
    (New-Scenario 'B13' 'beginner' '0 to 3km'  60 'M' 76  178 0 3 3 10)

    (New-Scenario 'I01' 'intermediate' '3 to 10km'  27 'M' 72  178 3 3 10  8)
    (New-Scenario 'I02' 'intermediate' '3 to 10km'  33 'F' 60  165 3 4 10 12)
    (New-Scenario 'I03' 'intermediate' '5 to 10km'  40 'M' 85  183 5 3 10  8)
    (New-Scenario 'I04' 'intermediate' '5 to 15km'  36 'F' 58  170 5 4 15 10)
    (New-Scenario 'I05' 'intermediate' '5 to 15km'  48 'M' 80  174 5 3 15 16)
    (New-Scenario 'I06' 'intermediate' '4 to 10km'  24 'F' 63  175 4 3 10  6)
    (New-Scenario 'I07' 'intermediate' '5 to 12km'  52 'F' 70  158 5 3 12 10)
    (New-Scenario 'I08' 'intermediate' '3 to 15km'  30 'M' 95  170 3 4 15 12)

    (New-Scenario 'H01' 'advanced' '10 to 21km HM'  32 'M' 70  176 10 4 21.1 10)
    (New-Scenario 'H02' 'advanced' '12 to 25km'     37 'F' 54  162 12 4 25   12)
    (New-Scenario 'H03' 'advanced' '15 to 30km'     44 'M' 78  182 15 4 30   12)
    (New-Scenario 'H04' 'advanced' '20 to 42km Mara' 29 'F' 52  168 20 5 42.2 16)
    (New-Scenario 'H05' 'advanced' '15 to 42km tight' 50 'M' 74  175 15 4 42.2 12)
    (New-Scenario 'H06' 'advanced' '10 to 15km'     26 'other' 68 173 10 3 15 6)
    (New-Scenario 'H07' 'advanced' '12 to 21km HM'  61 'F' 62  160 12 3 21.1 12)

    (New-Scenario 'C01' 'comeback' 'was30/wk now3 to10' 35 'M' 77 179 3 3 10 6)
    (New-Scenario 'C02' 'comeback' 'was30/wk now5 to10' 42 'F' 64 167 5 3 10 4)
    (New-Scenario 'C03' 'comeback' 'post-injury 0 to5'  39 'M' 86 181 0 3 5 10)
    (New-Scenario 'C04' 'comeback' 'post-injury 2 to8'  45 'F' 59 164 2 3 8 8)
    (New-Scenario 'C05' 'comeback' '6wk off 8 to12'     33 'M' 73 177 8 4 12 6)
    (New-Scenario 'C06' 'comeback' 'returning HM 8 to21' 28 'F' 56 171 8 4 21.1 10)
    (New-Scenario 'C07' 'comeback' 'long pause 1 to5'   57 'M' 90 173 1 2 5 8)

    (New-Scenario 'M01' 'maintain' 'already 10 to10' 31 'M' 75 180 10 3 10 6)
    (New-Scenario 'M02' 'maintain' 'already 5 to5'   27 'F' 61 166 5 3 5 4)

    (New-Scenario 'E01' 'edge' 'teen 0 to5'     16 'F' 52 160 0 3 5 8)
    (New-Scenario 'E02' 'edge' 'BMI37 0 to5'    43 'M' 115 175 0 3 5 12)
    (New-Scenario 'E03' 'edge' '2days 5 to15'   49 'F' 67 169 5 2 15 12)
    (New-Scenario 'E04' 'edge' 'ask4w 0 to5'    23 'M' 80 188 0 3 5 4)
    (New-Scenario 'E05' 'edge' 'underweight 0 to5' 21 'F' 45 170 0 3 5 8)
)

$rows = foreach ($s in $scenarios) {
    $p = @{
        ageYears = $s.age; sex = $s.sex; weightKg = $s.weightKg; heightCm = $s.heightCm
        currentLongestRunKm = $s.currentKm; daysPerWeekAvailable = $s.days
    }
    $g = @{ targetDistanceKm = $s.targetKm; horizonWeeks = $s.askWeeks }
    $r = Evaluate-PlanWeeks -P $p -G $g

    [pscustomobject]@{
        id                 = $s.id
        category           = $s.category
        goal               = $s.goal
        age                = $s.age
        sex                = $s.sex
        weight_kg          = $s.weightKg
        height_cm          = $s.heightCm
        bmi                = $r.bmi
        current_longest_km = $s.currentKm
        days_per_week      = $s.days
        target_km          = $s.targetKm
        ask_weeks          = $s.askWeeks
        plan_weeks         = $r.planWeeks
        verdict            = $r.verdict
        path               = $r.path
    }
}

$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$out = Join-Path $OutDir "botty_eval_$stamp.csv"
$simple = Join-Path $OutDir 'botty_eval_simple.csv'
$hybrid = Join-Path $OutDir 'botty_eval_hybrid.csv'
$latest = Join-Path $OutDir 'botty_eval_latest.csv'
$rows | Export-Csv -Path $out -NoTypeInformation -Encoding UTF8
$rows | Export-Csv -Path $hybrid -NoTypeInformation -Encoding UTF8
foreach ($path in @($simple, $latest)) {
    try { $rows | Export-Csv -Path $path -NoTypeInformation -Encoding UTF8 }
    catch { Write-Host "Note: could not overwrite $path (file open?)" }
}

Write-Host ""
Write-Host "Wrote $($rows.Count) scenarios (hybrid model) -> $hybrid"
Write-Host "  also: $out"
Write-Host ""
Write-Host "=== Beginners (was 23 weeks for 0->5) ==="
$rows | Where-Object { $_.category -eq 'beginner' -or $_.id -in @('E01','E02','E04','C03') } |
    Format-Table id, age, sex, goal, ask_weeks, plan_weeks, verdict, path -AutoSize
Write-Host "=== Verdict counts ==="
$rows | Group-Object verdict | ForEach-Object { Write-Host ("  {0}: {1}" -f $_.Name, $_.Count) }
