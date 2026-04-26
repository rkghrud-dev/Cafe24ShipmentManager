using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace Cafe24ShipmentManager.Services;

internal static class Cafe24SharedTokenStore
{
    public static string GetDefaultPath()
    {
        return Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.Desktop),
            "key",
            "cafe24_token.json");
    }

    public static void LoadInto(Cafe24Config config, AppLogger? log = null)
    {
        var path = ResolvePath(config.TokenFilePath);
        config.TokenFilePath = path;
        if (!File.Exists(path))
        {
            log?.Warn($"공유 Cafe24 토큰 파일이 없어 appsettings fallback 사용: {path}");
            return;
        }

        var json = JObject.Parse(File.ReadAllText(path));
        config.MallId = PreferConfigured(config.MallId, json, "MallId");
        config.ClientId = PreferConfigured(config.ClientId, json, "ClientId");
        config.ClientSecret = PreferConfigured(config.ClientSecret, json, "ClientSecret");
        config.AccessToken = Pick(json, "AccessToken", config.AccessToken);
        config.RefreshToken = Pick(json, "RefreshToken", config.RefreshToken);
        config.RedirectUri = PreferConfigured(config.RedirectUri, json, "RedirectUri");
        // Shared token refresh can negotiate a newer per-mall API version.
        // Keep using that stored value on the next launch unless config overrides it later.
        config.ApiVersion = Pick(json, "ApiVersion", config.ApiVersion);
        config.ShopNo = PreferConfigured(config.ShopNo, json, "ShopNo");
        config.Scope = PreferConfigured(config.Scope, json, "Scope");
        log?.Info($"공유 Cafe24 토큰 파일 로드: {path}");
    }

    public static void Save(Cafe24Config config, bool markTokenRefresh = false)
    {
        var path = ResolvePath(config.TokenFilePath);
        var directory = Path.GetDirectoryName(path);
        if (!string.IsNullOrWhiteSpace(directory))
        {
            Directory.CreateDirectory(directory);
        }

        var json = new JObject();
        if (File.Exists(path))
        {
            try
            {
                json = JObject.Parse(File.ReadAllText(path));
            }
            catch
            {
                json = new JObject();
            }
        }

        var previousAccessToken = json["AccessToken"]?.ToString();
        var previousRefreshToken = json["RefreshToken"]?.ToString();
        var accessTokenChanged = !string.Equals(previousAccessToken, config.AccessToken, StringComparison.Ordinal);
        var refreshTokenChanged = !string.Equals(previousRefreshToken, config.RefreshToken, StringComparison.Ordinal);
        var now = DateTime.Now.ToString("o");

        json["MallId"] = config.MallId;
        json["ClientId"] = config.ClientId;
        json["ClientSecret"] = config.ClientSecret;
        json["AccessToken"] = config.AccessToken;
        json["RefreshToken"] = config.RefreshToken;
        json["RedirectUri"] = config.RedirectUri;
        json["ApiVersion"] = config.ApiVersion;
        json["ShopNo"] = config.ShopNo;
        json["Scope"] = config.Scope;

        if (markTokenRefresh || accessTokenChanged || string.IsNullOrWhiteSpace(json["UpdatedAt"]?.ToString()))
            json["UpdatedAt"] = now;

        if (markTokenRefresh || refreshTokenChanged || string.IsNullOrWhiteSpace(json["RefreshTokenUpdatedAt"]?.ToString()))
            json["RefreshTokenUpdatedAt"] = markTokenRefresh || refreshTokenChanged ? now : json["UpdatedAt"]?.ToString() ?? now;

        File.WriteAllText(path, json.ToString(Formatting.Indented));
    }
    private static string ResolvePath(string? path)
    {
        if (string.IsNullOrWhiteSpace(path))
        {
            return GetDefaultPath();
        }

        var expandedPath = Environment.ExpandEnvironmentVariables(path);
        return Path.IsPathRooted(expandedPath)
            ? expandedPath
            : Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, expandedPath));
    }

    private static string Pick(JObject json, string propertyName, string fallback)
    {
        var value = json[propertyName]?.ToString();
        return string.IsNullOrWhiteSpace(value) ? fallback : value;
    }

    private static string PreferConfigured(string configuredValue, JObject json, string propertyName)
    {
        return string.IsNullOrWhiteSpace(configuredValue)
            ? Pick(json, propertyName, configuredValue)
            : configuredValue;
    }
}
