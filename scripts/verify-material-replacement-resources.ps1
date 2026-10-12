[CmdletBinding()]
param([string]$BuildRoot)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $BuildRoot) { $BuildRoot = Join-Path $repoRoot 'build' }
$matrix = Get-Content -LiteralPath (Join-Path $repoRoot 'version-matrix.json') -Raw -Encoding UTF8 | ConvertFrom-Json

foreach ($trackProperty in $matrix.tracks.PSObject.Properties) {
    $trackRoot = if ($trackProperty.Name -eq '1.21x') { $repoRoot } else { Join-Path $repoRoot "tracks\$($trackProperty.Name)" }
    $resources = Join-Path $trackRoot 'src\main\resources'
    $coreMixins = Get-Content -LiteralPath (Join-Path $resources 'quickcraft.mixins.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $requiredMixins = @($coreMixins.client | Where-Object {
        $_ -match '^Litematica(Global|Projection|ContainerMemory|MaterialList|WidgetMaterialList|SchematicBlocks|GuiMaterialList)' -or
        $_ -in @('MalilibWidgetContainerInvoker', 'MalilibGuiListAccessor', 'GuiBaseAccessor')
    })
    foreach ($version in $trackProperty.Value.versions) {
        $targetMixins = @($requiredMixins)
        if ($trackProperty.Name -eq '1.21x' -and $version.project -eq '1.21.11') {
            $targetMixins += 'LitematicaGlobalProtoPlacementMixin'
        }
        # 必须检查处理后的资源；独立版本文件会整份覆盖公共注册表和语言文件。
        $targetRoot = Join-Path $BuildRoot "targets\$($version.project)"
        $output = Join-Path $targetRoot 'resources\main'
        $mixins = Get-Content -LiteralPath (Join-Path $output 'quickcraft.mixins.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach ($name in $targetMixins) {
            if ($name -notin $mixins.client) { throw "$($version.project): missing material Mixin registration: $name" }
            $classFile = Join-Path $targetRoot "classes\java\main\com\yiyihehe\quickcraft\mixin\$name.class"
            if (-not (Test-Path -LiteralPath $classFile)) { throw "$($version.project): registered Mixin has no compiled class: $name" }
        }
        foreach ($language in @('zh_cn', 'en_us')) {
            $relativePath = "assets\quickcraft\lang\$language.json"
            $coreLanguage = Get-Content -LiteralPath (Join-Path $resources $relativePath) -Raw -Encoding UTF8 | ConvertFrom-Json -AsHashtable
            $targetLanguage = Get-Content -LiteralPath (Join-Path $output $relativePath) -Raw -Encoding UTF8 | ConvertFrom-Json -AsHashtable
            $requiredKeys = @($coreLanguage.Keys | Where-Object {
                $_ -match '^quickcraft\.litematica\.' -or
                $_ -match '^quickcraft\.config\.projection_tools\.(name|prettyName|comment)\.(showLitematicaMaterialReplacements|allowPersistentMaterialIgnores|globalContainerReplacements|globalProjectionMaterialReplacements|materialListIgnores|projectionMaterialReplacements)$'
            })
            foreach ($key in $requiredKeys) {
                if (-not $targetLanguage.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($targetLanguage[$key])) {
                    throw "$($version.project)/${language}: missing material translation: $key"
                }
            }
        }
        Write-Host "verified $($version.project): $($targetMixins.Count) material Mixins and effective language resources"
    }
}
