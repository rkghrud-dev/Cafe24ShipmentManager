using System.Globalization;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using Cafe24ShipmentManager.Models;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace Cafe24ShipmentManager.Services;

public sealed class CoupangConfig
{
    public bool Enabled { get; set; } = true;
    public string DisplayName { get; set; } = "";
    public string VendorId { get; set; } = "";
    public string AccessKey { get; set; } = "";
    public string SecretKey { get; set; } = "";
    public string ApiBaseUrl { get; set; } = "https://api-gateway.coupang.com";
    public string DefaultShippingCompanyCode { get; set; } = "CJGLS";
    public int OrderFetchDays { get; set; } = 14;
    public List<string> FetchStatuses { get; set; } = new() { "ACCEPT", "INSTRUCT" };
}

public sealed class CoupangApiClient : IMarketplaceApiClient
{
    private readonly HttpClient _http;
    private readonly CoupangConfig _config;
    private readonly AppLogger _log;

    public static readonly Dictionary<string, string> ShippingCompanyCodes = new(StringComparer.OrdinalIgnoreCase)
    {
        { "CJ대한통운", "CJGLS" },
        { "대한통운", "CJGLS" },
        { "한진택배", "HANJIN" },
        { "롯데택배", "HYUNDAI" },
        { "롯데글로벌로지스", "LOTTEGLOBAL" },
        { "로젠택배", "KGB" },
        { "우체국택배", "EPOST" },
        { "우체국", "EPOST" },
        { "경동택배", "KDEXP" },
        { "대신택배", "DAESIN" },
        { "자체배송", "DIRECT" },
        { "직접배송", "DIRECT" },
        { "직접수령", "DIRECT" },
        { "업체직송", "DIRECT" }
    };

    public CoupangApiClient(CoupangConfig config, AppLogger logger)
    {
        _config = config;
        _log = logger;
        _http = new HttpClient
        {
            BaseAddress = new Uri(config.ApiBaseUrl.TrimEnd('/') + "/"),
            Timeout = TimeSpan.FromSeconds(30)
        };
        _http.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
    }

    public string SourceKey => MarketplaceSourceKey.FromCoupangVendorId(_config.VendorId);
    public string DisplayName => string.IsNullOrWhiteSpace(_config.DisplayName) ? _config.VendorId : _config.DisplayName;
    public string DefaultShippingCompanyCode => string.IsNullOrWhiteSpace(_config.DefaultShippingCompanyCode)
        ? "CJGLS"
        : _config.DefaultShippingCompanyCode.Trim();
    public int DefaultOrderFetchDays => _config.OrderFetchDays;

    public async Task<List<Cafe24Order>> FetchRecentOrders(DateTime startDt, DateTime endDt, IProgress<string>? progress = null, string? orderStatus = null)
    {
        var orders = new List<Cafe24Order>();
        var statuses = (_config.FetchStatuses ?? new List<string>())
            .Where(s => !string.IsNullOrWhiteSpace(s))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        if (!string.IsNullOrWhiteSpace(orderStatus))
            statuses = new List<string> { orderStatus };
        if (statuses.Count == 0)
            statuses.Add("ACCEPT");

        var fromDate = startDt.Date;
        var toDate = endDt.Date;

        foreach (var status in statuses)
        {
            string? nextToken = null;
            var page = 0;

            do
            {
                page++;
                progress?.Report($"쿠팡 주문 조회 중... ({fromDate:yyyy-MM-dd} ~ {toDate:yyyy-MM-dd}, 상태={status}, 페이지 {page})");

                var query = new List<KeyValuePair<string, string>>
                {
                    new("createdAtFrom", ToKstIsoOffset(fromDate)),
                    new("createdAtTo", ToKstIsoOffset(toDate)),
                    new("status", status),
                    new("maxPerPage", "50")
                };
                if (!string.IsNullOrWhiteSpace(nextToken))
                    query.Add(new("nextToken", nextToken));

                var path = $"v2/providers/openapi/apis/api/v5/vendors/{_config.VendorId}/ordersheets";
                var response = await SendAsync(HttpMethod.Get, path, query, null);
                var body = response != null ? await response.Content.ReadAsStringAsync() : "no response";

                if (response == null || !response.IsSuccessStatusCode)
                {
                    _log.Error($"[{DisplayName}] 쿠팡 주문 조회 실패: {response?.StatusCode} - {body}");
                    break;
                }

                var root = JObject.Parse(body);
                var data = root["data"] as JArray;
                if (data == null || data.Count == 0)
                    break;

                foreach (var entry in data.OfType<JObject>())
                    orders.AddRange(ParseOrderSheet(entry));

                nextToken = root["nextToken"]?.ToString();
            }
            while (!string.IsNullOrWhiteSpace(nextToken));
        }

        var distinct = orders
            .GroupBy(order => $"{order.MallId}|{order.OrderId}|{order.OrderItemCode}|{order.ShippingCode}", StringComparer.OrdinalIgnoreCase)
            .Select(g => g.First())
            .ToList();

        _log.Info($"[{DisplayName}] 쿠팡 주문 {distinct.Count}건 조회 완료 ({startDt:yyyy-MM-dd} ~ {endDt:yyyy-MM-dd})");
        return distinct;
    }
    public async Task<(bool success, string responseBody, int statusCode)> PushTrackingNumber(Cafe24Order order, string trackingNumber, string shippingCompanyCode)
    {
        if (!TryParseLong(order.ShippingCode, out var shipmentBoxId))
            return (false, $"shipmentBoxId가 없어 송장 반영을 진행할 수 없습니다. ({order.ShippingCode})", 0);
        if (!TryParseLong(order.OrderId, out var orderId))
            return (false, $"orderId가 없어 송장 반영을 진행할 수 없습니다. ({order.OrderId})", 0);
        if (!TryParseLong(order.OrderItemCode, out var vendorItemId))
            return (false, $"vendorItemId가 없어 송장 반영을 진행할 수 없습니다. ({order.OrderItemCode})", 0);

        try
        {
            if (string.Equals(order.OrderStatus, "ACCEPT", StringComparison.OrdinalIgnoreCase))
            {
                var acknowledge = await AcknowledgeAsync(shipmentBoxId);
                if (!acknowledge.success)
                    return acknowledge;
            }

            var normalizedTrackingNumber = NormalizeInvoiceNumber(trackingNumber);
            if (!string.Equals(normalizedTrackingNumber, trackingNumber, StringComparison.Ordinal))
                _log.Info($"[{DisplayName}] 쿠팡 송장번호 정규화: {trackingNumber} → {normalizedTrackingNumber}");

            return await UploadInvoiceAsync(orderId, shipmentBoxId, vendorItemId, normalizedTrackingNumber, shippingCompanyCode);
        }
        catch (Exception ex)
        {
            _log.Error($"[{DisplayName}] 쿠팡 송장 반영 예외: {order.OrderId}", ex);
            return (false, ex.Message, 0);
        }
    }

    public string ResolveShippingCompanyCode(string? shippingCompanyName)
    {
        if (string.IsNullOrWhiteSpace(shippingCompanyName))
            return DefaultShippingCompanyCode;

        foreach (var pair in ShippingCompanyCodes)
        {
            if (shippingCompanyName.Contains(pair.Key, StringComparison.OrdinalIgnoreCase))
                return pair.Value;
        }

        return DefaultShippingCompanyCode;
    }

    private static string NormalizeInvoiceNumber(string trackingNumber)
    {
        if (string.IsNullOrWhiteSpace(trackingNumber))
            return string.Empty;

        return new string(trackingNumber.Where(char.IsLetterOrDigit).ToArray());
    }

    private async Task<(bool success, string responseBody, int statusCode)> AcknowledgeAsync(long shipmentBoxId)
    {
        var path = $"v2/providers/openapi/apis/api/v4/vendors/{_config.VendorId}/ordersheets/acknowledgement";
        var payload = new
        {
            vendorId = _config.VendorId,
            shipmentBoxIds = new[] { shipmentBoxId }
        };

        var response = await SendAsync(HttpMethod.Put, path, null, payload);
        var body = response != null ? await response.Content.ReadAsStringAsync() : "no response";
        var statusCode = (int)(response?.StatusCode ?? 0);

        if (response == null || !response.IsSuccessStatusCode)
        {
            _log.Error($"[{DisplayName}] 쿠팡 상품준비중 처리 실패: {shipmentBoxId} → {statusCode}: {body}");
            return (false, body, statusCode);
        }

        _log.Info($"[{DisplayName}] 쿠팡 상품준비중 처리 성공: {shipmentBoxId}");
        return (true, body, statusCode);
    }

    private async Task<(bool success, string responseBody, int statusCode)> UploadInvoiceAsync(long orderId, long shipmentBoxId, long vendorItemId, string trackingNumber, string shippingCompanyCode)
    {
        var path = $"v2/providers/openapi/apis/api/v4/vendors/{_config.VendorId}/orders/invoices";
        var payload = new
        {
            vendorId = _config.VendorId,
            orderSheetInvoiceApplyDtos = new[]
            {
                new
                {
                    shipmentBoxId,
                    orderId,
                    vendorItemId,
                    deliveryCompanyCode = shippingCompanyCode,
                    invoiceNumber = trackingNumber,
                    splitShipping = false,
                    preSplitShipped = false,
                    estimatedShippingDate = ""
                }
            }
        };

        var response = await SendAsync(HttpMethod.Post, path, null, payload);
        var body = response != null ? await response.Content.ReadAsStringAsync() : "no response";
        var statusCode = (int)(response?.StatusCode ?? 0);
        var success = response?.IsSuccessStatusCode ?? false;

        if (success)
        {
            try
            {
                var root = JObject.Parse(body);
                success = root["data"]?["responseCode"]?.Value<int?>() is null or 0;
            }
            catch
            {
                success = true;
            }
        }

        if (success)
            _log.Info($"[{DisplayName}] 쿠팡 송장 반영 성공: {orderId} / {shipmentBoxId} → {trackingNumber}");
        else
            _log.Error($"[{DisplayName}] 쿠팡 송장 반영 실패: {orderId} / {shipmentBoxId} → {statusCode}: {body}");

        return (success, body, statusCode);
    }

    private IEnumerable<Cafe24Order> ParseOrderSheet(JObject orderSheet)
    {
        var receiver = orderSheet["receiver"] as JObject;
        var orderItems = orderSheet["orderItems"] as JArray;
        if (orderItems == null || orderItems.Count == 0)
            yield break;

        var rawJson = orderSheet.ToString(Formatting.None);
        var orderId = orderSheet["orderId"]?.ToString() ?? string.Empty;
        var orderedAt = orderSheet["orderedAt"]?.ToString() ?? string.Empty;
        var orderStatus = orderSheet["status"]?.ToString() ?? string.Empty;
        var recipientName = receiver?["name"]?.ToString() ?? string.Empty;
        var safeNumber = receiver?["safeNumber"]?.ToString() ?? receiver?["receiverNumber"]?.ToString() ?? string.Empty;
        var receiverNumber = receiver?["receiverNumber"]?.ToString() ?? string.Empty;

        foreach (var item in orderItems.OfType<JObject>())
        {
            var sellerProductName = item["sellerProductName"]?.ToString() ?? string.Empty;
            var sellerProductItemName = item["sellerProductItemName"]?.ToString() ?? string.Empty;
            var vendorItemName = item["vendorItemName"]?.ToString() ?? string.Empty;
            var productName = string.Join(" / ", new[] { sellerProductName, sellerProductItemName, vendorItemName }
                .Where(value => !string.IsNullOrWhiteSpace(value))
                .Distinct(StringComparer.OrdinalIgnoreCase));

            yield return new Cafe24Order
            {
                MallId = SourceKey,
                MarketName = DisplayName,
                OrderId = orderId,
                OrderItemCode = item["vendorItemId"]?.ToString() ?? string.Empty,
                ShippingCode = item["shipmentBoxId"]?.ToString() ?? orderSheet["shipmentBoxId"]?.ToString() ?? string.Empty,
                RecipientName = recipientName,
                RecipientCellPhone = PhoneNormalizer.Normalize(safeNumber),
                RecipientPhone = PhoneNormalizer.Normalize(receiverNumber),
                OrderStatus = orderStatus,
                ProductName = productName,
                OrderAmount = ParseDecimal(item["orderPrice"]),
                Quantity = ParseInt(item["shippingCount"]) ?? ParseInt(item["orderCount"]) ?? 0,
                OrderDate = orderedAt,
                RawJson = rawJson,
                CachedAt = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture)
            };
        }
    }

    private async Task<HttpResponseMessage?> SendAsync(HttpMethod method, string path, IReadOnlyList<KeyValuePair<string, string>>? query, object? body)
    {
        var queryString = BuildQueryString(query);
        var signedDate = DateTime.UtcNow.ToString("yyMMdd'T'HHmmss'Z'", CultureInfo.InvariantCulture);
        var signature = CreateSignature(signedDate, method.Method, "/" + path.TrimStart('/'), queryString);
        var authorization = $"CEA algorithm=HmacSHA256, access-key={_config.AccessKey}, signed-date={signedDate}, signature={signature}";
        var requestUri = string.IsNullOrWhiteSpace(queryString) ? path : $"{path}?{queryString}";

        var request = new HttpRequestMessage(method, requestUri);
        request.Headers.TryAddWithoutValidation("Authorization", authorization);
        request.Headers.TryAddWithoutValidation("X-EXTENDED-TIMEOUT", "90000");

        if (body != null)
        {
            var json = JsonConvert.SerializeObject(body);
            request.Content = new StringContent(json, Encoding.UTF8, "application/json");
        }

        return await _http.SendAsync(request);
    }

    private string CreateSignature(string signedDate, string method, string path, string queryString)
    {
        var message = signedDate + method.ToUpperInvariant() + path + queryString;
        using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(_config.SecretKey));
        var hash = hmac.ComputeHash(Encoding.UTF8.GetBytes(message));
        return Convert.ToHexString(hash).ToLowerInvariant();
    }

    private static string BuildQueryString(IReadOnlyList<KeyValuePair<string, string>>? query)
    {
        if (query == null || query.Count == 0)
            return string.Empty;

        return string.Join("&", query.Select(pair => $"{Uri.EscapeDataString(pair.Key)}={Uri.EscapeDataString(pair.Value)}"));
    }

    private static string ToKstIsoOffset(DateTime value)
    {
        return DateTime.SpecifyKind(value, DateTimeKind.Unspecified)
            .ToString("yyyy-MM-dd", CultureInfo.InvariantCulture) + "+09:00";
    }

    private static bool TryParseLong(string? value, out long result)
    {
        return long.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out result);
    }

    private static int? ParseInt(JToken? token)
    {
        return int.TryParse(token?.ToString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var parsed)
            ? parsed
            : null;
    }

    private static decimal ParseDecimal(JToken? token)
    {
        if (token == null)
            return 0;

        if (decimal.TryParse(token.ToString(), NumberStyles.Any, CultureInfo.InvariantCulture, out var plain))
            return plain;

        if (token is JObject money && decimal.TryParse(money["amount"]?.ToString(), NumberStyles.Any, CultureInfo.InvariantCulture, out var amount))
            return amount;

        return 0;
    }
}





