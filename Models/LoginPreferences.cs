namespace Cafe24ShipmentManager.Models;

public sealed class LoginPreferences
{
    public int Id { get; set; } = 1;
    public string LastUserName { get; set; } = "";
    public bool SavePassword { get; set; }
    public bool AutoLogin { get; set; }
    public string ProtectedPassword { get; set; } = "";
    public string ProtectedRememberToken { get; set; } = "";
    public string UpdatedAt { get; set; } = "";
}
