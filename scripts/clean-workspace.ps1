param(
    [switch]$IncludeRun,
    [switch]$IncludeGradleCache,
    [switch]$IdeOutputsOnly
)

if ($IdeOutputsOnly -and ($IncludeRun -or $IncludeGradleCache)) {
    throw '-IdeOutputsOnly cannot be combined with -IncludeRun or -IncludeGradleCache.'
}

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$versionMatrix = Join-Path $repositoryRoot 'version-matrix.json'
if (-not (Test-Path -LiteralPath $versionMatrix -PathType Leaf)) {
    throw "Not a QuickCraft multi-version repository: $repositoryRoot"
}

$targets = [System.Collections.Generic.List[string]]::new()
if (-not $IdeOutputsOnly) {
    $targets.Add((Join-Path $repositoryRoot 'build'))
    $targets.Add((Join-Path $repositoryRoot 'logs'))
    $targets.Add((Join-Path $repositoryRoot 'tracks\26x\build'))
    $targets.Add((Join-Path $repositoryRoot 'tracks\26x\logs'))
}

$versionRoots = @(
    Join-Path $repositoryRoot 'versions'
    Join-Path $repositoryRoot 'tracks\26x\versions'
)
foreach ($versionRoot in $versionRoots) {
    if (-not (Test-Path -LiteralPath $versionRoot -PathType Container)) {
        continue
    }
    $generatedNames = if ($IdeOutputsOnly) { @('bin') } else { @('bin', 'build', 'logs', 'run') }
    foreach ($versionDirectory in Get-ChildItem -LiteralPath $versionRoot -Directory) {
        foreach ($generatedName in $generatedNames) {
            $targets.Add((Join-Path $versionDirectory.FullName $generatedName))
        }
    }
}

if ($IncludeRun) {
    $targets.Add((Join-Path $repositoryRoot 'run'))
}
if ($IncludeGradleCache) {
    $targets.Add((Join-Path $repositoryRoot '.gradle'))
    $targets.Add((Join-Path $repositoryRoot 'tracks\26x\.gradle'))
}

$repositoryPrefix = $repositoryRoot.TrimEnd('\') + '\'
foreach ($target in $targets | Select-Object -Unique) {
    $absoluteTarget = [System.IO.Path]::GetFullPath($target)
    if (-not $absoluteTarget.StartsWith($repositoryPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove a path outside the repository: $absoluteTarget"
    }
    if (Test-Path -LiteralPath $absoluteTarget) {
        Remove-Item -LiteralPath $absoluteTarget -Recurse -Force
        Write-Host "removed $absoluteTarget"
    }
}
