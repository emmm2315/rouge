$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$source = Join-Path $projectRoot 'character_assets_v3_atlas_rebuild_20260913/runtime_frames'
$destination = Join-Path $projectRoot 'src/main/resources/com/phantomcorridor/sprites/player_v3'
$manifest = Get-Content -LiteralPath (Join-Path $source 'runtime_manifest.json') -Raw | ConvertFrom-Json
New-Item -ItemType Directory -Force $destination | Out-Null
Copy-Item -LiteralPath (Join-Path $source 'body') -Destination $destination -Recurse -Force
Copy-Item -LiteralPath (Join-Path $source 'runtime_manifest.json') -Destination $destination -Force
$lines = @('# Generated from runtime_manifest.json by tools/Import-CharacterAssets.ps1',
    "width=$($manifest.canvas.width)", "height=$($manifest.canvas.height)",
    "anchorX=$($manifest.anchor.x)", "anchorY=$($manifest.anchor.y)")
foreach ($sequence in $manifest.sequences.PSObject.Properties) {
    $value = $sequence.Value
    $lines += "$($sequence.Name)=$($value.fps),$($value.frames),$($value.loop.ToString().ToLowerInvariant())"
}
[System.IO.File]::WriteAllLines((Join-Path $destination 'animations.properties'), $lines)
