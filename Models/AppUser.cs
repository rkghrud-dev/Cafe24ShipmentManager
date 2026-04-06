namespace Cafe24ShipmentManager.Models;

public sealed class AppUser
{
    public long Id { get; set; }
    public string UserName { get; set; } = "";
    public string DisplayName { get; set; } = "";
    public string PasswordHash { get; set; } = "";
    public string PasswordSalt { get; set; } = "";
    public bool IsActive { get; set; } = true;
    public string CreatedAt { get; set; } = "";
    public string LastLoginAt { get; set; } = "";
    public string RememberTokenHash { get; set; } = "";
    public string RememberTokenSalt { get; set; } = "";
    public string RememberTokenIssuedAt { get; set; } = "";

    public string EffectiveDisplayName => string.IsNullOrWhiteSpace(DisplayName) ? UserName : DisplayName;
}
