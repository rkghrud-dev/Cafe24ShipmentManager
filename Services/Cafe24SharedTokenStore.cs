using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace Cafe24ShipmentManager.Services;

internal static class Cafe24SharedTokenStore
{
    private sealed class TokenFileLock : IDisposable
    {
        private readonly FileStream _stream;

        public TokenFileLock(FileStream stream)
        {
            _stream = stream;
        }

        public void Dispose()
        {
            try
            {
                _stream.Unlock(0, 1);
            }
            catch
            {
            }

            _stream.Dispose();
        }
    }

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
        SyncTokenCopies(config, path, json);
    }

    public static IDisposable AcquireTokenFileLock(string? tokenFilePath, AppLogger? log = null)
    {
        var path = ResolvePath(tokenFilePath);
        var directory = Path.GetDirectoryName(path);
        if (!string.IsNullOrWhiteSpace(directory))
        {
            Directory.CreateDirectory(directory);
        }

        var lockPath = path + ".lock";
        var started = DateTime.UtcNow;
        while (true)
        {
            try
            {
                var stream = new FileStream(lockPath, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.ReadWrite);
                stream.Lock(0, 1);
                if ((DateTime.UtcNow - started).TotalSeconds >= 1)
                    log?.Info($"Cafe24 토큰 잠금 획득: {lockPath}");
                return new TokenFileLock(stream);
            }
            catch (IOException)
            {
                if ((DateTime.UtcNow - started).TotalSeconds > 60)
                    throw new TimeoutException($"Cafe24 토큰 잠금 대기 시간 초과: {lockPath}");
                Thread.Sleep(250);
            }
        }
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

    private static void SyncTokenCopies(Cafe24Config config, string sourcePath, JObject sourceJson)
    {
        if (string.IsNullOrWhiteSpace(config.MallId) || string.IsNullOrWhiteSpace(config.AccessToken))
            return;

        foreach (var candidate in EnumerateTokenCopyCandidates(sourcePath))
        {
            try
            {
                if (string.Equals(
                        Path.GetFullPath(candidate),
                        Path.GetFullPath(sourcePath),
                        StringComparison.OrdinalIgnoreCase))
                    continue;

                var json = JObject.Parse(File.ReadAllText(candidate));
                var mallId = PickAny(json, "MallId", "MALL_ID", "mall_id", "mallId");
                if (!string.Equals(mallId, config.MallId, StringComparison.OrdinalIgnoreCase))
                    continue;

                CopyIfPresent(sourceJson, json, "MallId", "MALL_ID", "mall_id", "mallId");
                CopyIfPresent(sourceJson, json, "ClientId", "CLIENT_ID", "client_id", "clientId");
                CopyIfPresent(sourceJson, json, "ClientSecret", "CLIENT_SECRET", "client_secret", "clientSecret");
                CopyIfPresent(sourceJson, json, "AccessToken", "ACCESS_TOKEN", "access_token", "accessToken");
                CopyIfPresent(sourceJson, json, "RefreshToken", "REFRESH_TOKEN", "refresh_token", "refreshToken");
                CopyIfPresent(sourceJson, json, "RedirectUri", "REDIRECT_URI", "redirect_uri", "redirectUri");
                CopyIfPresent(sourceJson, json, "ApiVersion", "API_VERSION", "api_version", "apiVersion");
                CopyIfPresent(sourceJson, json, "ShopNo", "SHOP_NO", "shop_no", "shopNo");
                CopyIfPresent(sourceJson, json, "Scope", "SCOPE", "scope");
                CopyIfPresent(sourceJson, json, "UpdatedAt", "UPDATED_AT", "updated_at", "updatedAt");
                CopyIfPresent(sourceJson, json, "RefreshTokenUpdatedAt", "REFRESH_TOKEN_UPDATED_AT", "refresh_token_updated_at", "refreshTokenUpdatedAt");

                File.WriteAllText(candidate, json.ToString(Formatting.Indented));
            }
            catch
            {
            }
        }
    }

    private static IEnumerable<string> EnumerateTokenCopyCandidates(string sourcePath)
    {
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var desktop = Environment.GetFolderPath(Environment.SpecialFolder.Desktop);
        var candidateDirs = new[]
        {
            Path.Combine(desktop, "key"),
            Path.Combine(desktop, "key", "마켓별_키정리", "02_카페24"),
            Path.Combine(AppContext.BaseDirectory, "data", "market_keys"),
            Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "WEBOCRV2_LOCAL", "webocrcludev2", "data", "market_keys")),
        };

        foreach (var dir in candidateDirs)
        {
            if (!Directory.Exists(dir))
                continue;

            foreach (var file in Directory.EnumerateFiles(dir, "cafe24_token*.json", SearchOption.AllDirectories))
            {
                if (seen.Add(file))
                    yield return file;
            }
        }

        if (File.Exists(sourcePath) && seen.Add(sourcePath))
            yield return sourcePath;
    }

    private static string PickAny(JObject json, params string[] propertyNames)
    {
        foreach (var propertyName in propertyNames)
        {
            var value = json[propertyName]?.ToString();
            if (!string.IsNullOrWhiteSpace(value))
                return value;
        }

        return "";
    }

    private static void CopyIfPresent(JObject source, JObject target, params string[] aliases)
    {
        JToken? value = null;
        foreach (var alias in aliases)
        {
            value = source[alias];
            if (value != null && value.Type != JTokenType.Null)
                break;
            value = null;
        }

        if (value == null)
            return;

        foreach (var alias in aliases)
        {
            if (target[alias] != null)
            {
                target[alias] = value.DeepClone();
                return;
            }
        }

        target[aliases[0]] = value.DeepClone();
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
