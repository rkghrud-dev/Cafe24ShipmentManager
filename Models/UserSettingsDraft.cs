namespace Cafe24ShipmentManager.Models;

public sealed class UserSettingsDraft
{
    public string GoogleCredentialPath { get; set; } = "";
    public string GoogleSpreadsheetId { get; set; } = "";
    public string GoogleDefaultSheetName { get; set; } = "";
    public string Cafe24Json { get; set; } = "";
    public string CoupangJson { get; set; } = "";

    public bool HasMarketplaceConfig =>
        !string.IsNullOrWhiteSpace(Cafe24Json) ||
        !string.IsNullOrWhiteSpace(CoupangJson);
}
