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

        var cafe24Section = config["Cafe24"] as JObject;
        var cafe24Configs = BuildCafe24Configs(cafe24Section, configPath);

        var dbConnStrRaw = config["Database"]?["ConnectionString"]?.ToString() ?? "Data Source=app.db";
        var dbConnStr = NormalizeSqliteConnectionString(dbConnStrRaw);
        var logDir = ResolvePath(config["Logging"]?["LogDirectory"]?.ToString() ?? "logs");

        var logger = new AppLogger(logDir);
        foreach (var cafe24Config in cafe24Configs)
            Cafe24SharedTokenStore.LoadInto(cafe24Config, logger);

        cafe24Configs = cafe24Configs
            .Where(c => !string.IsNullOrWhiteSpace(c.MallId))
            .GroupBy(c => c.MallId, StringComparer.OrdinalIgnoreCase)
            .Select(g => g.First())
            .ToList();

        if (cafe24Configs.Count == 0)
        {
            MessageBox.Show("Cafe24 설정을 찾지 못했습니다. 토큰 파일 경로를 확인하세요.",
                "Cafe24 설정 오류", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }

        logger.Info($"Cafe24 연동 몰: {string.Join(", ", cafe24Configs.Select(c => $"{ResolveDisplayName(c)}({c.MallId})"))}");

        var db = new DatabaseManager(dbConnStr);
        Application.Run(new MainForm(db, logger, cafe24Configs, credentialPath, spreadsheetId, defaultSheetName));
    }

    private static List<Cafe24Config> BuildCafe24Configs(JObject? cafe24Section, string configPath)
    {
        var markets = cafe24Section?["Markets"] as JArray;
        if (markets != null && markets.Count > 0)
        {
            return markets
                .OfType<JObject>()
                .Select(marketSection => CreateCafe24Config(cafe24Section, marketSection, configPath))
                .ToList();
        }

        return new List<Cafe24Config>
        {
            CreateCafe24Config(cafe24Section, null, configPath)
        };
    }

    private static Cafe24Config CreateCafe24Config(JObject? cafe24Section, JObject? marketSection, string configPath)
    {
        var tokenPath = ResolvePath(ReadString(marketSection, "TokenFilePath",
            ReadString(cafe24Section, "TokenFilePath", Cafe24SharedTokenStore.GetDefaultPath())));

        return new Cafe24Config
        {
            DisplayName = ReadString(marketSection, "DisplayName", ReadString(cafe24Section, "DisplayName", "")),
            MallId = ReadString(marketSection, "MallId", ReadString(cafe24Section, "MallId", "")),
            AccessToken = ReadString(marketSection, "AccessToken", ReadString(cafe24Section, "AccessToken", "")),
            ClientId = ReadString(marketSection, "ClientId", ReadString(cafe24Section, "ClientId", "")),
            ClientSecret = ReadString(marketSection, "ClientSecret", ReadString(cafe24Section, "ClientSecret", "")),
            RefreshToken = ReadString(marketSection, "RefreshToken", ReadString(cafe24Section, "RefreshToken", "")),
            ApiVersion = ReadString(marketSection, "ApiVersion", ReadString(cafe24Section, "ApiVersion", "2025-12-01")),
            DefaultShippingCompanyCode = ReadString(marketSection, "DefaultShippingCompanyCode", ReadString(cafe24Section, "DefaultShippingCompanyCode", "0019")),
            OrderFetchDays = ReadInt(marketSection, "OrderFetchDays", ReadInt(cafe24Section, "OrderFetchDays", 14)),
            ConfigFilePath = configPath,
            RedirectUri = ReadString(marketSection, "RedirectUri", ReadString(cafe24Section, "RedirectUri", "")),
            ShopNo = ReadString(marketSection, "ShopNo", ReadString(cafe24Section, "ShopNo", "1")),
            Scope = ReadString(marketSection, "Scope", ReadString(cafe24Section, "Scope", "")),
            TokenFilePath = tokenPath
        };
    }

    private static string ReadString(JObject? section, string propertyName, string fallback)
    {
        var value = section?[propertyName]?.ToString();
        return string.IsNullOrWhiteSpace(value) ? fallback : value;
    }

    private static int ReadInt(JObject? section, string propertyName, int fallback)
    {
        return section?[propertyName]?.Value<int?>() ?? fallback;
    }

    private static string ResolveDisplayName(Cafe24Config config)
    {
        return string.IsNullOrWhiteSpace(config.DisplayName) ? config.MallId : config.DisplayName;
    }

    private static string ResolvePath(string path)
    {
        if (string.IsNullOrWhiteSpace(path)) return path;

        var expandedPath = Environment.ExpandEnvironmentVariables(path);
        return Path.IsPathRooted(expandedPath)
            ? expandedPath
            : Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, expandedPath));
    }

    private static string NormalizeSqliteConnectionString(string connectionString)
    {
        var builder = new SqliteConnectionStringBuilder(connectionString);
        if (!string.IsNullOrWhiteSpace(builder.DataSource))
            builder.DataSource = ResolvePath(builder.DataSource);
        return builder.ToString();
    }
}