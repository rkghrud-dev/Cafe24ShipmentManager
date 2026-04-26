param(
    [string]$Runtime = 'win-x64',
    [string]$AuthProjectPath = "$env:USERPROFILE\Desktop\Cafe24Auth\Cafe24Auth.csproj",
    [switch]$SelfContainedApps,
    [string]$PackageVersion = ''
)

$ErrorActionPreference = 'Stop'

$ProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$MainProjectPath = Join-Path $ProjectRoot 'Cafe24ShipmentManager.csproj'
$BootstrapperProjectPath = Join-Path $PSScriptRoot 'SetupBootstrapper\SetupBootstrapper.csproj'
$BootstrapperPayloadDir = Join-Path $PSScriptRoot 'SetupBootstrapper\Payload'
$DistRoot = Join-Path $ProjectRoot 'dist'
$ScratchRoot = Join-Path $env:TEMP 'Cafe24ShipmentManagerInstallerBuild'
$PublishRoot = Join-Path $ScratchRoot 'publish'
$MainPublish = Join-Path $PublishRoot 'Cafe24ShipmentManager'
$AuthPublish = Join-Path $PublishRoot 'Cafe24Auth'
$SetupPublish = Join-Path $PublishRoot 'SetupBootstrapper'
$SetupOutputPath = Join-Path $DistRoot 'Cafe24ShipmentManager-Setup.exe'
if ([string]::IsNullOrWhiteSpace($PackageVersion)) {
    $PackageVersion = Get-Date -Format 'yyyy.MM.dd.HHmm'
}

function Assert-SafeGeneratedPath([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $temp = [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\')
    $project = [IO.Path]::GetFullPath($ProjectRoot).TrimEnd('\')

    if (-not $full.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase) -and
        -not $full.StartsWith($project, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to delete path outside temp/project: $full"
    }
}

function Reset-Directory([string]$Path) {
    Assert-SafeGeneratedPath $Path
    if (Test-Path -LiteralPath $Path) {
        Remove-Item -LiteralPath $Path -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $Path | Out-Null
}

function Copy-DirectoryContents([string]$Source, [string]$Destination) {
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    Get-ChildItem -LiteralPath $Source -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $Destination $_.Name) -Recurse -Force
    }
}

function Invoke-DotnetPublish([string]$ProjectPath, [string]$OutputPath, [bool]$SelfContained, [bool]$ApplyPackageVersion = $false) {
    $args = @('publish', $ProjectPath, '-c', 'Release', '-o', $OutputPath, '/p:UseAppHost=true')
    if ($ApplyPackageVersion) {
        $args += @(
            "/p:InformationalVersion=$PackageVersion",
            "/p:AssemblyVersion=1.0.0.0",
            "/p:FileVersion=1.0.0.0",
            '/p:IncludeSourceRevisionInInformationalVersion=false'
        )
    }
    if ($SelfContained) {
        $args += @('-r', $Runtime, '--self-contained', 'true')
    }
    else {
        $args += @('--self-contained', 'false')
    }

    & dotnet @args
    if ($LASTEXITCODE -ne 0) {
        throw "dotnet publish failed: $ProjectPath"
    }
}

function Publish-AuthTool {
    if (Test-Path -LiteralPath $AuthProjectPath) {
        try {
            Invoke-DotnetPublish -ProjectPath $AuthProjectPath -OutputPath $AuthPublish -SelfContained:$SelfContainedApps
            return
        }
        catch {
            Write-Warning $_.Exception.Message
            Write-Warning 'Cafe24Auth publish failed; trying existing publish/bin folders.'
        }
    }

    $authRoot = Split-Path -Parent $AuthProjectPath
    $fallbacks = @(
        (Join-Path $authRoot 'publish5'),
        (Join-Path $authRoot 'publish4'),
        (Join-Path $authRoot 'bin\Release\net6.0-windows'),
        (Join-Path $authRoot 'bin\Debug\net6.0-windows')
    )

    foreach ($fallback in $fallbacks) {
        $exe = Join-Path $fallback 'Cafe24Auth.exe'
        if (Test-Path -LiteralPath $exe) {
            Write-Host "Using existing Cafe24Auth output: $fallback"
            Copy-DirectoryContents -Source $fallback -Destination $AuthPublish
            return
        }
    }

    throw "Cafe24Auth.exe를 찾을 수 없습니다. AuthProjectPath를 확인하세요: $AuthProjectPath"
}

function Add-FirstInstallSeedFiles {
    $seedFiles = @('app.db', 'appsettings.example.json')
    foreach ($name in $seedFiles) {
        $source = Join-Path $ProjectRoot $name
        if (Test-Path -LiteralPath $source) {
            Copy-Item -LiteralPath $source -Destination (Join-Path $MainPublish $name) -Force
        }
    }

    $installConfig = Join-Path $PSScriptRoot 'appsettings.install.json'
    if (Test-Path -LiteralPath $installConfig) {
        Copy-Item -LiteralPath $installConfig -Destination (Join-Path $MainPublish 'appsettings.json') -Force
    }
}

function New-Zip([string]$SourceDir, [string]$DestinationZip) {
    if (Test-Path -LiteralPath $DestinationZip) {
        Remove-Item -LiteralPath $DestinationZip -Force
    }
    Compress-Archive -Path (Join-Path $SourceDir '*') -DestinationPath $DestinationZip -Force
}

function Publish-SetupBootstrapper([bool]$SelfContained) {
    Reset-Directory $SetupPublish
    $args = @(
        'publish', $BootstrapperProjectPath,
        '-c', 'Release',
        '-r', $Runtime,
        '-o', $SetupPublish,
        '/p:PublishSingleFile=true',
        '/p:IncludeNativeLibrariesForSelfExtract=true',
        "/p:Version=$PackageVersion",
        "/p:InformationalVersion=$PackageVersion",
        "/p:AssemblyVersion=1.0.0.0",
        "/p:FileVersion=1.0.0.0",
        '/p:IncludeSourceRevisionInInformationalVersion=false'
    )

    if ($SelfContained) {
        $args += @('--self-contained', 'true')
    }
    else {
        $args += @('--self-contained', 'false')
    }

    & dotnet @args 2>&1 | Out-Host
    $code = $LASTEXITCODE
    return [int]$code
}

Write-Host '== Cafe24 Shipment Manager installer build =='
Write-Host "Project: $ProjectRoot"
Write-Host "Version: $PackageVersion"

Reset-Directory $ScratchRoot
Reset-Directory $BootstrapperPayloadDir
New-Item -ItemType Directory -Force -Path $MainPublish, $AuthPublish, $DistRoot | Out-Null

Write-Host 'Publishing main app...'
Invoke-DotnetPublish -ProjectPath $MainProjectPath -OutputPath $MainPublish -SelfContained:$SelfContainedApps -ApplyPackageVersion:$true
Add-FirstInstallSeedFiles

Write-Host 'Publishing Cafe24Auth sidecar...'
Publish-AuthTool

Write-Host 'Creating payload archives...'
New-Zip -SourceDir $MainPublish -DestinationZip (Join-Path $BootstrapperPayloadDir 'Cafe24ShipmentManager.zip')
New-Zip -SourceDir $AuthPublish -DestinationZip (Join-Path $BootstrapperPayloadDir 'Cafe24Auth.zip')

Write-Host 'Publishing Setup bootstrapper...'
$exit = Publish-SetupBootstrapper -SelfContained:$true
if ($exit -ne 0) {
    Write-Warning "Self-contained setup publish failed with exit code $exit. Retrying framework-dependent single-file setup."
    $exit = Publish-SetupBootstrapper -SelfContained:$false
}
if ($exit -ne 0) {
    throw "Setup bootstrapper publish failed with exit code $exit"
}

$setupExe = Join-Path $SetupPublish 'Cafe24ShipmentManager-Setup.exe'
if (-not (Test-Path -LiteralPath $setupExe)) {
    throw "Setup bootstrapper did not create: $setupExe"
}

Copy-Item -LiteralPath $setupExe -Destination $SetupOutputPath -Force
Write-Host "Created: $SetupOutputPath"
