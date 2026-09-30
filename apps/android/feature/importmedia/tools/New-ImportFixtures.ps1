param([string]$Ffmpeg = 'ffmpeg', [string]$MissingSourceFixture = "lazyeng-e2-t2-missing-$([guid]::NewGuid().ToString('N')).mp4")
$ErrorActionPreference = 'Stop'
$directory = Join-Path $PSScriptRoot '../build/device-fixtures'
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$mp4 = Join-Path $directory 'lazyeng-import-fixture.mp4'
& $Ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=320x240:rate=24:duration=5' -f lavfi -i 'anullsrc=channel_layout=stereo:sample_rate=44100' -shortest -c:v libx264 -profile:v baseline -pix_fmt yuv420p -c:a aac -movflags +faststart $mp4
if ($LASTEXITCODE -ne 0) { throw 'MP4 fixture generation failed' }
& $Ffmpeg -hide_banner -loglevel error -y -i $mp4 -c copy (Join-Path $directory 'lazyeng-import-fixture.mkv')
if ($LASTEXITCODE -ne 0) { throw 'MKV fixture generation failed' }
[IO.File]::WriteAllText((Join-Path $directory 'lazyeng-import-fixture.srt'), "1`n00:00:00,100 --> 00:00:02,000`nAn original test pattern.`n`n2`n00:00:02,100 --> 00:00:04,000`nOnly synthetic media is used.`n")
[IO.File]::WriteAllText((Join-Path $directory 'lazyeng-import-broken.mp4'), 'This is deliberately not a video container.')
[IO.File]::WriteAllBytes((Join-Path $directory 'lazyeng-import-empty.mp4'), [byte[]]@())
$missingSourcePath = Join-Path $directory $MissingSourceFixture
if (Test-Path -LiteralPath $missingSourcePath) { throw 'Refusing to overwrite the removal-test fixture.' }
Copy-Item -LiteralPath $mp4 -Destination $missingSourcePath
Get-ChildItem -LiteralPath $directory | Select-Object Name, Length
Write-Output "MissingSourceFixture=$MissingSourceFixture"
