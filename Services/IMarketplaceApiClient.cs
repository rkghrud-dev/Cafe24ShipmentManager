using Cafe24ShipmentManager.Models;

namespace Cafe24ShipmentManager.Services;

public interface IMarketplaceApiClient
{
    string SourceKey { get; }
    string DisplayName { get; }
    string DefaultShippingCompanyCode { get; }
    int DefaultOrderFetchDays { get; }

    Task<List<Cafe24Order>> FetchRecentOrders(DateTime startDt, DateTime endDt, IProgress<string>? progress = null, string? orderStatus = null);
    Task<(bool success, string responseBody, int statusCode)> PushTrackingNumber(Cafe24Order order, string trackingNumber, string shippingCompanyCode);
    string ResolveShippingCompanyCode(string? shippingCompanyName);
}
