$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Out = Join-Path $Root 'deliverables'
New-Item -ItemType Directory -Force $Out | Out-Null
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker Desktop with devkitpro/devkitarm is required.' }
docker run --rm -v "$Root/ctr:/work" -w /work devkitpro/devkitarm:latest make
if (-not (Get-Command makerom -ErrorAction SilentlyContinue)) { throw 'Install Project_CTR makerom and add it to PATH.' }
makerom -f cia -o (Join-Path $Out 'SaveBridgeMulti-v0.3.cia') -elf (Join-Path $Root 'ctr/SaveBridgeMulti.elf') -rsf (Join-Path $Root 'ctr/app.rsf') -icon (Join-Path $Root 'ctr/SaveBridgeMulti.smdh') -ver 3
Write-Host 'CIA complete. Build the Android APK with Android SDK build-tools 34+; see BUILD.md.'
Get-ChildItem $Out -File | Get-FileHash -Algorithm SHA256 | Format-Table
