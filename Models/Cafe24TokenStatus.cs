namespace Cafe24ShipmentManager.Models;

public sealed class Cafe24TokenStatus
{
    public string DisplayName { get; set; } = "";
    public string MallId { get; set; } = "";
    public string TokenFilePath { get; set; } = "";
    public string AccessToken { get; set; } = "";
    public string ApiVersion { get; set; } = "2025-12-01";
    public DateTime UpdatedAt { get; set; }
    public string StatusMessage { get; set; } = "";

    public bool HasTokenFile => !string.IsNullOrWhiteSpace(TokenFilePath);
    public bool HasAccessToken => !string.IsNullOrWhiteSpace(AccessToken);
    public DateTime AccessExpiresAt => UpdatedAt == default ? DateTime.MinValue : UpdatedAt.AddHours(2);
    public TimeSpan AccessRemaining => AccessExpiresAt == DateTime.MinValue ? TimeSpan.Zero : AccessExpiresAt - DateTime.Now;
    public bool NeedsAccessWarning => HasAccessToken && AccessRemaining.TotalMinutes <= 30;
}