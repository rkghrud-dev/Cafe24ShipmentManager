param(
    [switch]$Quiet,
    [switch]$FromTemp
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms

$AppId = 'Cafe24ShipmentManager'
$AppDisplayName = 'Cafe24 송장 관리자'
$InstallRoot = Join-Path $env:LOCALAPPDATA 'Programs\Cafe24ShipmentManager'
$DesktopShortcut = Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::DesktopDirectory)) 'Cafe24 송장 관리자.lnk'
$UninstallRegKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$AppId"

function Test-IsChildPath([string]$Path, [string]$Parent) {
    $fullPath = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $fullParent = [IO.Path]::GetFullPath($Parent).TrimEnd('\')
    return $fullPath.StartsWith($fullParent, [StringComparison]::OrdinalIgnoreCase)
}

if (-not $FromTemp -and -not [string]::IsNullOrWhiteSpace($PSCommandPath) -and (Test-IsChildPath $PSCommandPath $InstallRoot)) {
    $tempScript = Join-Path $env:TEMP ("Cafe24ShipmentManager-uninstall-" + [Guid]::NewGuid().ToString('N') + '.ps1')
    Copy-Item -LiteralPath $PSCommandPath -Destination $tempScript -Force
    $argsList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $tempScript, '-FromTemp')
    if ($Quiet) {
        $argsList += '-Quiet'
    }
    $proc = Start-Process -FilePath 'powershell.exe' -ArgumentList $argsList -Wait -PassThru
    exit $proc.ExitCode
}

function Stop-RunningMainApp {
    Get-Process -Name 'Cafe24ShipmentManager' -ErrorAction SilentlyContinue | ForEach-Object {
        try {
            if (-not $_.HasExited) {
                [void]$_.CloseMainWindow()
                Start-Sleep -Milliseconds 800
            }
            if (-not $_.HasExited) {
                $_.Kill()
                $_.WaitForExit(3000)
            }
        }
        catch {
        }
    }
}

try {
    if (-not $Quiet) {
        $answer = [System.Windows.Forms.MessageBox]::Show(
            "설치된 $AppDisplayName 프로그램을 삭제할까요?`n`n설치 폴더와 바탕화면 바로가기가 삭제됩니다.",
            '프로그램 삭제',
            'YesNo',
            'Question')
        if ($answer -ne [System.Windows.Forms.DialogResult]::Yes) {
            exit 0
        }
    }

    Stop-RunningMainApp
    Remove-Item -LiteralPath $DesktopShortcut -Force -ErrorAction SilentlyContinue
    Remove-Item -Path $UninstallRegKey -Recurse -Force -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $InstallRoot) {
        Remove-Item -LiteralPath $InstallRoot -Recurse -Force
    }

    if (-not $Quiet) {
        [System.Windows.Forms.MessageBox]::Show('삭제가 완료되었습니다.', $AppDisplayName, 'OK', 'Information') | Out-Null
    }
    exit 0
}
catch {
    if (-not $Quiet) {
        [System.Windows.Forms.MessageBox]::Show($_.Exception.Message, '삭제 오류', 'OK', 'Error') | Out-Null
    }
    exit 1
}