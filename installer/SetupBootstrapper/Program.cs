using Microsoft.Win32;
using System.Diagnostics;
using System.Drawing;
using System.IO.Compression;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace Cafe24ShipmentManager.Setup;

internal static class Program
{
    private const string AppId = "Cafe24ShipmentManager";
    private const string AppDisplayName = "Cafe24 송장 관리자";
    private const string Publisher = "Cafe24ShipmentManager";
    private const string MainPayloadResource = "Payload.Cafe24ShipmentManager.zip";
    private const string AuthPayloadResource = "Payload.Cafe24Auth.zip";
    private static readonly string PackageVersion =
        typeof(Program).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion
        ?? typeof(Program).Assembly.GetName().Version?.ToString()
        ?? "local";

    private static readonly string InstallRoot = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Programs",
        "Cafe24ShipmentManager");

    private static readonly string MainInstallDir = Path.Combine(InstallRoot, "MainApp");
    private static readonly string AuthInstallDir = Path.Combine(InstallRoot, "Cafe24Auth");
    private static readonly string MainExe = Path.Combine(MainInstallDir, "Cafe24ShipmentManager.exe");
    private static readonly string InstalledSetupExe = Path.Combine(InstallRoot, "Cafe24ShipmentManager-Setup.exe");
    private static readonly string DesktopShortcut = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
        "Cafe24 송장 관리자.lnk");

    [STAThread]
    private static void Main(string[] args)
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        try
        {
            var argSet = args.Select(arg => arg.Trim().ToLowerInvariant()).ToHashSet(StringComparer.OrdinalIgnoreCase);
            var quiet = argSet.Contains("/quiet") || argSet.Contains("-quiet");

            if (argSet.Contains("/uninstall") || argSet.Contains("-uninstall"))
            {
                Uninstall(argSet.Contains("/from-temp") || argSet.Contains("-from-temp"), quiet);
                return;
            }

            var choice = ShowSetupWindow();
            if (choice.Action == SetupAction.Install)
                Install(choice.CreateDesktopShortcut, choice.LaunchAfterInstall);
            else if (choice.Action == SetupAction.Remove)
                Uninstall(fromTemp: false, quiet: false);
        }
        catch (Exception ex)
        {
            MessageBox.Show(ex.Message, "설치 오류", MessageBoxButtons.OK, MessageBoxIcon.Error);
            Environment.ExitCode = 1;
        }
    }

    private static SetupChoice ShowSetupWindow()
    {
        var installed = File.Exists(MainExe);
        using var form = new Form
        {
            Text = $"{AppDisplayName} 설치",
            StartPosition = FormStartPosition.CenterScreen,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false,
            MinimizeBox = false,
            ClientSize = new Size(470, 260)
        };

        var title = new Label
        {
            Text = AppDisplayName,
            Font = new Font("Malgun Gothic", 14, FontStyle.Bold),
            AutoSize = true,
            Location = new Point(22, 18)
        };
        form.Controls.Add(title);

        var version = new Label
        {
            Text = $"버전 {PackageVersion}",
            Font = new Font("Malgun Gothic", 9),
            AutoSize = true,
            ForeColor = Color.DimGray,
            Location = new Point(24, 45)
        };
        form.Controls.Add(version);

        var state = new Label
        {
            Font = new Font("Malgun Gothic", 9),
            Size = new Size(420, 48),
            Location = new Point(24, 74),
            Text = installed
                ? "이미 설치되어 있습니다. '업데이트하기'를 누르면 설정과 DB는 보존하고 프로그램 파일만 최신 버전으로 교체합니다."
                : "이 컴퓨터에 설치되어 있지 않습니다. '설치하기'를 누르면 현재 최신 버전이 설치됩니다."
        };
        form.Controls.Add(state);

        var shortcut = new CheckBox
        {
            Text = "바탕화면 바로가기 만들기",
            Font = new Font("Malgun Gothic", 9),
            AutoSize = true,
            Checked = true,
            Location = new Point(27, 132)
        };
        form.Controls.Add(shortcut);

        var launch = new CheckBox
        {
            Text = "설치 후 바로 실행",
            Font = new Font("Malgun Gothic", 9),
            AutoSize = true,
            Checked = true,
            Location = new Point(27, 161)
        };
        form.Controls.Add(launch);

        var choice = new SetupChoice();

        var installButton = new Button
        {
            Text = installed ? "업데이트하기" : "설치하기",
            Font = new Font("Malgun Gothic", 9),
            Size = new Size(110, 34),
            Location = new Point(106, 206)
        };
        installButton.Click += (_, _) =>
        {
            choice.Action = SetupAction.Install;
            choice.CreateDesktopShortcut = shortcut.Checked;
            choice.LaunchAfterInstall = launch.Checked;
            form.Close();
        };
        form.Controls.Add(installButton);

        var removeButton = new Button
        {
            Text = "삭제하기",
            Font = new Font("Malgun Gothic", 9),
            Size = new Size(110, 34),
            Location = new Point(226, 206),
            Enabled = installed
        };
        removeButton.Click += (_, _) =>
        {
            choice.Action = SetupAction.Remove;
            form.Close();
        };
        form.Controls.Add(removeButton);

        var cancelButton = new Button
        {
            Text = "취소",
            Font = new Font("Malgun Gothic", 9),
            Size = new Size(90, 34),
            Location = new Point(346, 206)
        };
        cancelButton.Click += (_, _) =>
        {
            choice.Action = SetupAction.Cancel;
            form.Close();
        };
        form.Controls.Add(cancelButton);

        form.AcceptButton = installButton;
        form.CancelButton = cancelButton;
        form.ShowDialog();

        return choice;
    }

    private static void Install(bool createDesktopShortcut, bool launchAfterInstall)
    {
        StopRunningMainApp();
        Directory.CreateDirectory(InstallRoot);

        var tempRoot = Path.Combine(Path.GetTempPath(), "Cafe24ShipmentManagerInstall_" + Guid.NewGuid().ToString("N"));
        var mainZip = Path.Combine(tempRoot, "Cafe24ShipmentManager.zip");
        var authZip = Path.Combine(tempRoot, "Cafe24Auth.zip");
        var mainExtract = Path.Combine(tempRoot, "main");
        var authExtract = Path.Combine(tempRoot, "auth");

        try
        {
            Directory.CreateDirectory(tempRoot);
            Directory.CreateDirectory(mainExtract);
            Directory.CreateDirectory(authExtract);

            ExtractResourceToFile(MainPayloadResource, mainZip);
            ExtractResourceToFile(AuthPayloadResource, authZip);
            ZipFile.ExtractToDirectory(mainZip, mainExtract, overwriteFiles: true);
            ZipFile.ExtractToDirectory(authZip, authExtract, overwriteFiles: true);

            CopyPayloadPreservingData(mainExtract, MainInstallDir, new[]
            {
                "app.db",
                "appsettings.json",
                "credentials.json",
                "logs",
                "Data",
                "token"
            });

            DeletePath(AuthInstallDir);
            Directory.CreateDirectory(AuthInstallDir);
            CopyDirectoryContents(authExtract, AuthInstallDir);

            CopySetupExeForUninstall();
            if (createDesktopShortcut)
                CreateDesktopShortcut();

            RegisterUninstallEntry();

            if (launchAfterInstall && File.Exists(MainExe))
            {
                Process.Start(new ProcessStartInfo
                {
                    FileName = MainExe,
                    WorkingDirectory = MainInstallDir,
                    UseShellExecute = true
                });
            }

            MessageBox.Show($"설치/업데이트가 완료되었습니다.\n\n버전: {PackageVersion}\n설치 위치: {InstallRoot}",
                AppDisplayName, MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        finally
        {
            DeletePath(tempRoot);
        }
    }

    private static void Uninstall(bool fromTemp, bool quiet)
    {
        var currentExe = Environment.ProcessPath ?? Application.ExecutablePath;
        if (!fromTemp && !string.IsNullOrWhiteSpace(currentExe) && IsChildPath(currentExe, InstallRoot))
        {
            var tempExe = Path.Combine(Path.GetTempPath(), "Cafe24ShipmentManager-Setup-" + Guid.NewGuid().ToString("N") + ".exe");
            File.Copy(currentExe, tempExe, overwrite: true);
            Process.Start(new ProcessStartInfo
            {
                FileName = tempExe,
                Arguments = "/uninstall /from-temp" + (quiet ? " /quiet" : ""),
                UseShellExecute = true
            });
            return;
        }

        if (!quiet)
        {
            var answer = MessageBox.Show(
                $"설치된 {AppDisplayName} 프로그램을 삭제할까요?\n\n설치 폴더와 바탕화면 바로가기가 삭제됩니다.",
                "프로그램 삭제",
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Question);
            if (answer != DialogResult.Yes)
                return;
        }

        StopRunningMainApp();
        DeletePath(DesktopShortcut);
        DeleteRegistryTree(Registry.CurrentUser, @"Software\Microsoft\Windows\CurrentVersion\Uninstall\" + AppId);
        DeletePath(InstallRoot);

        if (!quiet)
            MessageBox.Show("삭제가 완료되었습니다.", AppDisplayName, MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    private static void ExtractResourceToFile(string resourceName, string destinationPath)
    {
        var assembly = Assembly.GetExecutingAssembly();
        using var stream = assembly.GetManifestResourceStream(resourceName)
            ?? throw new InvalidOperationException($"설치 payload 리소스를 찾을 수 없습니다: {resourceName}");
        using var file = File.Create(destinationPath);
        stream.CopyTo(file);
    }

    private static void CopyPayloadPreservingData(string source, string destination, IEnumerable<string> preserveNames)
    {
        Directory.CreateDirectory(destination);
        var preserveSet = new HashSet<string>(preserveNames, StringComparer.OrdinalIgnoreCase);

        foreach (var existing in Directory.EnumerateFileSystemEntries(destination))
        {
            var name = Path.GetFileName(existing);
            if (!preserveSet.Contains(name))
                DeletePath(existing);
        }

        foreach (var item in Directory.EnumerateFileSystemEntries(source))
        {
            var name = Path.GetFileName(item);
            var target = Path.Combine(destination, name);
            if (preserveSet.Contains(name) && PathExists(target))
                continue;

            CopyPath(item, target);
        }
    }

    private static void CopyDirectoryContents(string source, string destination)
    {
        Directory.CreateDirectory(destination);
        foreach (var item in Directory.EnumerateFileSystemEntries(source))
            CopyPath(item, Path.Combine(destination, Path.GetFileName(item)));
    }

    private static void CopyPath(string source, string destination)
    {
        if (Directory.Exists(source))
        {
            Directory.CreateDirectory(destination);
            foreach (var child in Directory.EnumerateFileSystemEntries(source))
                CopyPath(child, Path.Combine(destination, Path.GetFileName(child)));
            return;
        }

        Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
        if (File.Exists(destination))
            File.SetAttributes(destination, FileAttributes.Normal);
        File.Copy(source, destination, overwrite: true);
    }

    private static void DeletePath(string path)
    {
        if (string.IsNullOrWhiteSpace(path))
            return;

        if (File.Exists(path))
        {
            File.SetAttributes(path, FileAttributes.Normal);
            File.Delete(path);
            return;
        }

        if (!Directory.Exists(path))
            return;

        foreach (var entry in Directory.EnumerateFileSystemEntries(path, "*", SearchOption.AllDirectories))
        {
            if (File.Exists(entry))
                File.SetAttributes(entry, FileAttributes.Normal);
        }
        Directory.Delete(path, recursive: true);
    }

    private static bool PathExists(string path) => File.Exists(path) || Directory.Exists(path);

    private static void StopRunningMainApp()
    {
        foreach (var process in Process.GetProcessesByName("Cafe24ShipmentManager"))
        {
            try
            {
                if (!process.HasExited)
                {
                    process.CloseMainWindow();
                    process.WaitForExit(1200);
                }
                if (!process.HasExited)
                {
                    process.Kill(entireProcessTree: true);
                    process.WaitForExit(3000);
                }
            }
            catch
            {
            }
            finally
            {
                process.Dispose();
            }
        }
    }

    private static void CopySetupExeForUninstall()
    {
        var currentExe = Environment.ProcessPath ?? Application.ExecutablePath;
        if (string.IsNullOrWhiteSpace(currentExe))
            return;

        if (!string.Equals(Path.GetFullPath(currentExe), Path.GetFullPath(InstalledSetupExe), StringComparison.OrdinalIgnoreCase))
            File.Copy(currentExe, InstalledSetupExe, overwrite: true);
    }

    private static void CreateDesktopShortcut()
    {
        var shellType = Type.GetTypeFromProgID("WScript.Shell")
            ?? throw new InvalidOperationException("Windows 바로가기 생성 COM 객체를 찾을 수 없습니다.");

        object? shell = null;
        object? shortcut = null;
        try
        {
            shell = Activator.CreateInstance(shellType);
            shortcut = shellType.InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell, new object[] { DesktopShortcut });
            SetComProperty(shortcut!, "TargetPath", MainExe);
            SetComProperty(shortcut!, "WorkingDirectory", MainInstallDir);
            SetComProperty(shortcut!, "IconLocation", MainExe + ",0");
            SetComProperty(shortcut!, "Description", AppDisplayName);
            shortcut!.GetType().InvokeMember("Save", BindingFlags.InvokeMethod, null, shortcut, Array.Empty<object>());
        }
        finally
        {
            if (shortcut != null && Marshal.IsComObject(shortcut))
                Marshal.FinalReleaseComObject(shortcut);
            if (shell != null && Marshal.IsComObject(shell))
                Marshal.FinalReleaseComObject(shell);
        }
    }

    private static void SetComProperty(object target, string propertyName, object value)
    {
        target.GetType().InvokeMember(propertyName, BindingFlags.SetProperty, null, target, new[] { value });
    }

    private static void RegisterUninstallEntry()
    {
        using var key = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Uninstall\" + AppId);
        if (key == null)
            return;

        key.SetValue("DisplayName", AppDisplayName, RegistryValueKind.String);
        key.SetValue("DisplayVersion", PackageVersion, RegistryValueKind.String);
        key.SetValue("Publisher", Publisher, RegistryValueKind.String);
        key.SetValue("InstallLocation", InstallRoot, RegistryValueKind.String);
        key.SetValue("DisplayIcon", MainExe, RegistryValueKind.String);
        key.SetValue("UninstallString", $"\"{InstalledSetupExe}\" /uninstall", RegistryValueKind.String);
        key.SetValue("QuietUninstallString", $"\"{InstalledSetupExe}\" /uninstall /quiet", RegistryValueKind.String);
        key.SetValue("NoModify", 1, RegistryValueKind.DWord);
        key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
        key.SetValue("EstimatedSize", GetDirectorySizeKb(InstallRoot), RegistryValueKind.DWord);
    }

    private static int GetDirectorySizeKb(string path)
    {
        if (!Directory.Exists(path))
            return 0;

        long bytes = 0;
        foreach (var file in Directory.EnumerateFiles(path, "*", SearchOption.AllDirectories))
        {
            try { bytes += new FileInfo(file).Length; }
            catch { }
        }

        return (int)Math.Ceiling(bytes / 1024d);
    }

    private static void DeleteRegistryTree(RegistryKey root, string subKey)
    {
        try { root.DeleteSubKeyTree(subKey, throwOnMissingSubKey: false); }
        catch { }
    }

    private static bool IsChildPath(string path, string parent)
    {
        var fullPath = Path.GetFullPath(path).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar) + Path.DirectorySeparatorChar;
        var fullParent = Path.GetFullPath(parent).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar) + Path.DirectorySeparatorChar;
        return fullPath.StartsWith(fullParent, StringComparison.OrdinalIgnoreCase);
    }

    private enum SetupAction
    {
        Cancel,
        Install,
        Remove
    }

    private sealed class SetupChoice
    {
        public SetupAction Action { get; set; } = SetupAction.Cancel;
        public bool CreateDesktopShortcut { get; set; } = true;
        public bool LaunchAfterInstall { get; set; } = true;
    }
}
