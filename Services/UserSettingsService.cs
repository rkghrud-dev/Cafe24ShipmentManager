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
        return new UserSettingsDraft
        {
            GoogleCredentialPath = "credentials.json",
            GoogleSpreadsheetId = "",
            GoogleDefaultSheetName = "출고정보",
            Cafe24Json = GetCafe24SampleJson(),
            CoupangJson = GetCoupangSampleJson()
        };
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

        if (!IsAdminUser(user))
        {
            effective["Cafe24"] = new JObject();
            effective["Coupang"] = new JObject();
        }

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

    private static string GetCafe24SampleJson()
    {
        var sample = new JObject
        {
            ["Markets"] = new JArray
            {
                new JObject
                {
                    ["Enabled"] = true,
                    ["DisplayName"] = "내 Cafe24 몰",
                    ["MallId"] = "YOUR_MALL_ID",
                    ["ClientId"] = "YOUR_CLIENT_ID",
                    ["ClientSecret"] = "YOUR_CLIENT_SECRET",
                    ["AccessToken"] = "",
                    ["RefreshToken"] = "",
                    ["TokenFilePath"] = "%USERPROFILE%\\Desktop\\key\\cafe24_token.json"
                }
            },
            ["DefaultShippingCompanyCode"] = "0019",
            ["OrderFetchDays"] = 14
        };

        return sample.ToString(Formatting.Indented);
    }

    private static string GetCoupangSampleJson()
    {
        var sample = new JObject
        {
            ["Enabled"] = true,
            ["DisplayName"] = "내 쿠팡 마켓",
            ["VendorId"] = "YOUR_COUPANG_VENDOR_ID",
            ["AccessKey"] = "YOUR_COUPANG_ACCESS_KEY",
            ["SecretKey"] = "YOUR_COUPANG_SECRET_KEY",
            ["ApiBaseUrl"] = "https://api-gateway.coupang.com",
            ["DefaultShippingCompanyCode"] = "CJGLS",
            ["OrderFetchDays"] = 14,
            ["FetchStatuses"] = new JArray("ACCEPT", "INSTRUCT")
        };

        return sample.ToString(Formatting.Indented);
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
