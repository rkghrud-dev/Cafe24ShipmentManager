namespace Cafe24ShipmentManager.Models;

public sealed class Cafe24TokenStatus
{
    public string DisplayName { get; set; } = "";
    public string MallId { get; set; } = "";
    public string TokenFilePath { get; set; } = "";
    public string ClientId { get; set; } = "";
    public string ClientSecret { get; set; } = "";
    public string RefreshToken { get; set; } = "";
    public string RedirectUri { get; set; } = "";
    public string Scope { get; set; } = "";
    public string ApiVersion { get; set; } = "2025-12-01";
    public string ShopNo { get; set; } = "1";
    public DateTime UpdatedAt { get; set; }
    public DateTime RefreshTokenUpdatedAt { get; set; }
    public string StatusMessage { get; set; } = "";

    public bool HasTokenFile => !string.IsNullOrWhiteSpace(TokenFilePath);
    public bool HasRefreshToken => !string.IsNullOrWhiteSpace(RefreshToken);
    public DateTime AccessExpiresAt => UpdatedAt == default ? DateTime.MinValue : UpdatedAt.AddHours(2);
    public DateTime RefreshExpiresAt
    {
        get
        {
            var baseline = RefreshTokenUpdatedAt == default ? UpdatedAt : RefreshTokenUpdatedAt;
            return baseline == default ? DateTime.MinValue : baseline.AddDays(14);
        }
    }

    public TimeSpan AccessRemaining => AccessExpiresAt == DateTime.MinValue ? TimeSpan.Zero : AccessExpiresAt - DateTime.Now;
    public TimeSpan RefreshRemaining => RefreshExpiresAt == DateTime.MinValue ? TimeSpan.Zero : RefreshExpiresAt - DateTime.Now;
    public bool NeedsRefreshWarning => HasRefreshToken && RefreshRemaining.TotalDays <= 5;
}
