using System.Net.Http.Headers;
using System.Text;
using Cafe24ShipmentManager.Models;
using Newtonsoft.Json.Linq;

namespace Cafe24ShipmentManager.Services;

public sealed class Cafe24TokenRefreshService
{
    private readonly AppLogger _log;

    public Cafe24TokenRefreshService(AppLogger log)
    {
        _log = log;
    }

    public IReadOnlyList<Cafe24TokenStatus> LoadStatuses(IEnumerable<Cafe24ApiClient> clients)
    {
        return clients.Select(LoadStatus).ToList();
    }

    public Cafe24TokenStatus LoadStatus(Cafe24ApiClient client)
    {
        return LoadStatus(client.DisplayName, client.MallId, client.TokenFilePath);
    }

    public Cafe24TokenStatus LoadStatus(string displayName, string mallId, string tokenFilePath)
    {
        var resolvedPath = ResolvePath(tokenFilePath);
        var status = new Cafe24TokenStatus
        {
            DisplayName = displayName,
            MallId = mallId,
            TokenFilePath = resolvedPath,
            StatusMessage = "토큰 파일 확인 필요"
        };

        if (string.IsNullOrWhiteSpace(resolvedPath) || !File.Exists(resolvedPath))
        {
            status.StatusMessage = "토큰 파일 없음";
            return status;
        }

        try
        {
            var json = JObject.Parse(File.ReadAllText(resolvedPath));
            status.MallId = ReadString(json, "MallId", mallId);
            status.ClientId = ReadString(json, "ClientId", "");
            status.ClientSecret = ReadString(json, "ClientSecret", "");
            status.RefreshToken = ReadString(json, "RefreshToken", "");
            status.RedirectUri = ReadString(json, "RedirectUri", "");
            status.Scope = ReadString(json, "Scope", "");
            status.ApiVersion = ReadString(json, "ApiVersion", "2025-12-01");
            status.ShopNo = ReadString(json, "ShopNo", "1");
            status.UpdatedAt = ReadDateTime(json, "UpdatedAt");
            status.RefreshTokenUpdatedAt = ReadDateTime(json, "RefreshTokenUpdatedAt");
            status.StatusMessage = BuildStatusMessage(status);
            return status;
        }
        catch (Exception ex)
        {
            status.StatusMessage = $"토큰 파일 오류: {ex.Message}";
            return status;
        }
    }

    public async Task<(bool success, string message, Cafe24TokenStatus? status)> RefreshAsync(Cafe24TokenStatus status)
    {
        if (string.IsNullOrWhiteSpace(status.MallId))
            return (false, "Mall ID가 없습니다.", null);
        if (string.IsNullOrWhiteSpace(status.ClientId) || string.IsNullOrWhiteSpace(status.ClientSecret))
            return (false, "Client ID/Secret이 없습니다.", null);
        if (string.IsNullOrWhiteSpace(status.RefreshToken))
            return (false, "Refresh Token이 없습니다.", null);
        if (string.IsNullOrWhiteSpace(status.TokenFilePath) || !File.Exists(status.TokenFilePath))
            return (false, "토큰 JSON 파일을 찾을 수 없습니다.", null);

        try
        {
            var tokenUrl = $"https://{status.MallId}.cafe24api.com/api/v2/oauth/token";
            var authBytes = Encoding.ASCII.GetBytes($"{status.ClientId}:{status.ClientSecret}");
            var authHeader = Convert.ToBase64String(authBytes);

            using var http = new HttpClient();
            var request = new HttpRequestMessage(HttpMethod.Post, tokenUrl)
            {
                Headers = { Authorization = new AuthenticationHeaderValue("Basic", authHeader) },
                Content = new FormUrlEncodedContent(new Dictionary<string, string>
                {
                    { "grant_type", "refresh_token" },
                    { "refresh_token", status.RefreshToken }
                })
            };

            var response = await http.SendAsync(request);
            var body = await response.Content.ReadAsStringAsync();
            if (!response.IsSuccessStatusCode && body.Contains("Invalid client_secret", StringComparison.OrdinalIgnoreCase))
            {
                _log.Warn($"[{status.DisplayName}] 수동 토큰 갱신 재시도: client_id/client_secret 본문 전송 방식");
                var retryRequest = new HttpRequestMessage(HttpMethod.Post, tokenUrl)
                {
                    Content = new FormUrlEncodedContent(new Dictionary<string, string>
                    {
                        { "grant_type", "refresh_token" },
                        { "refresh_token", status.RefreshToken },
                        { "client_id", status.ClientId },
                        { "client_secret", status.ClientSecret }
                    })
                };

                response = await http.SendAsync(retryRequest);
                body = await response.Content.ReadAsStringAsync();
            }

            if (!response.IsSuccessStatusCode)
            {
                _log.Warn($"[{status.DisplayName}] 수동 토큰 갱신 실패 ({response.StatusCode}): {body}");
                if (body.Contains("invalid refresh_token", StringComparison.OrdinalIgnoreCase) ||
                    body.Contains("invalid_grant", StringComparison.OrdinalIgnoreCase))
                {
                    return (false, $"Refresh Token이 더 이상 유효하지 않습니다. '{status.DisplayName}' 마켓은 재인증이 필요합니다.", null);
                }
                return (false, $"토큰 갱신 실패: {body}", null);
            }

            var json = JObject.Parse(File.ReadAllText(status.TokenFilePath));
            var tokenJson = JObject.Parse(body);
            var now = DateTime.Now;
            var nextRefreshToken = tokenJson["refresh_token"]?.ToString();
            var nextAccessToken = tokenJson["access_token"]?.ToString();
            if (string.IsNullOrWhiteSpace(nextAccessToken))
                return (false, "응답에 access_token이 없습니다.", null);

            json["MallId"] = status.MallId;
            json["ClientId"] = status.ClientId;
            json["ClientSecret"] = status.ClientSecret;
            json["AccessToken"] = nextAccessToken;
            json["RefreshToken"] = string.IsNullOrWhiteSpace(nextRefreshToken) ? status.RefreshToken : nextRefreshToken;
            json["RedirectUri"] = status.RedirectUri;
            json["ApiVersion"] = status.ApiVersion;
            json["ShopNo"] = status.ShopNo;
            json["Scope"] = status.Scope;
            json["UpdatedAt"] = now.ToString("o");
            json["RefreshTokenUpdatedAt"] = now.ToString("o");

            File.WriteAllText(status.TokenFilePath, json.ToString());
            _log.Info($"[{status.DisplayName}] 수동 토큰 갱신 완료: {status.TokenFilePath}");

            var refreshed = LoadStatus(status.DisplayName, status.MallId, status.TokenFilePath);
            refreshed.StatusMessage = "수동 갱신 완료";
            return (true, "토큰 갱신이 완료되었습니다.", refreshed);
        }
        catch (Exception ex)
        {
            _log.Error($"[{status.DisplayName}] 수동 토큰 갱신 예외", ex);
            return (false, $"토큰 갱신 중 오류: {ex.Message}", null);
        }
    }

    private static string BuildStatusMessage(Cafe24TokenStatus status)
    {
        if (!status.HasRefreshToken)
            return "Refresh Token 없음";
        if (status.RefreshExpiresAt == DateTime.MinValue)
            return "만료 정보 없음";
        if (status.RefreshRemaining.TotalDays <= 0)
            return "Refresh Token 만료";
        if (status.RefreshRemaining.TotalDays <= 5)
            return $"경고: {(int)Math.Ceiling(status.RefreshRemaining.TotalDays)}일 남음";
        return "사용 가능";
    }

    private static string ResolvePath(string path)
    {
        if (string.IsNullOrWhiteSpace(path))
            return "";

        var expanded = Environment.ExpandEnvironmentVariables(path.Trim());
        return Path.IsPathRooted(expanded)
            ? expanded
            : Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, expanded));
    }

    private static string ReadString(JObject json, string propertyName, string fallback)
    {
        var value = json[propertyName]?.ToString();
        return string.IsNullOrWhiteSpace(value) ? fallback : value;
    }

    private static DateTime ReadDateTime(JObject json, string propertyName)
    {
        var raw = json[propertyName]?.ToString();
        return DateTime.TryParse(raw, out var value) ? value : default;
    }
}
