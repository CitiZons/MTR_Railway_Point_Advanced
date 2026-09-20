param(
    [string]$MinecraftRoot = 'D:/Minecraft/CitiZons/10.alpha/C10.alpha.4.client/.minecraft',
    [string]$WorldName = 'MTR_test',
    [string]$Java = 'C:/Users/Lenovo/.gradle/jdks/eclipse_adoptium-17-amd64-windows/jdk-17.0.20+8/bin/java.exe'
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$game = Join-Path $project 'build/runtime-actual-012'
$source = Join-Path $MinecraftRoot "saves/$WorldName"
$copy = Join-Path $game 'saves/point-actual-copy'
if (Test-Path -LiteralPath $copy) { Remove-Item -LiteralPath $copy -Recurse -Force }
$null = New-Item -ItemType Directory -Force "$game/mods", "$copy/data", "$copy/region", "$copy/entities", "$copy/playerdata"
Copy-Item -LiteralPath "$source/level.dat" -Destination $copy
if (Test-Path -LiteralPath "$source/level.dat_old") { Copy-Item -LiteralPath "$source/level.dat_old" -Destination $copy }
Copy-Item -LiteralPath "$source/mtr" -Destination $copy -Recurse
Get-ChildItem -LiteralPath "$source/data" -File -Filter '*.dat' | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination "$copy/data" }
foreach ($name in @('r.0.-1.mca','r.0.0.mca')) {
    if (Test-Path -LiteralPath "$source/region/$name") { Copy-Item -LiteralPath "$source/region/$name" -Destination "$copy/region" }
    if (Test-Path -LiteralPath "$source/entities/$name") { Copy-Item -LiteralPath "$source/entities/$name" -Destination "$copy/entities" }
}
Get-ChildItem -LiteralPath "$source/playerdata" -File | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination "$copy/playerdata" }
Copy-Item -LiteralPath "$project/../MTR_Optional_Rail_addon/build/libs/mtr_optional_rail_addon-0.1.0.jar" -Destination "$game/mods" -Force
Copy-Item -LiteralPath "$project/build/libs/point-runtime-probe-0.1.2.jar" -Destination "$game/mods" -Force
Copy-Item -LiteralPath "$project/../MTR_BRsignal_addon/libs/MTR-forge-4.0.3+1.20.1.jar" -Destination "$game/mods" -Force
Copy-Item -LiteralPath "$project/build/libs/mtr_railway_point_advanced-0.1.2.jar" -Destination "$game/mods" -Force
$versionName = '1.20.1-Forge'
$versionDir = "$MinecraftRoot/versions/$versionName"
$version = Get-Content -LiteralPath "$versionDir/$versionName.json" -Raw | ConvertFrom-Json
$libraryDir = "$MinecraftRoot/libraries"
$classpath = [Collections.Generic.List[string]]::new()
foreach ($library in $version.libraries) {
    $allow = !$library.rules
    foreach ($rule in $library.rules) { $matchesOS = (!$rule.os -or (!$rule.os.name -or $rule.os.name -eq 'windows') -and (!$rule.os.arch -or $rule.os.arch -eq 'x86_64')); if ($matchesOS) { $allow = $rule.action -eq 'allow' } }
    if ($allow -and $library.downloads.artifact.path) { $classpath.Add("$libraryDir/$($library.downloads.artifact.path)") }
}
$classpath.Add("$versionDir/$versionName.jar")
$vars = @{natives_directory="$versionDir/natives-windows-x86_64";launcher_name='PointActualProbe';launcher_version='1';classpath=($classpath -join ';');library_directory=$libraryDir;classpath_separator=';';version_name=$versionName;primary_jar_name="$versionName.jar"}
$argsList = [Collections.Generic.List[string]]::new();$argsList.Add('-Xmx4G');$argsList.Add('-DpointProbeExisting=true');$argsList.Add('-DpointProbePartColors=true')
foreach ($arg0 in $version.arguments.jvm) { if ($arg0 -isnot [string]) { continue };$arg=$arg0;foreach ($key in $vars.Keys) {$arg=$arg.Replace(('${'+$key+'}'),[string]$vars[$key])};$argsList.Add($arg) }
$argsList.Add($version.mainClass)
@('--username','RailProbe','--version',$versionName,'--gameDir',$game,'--assetsDir',"$MinecraftRoot/assets",'--assetIndex',[string]$version.assetIndex.id,'--uuid','00000000000000000000000000000001','--accessToken','0','--userType','legacy','--versionType','release','--width','1100','--height','800','--quickPlaySingleplayer','point-actual-copy','--launchTarget','forgeclient','--fml.forgeVersion','47.4.18','--fml.mcVersion','1.20.1','--fml.forgeGroup','net.minecraftforge','--fml.mcpVersion','20230612.114412') | ForEach-Object {$argsList.Add($_)}
$utf8=[Text.UTF8Encoding]::new($false);[IO.File]::WriteAllLines("$game/java.args",@($argsList|ForEach-Object {'"'+$_.Replace('\','/').Replace('"','\"')+'"'}),$utf8);[IO.File]::WriteAllText("$game/options.txt","lang:zh_cn`nguiScale:2`nrenderDistance:6`npauseOnLostFocus:false`n",$utf8)
$process=Start-Process -FilePath $Java -ArgumentList ('@"'+"$game/java.args"+'"') -WorkingDirectory $game -WindowStyle Hidden -RedirectStandardOutput "$game/stdout.log" -RedirectStandardError "$game/stderr.log" -PassThru
Write-Output "Runtime actual probe PID: $($process.Id)";Write-Output "Logs: $game"
