using Cafe24ShipmentManager;
using Cafe24ShipmentManager.Data;
using Cafe24ShipmentManager.Services;
using Microsoft.Data.Sqlite;
using Newtonsoft.Json.Linq;

namespace Cafe24ShipmentManager;

static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();

        Application.ThreadException += (_, e) =>
        {
            MessageBox.Show($"예기치 않은 오류:\n{e.Exception.Message}\n\n{e.Exception.StackTrace}",
                "오류", MessageBoxButtons.OK, MessageBoxIcon.Error);
        };
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
        {
            var ex = e.ExceptionObject as Exception;
            MessageBox.Show($"치명적 오류:\n{ex?.Message}\n\n{ex?.StackTrace}",
                "치명적 오류", MessageBoxButtons.OK, MessageBoxIcon.Error);
        };
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);

        var configPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "appsettings.json");
        JObject config;
        try
        {
            config = JObject.Parse(File.ReadAllText(configPath));
        }
        catch
        {
            config = new JObject();
        }

        var gsSection = config["GoogleSheets"];
        var credentialPath = ResolvePath(gsSection?["CredentialPath"]?.ToString() ?? "");
        var spreadsheetId = gsSection?["SpreadsheetId"]?.ToString() ?? "";
        var defaultSheetName = gsSection?["DefaultSheetName"]?.ToString() ?? "";

        var cafe24Section = config["Cafe24"];
        var cafe24Config = new Cafe24Config
        {
            MallId = cafe24Section?["MallId"]?.ToString() ?? "",
            AccessToken = cafe24Section?["AccessToken"]?.ToString() ?? "",
            ClientId = cafe24Section?["ClientId"]?.ToString() ?? "",
            ClientSecret = cafe24Section?["ClientSecret"]?.ToString() ?? "",
            RefreshToken = cafe24Section?["RefreshToken"]?.ToString() ?? "",
            ApiVersion = cafe24Section?["ApiVersion"]?.ToString() ?? "2025-12-01",
            DefaultShippingCompanyCode = cafe24Section?["DefaultShippingCompanyCode"]?.ToString() ?? "0019",
            OrderFetchDays = cafe24Section?["OrderFetchDays"]?.Value<int>() ?? 14,
            ConfigFilePath = configPath,
            RedirectUri = cafe24Section?["RedirectUri"]?.ToString() ?? "",
            ShopNo = cafe24Section?["ShopNo"]?.ToString() ?? "1",
            Scope = cafe24Section?["Scope"]?.ToString() ?? "",
            TokenFilePath = ResolvePath(cafe24Section?["TokenFilePath"]?.ToString() ?? Cafe24SharedTokenStore.GetDefaultPath())
        };

        var dbConnStrRaw = config["Database"]?["ConnectionString"]?.ToString() ?? "Data Source=app.db";
        var dbConnStr = NormalizeSqliteConnectionString(dbConnStrRaw);
        var logDir = ResolvePath(config["Logging"]?["LogDirectory"]?.ToString() ?? "logs");

        var logger = new AppLogger(logDir);
        Cafe24SharedTokenStore.LoadInto(cafe24Config, logger);
        var db = new DatabaseManager(dbConnStr);

        Application.Run(new MainForm(db, logger, cafe24Config, credentialPath, spreadsheetId, defaultSheetName));
    }

    private static string ResolvePath(string path)
    {
        if (string.IsNullOrWhiteSpace(path)) return path;
        return Path.IsPathRooted(path) ? path : Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, path));
    }

    private static string NormalizeSqliteConnectionString(string connectionString)
    {
        var builder = new SqliteConnectionStringBuilder(connectionString);
        if (!string.IsNullOrWhiteSpace(builder.DataSource))
            builder.DataSource = ResolvePath(builder.DataSource);
        return builder.ToString();
    }
}