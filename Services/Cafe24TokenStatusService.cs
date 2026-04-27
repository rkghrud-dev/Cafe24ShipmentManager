using Cafe24ShipmentManager.Models;
using Newtonsoft.Json.Linq;

namespace Cafe24ShipmentManager.Services;

public sealed class Cafe24TokenStatusService
{
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
            status.AccessToken = ReadString(json, "AccessToken", "");
            status.ApiVersion = ReadString(json, "ApiVersion", "2025-12-01");
            status.UpdatedAt = ReadDateTime(json, "UpdatedAt");
            status.StatusMessage = BuildStatusMessage(status);
            return status;
        }
        catch (Exception ex)
        {
            status.StatusMessage = $"토큰 파일 오류: {ex.Message}";
            return status;
        }
    }

    private static string BuildStatusMessage(Cafe24TokenStatus status)
    {
        if (!status.HasAccessToken)
            return "Access Token 없음 - 외부 인증 필요";
        if (status.AccessExpiresAt == DateTime.MinValue)
            return "UpdatedAt 없음 - 외부 인증 필요";
        if (status.AccessRemaining.TotalSeconds <= 0)
            return "Access Token 만료 - 외부 인증 필요";
        if (status.NeedsAccessWarning)
            return $"곧 만료: {FormatRemaining(status.AccessRemaining)}";
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
        var value = json[propertyName]?.ToString();
        if (string.IsNullOrWhiteSpace(value))
            return DateTime.MinValue;

        return DateTime.TryParse(value, out var parsed)
            ? parsed
            : DateTime.MinValue;
    }

    private static string FormatRemaining(TimeSpan value)
    {
        if (value.TotalSeconds <= 0)
            return "만료";
        if (value.TotalHours < 1)
            return $"{Math.Max(1, (int)Math.Ceiling(value.TotalMinutes))}분";
        return $"{Math.Max(1, (int)Math.Ceiling(value.TotalHours))}시간";
    }
}