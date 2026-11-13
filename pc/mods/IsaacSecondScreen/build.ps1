param(
    [ValidateSet('Debug', 'Release')]
    [string]$Configuration = 'Release',
    [switch]$SkipLauncherPayloadUpdate
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourceRoot = Join-Path $projectRoot 'src'
$assetRoot = Join-Path $projectRoot 'assets'
$modRoot = Join-Path $projectRoot 'mod'
$buildRoot = Join-Path $projectRoot ('build\' + $Configuration.ToLowerInvariant())
$testRoot = Join-Path $projectRoot 'build\tests'
$packageRoot = Join-Path $projectRoot 'package'
$compiler = 'C:\msys64\mingw32\bin\i686-w64-mingw32-g++.exe'
$objdump = 'C:\msys64\mingw32\bin\objdump.exe'

if (-not (Test-Path -LiteralPath $compiler -PathType Leaf)) {
    throw "32-bit MinGW compiler not found: $compiler"
}
$env:PATH = (Split-Path -Parent $compiler) + ';' + $env:PATH
New-Item -ItemType Directory -Force -Path $buildRoot, $testRoot, $packageRoot | Out-Null

$common = @(
    '-std=c++17', '-m32', '-Wall', '-Wextra', '-Wpedantic', '-Werror',
    '-DWIN32_LEAN_AND_MEAN', '-DNOMINMAX', '-DUNICODE', '-D_UNICODE',
    '-I', $sourceRoot, '-static', '-static-libgcc', '-static-libstdc++'
)
$optimization = if ($Configuration -eq 'Release') {
    # Keep the normal symbol table in the shipped proxy. Besides preserving
    # actionable Wine crash traces, avoiding an opaque stripped/static PE
    # prevents endpoint scanners from misclassifying this transparent winmm
    # forwarder as a packed binary.
    @('-O2', '-DNDEBUG')
} else {
    @('-O0', '-g3')
}
$testOptimization = if ($Configuration -eq 'Release') { @('-O2') } else { $optimization }

$protocolTest = Join-Path $testRoot 'ProtocolTests.exe'
& $compiler @($common + $testOptimization + @(
    (Join-Path $projectRoot 'tests\ProtocolTests.cpp'),
    (Join-Path $sourceRoot 'Protocol.cpp'),
    '-o', $protocolTest
))
if ($LASTEXITCODE -ne 0) { throw 'ProtocolTests compilation failed' }
& $protocolTest
if ($LASTEXITCODE -ne 0) { throw 'ProtocolTests failed' }

$inputTest = Join-Path $testRoot 'PanelInputTests.exe'
& $compiler @($common + $testOptimization + @(
    (Join-Path $projectRoot 'tests\PanelInputTests.cpp'),
    (Join-Path $sourceRoot 'PanelInput.cpp'),
    '-o', $inputTest
))
if ($LASTEXITCODE -ne 0) { throw 'PanelInputTests compilation failed' }
& $inputTest
if ($LASTEXITCODE -ne 0) { throw 'PanelInputTests failed' }

$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\..\..'))
$researchLua = Join-Path $workspaceRoot '.research\isaac\device-original\Lua5.3.3r.dll'
if (Test-Path -LiteralPath $researchLua -PathType Leaf) {
    $luaSyntaxTest = Join-Path $testRoot 'LuaSyntaxTests.exe'
    & $compiler @($common + $testOptimization + @(
        (Join-Path $projectRoot 'tests\LuaSyntaxTests.cpp'), '-o', $luaSyntaxTest
    ))
    if ($LASTEXITCODE -ne 0) { throw 'LuaSyntaxTests compilation failed' }
    & $luaSyntaxTest $researchLua (Join-Path $modRoot 'main.lua')
    if ($LASTEXITCODE -ne 0) { throw 'LuaSyntaxTests failed' }
}

$sources = @(
    (Join-Path $sourceRoot 'Proxy.cpp'),
    (Join-Path $sourceRoot 'LuaBridge.cpp'),
    (Join-Path $sourceRoot 'NativeState.cpp'),
    (Join-Path $sourceRoot 'NativeMap.cpp'),
    (Join-Path $sourceRoot 'Mod.cpp'),
    (Join-Path $sourceRoot 'BuildIdentity.cpp'),
    (Join-Path $sourceRoot 'Protocol.cpp'),
    (Join-Path $sourceRoot 'PanelInput.cpp'),
    (Join-Path $sourceRoot 'AssetCatalog.cpp'),
    (Join-Path $sourceRoot 'AssetCache.cpp'),
    (Join-Path $sourceRoot 'BitmapFont.cpp'),
    (Join-Path $sourceRoot 'Panel.cpp'),
    (Join-Path $sourceRoot 'Log.cpp')
)
$output = Join-Path $buildRoot 'winmm.dll'
& $compiler @($common + $optimization + @(
    '-shared', '-Wl,--kill-at', '-Wl,--no-insert-timestamp',
    '-Wl,--dynamicbase', '-Wl,--nxcompat', '-o', $output
) + $sources + @(
    (Join-Path $sourceRoot 'winmm.def'),
    '-lws2_32', '-lgdi32', '-lgdiplus', '-luser32', '-lbcrypt'
))
if ($LASTEXITCODE -ne 0) { throw 'Isaac winmm proxy compilation failed' }

$header = & $objdump -f $output
if ($LASTEXITCODE -ne 0 -or ($header -join "`n") -notmatch 'pei-i386') {
    throw 'Output is not a 32-bit PE DLL.'
}
$pe = & $objdump -p $output
foreach ($name in @(
    'timeBeginPeriod',
    'timeEndPeriod',
    'timeGetDevCaps',
    'luaopen_isaac_second_screen'
)) {
    if (($pe -join "`n") -notmatch [regex]::Escape($name)) {
        throw "The winmm proxy is missing export $name"
    }
}
foreach ($runtime in @('libgcc_s', 'libstdc++', 'libwinpthread')) {
    if (($pe -join "`n") -match [regex]::Escape($runtime)) {
        throw "Unexpected dynamic MinGW dependency: $runtime"
    }
}

$proxyProbe = Join-Path $testRoot 'ProxyRuntimeProbe.exe'
& $compiler @($common + $testOptimization + @(
    (Join-Path $projectRoot 'tests\ProxyRuntimeProbe.cpp'),
    '-o', $proxyProbe
))
if ($LASTEXITCODE -ne 0) { throw 'ProxyRuntimeProbe compilation failed' }
& $proxyProbe $output
if ($LASTEXITCODE -ne 0) { throw 'ProxyRuntimeProbe failed' }

if (Test-Path -LiteralPath $researchLua -PathType Leaf) {
    $luaBridgeTest = Join-Path $testRoot 'LuaBridgeTests.exe'
    & $compiler @($common + $testOptimization + @(
        (Join-Path $projectRoot 'tests\LuaBridgeTests.cpp'),
        '-o', $luaBridgeTest
    ))
    if ($LASTEXITCODE -ne 0) { throw 'LuaBridgeTests compilation failed' }
    & $luaBridgeTest $researchLua $output
    if ($LASTEXITCODE -ne 0) { throw 'LuaBridgeTests failed' }
}

$researchExe = Join-Path $workspaceRoot '.research\isaac\device-original\isaac-ng.exe'
if (Test-Path -LiteralPath $researchExe -PathType Leaf) {
    $item = Get-Item -LiteralPath $researchExe
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $researchExe).Hash.ToLowerInvariant()
    $steamBuild = $item.Length -eq 9362944L -and
        $hash -eq 'e5faf6f513c6de9cc9ff1c335ef9c541e2b33fc623aa3b8ffbd2e6a6c0027bb0'
    $drmFreeBuild = $item.Length -eq 9176064L -and
        $hash -eq 'b4743967f3d31050f7b4222e42ac6beae6a0b7850b1f1adaac07ef7b94d01ec8'
    if (-not ($steamBuild -or $drmFreeBuild)) {
        throw 'The local exact-build executable evidence no longer matches J460.'
    }
    $identityTest = Join-Path $testRoot 'BuildIdentityTests.exe'
    & $compiler @($common + $testOptimization + @(
        (Join-Path $projectRoot 'tests\BuildIdentityTests.cpp'),
        (Join-Path $sourceRoot 'BuildIdentity.cpp'),
        '-o', $identityTest, '-lbcrypt'
    ))
    if ($LASTEXITCODE -ne 0) { throw 'BuildIdentityTests compilation failed' }
    & $identityTest $researchExe
    if ($LASTEXITCODE -ne 0) { throw 'BuildIdentityTests failed' }
}

$catalog = Join-Path $assetRoot 'catalog.tsv'
if (-not (Test-Path -LiteralPath $catalog -PathType Leaf) -or
    (Get-Content -LiteralPath $catalog -First 1) -notmatch '^V\t1\t') {
    throw 'The exact-build native asset catalog is missing or invalid.'
}

function Assert-SafeGeneratedRoot([string]$Path, [string]$ExpectedParent) {
    $resolved = [IO.Path]::GetFullPath($Path)
    $parent = [IO.Path]::GetFullPath($ExpectedParent).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($parent, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to replace generated files outside $ExpectedParent"
    }
}

# The proxy belongs beside isaac-ng.exe. Assets remain under resources/scripts
# so the payload does not pollute the game root with more than one bootstrap.
$null = Assert-SafeGeneratedRoot $packageRoot $projectRoot
if (Test-Path -LiteralPath $packageRoot) {
    Remove-Item -LiteralPath $packageRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $packageRoot | Out-Null
$packageAssets = Join-Path $packageRoot 'isaac_second_screen_assets'
Assert-SafeGeneratedRoot $packageAssets $packageRoot
if (Test-Path -LiteralPath $packageAssets) {
    Remove-Item -LiteralPath $packageAssets -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $packageAssets | Out-Null
Copy-Item -LiteralPath (Join-Path $assetRoot 'catalog.tsv') `
    -Destination (Join-Path $packageAssets 'catalog.tsv') -Force
Copy-Item -LiteralPath (Join-Path $assetRoot 'game') `
    -Destination (Join-Path $packageAssets 'game') -Recurse -Force
Copy-Item -LiteralPath (Join-Path $assetRoot 'custom') `
    -Destination (Join-Path $packageAssets 'custom') -Recurse -Force
Copy-Item -LiteralPath $output -Destination (Join-Path $packageRoot 'winmm.dll') -Force
Copy-Item -LiteralPath (Join-Path $modRoot 'main.lua') -Destination (Join-Path $packageRoot 'main.lua') -Force
Copy-Item -LiteralPath (Join-Path $modRoot 'metadata.xml') -Destination (Join-Path $packageRoot 'metadata.xml') -Force

if (-not $SkipLauncherPayloadUpdate) {
    $launcherPayload = Join-Path $workspaceRoot 'launcher\src\main\assets\mods\isaac-repentance\payload'
    $launcherModRoot = Split-Path -Parent $launcherPayload
    Assert-SafeGeneratedRoot $launcherPayload $launcherModRoot
    if (Test-Path -LiteralPath $launcherPayload) {
        Remove-Item -LiteralPath $launcherPayload -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path `
        (Join-Path $launcherPayload 'native'), `
        (Join-Path $launcherPayload 'mod'), `
        (Join-Path $launcherPayload 'assets') | Out-Null
    Copy-Item -LiteralPath $output `
        -Destination (Join-Path $launcherPayload 'native\winmm.dll') -Force
    Copy-Item -LiteralPath (Join-Path $modRoot 'main.lua') `
        -Destination (Join-Path $launcherPayload 'mod\main.lua') -Force
    Copy-Item -LiteralPath (Join-Path $modRoot 'metadata.xml') `
        -Destination (Join-Path $launcherPayload 'mod\metadata.xml') -Force
    Copy-Item -LiteralPath (Join-Path $assetRoot 'catalog.tsv') `
        -Destination (Join-Path $launcherPayload 'assets\catalog.tsv') -Force
    Copy-Item -LiteralPath (Join-Path $assetRoot 'game') `
        -Destination (Join-Path $launcherPayload 'assets\game') -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $assetRoot 'custom') `
        -Destination (Join-Path $launcherPayload 'assets\custom') -Recurse -Force

    $files = [Collections.Generic.List[object]]::new()
    $declared = @(
        @('native/winmm.dll', 'winmm.dll'),
        @('mod/main.lua', 'mods/DualScreenIsaac/main.lua'),
        @('mod/metadata.xml', 'mods/DualScreenIsaac/metadata.xml')
    )
    foreach ($entry in $declared) {
        $source = [string]$entry[0]
        $file = Join-Path $launcherPayload ($source.Replace('/', '\'))
        $files.Add([ordered]@{
            source = $source
            target = [string]$entry[1]
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLowerInvariant()
        })
    }
    Get-ChildItem -LiteralPath (Join-Path $launcherPayload 'assets') -Recurse -File |
        Sort-Object FullName | ForEach-Object {
            $relative = $_.FullName.Substring($launcherPayload.Length + 1).Replace('\', '/')
            $assetRelative = $relative.Substring('assets/'.Length)
            $files.Add([ordered]@{
                source = $relative
                target = 'resources/scripts/isaac_second_screen_assets/' + $assetRelative
                sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
            })
        }
    $manifest = [ordered]@{
        version = 'isaac-native-j460-2026.11.13.03'
        files = $files
        remove = @('resources/scripts/isaac_second_screen.dll')
    }
    $manifestPath = Join-Path $launcherModRoot 'manifest.json'
    $json = $manifest | ConvertTo-Json -Depth 6
    [IO.File]::WriteAllText($manifestPath, $json + "`n", [Text.UTF8Encoding]::new($false))
}

$outputHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $output).Hash.ToLowerInvariant()
Write-Host "Built $output"
Write-Host "architecture=i386 bytes=$((Get-Item -LiteralPath $output).Length) sha256=$outputHash"


