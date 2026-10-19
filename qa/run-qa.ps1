<#
.SYNOPSIS
  Runs the Y Walk JVM QA suite and prints a defect summary + the virtual-users report.
.EXAMPLE
  .\qa\run-qa.ps1                 # everything
  .\qa\run-qa.ps1 -Only Classifier  # one class (name fragment)
#>
param([string]$Only = '')

$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
    $filter = if ($Only) { @('--tests', "*$Only*") } else { @() }
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat :app:testDebugUnitTest @filter --continue --console=plain *> $null

    $rows = foreach ($f in Get-ChildItem 'app\build\test-results\testDebugUnitTest' -Filter *.xml) {
        [xml]$x = Get-Content $f.FullName -Raw -Encoding UTF8
        foreach ($tc in $x.testsuite.testcase) {
            [pscustomobject]@{
                Class  = $tc.classname.Split('.')[-1]
                Test   = $tc.name
                Status = if ($tc.failure) { if ($tc.name -like 'defect_*') { 'KNOWN DEFECT' } else { 'FAIL' } } else { 'pass' }
                Detail = if ($tc.failure) {
                    $m = ($tc.failure.message -split "`n")[0] -replace '^java.lang.AssertionError: ', ''
                    if ($m.Length -gt 110) { $m.Substring(0, 110) + '…' } else { $m }
                } else { '' }
            }
        }
    }
    $rows | Where-Object Status -ne 'pass' | Format-Table Class, Test, Status, Detail -AutoSize -Wrap
    $pass = @($rows | Where-Object Status -eq 'pass').Count
    $known = @($rows | Where-Object Status -eq 'KNOWN DEFECT').Count
    $fail = @($rows | Where-Object Status -eq 'FAIL').Count
    Write-Host ("{0} tests: {1} pass, {2} known defects still open, {3} unexpected failures" -f $rows.Count, $pass, $known, $fail)

    $vu = 'app\build\qa-reports\virtual_users.txt'
    if (Test-Path $vu) { Write-Host ''; Get-Content $vu -Encoding UTF8 }
    Write-Host "`nHTML report: $root\app\build\reports\tests\testDebugUnitTest\index.html"
    if ($fail -gt 0) { exit 1 } else { exit 0 }
} finally {
    Pop-Location
}
