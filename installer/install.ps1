param(
    [switch]$Install,
    [switch]$Uninstall,
    [switch]$Quiet,
    [switch]$NoShortcut,
    [switch]$NoLaunch
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$AppId = 'Cafe24ShipmentManager'
$AppDisplayName = 'Cafe24 송장 관리자'
$Publisher = 'Cafe24ShipmentManager'
$PackageVersion = '__PACKAGE_VERSION__'
if ($PackageVersion -like '__*__') {
    $PackageVersion = (Get-Date).ToString('yyyy.MM.dd.HHmm')
}

$InstallRoot = Join-Path $env:LOCALAPPDATA 'Programs\Cafe24ShipmentManager'
$MainInstallDir = Join-Path $InstallRoot 'MainApp'
$AuthInstallDir = Join-Path $InstallRoot 'Cafe24Auth'
$MainExe = Join-Path $MainInstallDir 'Cafe24ShipmentManager.exe'
$DesktopShortcut = Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::DesktopDirectory)) 'Cafe24 송장 관리자.lnk'
$UninstallRegKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$AppId"

$ScriptDir = Split-Path -Parent $PSCommandPath
if ([string]::IsNullOrWhiteSpace($ScriptDir)) {
    $ScriptDir = (Get-Location).Path
}

$MainPayload = Join-Path $ScriptDir 'Cafe24ShipmentManager.zip'
$AuthPayload = Join-Path $ScriptDir 'Cafe24Auth.zip'
$UninstallScriptSource = Join-Path $ScriptDir 'uninstall.ps1'

function Show-Error([string]$Message) {
    [System.Windows.Forms.MessageBox]::Show($Message, '설치 오류', 'OK', 'Error') | Out-Null
}

function Show-Info([string]$Message) {
    if (-not $Quiet) {
        [System.Windows.Forms.MessageBox]::Show($Message, $AppDisplayName, 'OK', 'Information') | Out-Null
    }
}

function Test-Installed {
    return Test-Path -LiteralPath $MainExe
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

function Get-DirectorySizeKb([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        return 0
    }

    $size = (Get-ChildItem -LiteralPath $Path -Recurse -Force -ErrorAction SilentlyContinue |
        Where-Object { -not $_.PSIsContainer } |
        Measure-Object -Property Length -Sum).Sum

    if ($null -eq $size) {
        return 0
    }

    return [int][Math]::Ceiling($size / 1KB)
}

function New-DesktopShortcut {
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($DesktopShortcut)
    $shortcut.TargetPath = $MainExe
    $shortcut.WorkingDirectory = $MainInstallDir
    $shortcut.IconLocation = "$MainExe,0"
    $shortcut.Description = $AppDisplayName
    $shortcut.Save()
}

function Register-UninstallEntry {
    New-Item -Path $UninstallRegKey -Force | Out-Null

    $uninstallScript = Join-Path $InstallRoot 'uninstall.ps1'
    $uninstallCommand = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$uninstallScript`""
    $quietUninstallCommand = "$uninstallCommand -Quiet"

    New-ItemProperty -Path $UninstallRegKey -Name 'DisplayName' -Value $AppDisplayName -PropertyType String -Force | Out-Null
    New-ItemProperty -Path $UninstallRegKey -Name 'DisplayVersion' -Value $PackageVersion -PropertyType String -Force | Out-Null
    New-ItemProperty -Path $UninstallRegKey -Name 'Publisher' -Value $Publisher -PropertyType String -Force | Out-Null
    New-ItemProperty -Path $UninstallRegKey -Name 'InstallLocation' -Value $InstallRoot -PropertyType String -Force | Out-Null
    New-ItemProperty -Path $UninstallRegKey -Name 'DisplayIcon' -Value $MainExe -PropertyType String -Force | Out-Null
    New-ItemProperty -Path $UninstallRegKey -Name 'UninstallString' -Value $uninstallCommand -PropertyType String -Force | Out-Null
    New-ItemProperty -Path $UninstallRegKey -Name 'QuietUninstallString' -Value $quietUninstallCommand -PropertyType String -Force | Out-Null
    New-ItemProperty -Path $UninstallRegKey -Name 'NoModify' -Value 1 -PropertyType DWord -Force | Out-Null
    New-ItemProperty -Path $UninstallRegKey -Name 'NoRepair' -Value 1 -PropertyType DWord -Force | Out-Null
    New-ItemProperty -Path $UninstallRegKey -Name 'EstimatedSize' -Value (Get-DirectorySizeKb $InstallRoot) -PropertyType DWord -Force | Out-Null
}

function Copy-PayloadPreservingData([string]$Source, [string]$Destination, [string[]]$PreserveNames) {
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null

    $preserveSet = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($name in $PreserveNames) {
        [void]$preserveSet.Add($name)
    }

    Get-ChildItem -LiteralPath $Destination -Force -ErrorAction SilentlyContinue | ForEach-Object {
        if (-not $preserveSet.Contains($_.Name)) {
            Remove-Item -LiteralPath $_.FullName -Recurse -Force
        }
    }

    Get-ChildItem -LiteralPath $Source -Force | ForEach-Object {
        $target = Join-Path $Destination $_.Name
        if ($preserveSet.Contains($_.Name) -and (Test-Path -LiteralPath $target)) {
            return
        }

        Copy-Item -LiteralPath $_.FullName -Destination $target -Recurse -Force
    }
}

function Install-App([bool]$CreateShortcut, [bool]$LaunchAfterInstall) {
    if (-not (Test-Path -LiteralPath $MainPayload)) {
        throw "설치 payload를 찾을 수 없습니다: $MainPayload"
    }
    if (-not (Test-Path -LiteralPath $AuthPayload)) {
        throw "Cafe24Auth payload를 찾을 수 없습니다: $AuthPayload"
    }

    Stop-RunningMainApp

    New-Item -ItemType Directory -Force -Path $InstallRoot | Out-Null
    $tempRoot = Join-Path $env:TEMP ("Cafe24ShipmentManagerInstall_" + [Guid]::NewGuid().ToString('N'))
    $mainExtract = Join-Path $tempRoot 'main'
    $authExtract = Join-Path $tempRoot 'auth'

    try {
        New-Item -ItemType Directory -Force -Path $mainExtract, $authExtract | Out-Null
        Expand-Archive -LiteralPath $MainPayload -DestinationPath $mainExtract -Force
        Expand-Archive -LiteralPath $AuthPayload -DestinationPath $authExtract -Force

        Copy-PayloadPreservingData `
            -Source $mainExtract `
            -Destination $MainInstallDir `
            -PreserveNames @('app.db', 'appsettings.json', 'credentials.json', 'logs', 'Data', 'token')

        if (Test-Path -LiteralPath $AuthInstallDir) {
            Remove-Item -LiteralPath $AuthInstallDir -Recurse -Force
        }
        New-Item -ItemType Directory -Force -Path $AuthInstallDir | Out-Null
        Get-ChildItem -LiteralPath $authExtract -Force | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $AuthInstallDir $_.Name) -Recurse -Force
        }

        if (Test-Path -LiteralPath $UninstallScriptSource) {
            Copy-Item -LiteralPath $UninstallScriptSource -Destination (Join-Path $InstallRoot 'uninstall.ps1') -Force
        }

        if ($CreateShortcut) {
            New-DesktopShortcut
        }

        Register-UninstallEntry

        if ($LaunchAfterInstall -and (Test-Path -LiteralPath $MainExe)) {
            Start-Process -FilePath $MainExe -WorkingDirectory $MainInstallDir
        }

        Show-Info "설치가 완료되었습니다.`n`n설치 위치: $InstallRoot"
    }
    finally {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Remove-InstalledApp([bool]$Confirm) {
    if ($Confirm) {
        $answer = [System.Windows.Forms.MessageBox]::Show(
            "설치된 $AppDisplayName 프로그램을 삭제할까요?`n`n설치 폴더와 바탕화면 바로가기가 삭제됩니다.",
            '프로그램 삭제',
            'YesNo',
            'Question')
        if ($answer -ne [System.Windows.Forms.DialogResult]::Yes) {
            return $false
        }
    }

    Stop-RunningMainApp

    Remove-Item -LiteralPath $DesktopShortcut -Force -ErrorAction SilentlyContinue
    Remove-Item -Path $UninstallRegKey -Recurse -Force -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $InstallRoot) {
        Remove-Item -LiteralPath $InstallRoot -Recurse -Force
    }

    Show-Info '삭제가 완료되었습니다.'
    return $true
}

function Show-SetupWindow {
    $installed = Test-Installed

    $form = New-Object System.Windows.Forms.Form
    $form.Text = "$AppDisplayName 설치"
    $form.StartPosition = 'CenterScreen'
    $form.FormBorderStyle = 'FixedDialog'
    $form.MaximizeBox = $false
    $form.MinimizeBox = $false
    $form.ClientSize = New-Object System.Drawing.Size(470, 245)
    $form.Tag = 'cancel'

    $title = New-Object System.Windows.Forms.Label
    $title.Text = $AppDisplayName
    $title.Font = New-Object System.Drawing.Font('Malgun Gothic', 14, [System.Drawing.FontStyle]::Bold)
    $title.AutoSize = $true
    $title.Location = New-Object System.Drawing.Point(22, 18)
    $form.Controls.Add($title)

    $state = New-Object System.Windows.Forms.Label
    $state.Font = New-Object System.Drawing.Font('Malgun Gothic', 9)
    $state.Size = New-Object System.Drawing.Size(420, 48)
    $state.Location = New-Object System.Drawing.Point(24, 58)
    if ($installed) {
        $state.Text = "이미 설치되어 있습니다. '설치하기'를 누르면 최신 파일로 다시 설치하고, '삭제하기'를 누르면 제거합니다."
    }
    else {
        $state.Text = "이 컴퓨터에 설치되어 있지 않습니다. '설치하기'를 누르면 현재 최신 버전이 설치됩니다."
    }
    $form.Controls.Add($state)

    $shortcut = New-Object System.Windows.Forms.CheckBox
    $shortcut.Text = '바탕화면 바로가기 만들기'
    $shortcut.Font = New-Object System.Drawing.Font('Malgun Gothic', 9)
    $shortcut.AutoSize = $true
    $shortcut.Checked = $true
    $shortcut.Location = New-Object System.Drawing.Point(27, 116)
    $form.Controls.Add($shortcut)

    $launch = New-Object System.Windows.Forms.CheckBox
    $launch.Text = '설치 후 바로 실행'
    $launch.Font = New-Object System.Drawing.Font('Malgun Gothic', 9)
    $launch.AutoSize = $true
    $launch.Checked = $true
    $launch.Location = New-Object System.Drawing.Point(27, 145)
    $form.Controls.Add($launch)

    $installButton = New-Object System.Windows.Forms.Button
    $installButton.Text = '설치하기'
    $installButton.Font = New-Object System.Drawing.Font('Malgun Gothic', 9)
    $installButton.Size = New-Object System.Drawing.Size(110, 34)
    $installButton.Location = New-Object System.Drawing.Point(106, 190)
    $installButton.Add_Click({ $form.Tag = 'install'; $form.Close() })
    $form.Controls.Add($installButton)

    $removeButton = New-Object System.Windows.Forms.Button
    $removeButton.Text = '삭제하기'
    $removeButton.Font = New-Object System.Drawing.Font('Malgun Gothic', 9)
    $removeButton.Size = New-Object System.Drawing.Size(110, 34)
    $removeButton.Location = New-Object System.Drawing.Point(226, 190)
    $removeButton.Enabled = $installed
    $removeButton.Add_Click({ $form.Tag = 'remove'; $form.Close() })
    $form.Controls.Add($removeButton)

    $cancelButton = New-Object System.Windows.Forms.Button
    $cancelButton.Text = '취소'
    $cancelButton.Font = New-Object System.Drawing.Font('Malgun Gothic', 9)
    $cancelButton.Size = New-Object System.Drawing.Size(90, 34)
    $cancelButton.Location = New-Object System.Drawing.Point(346, 190)
    $cancelButton.Add_Click({ $form.Tag = 'cancel'; $form.Close() })
    $form.Controls.Add($cancelButton)

    $form.AcceptButton = $installButton
    $form.CancelButton = $cancelButton

    [void]$form.ShowDialog()
    return [PSCustomObject]@{
        Action = [string]$form.Tag
        CreateShortcut = [bool]$shortcut.Checked
        LaunchAfterInstall = [bool]$launch.Checked
    }
}

try {
    if ($Uninstall) {
        [void](Remove-InstalledApp -Confirm:(-not $Quiet))
        exit 0
    }

    if ($Install -or $Quiet) {
        Install-App -CreateShortcut:(-not $NoShortcut) -LaunchAfterInstall:(-not $NoLaunch)
        exit 0
    }

    $choice = Show-SetupWindow
    switch ($choice.Action) {
        'install' {
            Install-App -CreateShortcut:$choice.CreateShortcut -LaunchAfterInstall:$choice.LaunchAfterInstall
        }
        'remove' {
            [void](Remove-InstalledApp -Confirm:$true)
        }
        default {
            exit 0
        }
    }
}
catch {
    Show-Error $_.Exception.Message
    exit 1
}