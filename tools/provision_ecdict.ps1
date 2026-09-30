param(
    [Parameter(Mandatory = $true)][string]$DatabasePath,
    [string]$Serial = "52072092",
    [string]$PackageName = "com.lazyeng.family"
)

$ErrorActionPreference = "Stop"
$database = (Resolve-Path -LiteralPath $DatabasePath).Path
$uidLine = & adb -s $Serial shell cmd package list packages -U $PackageName | Select-String "package:$PackageName\s+uid:(\d+)"
if (-not $uidLine -or $uidLine.Matches.Count -eq 0) { throw "Could not resolve installed package UID for $PackageName" }
$uid = $uidLine.Matches[0].Groups[1].Value
$remote = "/data/local/tmp/ecdictionary.sqlite"
& adb -s $Serial push $database $remote
if ($LASTEXITCODE -ne 0) { throw "adb push failed" }
$command = "mkdir -p /data/user/0/$PackageName/files/dictionary && cp $remote /data/user/0/$PackageName/files/dictionary/ecdictionary.sqlite && chown $uid`:$uid /data/user/0/$PackageName/files/dictionary/ecdictionary.sqlite && chmod 600 /data/user/0/$PackageName/files/dictionary/ecdictionary.sqlite"
& adb -s $Serial shell su -c $command
if ($LASTEXITCODE -ne 0) { throw "Root copy failed; this developer fallback requires a rooted test device" }
& adb -s $Serial shell su -c "rm -f $remote"
Write-Output "Provisioned $database to $PackageName private dictionary directory (UID $uid)."
