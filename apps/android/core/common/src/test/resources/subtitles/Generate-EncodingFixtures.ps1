# Mechanical encoding/newline variants of the synthetic bilingual fixture. No external input.
$ErrorActionPreference = 'Stop'
$source = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'bilingual.srt')) -replace "`r`n", "`n"
$utf8 = [System.Text.UTF8Encoding]::new($false, $true)
[System.IO.File]::WriteAllText((Join-Path $PSScriptRoot 'bom.srt'), $source, [System.Text.UTF8Encoding]::new($true, $true))
[System.IO.File]::WriteAllText((Join-Path $PSScriptRoot 'crlf.srt'), ($source -replace "`n", "`r`n"), $utf8)
[System.IO.File]::WriteAllText((Join-Path $PSScriptRoot 'utf16le.srt'), $source, [System.Text.UnicodeEncoding]::new($false, $true, $true))
[System.IO.File]::WriteAllText((Join-Path $PSScriptRoot 'utf16be.srt'), $source, [System.Text.UnicodeEncoding]::new($true, $true, $true))
[System.IO.File]::WriteAllText((Join-Path $PSScriptRoot 'gbk.srt'), $source, [System.Text.Encoding]::GetEncoding(936, [System.Text.EncoderFallback]::ExceptionFallback, [System.Text.DecoderFallback]::ExceptionFallback))
$invalid = $utf8.GetBytes($source) + [byte[]](0xFF, 0x81)
[System.IO.File]::WriteAllBytes((Join-Path $PSScriptRoot 'invalid-encoding.srt'), $invalid)
