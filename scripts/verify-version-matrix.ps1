[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$matrixPath = Join-Path $repoRoot 'version-matrix.json'
$matrix = Get-Content -LiteralPath $matrixPath -Raw -Encoding UTF8 | ConvertFrom-Json
$failures = [System.Collections.Generic.List[string]]::new()

function Read-GradleProperties {
    param([Parameter(Mandatory)][string]$Path)

    $properties = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        if ($separator -lt 1) {
            continue
        }
        $properties[$trimmed.Substring(0, $separator).Trim()] = $trimmed.Substring($separator + 1).Trim()
    }
    return $properties
}

function Assert-Equal {
    param(
        [Parameter(Mandatory)][string]$Label,
        [AllowNull()][object]$Expected,
        [AllowNull()][object]$Actual
    )

    if ([string]$Expected -cne [string]$Actual) {
        $failures.Add("${Label}: expected '$Expected', actual '$Actual'")
    }
}

foreach ($trackProperty in $matrix.tracks.PSObject.Properties) {
    $trackName = $trackProperty.Name
    $track = $trackProperty.Value
    $trackRoot = if ($trackName -eq '1.21x') {
        $repoRoot
    } else {
        Join-Path $repoRoot "tracks\$trackName"
    }

    $mainProjectPath = Join-Path $trackRoot 'versions\mainProject'
    Assert-Equal "$trackName core project" $track.core ((Get-Content -LiteralPath $mainProjectPath -Raw -Encoding UTF8).Trim())

    $declaredProjects = @($track.versions | ForEach-Object { $_.project })
    $actualProjects = @(Get-ChildItem -LiteralPath (Join-Path $trackRoot 'versions') -Directory | ForEach-Object { $_.Name })
    Assert-Equal "$trackName project directories" (($declaredProjects | Sort-Object) -join ',') (($actualProjects | Sort-Object) -join ',')

    foreach ($version in $track.versions) {
        $versionRoot = Join-Path $trackRoot "versions\$($version.project)"
        $properties = Read-GradleProperties (Join-Path $versionRoot 'gradle.properties')
        Assert-Equal "$trackName/$($version.project) minecraft_version" $version.minecraft $properties.minecraft_version
        Assert-Equal "$trackName/$($version.project) supported_minecraft_versions" $version.supportedMinecraft $properties.supported_minecraft_versions
        Assert-Equal "$trackName/$($version.project) loader_version" $version.loader.compile $properties.loader_version
        Assert-Equal "$trackName/$($version.project) malilib_version" $version.malilib.compile $properties.malilib_version
        Assert-Equal "$trackName/$($version.project) litematica_version" $version.litematica.compile $properties.litematica_version

        $metadataPath = if ($version.project -eq $track.core) {
            Join-Path $trackRoot 'src\main\resources\fabric.mod.json'
        } else {
            Join-Path $versionRoot 'src\main\resources\fabric.mod.json'
        }
        $metadata = Get-Content -LiteralPath $metadataPath -Raw -Encoding UTF8 | ConvertFrom-Json
        Assert-Equal "$trackName/$($version.project) fabric.mod minecraft" $version.minecraftDeclared $metadata.depends.minecraft
        Assert-Equal "$trackName/$($version.project) fabric.mod java" ">=$($track.java)" $metadata.depends.java
        Assert-Equal "$trackName/$($version.project) fabric.mod fabricloader" $version.loader.declared $metadata.depends.fabricloader
        Assert-Equal "$trackName/$($version.project) fabric.mod malilib" $version.malilib.declared $metadata.depends.malilib
        Assert-Equal "$trackName/$($version.project) fabric.mod litematica" $version.litematica.declared $metadata.suggests.litematica

        Write-Host "verified $trackName/$($version.project)"
    }
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Host 'version matrix is consistent'
