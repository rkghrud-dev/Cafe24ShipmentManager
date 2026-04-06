namespace Cafe24ShipmentManager.Models;

public sealed class AppUserSettings
{
    public long UserId { get; set; }
    public string ProtectedGoogleCredentialPath { get; set; } = "";
    public string GoogleSpreadsheetId { get; set; } = "";
    public string GoogleDefaultSheetName { get; set; } = "";
    public string ProtectedCafe24Json { get; set; } = "";
    public string ProtectedCoupangJson { get; set; } = "";
    public string UpdatedAt { get; set; } = "";
}
