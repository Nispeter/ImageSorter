# Entorno de build para ESTA sesión de PowerShell (no modifica variables globales).
# Uso: . .\scripts\env.ps1
$env:ANDROID_HOME = 'E:\Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_AVD_HOME = 'E:\Android\avd'
$env:GRADLE_USER_HOME = 'E:\Android\gradle-home'
$env:Path = "$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\emulator;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"
