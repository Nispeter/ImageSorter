# Utilidades para los tests orquestados desde el host (T14, T15). Requiere el emulador arrancado.
. "$PSScriptRoot\env.ps1"
$ProjectRoot = (Resolve-Path "$PSScriptRoot\..").Path
$TestRunner = 'com.imagesorter.test/androidx.test.runner.AndroidJUnitRunner'
$TestClass = 'com.imagesorter.LifecycleSafetyTest'

function Install-TestApks {
    & "$ProjectRoot\gradlew.bat" -p "$ProjectRoot" --console=plain -q installDebug installDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw 'No se pudieron instalar los APKs' }
}

# Ejecuta un paso de LifecycleSafetyTest y devuelve los valores que reportó (imagesorter.*).
function Invoke-Step([string]$Method, [string[]]$Extra = @()) {
    $out = adb shell am instrument -w -e class "$TestClass#$Method" -e lifecycleStep $Method @Extra $TestRunner
    $out | ForEach-Object { Write-Host "    $_" }
    $text = $out -join "`n"
    if ($text -match 'INSTRUMENTATION_STATUS_CODE: -4') { throw "El paso $Method se omitió (assumption)" }
    if ($text -notmatch 'OK \(1 test\)') { throw "Falló el paso $Method" }
    $status = @{}
    foreach ($line in $out) {
        if ($line -match 'INSTRUMENTATION_STATUS: imagesorter\.(\w+)=(.*)$') { $status[$Matches[1]] = $Matches[2].Trim() }
    }
    return $status
}

function New-RunId { "IST_$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())" }
