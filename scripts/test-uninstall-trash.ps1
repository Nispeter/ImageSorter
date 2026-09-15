# T15: desinstalar la app no borra lo que está en la papelera; tras reinstalar se puede restaurar intacto.
. "$PSScriptRoot\lifecycle-common.ps1"
Install-TestApks
$runId = New-RunId

Write-Host '1) Enviar una foto a la papelera con la app'
$s = Invoke-Step 'trashBeforeUninstall' @('-e', 'runId', $runId)
if (-not ($s.key -match '^([\w-]+):(\d+)$') -or -not $s.path -or -not $s.sha) {
    throw "El paso 1 no reportó la foto; no se desinstala nada"
}
$volume = $Matches[1]
$id = $Matches[2]

Write-Host '2) Desinstalar la app'
adb uninstall com.imagesorter | Out-Host
if ("$(adb shell pm path com.imagesorter)".Trim()) { throw 'La app sigue instalada: la desinstalación falló' }

Write-Host '3) Comprobar desde el shell que la foto sigue en la papelera e intacta'
$row = (adb shell content query --uri "content://media/$volume/images/media/$id" --projection is_trashed:_data) -join ' '
if ($row -notmatch 'is_trashed=1') { throw "La foto ya no está en la papelera tras desinstalar: $row" }
$sha = ((adb shell sha256sum $s.path) -split '\s+')[0]
if ($sha -ne $s.sha) { throw "El archivo cambió tras desinstalar ($sha != $($s.sha))" }

Write-Host '4) Reinstalar y restaurar desde la app'
Install-TestApks
Invoke-Step 'restoreAfterReinstall' @('-e', 'runId', $runId, '-e', 'key', $s.key, '-e', 'sha', $s.sha) | Out-Null

Write-Host 'T15 OK'
