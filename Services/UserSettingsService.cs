using System.Security.Cryptography;
using System.Text;
using Cafe24ShipmentManager.Data;
using Cafe24ShipmentManager.Models;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace Cafe24ShipmentManager.Services;

public sealed class UserSettingsService
{
    private readonly DatabaseManager _db;
    private readonly AppLogger _log;

    public UserSettingsService(DatabaseManager db, AppLogger log)
    {
        _db = db;
        _log = log;
    }

    public bool IsAdminUser(AppUser user)
    {
        return string.Equals(user.UserName, "admin", StringComparison.OrdinalIgnoreCase);
    }

    public bool HasMarketplaceConfiguration(long userId)
    {
        var settings = _db.GetUserSettings(userId);
        return !string.IsNullOrWhiteSpace(Unprotect(settings.ProtectedCafe24Json)) ||
               !string.IsNullOrWhiteSpace(Unprotect(settings.ProtectedCoupangJson));
    }

    public UserSettingsDraft CreateDraftForRegistration()
    {
        return new UserSettingsDraft();
    }

    public IReadOnlyList<Cafe24MarketEntry> LoadCafe24Markets(long userId)
    {
        return ParseCafe24MarketEntries(LoadDraft(userId).Cafe24Json);
    }

    public void SaveCafe24Markets(long userId, IEnumerable<Cafe24MarketEntry> markets, bool requireMarketplaceConfig)
    {
        var existingDraft = LoadDraft(userId);
        var normalizedMarkets = NormalizeCafe24MarketEntries(markets).ToList();

        var draft = new UserSettingsDraft
        {
            GoogleCredentialPath = existingDraft.GoogleCredentialPath,
            GoogleSpreadsheetId = existingDraft.GoogleSpreadsheetId,
            GoogleDefaultSheetName = existingDraft.GoogleDefaultSheetName,
            Cafe24Json = BuildCafe24MarketJson(existingDraft.Cafe24Json, normalizedMarkets),
            CoupangJson = existingDraft.CoupangJson
        };

        SaveUserSettings(userId, draft, requireMarketplaceConfig);
    }

    public UserSettingsDraft LoadDraft(long userId)
    {
        var settings = _db.GetUserSettings(userId);
        return new UserSettingsDraft
        {
            GoogleCredentialPath = Unprotect(settings.ProtectedGoogleCredentialPath),
            GoogleSpreadsheetId = settings.GoogleSpreadsheetId,
            GoogleDefaultSheetName = settings.GoogleDefaultSheetName,
            Cafe24Json = Unprotect(settings.ProtectedCafe24Json),
            CoupangJson = Unprotect(settings.ProtectedCoupangJson)
        };
    }

    public UserSettingsDraft ValidateDraft(UserSettingsDraft draft, bool requireMarketplaceConfig)
    {
        var normalizedDraft = NormalizeDraft(draft);

        if (requireMarketplaceConfig && !normalizedDraft.HasMarketplaceConfig)
            throw new InvalidOperationException("최소 하나 이상의 마켓 키(Cafe24 또는 쿠팡)를 입력하세요.");

        ValidateOptionalJson(normalizedDraft.Cafe24Json, "Cafe24 설정");
        ValidateOptionalJson(normalizedDraft.CoupangJson, "쿠팡 설정");
        return normalizedDraft;
    }

    public void SaveUserSettings(long userId, UserSettingsDraft draft, bool requireMarketplaceConfig)
    {
        var normalizedDraft = ValidateDraft(draft, requireMarketplaceConfig);

        var settings = new AppUserSettings
        {
            UserId = userId,
            ProtectedGoogleCredentialPath = Protect(normalizedDraft.GoogleCredentialPath),
            GoogleSpreadsheetId = normalizedDraft.GoogleSpreadsheetId,
            GoogleDefaultSheetName = normalizedDraft.GoogleDefaultSheetName,
            ProtectedCafe24Json = Protect(normalizedDraft.Cafe24Json),
            ProtectedCoupangJson = Protect(normalizedDraft.CoupangJson),
            UpdatedAt = Now()
        };

        _db.SaveUserSettings(settings);
        _log.Info($"사용자별 키 설정 저장: UserId={userId}");
    }

    public JObject BuildEffectiveConfig(JObject baseConfig, AppUser user)
    {
        var effective = (JObject)baseConfig.DeepClone();
        var settings = LoadDraft(user.Id);

        // Marketplace credentials are user-owned. Even admin starts with no sources
        // until JSON/API settings are explicitly saved in the profile screen.
        effective["Cafe24"] = new JObject();
        effective["Coupang"] = new JObject();

        ApplyGoogleSettings(effective, settings);
        ApplyJsonSection(effective, "Cafe24", settings.Cafe24Json);
        ApplyJsonSection(effective, "Coupang", settings.CoupangJson);

        return effective;
    }

    private static UserSettingsDraft NormalizeDraft(UserSettingsDraft draft)
    {
        return new UserSettingsDraft
        {
            GoogleCredentialPath = draft.GoogleCredentialPath.Trim(),
            GoogleSpreadsheetId = draft.GoogleSpreadsheetId.Trim(),
            GoogleDefaultSheetName = draft.GoogleDefaultSheetName.Trim(),
            Cafe24Json = draft.Cafe24Json.Trim(),
            CoupangJson = draft.CoupangJson.Trim()
        };
    }

    private static IReadOnlyList<Cafe24MarketEntry> ParseCafe24MarketEntries(string json)
    {
        if (string.IsNullOrWhiteSpace(json))
            return Array.Empty<Cafe24MarketEntry>();

        JObject cafe24;
        try
        {
            cafe24 = JObject.Parse(json);
        }
        catch (JsonException)
        {
            return Array.Empty<Cafe24MarketEntry>();
        }

        var result = new List<Cafe24MarketEntry>();
        var markets = cafe24["Markets"] as JArray;
        if (markets != null && markets.Count > 0)
        {
            foreach (var marketSection in markets.OfType<JObject>())
                AddCafe24MarketEntry(result, marketSection);

            return result;
        }

        if (cafe24.Properties().Any())
            AddCafe24MarketEntry(result, cafe24);

        return result;
    }

    private static void AddCafe24MarketEntry(ICollection<Cafe24MarketEntry> target, JObject marketSection)
    {
        var displayName = ReadString(marketSection, "DisplayName", ReadString(marketSection, "MallId", ""));
        var tokenFilePath = ReadString(marketSection, "TokenFilePath", "");

        if (string.IsNullOrWhiteSpace(displayName) && string.IsNullOrWhiteSpace(tokenFilePath))
            return;

        target.Add(new Cafe24MarketEntry
        {
            DisplayName = displayName,
            TokenFilePath = tokenFilePath
        });
    }

    private static IReadOnlyList<Cafe24MarketEntry> NormalizeCafe24MarketEntries(IEnumerable<Cafe24MarketEntry> markets)
    {
        var normalized = new List<Cafe24MarketEntry>();
        var seenNames = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var seenPaths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        foreach (var market in markets ?? Enumerable.Empty<Cafe24MarketEntry>())
        {
            var displayName = market.DisplayName?.Trim() ?? "";
            var tokenFilePath = market.TokenFilePath?.Trim() ?? "";

            if (string.IsNullOrWhiteSpace(displayName) && string.IsNullOrWhiteSpace(tokenFilePath))
                continue;

            if (string.IsNullOrWhiteSpace(displayName))
                throw new InvalidOperationException("마켓명을 입력하세요.");

            if (string.IsNullOrWhiteSpace(tokenFilePath))
                throw new InvalidOperationException($"'{displayName}'의 JSON 키 파일을 선택하세요.");

            var resolvedPath = ResolveComparablePath(tokenFilePath);
            if (!File.Exists(resolvedPath))
                throw new InvalidOperationException($"'{displayName}'의 JSON 키 파일을 찾을 수 없습니다: {resolvedPath}");

            if (!seenNames.Add(displayName))
                throw new InvalidOperationException($"중복된 마켓명입니다: {displayName}");

            if (!seenPaths.Add(resolvedPath))
                throw new InvalidOperationException($"같은 JSON 키 파일이 중복되었습니다: {resolvedPath}");

            normalized.Add(new Cafe24MarketEntry
            {
                DisplayName = displayName,
                TokenFilePath = tokenFilePath
            });
        }

        return normalized;
    }

    private static string BuildCafe24MarketJson(string existingJson, IReadOnlyCollection<Cafe24MarketEntry> markets)
    {
        if (markets.Count == 0)
            return "";

        var cafe24 = new JObject();
        if (!string.IsNullOrWhiteSpace(existingJson))
        {
            try
            {
                var existing = JObject.Parse(existingJson);
                CopyIfPresent(existing, cafe24, "DefaultShippingCompanyCode");
                CopyIfPresent(existing, cafe24, "OrderFetchDays");
                CopyIfPresent(existing, cafe24, "ApiVersion");
            }
            catch (JsonException)
            {
            }
        }

        cafe24["Markets"] = new JArray(markets.Select(market => new JObject
        {
            ["Enabled"] = true,
            ["DisplayName"] = market.DisplayName,
            ["TokenFilePath"] = market.TokenFilePath
        }));

        return cafe24.ToString(Formatting.Indented);
    }

    private static void CopyIfPresent(JObject source, JObject target, string propertyName)
    {
        var token = source[propertyName];
        if (token == null || token.Type == JTokenType.Null)
            return;

        target[propertyName] = token.DeepClone();
    }

    private static string ResolveComparablePath(string path)
    {
        var expandedPath = Environment.ExpandEnvironmentVariables(path.Trim());
        return Path.IsPathRooted(expandedPath)
            ? expandedPath
            : Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, expandedPath));
    }

    private static string ReadString(JObject? section, string propertyName, string fallback)
    {
        var value = section?[propertyName]?.ToString();
        return string.IsNullOrWhiteSpace(value) ? fallback : value;
    }

    private static void ApplyGoogleSettings(JObject config, UserSettingsDraft draft)
    {
        if (string.IsNullOrWhiteSpace(draft.GoogleCredentialPath) &&
            string.IsNullOrWhiteSpace(draft.GoogleSpreadsheetId) &&
            string.IsNullOrWhiteSpace(draft.GoogleDefaultSheetName))
            return;

        var google = config["GoogleSheets"] as JObject ?? new JObject();
        config["GoogleSheets"] = google;

        if (!string.IsNullOrWhiteSpace(draft.GoogleCredentialPath))
            google["CredentialPath"] = draft.GoogleCredentialPath;
        if (!string.IsNullOrWhiteSpace(draft.GoogleSpreadsheetId))
            google["SpreadsheetId"] = draft.GoogleSpreadsheetId;
        if (!string.IsNullOrWhiteSpace(draft.GoogleDefaultSheetName))
            google["DefaultSheetName"] = draft.GoogleDefaultSheetName;
    }

    private static void ApplyJsonSection(JObject config, string sectionName, string json)
    {
        if (string.IsNullOrWhiteSpace(json))
            return;

        config[sectionName] = JObject.Parse(json);
    }

    private static void ValidateOptionalJson(string json, string label)
    {
        if (string.IsNullOrWhiteSpace(json))
            return;

        try
        {
            JObject.Parse(json);
        }
        catch (JsonException ex)
        {
            throw new InvalidOperationException($"{label} JSON 형식이 올바르지 않습니다: {ex.Message}");
        }
    }

    private static string Protect(string value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return "";

        var plainBytes = Encoding.UTF8.GetBytes(value);
        var protectedBytes = ProtectedData.Protect(plainBytes, null, DataProtectionScope.CurrentUser);
        return Convert.ToBase64String(protectedBytes);
    }

    private static string Unprotect(string value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return "";

        try
        {
            var protectedBytes = Convert.FromBase64String(value);
            var plainBytes = ProtectedData.Unprotect(protectedBytes, null, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(plainBytes);
        }
        catch
        {
            return "";
        }
    }

    private static string Now()
    {
        return DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss");
    }
}
