# T14: las decisiones sin confirmar sobreviven a la muerte del proceso y nada se ejecuta solo.
. "$PSScriptRoot\lifecycle-common.ps1"
Install-TestApks
$runId = New-RunId

Write-Host '1) Registrar decisiones (sin confirmar)'
$s = Invoke-Step 'stageThenDie' @('-e', 'runId', $runId)

Write-Host '2) Matar el proceso de la app'
adb shell am force-stop com.imagesorter

Write-Host '3) Reabrir la app y verificar'
Invoke-Step 'afterRestartQueuePersistsAndNothingExecuted' @('-e', 'runId', $runId, '-e', 'sha0', $s.sha0, '-e', 'sha1', $s.sha1) | Out-Null

Write-Host 'T14 OK'
