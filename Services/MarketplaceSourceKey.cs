namespace Cafe24ShipmentManager.Services;

internal static class MarketplaceSourceKey
{
    private const string CoupangPrefix = "coupang:";

    public static string FromCoupangVendorId(string vendorId)
    {
        return $"{CoupangPrefix}{vendorId.Trim()}";
    }

    public static bool IsCoupang(string? sourceKey)
    {
        return !string.IsNullOrWhiteSpace(sourceKey) &&
               sourceKey.StartsWith(CoupangPrefix, StringComparison.OrdinalIgnoreCase);
    }

    public static string ExtractCoupangVendorId(string? sourceKey)
    {
        if (!IsCoupang(sourceKey))
            return string.Empty;

        return sourceKey![CoupangPrefix.Length..];
    }
}
