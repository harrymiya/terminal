param(
    [switch]$NoRestore
)

$ErrorActionPreference = 'Stop'
$nativeRoot = Join-Path $PSScriptRoot 'native'
$webRoot = Join-Path $nativeRoot 'web'

$dotnet = (Get-Command dotnet -ErrorAction SilentlyContinue).Source
$localSdk = Join-Path $env:LOCALAPPDATA 'RemoteDevTerminal-dotnet-sdk\dotnet.exe'
if (-not $dotnet -and (Test-Path -LiteralPath $localSdk)) { $dotnet = $localSdk }
if (-not $dotnet) {
    throw '.NET 10 SDK is required to build. Install it with: winget install Microsoft.DotNet.SDK.10'
}
if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    throw 'Node.js and npm are required to build the bundled terminal UI.'
}

Push-Location $webRoot
try {
    if (Test-Path -LiteralPath (Join-Path $webRoot 'package-lock.json')) {
        npm ci --no-audit --no-fund
    } else {
        npm install --no-audit --no-fund
    }
    if ($LASTEXITCODE -ne 0) { throw "npm install failed with exit code $LASTEXITCODE" }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw "npm build failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

$publishDir = Join-Path $nativeRoot 'publish'
if (-not $NoRestore) {
    & $dotnet publish (Join-Path $nativeRoot 'RemoteDevTerminal.csproj') -c Release -o $publishDir
} else {
    & $dotnet publish (Join-Path $nativeRoot 'RemoteDevTerminal.csproj') -c Release --no-restore -o $publishDir
}
if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed with exit code $LASTEXITCODE" }

$www = Join-Path $publishDir 'www'
New-Item -ItemType Directory -Force -Path $www | Out-Null
Copy-Item (Join-Path $nativeRoot 'www\*') $www -Recurse -Force
Write-Host "Build complete: $publishDir"

$distDir = Join-Path $PSScriptRoot 'dist'
$packageRoot = Join-Path $distDir 'RemoteDevTerminal-win-x64'
New-Item -ItemType Directory -Force -Path $distDir | Out-Null
$distPrefix = [IO.Path]::GetFullPath($distDir).TrimEnd('\') + '\'
$packagePath = [IO.Path]::GetFullPath($packageRoot)
if (-not $packagePath.StartsWith($distPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Package output path escaped the windows\dist folder.'
}
if (Test-Path -LiteralPath $packageRoot) { Remove-Item -LiteralPath $packageRoot -Recurse -Force }
$packagePublish = Join-Path $packageRoot 'native\publish'
New-Item -ItemType Directory -Force -Path $packagePublish | Out-Null
Copy-Item (Join-Path $PSScriptRoot 'install.bat') $packageRoot -Force
Copy-Item (Join-Path $PSScriptRoot 'install.ps1') $packageRoot -Force
Copy-Item (Join-Path $PSScriptRoot 'launch.ps1') $packageRoot -Force
Copy-Item (Join-Path $PSScriptRoot 'README.md') $packageRoot -Force
Get-ChildItem -LiteralPath $publishDir -Force | ForEach-Object {
    if ($_.PSIsContainer) {
        Copy-Item -LiteralPath $_.FullName -Destination $packagePublish -Recurse -Force
    } elseif ($_.Extension -notin @('.pdb', '.xml')) {
        Copy-Item -LiteralPath $_.FullName -Destination $packagePublish -Force
    }
}
$archive = Join-Path $distDir 'RemoteDevTerminal-win-x64.zip'
Compress-Archive -Path (Join-Path $packageRoot '*') -DestinationPath $archive -Force
Write-Host "Install package: $archive"
