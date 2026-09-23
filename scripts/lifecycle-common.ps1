# Utilidades para los tests orquestados desde el host (T14, T15). SOLO para el emulador: estos pasos
# crean fotos, las mandan a la papelera, las restauran y desinstalan la app.
. "$PSScriptRoot\env.ps1"
$ProjectRoot = (Resolve-Path "$PSScriptRoot\..").Path
$TestRunner = 'io.github.nispeter.peakselect.test/androidx.test.runner.AndroidJUnitRunner'
$TestClass = 'com.imagesorter.LifecycleSafetyTest'

# Guarda: un único dispositivo destino y que sea un emulador (nunca un teléfono real).
$devices = @(adb devices | Select-String "`tdevice$")
if (-not $env:ANDROID_SERIAL -and $devices.Count -ne 1) {
    throw "Conecta exactamente un emulador o define ANDROID_SERIAL (dispositivos: $($devices.Count))"
}
$qemu = "$(adb shell getprop ro.boot.qemu)".Trim()
if ($qemu -ne '1') { $qemu = "$(adb shell getprop ro.kernel.qemu)".Trim() }
if ($qemu -ne '1') { throw 'El dispositivo no es un emulador: estos tests no se ejecutan en un teléfono real' }

function Install-TestApks {
    & "$ProjectRoot\gradlew.bat" -p "$ProjectRoot" --console=plain -q installDebug installDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw 'No se pudieron instalar los APKs' }
}

# Ejecuta un paso de LifecycleSafetyTest y devuelve lo que reportó (imagesorter.*). Solo lo da por
# bueno si el propio test confirmó que llegó al final (imagesorter.completed); "OK" no basta, porque
# un paso omitido por assumption también termina en OK.
function Invoke-Step([string]$Method, [string[]]$Extra = @()) {
    $out = adb shell am instrument -r -w -e class "$TestClass#$Method" -e lifecycleStep $Method @Extra $TestRunner
    $status = @{}
    foreach ($line in $out) {
        if ($line -match 'INSTRUMENTATION_STATUS: imagesorter\.(\w+)=(.*)$') { $status[$Matches[1]] = $Matches[2].Trim() }
    }
    if ((($out -join "`n") -match 'INSTRUMENTATION_STATUS_CODE: -[1-4]') -or $status.completed -ne $Method) {
        $out | ForEach-Object { Write-Host "    $_" }
        throw "El paso $Method no terminó correctamente"
    }
    Write-Host "    $Method OK"
    return $status
}

function New-RunId { "IST_$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())" }
