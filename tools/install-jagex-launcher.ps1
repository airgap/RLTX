# Routes the Jagex Launcher's "Play" for RuneLite through the RLTX dev client, on Windows.
#
# RuneLite disables developer mode (and with it plugin sideloading) whenever it is started by
# the RuneLite launcher, so the only way to run this plugin with a Jagex account is to have the
# Jagex Launcher start our own client. On Windows the launcher runs RuneLite.exe from the
# RuneLite install folder with the JX_* login variables in the environment. That executable is
# a native stub that reads config.json beside it for the class path, main class and JVM options
# to start on its bundled Java, so this script points config.json at our client and leaves the
# stub and the launcher alone.
#
# To uninstall: copy config.json.stock back over config.json.
param(
	[string]$RuneLiteDir
)
$ErrorActionPreference = 'Stop'

if (-not $RuneLiteDir) {
	$installed = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\RuneLite_is1' -ErrorAction SilentlyContinue
	$RuneLiteDir = if ($installed -and $installed.InstallLocation) { $installed.InstallLocation.TrimEnd('\') } else { Join-Path $env:LOCALAPPDATA 'RuneLite' }
}
$repo = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$classpathFile = Join-Path $repo 'build\rltx-classpath.txt'
if (-not (Test-Path $classpathFile)) { throw "missing $classpathFile; run .\gradlew.bat launchScript first" }
if (-not (Test-Path (Join-Path $RuneLiteDir 'RuneLite.exe'))) { throw "no RuneLite.exe in $RuneLiteDir; install RuneLite from the Jagex Launcher first, or pass -RuneLiteDir" }
$config = Join-Path $RuneLiteDir 'config.json'
$stock = "$config.stock"

$current = Get-Content $config -Raw | ConvertFrom-Json
if ($current.mainClass -eq 'rltx.RltxPluginTest') {
	Write-Output "RLTX already installed in $config; refreshing from $stock"
	$current = Get-Content $stock -Raw | ConvertFrom-Json
} else {
	Copy-Item $config $stock
	Write-Output "kept original as $stock"
}

# The stock heap is sized for the plain client; RLTX builds its scene buffers in Java first.
$vmArgs = @($current.vmArgs | Where-Object { $_ -and $_ -notlike '-Xss*' -and $_ -notlike '-Xmx*' -and $_ -notlike '--add-opens*' -and $_ -notlike '-Drltx.*' })
$vmArgs += '-Xss2m', '-Xmx2g', '--add-opens=java.base/java.lang.reflect=ALL-UNNAMED'
$vmArgs += '-Drltx.console=' + (Join-Path $env:USERPROFILE '.runelite\logs\rltx-console.log')

$current | Add-Member -NotePropertyName classPath -NotePropertyValue @(Get-Content $classpathFile | Where-Object { $_ }) -Force
$current | Add-Member -NotePropertyName mainClass -NotePropertyValue 'rltx.RltxPluginTest' -Force
$current | Add-Member -NotePropertyName vmArgs -NotePropertyValue $vmArgs -Force
# .NET writes UTF-8 without a byte order mark, which the stub's JSON reader would not skip.
[System.IO.File]::WriteAllText($config, ($current | ConvertTo-Json -Depth 5))
Write-Output "RLTX installed in $config"
