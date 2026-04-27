using Cafe24ShipmentManager.Data;
using Cafe24ShipmentManager.Models;
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

        var dbConnStrRaw = config["Database"]?["ConnectionString"]?.ToString() ?? "Data Source=app.db";
        var dbConnStr = NormalizeSqliteConnectionString(dbConnStrRaw);
        var logDir = ResolvePath(config["Logging"]?["LogDirectory"]?.ToString() ?? "logs");
        var logger = new AppLogger(logDir);
        var db = new DatabaseManager(dbConnStr);
        var authService = new AuthService(db, logger);
        var userSettingsService = new UserSettingsService(db, logger);

        var currentUser = authService.TryAutoLogin();
        if (currentUser == null)
        {
            using var loginForm = new LoginForm(authService, userSettingsService);
            if (loginForm.ShowDialog() != DialogResult.OK || loginForm.AuthenticatedUser == null)
                return;

            currentUser = loginForm.AuthenticatedUser;
        }

        if (!userSettingsService.IsAdminUser(currentUser) && !userSettingsService.HasMarketplaceConfiguration(currentUser.Id))
        {
            MessageBox.Show("이 계정에는 아직 연결된 마켓이 없습니다. 로그인 후 마켓명과 JSON 키 파일을 먼저 추가하세요.",
                "마켓 설정 필요", MessageBoxButtons.OK, MessageBoxIcon.Information);

            using var profileForm = new UserProfileForm(currentUser, userSettingsService, requireMarketplaceConfig: true);
            if (profileForm.ShowDialog() != DialogResult.OK)
                return;
        }

        logger.Info($"로그인 사용자: {currentUser.UserName}");

        var effectiveConfig = userSettingsService.BuildEffectiveConfig(config, currentUser);
        var gsSection = effectiveConfig["GoogleSheets"];
        var credentialPath = ResolvePath(gsSection?["CredentialPath"]?.ToString() ?? "");
        var spreadsheetId = gsSection?["SpreadsheetId"]?.ToString() ?? "";
        var defaultSheetName = gsSection?["DefaultSheetName"]?.ToString() ?? "";

        var marketClients = BuildMarketplaceClients(configPath, effectiveConfig, logger);
        if (marketClients.Count == 0)
            logger.Warn("연동 마켓 없음: 사용자 설정에서 JSON/API 키를 추가해야 합니다.");
        else
            logger.Info($"연동 마켓: {string.Join(", ", marketClients.Select(client => $"{client.DisplayName}({client.SourceKey})"))}");

        Application.Run(new MainForm(db, logger, authService, marketClients, credentialPath, spreadsheetId, defaultSheetName, currentUser, userSettingsService));
    }

    private static List<IMarketplaceApiClient> BuildMarketplaceClients(string configPath, JObject config, AppLogger logger)
    {
        var clients = new List<IMarketplaceApiClient>();

        var cafe24Section = config["Cafe24"] as JObject;
        var cafe24Configs = BuildCafe24Configs(cafe24Section, configPath)
            .Where(c => c.Enabled)
            .ToList();

        foreach (var cafe24Config in cafe24Configs)
            Cafe24SharedTokenStore.LoadInto(cafe24Config, logger);

        clients.AddRange(cafe24Configs
            .Where(c => !string.IsNullOrWhiteSpace(c.MallId))
            .GroupBy(c => c.MallId, StringComparer.OrdinalIgnoreCase)
            .Select(group => new Cafe24ApiClient(group.First(), logger)));

        var coupangSection = config["Coupang"] as JObject;
        clients.AddRange(BuildCoupangConfigs(coupangSection)
            .Where(c => c.Enabled)
            .Where(c => !string.IsNullOrWhiteSpace(c.VendorId))
            .Where(c => !string.IsNullOrWhiteSpace(c.AccessKey))
            .Where(c => !string.IsNullOrWhiteSpace(c.SecretKey))
            .Select(c => new CoupangApiClient(c, logger)));

        return clients;
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

        return cafe24Section == null
            ? new List<Cafe24Config>()
            : new List<Cafe24Config> { CreateCafe24Config(cafe24Section, null, configPath) };
    }

    private static Cafe24Config CreateCafe24Config(JObject? cafe24Section, JObject? marketSection, string configPath)
    {
        var tokenPath = ResolvePath(ReadString(marketSection, "TokenFilePath",
            ReadString(cafe24Section, "TokenFilePath", Cafe24SharedTokenStore.GetDefaultPath())));

        return new Cafe24Config
        {
            Enabled = ReadBool(marketSection, "Enabled", ReadBool(cafe24Section, "Enabled", true)),
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
            TokenFilePath = tokenPath,
            TokenProviderUrl = ReadString(marketSection, "TokenProviderUrl", ReadString(cafe24Section, "TokenProviderUrl", "")),
            TokenProviderKey = ReadString(marketSection, "TokenProviderKey", ReadString(cafe24Section, "TokenProviderKey", ""))
        };
    }

    private static List<CoupangConfig> BuildCoupangConfigs(JObject? coupangSection)
    {
        var markets = coupangSection?["Markets"] as JArray;
        if (markets != null && markets.Count > 0)
        {
            return markets
                .OfType<JObject>()
                .Select(marketSection => CreateCoupangConfig(coupangSection, marketSection))
                .ToList();
        }

        return coupangSection == null
            ? new List<CoupangConfig>()
            : new List<CoupangConfig> { CreateCoupangConfig(coupangSection, null) };
    }

    private static CoupangConfig CreateCoupangConfig(JObject? coupangSection, JObject? marketSection)
    {
        return new CoupangConfig
        {
            Enabled = ReadBool(marketSection, "Enabled", ReadBool(coupangSection, "Enabled", true)),
            DisplayName = ReadString(marketSection, "DisplayName", ReadString(coupangSection, "DisplayName", "홈런마켓")),
            VendorId = ReadString(marketSection, "VendorId", ReadString(coupangSection, "VendorId", "")),
            AccessKey = ReadString(marketSection, "AccessKey", ReadString(coupangSection, "AccessKey", "")),
            SecretKey = ReadString(marketSection, "SecretKey", ReadString(coupangSection, "SecretKey", "")),
            ApiBaseUrl = ReadString(marketSection, "ApiBaseUrl", ReadString(coupangSection, "ApiBaseUrl", "https://api-gateway.coupang.com")),
            DefaultShippingCompanyCode = ReadString(marketSection, "DefaultShippingCompanyCode", ReadString(coupangSection, "DefaultShippingCompanyCode", "CJGLS")),
            OrderFetchDays = ReadInt(marketSection, "OrderFetchDays", ReadInt(coupangSection, "OrderFetchDays", 14)),
            FetchStatuses = ReadStringList(marketSection, "FetchStatuses", ReadStringList(coupangSection, "FetchStatuses", new List<string> { "ACCEPT", "INSTRUCT" }))
        };
    }

    private static string ReadString(JObject? section, string propertyName, string fallback)
    {
        var value = section?[propertyName]?.ToString();
        return string.IsNullOrWhiteSpace(value) ? fallback : Environment.ExpandEnvironmentVariables(value);
    }

    private static int ReadInt(JObject? section, string propertyName, int fallback)
    {
        return section?[propertyName]?.Value<int?>() ?? fallback;
    }

    private static bool ReadBool(JObject? section, string propertyName, bool fallback)
    {
        return section?[propertyName]?.Value<bool?>() ?? fallback;
    }

    private static List<string> ReadStringList(JObject? section, string propertyName, List<string> fallback)
    {
        var array = section?[propertyName] as JArray;
        return array == null
            ? fallback
            : array.Values<string?>().Where(value => !string.IsNullOrWhiteSpace(value)).Select(value => value!).ToList();
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
