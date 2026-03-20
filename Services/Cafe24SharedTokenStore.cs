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
        config.MallId = Pick(json, "MallId", config.MallId);
        config.ClientId = Pick(json, "ClientId", config.ClientId);
        config.ClientSecret = Pick(json, "ClientSecret", config.ClientSecret);
        config.AccessToken = Pick(json, "AccessToken", config.AccessToken);
        config.RefreshToken = Pick(json, "RefreshToken", config.RefreshToken);
        config.RedirectUri = Pick(json, "RedirectUri", config.RedirectUri);
        config.ApiVersion = Pick(json, "ApiVersion", config.ApiVersion);
        config.ShopNo = Pick(json, "ShopNo", config.ShopNo);
        config.Scope = Pick(json, "Scope", config.Scope);
        log?.Info($"공유 Cafe24 토큰 파일 로드: {path}");
    }

    public static void Save(Cafe24Config config)
    {
        var path = ResolvePath(config.TokenFilePath);
        var directory = Path.GetDirectoryName(path);
        if (!string.IsNullOrWhiteSpace(directory))
        {
            Directory.CreateDirectory(directory);
        }

        var json = new JObject
        {
            ["MallId"] = config.MallId,
            ["ClientId"] = config.ClientId,
            ["ClientSecret"] = config.ClientSecret,
            ["AccessToken"] = config.AccessToken,
            ["RefreshToken"] = config.RefreshToken,
            ["RedirectUri"] = config.RedirectUri,
            ["ApiVersion"] = config.ApiVersion,
            ["ShopNo"] = config.ShopNo,
            ["Scope"] = config.Scope,
            ["UpdatedAt"] = DateTime.Now.ToString("o")
        };

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
}
