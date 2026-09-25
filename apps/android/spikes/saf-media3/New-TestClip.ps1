param([string]$Ffmpeg = 'ffmpeg')

$ErrorActionPreference = 'Stop'
$fixtureDirectory = Join-Path $PSScriptRoot 'build/fixtures'
New-Item -ItemType Directory -Force -Path $fixtureDirectory | Out-Null
$fixture = Join-Path $fixtureDirectory 'saf-synthetic.mp4'
# Only generated color bars and digital silence; no external media input.
& $Ffmpeg -hide_banner -loglevel error -y `
    -f lavfi -i 'testsrc2=size=320x240:rate=24:duration=5' `
    -f lavfi -i 'anullsrc=channel_layout=stereo:sample_rate=44100' `
    -shortest -c:v libx264 -profile:v baseline -level 3.0 -pix_fmt yuv420p `
    -c:a aac -b:a 64k -movflags +faststart $fixture
if ($LASTEXITCODE -ne 0) { throw 'Synthetic clip generation failed' }
[pscustomobject]@{
    File = $fixture
    Bytes = (Get-Item -LiteralPath $fixture).Length
    SHA256 = (Get-FileHash -LiteralPath $fixture -Algorithm SHA256).Hash.ToLowerInvariant()
}
