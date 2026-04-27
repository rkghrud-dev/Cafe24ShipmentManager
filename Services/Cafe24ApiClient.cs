using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.RegularExpressions;
using Cafe24ShipmentManager.Models;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace Cafe24ShipmentManager.Services;

public class Cafe24Config
{
    public bool Enabled { get; set; } = true;
    public string DisplayName { get; set; } = "";
    public string MallId { get; set; } = "";
    public string AccessToken { get; set; } = "";
    public string ClientId { get; set; } = "";
    public string ClientSecret { get; set; } = "";
    public string RefreshToken { get; set; } = "";
    public string ApiVersion { get; set; } = "2023-03-01";
    public string DefaultShippingCompanyCode { get; set; } = "0019"; // CJ대한통운
    public int OrderFetchDays { get; set; } = 14;
    public string? ConfigFilePath { get; set; }
    public string RedirectUri { get; set; } = "";
    public string ShopNo { get; set; } = "1";
    public string Scope { get; set; } = "";
    public string TokenFilePath { get; set; } = "";
}
public class Cafe24ApiClient : IMarketplaceApiClient
{
    private readonly HttpClient _http;
    private readonly Cafe24Config _config;
    private readonly AppLogger _log;
    private const int MaxRetries = 3;
    // 택배사 코드 매핑 (Cafe24 기준)
    // 이 쇼핑몰에 등록된 택배사 코드 (admin/carriers API 기준)
    public static readonly Dictionary<string, string> ShippingCompanyCodes = new()
    {
        { "CJ대한통운", "0006" },
        { "한진택배", "0018" },
        { "롯데글로벌로지스", "0079" },
        { "롯데택배", "0079" },
        { "로젠택배", "0004" },
        { "우체국택배", "0012" },
        { "자체배송", "0001" },
    };

    

    public Cafe24ApiClient(Cafe24Config config, AppLogger logger)
    {
        _config = config;
        _log = logger;
        _http = new HttpClient
        {
            BaseAddress = new Uri($"https://{config.MallId}.cafe24api.com/api/v2/"),
            Timeout = TimeSpan.FromSeconds(30)
        };
        ApplyAccessTokenHeader();
        ApplyApiVersionHeader();
        _http.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
    }

    private void ApplyAccessTokenHeader()
    {
        _http.DefaultRequestHeaders.Authorization = string.IsNullOrWhiteSpace(_config.AccessToken)
            ? null
            : new AuthenticationHeaderValue("Bearer", _config.AccessToken);
    }

    private bool ReloadAccessTokenFromFile()
    {
        var path = ResolveComparablePath(_config.TokenFilePath);
        if (string.IsNullOrWhiteSpace(path) || !File.Exists(path))
            return false;

        try
        {
            var json = JObject.Parse(File.ReadAllText(path));
            var latestAccessToken = json["AccessToken"]?.ToString();
            if (string.IsNullOrWhiteSpace(latestAccessToken) ||
                string.Equals(latestAccessToken, _config.AccessToken, StringComparison.Ordinal))
                return false;

            _config.AccessToken = latestAccessToken;
            _config.TokenFilePath = path;

            var latestRefreshToken = json["RefreshToken"]?.ToString();
            if (!string.IsNullOrWhiteSpace(latestRefreshToken))
                _config.RefreshToken = latestRefreshToken;

            var latestApiVersion = json["ApiVersion"]?.ToString();
            if (!string.IsNullOrWhiteSpace(latestApiVersion))
                _config.ApiVersion = latestApiVersion;

            var latestShopNo = json["ShopNo"]?.ToString();
            if (!string.IsNullOrWhiteSpace(latestShopNo))
                _config.ShopNo = latestShopNo;

            var latestScope = json["Scope"]?.ToString();
            if (!string.IsNullOrWhiteSpace(latestScope))
                _config.Scope = latestScope;

            ApplyAccessTokenHeader();
            ApplyApiVersionHeader();
            _log.Info($"[{ResolveMarketDisplayName()}] 최신 Cafe24 JSON Access Token 재로드: {Path.GetFileName(path)}");
            return true;
        }
        catch (Exception ex)
        {
            _log.Warn($"[{ResolveMarketDisplayName()}] Cafe24 JSON 재로드 실패: {ex.Message}");
            return false;
        }
    }
    public string SourceKey => _config.MallId;
    public string MallId => _config.MallId;
    public string TokenFilePath => _config.TokenFilePath;
    public string DisplayName => ResolveMarketDisplayName();
    public string DefaultShippingCompanyCode => _config.DefaultShippingCompanyCode;
    public int DefaultOrderFetchDays => _config.OrderFetchDays;

    /// <summary>
    /// 날짜 범위로 주문 목록 조회 (페이징 처리)
    /// </summary>
    /// <summary>
    /// 날짜 범위 + 주문상태로 주문 목록 조회 (페이징 처리)
    /// orderStatus: null이면 전체, "N20"이면 배송준비중 등
    /// </summary>
    public async Task<List<Cafe24Order>> FetchRecentOrders(DateTime startDt, DateTime endDt, IProgress<string>? progress = null, string? orderStatus = null)
    {
        var orders = new List<Cafe24Order>();
        var startDate = startDt.ToString("yyyy-MM-dd");
        var endDate = endDt.ToString("yyyy-MM-dd");
        int offset = 0;
        const int limit = 100;
        int page = 0;

        while (true)
        {
            page++;
            var statusLabel = orderStatus != null ? $", 상태={orderStatus}" : "";
            progress?.Report($"Cafe24 주문 조회 중... (페이지 {page}{statusLabel})");

            var url = $"admin/orders?start_date={startDate}&end_date={endDate}&limit={limit}&offset={offset}&embed=receivers,items";
            if (!string.IsNullOrEmpty(orderStatus))
                url += $"&order_status={orderStatus}";
            var response = await ExecuteWithRetry(() => _http.GetAsync(url));

            if (response == null || !response.IsSuccessStatusCode)
            {
                var body = response != null ? await response.Content.ReadAsStringAsync() : "no response";
                _log.Error($"주문 조회 실패: {response?.StatusCode} - {body}");
                break;
            }

            var json = await response.Content.ReadAsStringAsync();
            var jObj = JObject.Parse(json);
            var ordersArray = jObj["orders"] as JArray;

            if (ordersArray == null || ordersArray.Count == 0)
                break;

            foreach (var o in ordersArray)
            {
                var orderId = o["order_id"]?.ToString() ?? "";

                // items 안의 각 주문 아이템을 개별 행으로
                var items = o["items"] as JArray;
                if (items != null)
                {
                    var matchedItemCount = 0;
                    foreach (var item in items)
                    {
                        var itemStatus = item["order_status"]?.ToString() ?? "";
                        if (!string.IsNullOrWhiteSpace(orderStatus) &&
                            !string.Equals(itemStatus, orderStatus, StringComparison.OrdinalIgnoreCase))
                            continue;

                        orders.Add(ParseOrder(o, item, orderId));
                        matchedItemCount++;
                    }

                    if (matchedItemCount == 0 && string.IsNullOrWhiteSpace(orderStatus))
                        orders.Add(ParseOrder(o, null, orderId));
                }
                else if (string.IsNullOrWhiteSpace(orderStatus) ||
                         string.Equals(o["order_status"]?.ToString(), orderStatus, StringComparison.OrdinalIgnoreCase))
                {
                    orders.Add(ParseOrder(o, null, orderId));
                }
            }

            if (ordersArray.Count < limit)
                break;

            offset += limit;
        }

        _log.Info($"[{ResolveMarketDisplayName()}] Cafe24 주문 {orders.Count}건 조회 완료 ({startDate} ~ {endDate})");
        return orders;
    }

    private Cafe24Order ParseOrder(JToken order, JToken? item, string orderId)
    {
        // 수령인 정보: receivers 배열(embed) → 최상위 필드 → billing_name 순서로 탐색
        var receivers = order["receivers"] as JArray;
        var receiver = receivers?.FirstOrDefault();

        var receiverName = receiver?["name"]?.ToString()
                          ?? receiver?["receiver_name"]?.ToString()
                          ?? order["receiver_name"]?.ToString()
                          ?? order["buyer_name"]?.ToString()
                          ?? order["billing_name"]?.ToString() ?? "";

        var receiverPhone = receiver?["cellphone"]?.ToString()
                           ?? receiver?["receiver_cellphone"]?.ToString()
                           ?? order["receiver_cellphone"]?.ToString()
                           ?? order["buyer_cellphone"]?.ToString() ?? "";

        var receiverPhone2 = receiver?["phone"]?.ToString()
                            ?? receiver?["receiver_phone"]?.ToString()
                            ?? order["receiver_phone"]?.ToString()
                            ?? order["buyer_phone"]?.ToString() ?? "";

        return new Cafe24Order
        {
            MallId = _config.MallId,
            MarketName = ResolveMarketDisplayName(),
            OrderId = orderId,
            OrderItemCode = item?["order_item_code"]?.ToString() ?? "",
            RecipientName = receiverName,
            RecipientCellPhone = PhoneNormalizer.Normalize(receiverPhone),
            RecipientPhone = PhoneNormalizer.Normalize(receiverPhone2),
            OrderStatus = item?["order_status"]?.ToString() ?? order["order_status"]?.ToString() ?? "",
            ProductName = item?["product_name"]?.ToString() ?? "",
            OrderAmount = decimal.TryParse(item?["product_price"]?.ToString(), out var amt) ? amt : 0,
            Quantity = int.TryParse(item?["quantity"]?.ToString(), out var qty) ? qty : 0,
            OrderDate = order["order_date"]?.ToString() ?? "",
            ShippingCode = item?["shipping_code"]?.ToString() ?? "",
            RawJson = order.ToString(),
            CachedAt = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")
        };
    }

    /// <summary>
    /// 주문에 송장번호 입력 + 배송중 처리
    /// </summary>
    public Task<(bool success, string responseBody, int statusCode)> PushTrackingNumber(Cafe24Order order, string trackingNumber, string shippingCompanyCode)
    {
        return PushTrackingNumber(order.OrderId, order.OrderItemCode, trackingNumber, shippingCompanyCode);
    }

    /// <summary>
    /// 주문에 송장번호 입력 + 배송대기 처리
    /// </summary>
    public Task<(bool success, string responseBody, int statusCode)> PushDeliveryWaiting(Cafe24Order order, string trackingNumber, string shippingCompanyCode)
    {
        return PushShipmentStatus(order.OrderId, order.OrderItemCode, trackingNumber, shippingCompanyCode, "standby", "배송대기");
    }

    public string ResolveShippingCompanyCode(string? shippingCompanyName)
    {
        if (string.IsNullOrWhiteSpace(shippingCompanyName))
            return _config.DefaultShippingCompanyCode;

        foreach (var kv in ShippingCompanyCodes)
        {
            if (shippingCompanyName.Contains(kv.Key, StringComparison.OrdinalIgnoreCase)) return kv.Value;
        }

        return _config.DefaultShippingCompanyCode;
    }

    public Task<(bool success, string responseBody, int statusCode)> PushTrackingNumber(
        string orderId, string orderItemCode, string trackingNumber, string shippingCompanyCode)
    {
        return PushShipmentStatus(orderId, orderItemCode, trackingNumber, shippingCompanyCode, "shipping", "송장 반영");
    }

    private async Task<(bool success, string responseBody, int statusCode)> PushShipmentStatus(
        string orderId,
        string orderItemCode,
        string trackingNumber,
        string shippingCompanyCode,
        string status,
        string actionLabel)
    {
        try
        {
            // Cafe24 배송 정보 등록 API: POST /admin/orders/{order_id}/shipments
            var shipmentsUrl = $"admin/orders/{orderId}/shipments";

            var payload = new
            {
                shop_no = 1,
                request = new
                {
                    order_item_code = new[] { orderItemCode },
                    tracking_no = trackingNumber,
                    shipping_company_code = shippingCompanyCode,
                    status
                }
            };

            var jsonPayload = JsonConvert.SerializeObject(payload);
            _log.Info($"[{ResolveMarketDisplayName()}] {actionLabel} 요청: {orderId} → {shipmentsUrl}, body={jsonPayload}");

            var resp = await ExecuteWithRetry(() =>
            {
                var req = new HttpRequestMessage(HttpMethod.Post, shipmentsUrl)
                {
                    Content = new StringContent(jsonPayload, Encoding.UTF8, "application/json")
                };
                return _http.SendAsync(req);
            });

            var respBody = resp != null ? await resp.Content.ReadAsStringAsync() : "no response";
            var statusCode = (int)(resp?.StatusCode ?? 0);
            var success = resp?.IsSuccessStatusCode ?? false;

            if (success)
                _log.Info($"[{ResolveMarketDisplayName()}] {actionLabel} 성공: {orderId} → {trackingNumber}");
            else
                _log.Error($"[{ResolveMarketDisplayName()}] {actionLabel} 실패: {orderId} → {statusCode}: {respBody}");

            return (success, respBody, statusCode);
        }
        catch (Exception ex)
        {
            _log.Error($"[{ResolveMarketDisplayName()}] {actionLabel} 예외: {orderId}", ex);
            return (false, ex.Message, 0);
        }
    }

    private async Task<HttpResponseMessage?> ExecuteWithRetry(Func<Task<HttpResponseMessage>> action)
    {
        var reauthorizedForScope = false;
        var adjustedApiVersion = false;
        var retriedAfterJsonReload = false;

        for (int i = 0; i < MaxRetries; i++)
        {
            try
            {
                ReloadAccessTokenFromFile();
                var resp = await action();

                if (resp.StatusCode == HttpStatusCode.Unauthorized)
                {
                    var body = await resp.Content.ReadAsStringAsync();
                    if (IsInvalidAccessTokenResponse(body))
                    {
                        if (!retriedAfterJsonReload && ReloadAccessTokenFromFile())
                        {
                            retriedAfterJsonReload = true;
                            resp.Dispose();
                            _log.Info($"[{ResolveMarketDisplayName()}] 갱신된 Cafe24 JSON 적용 후 API 재시도");
                            continue;
                        }

                        _log.Warn($"[{ResolveMarketDisplayName()}] Access Token 만료/무효 감지. 자동 갱신은 비활성화되어 있습니다. Cafe24Auth에서 JSON을 갱신하면 다음 요청에서 다시 읽습니다.");
                    }
                    else
                    {
                        _log.Warn($"[{ResolveMarketDisplayName()}] Cafe24 인증 오류 감지. Cafe24Auth에서 JSON을 갱신한 뒤 다시 시도하세요.");
                    }

                    return resp;
                }

                if (resp.StatusCode == HttpStatusCode.Forbidden && !reauthorizedForScope)
                {
                    var body = await resp.Content.ReadAsStringAsync();
                    if (body.Contains("insufficient_scope", StringComparison.OrdinalIgnoreCase))
                    {
                        _log.Warn($"[{ResolveMarketDisplayName()}] scope 부족 감지. Cafe24 인증을 다시 확인하세요.");
                        return resp;
                    }
                }

                if (resp.StatusCode == HttpStatusCode.BadRequest && !adjustedApiVersion)
                {
                    var body = await resp.Content.ReadAsStringAsync();
                    if (TryAdjustApiVersion(body))
                    {
                        adjustedApiVersion = true;
                        ReloadAccessTokenFromFile();
                        resp = await action();
                    }
                }
                // Rate limit 처리
                if (resp.StatusCode == HttpStatusCode.TooManyRequests)
                {
                    var retryAfter = resp.Headers.RetryAfter?.Delta ?? TimeSpan.FromSeconds(2);
                    _log.Warn($"Rate limited. {retryAfter.TotalSeconds}초 후 재시도 ({i + 1}/{MaxRetries})");
                    await Task.Delay(retryAfter);
                    continue;
                }

                return resp;
            }
            catch (Exception ex)
            {
                _log.Warn($"API 호출 실패 (시도 {i + 1}/{MaxRetries}): {ex.Message}");
                if (i < MaxRetries - 1)
                    await Task.Delay(TimeSpan.FromSeconds(1 * (i + 1)));
            }
        }
        return null;
    }

    private static bool IsInvalidAccessTokenResponse(string responseBody)
    {
        return responseBody.Contains("invalid_token", StringComparison.OrdinalIgnoreCase) ||
               responseBody.Contains("access_token", StringComparison.OrdinalIgnoreCase);
    }
    private string ResolveMarketDisplayName()
    {
        return string.IsNullOrWhiteSpace(_config.DisplayName) ? _config.MallId : _config.DisplayName;
    }

    private void ApplyApiVersionHeader()
    {
        const string headerName = "X-Cafe24-Api-Version";
        var apiVersion = string.IsNullOrWhiteSpace(_config.ApiVersion)
            ? "2025-12-01"
            : _config.ApiVersion.Trim();

        _config.ApiVersion = apiVersion;
        _http.DefaultRequestHeaders.Remove(headerName);
        _http.DefaultRequestHeaders.Add(headerName, apiVersion);
    }

    private bool TryAdjustApiVersion(string responseBody)
    {
        if (string.IsNullOrWhiteSpace(responseBody))
            return false;

        var match = Regex.Match(
            responseBody,
            @"default value for the app version is (?<version>\d{4}-\d{2}-\d{2})",
            RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

        if (!match.Success)
            return false;

        var newVersion = match.Groups["version"].Value;
        if (string.IsNullOrWhiteSpace(newVersion) ||
            string.Equals(newVersion, _config.ApiVersion, StringComparison.OrdinalIgnoreCase))
            return false;

        var oldVersion = _config.ApiVersion;
        _config.ApiVersion = newVersion;
        ApplyApiVersionHeader();
        SaveTokensToConfig();
        _log.Warn($"[{ResolveMarketDisplayName()}] Cafe24 API 버전 자동 조정: {oldVersion} -> {newVersion}");
        return true;
    }
    private void SaveTokensToConfig(bool markTokenRefresh = false)
    {
        try
        {
            Cafe24SharedTokenStore.Save(_config, markTokenRefresh);
            _log.Info($"공유 Cafe24 토큰 파일 저장 완료: {_config.TokenFilePath}");

            var configPath = _config.ConfigFilePath;
            if (string.IsNullOrEmpty(configPath) || !File.Exists(configPath)) return;

            var text = File.ReadAllText(configPath);
            var json = JObject.Parse(text);
            var cafe24 = json["Cafe24"] as JObject;
            if (cafe24 == null)
            {
                cafe24 = new JObject();
                json["Cafe24"] = cafe24;
            }

            var markets = cafe24["Markets"] as JArray;
            if (markets != null && markets.Count > 0)
            {
                var marketConfig = FindOrCreateMarketConfigEntry(markets, _config);
                marketConfig["DisplayName"] = ResolveMarketDisplayName();
                marketConfig["MallId"] = _config.MallId;
                marketConfig["ApiVersion"] = _config.ApiVersion;
                marketConfig["TokenFilePath"] = _config.TokenFilePath;
            }
            else
            {
                cafe24["DisplayName"] = ResolveMarketDisplayName();
                cafe24["ApiVersion"] = _config.ApiVersion;
                cafe24["TokenFilePath"] = _config.TokenFilePath;
            }

            File.WriteAllText(configPath, json.ToString(Formatting.Indented));
        }
        catch (Exception ex)
        {
            _log.Warn($"공유 Cafe24 토큰 저장 실패: {ex.Message}");
        }
    }

    private static JObject FindOrCreateMarketConfigEntry(JArray markets, Cafe24Config config)
    {
        var target = markets
            .OfType<JObject>()
            .FirstOrDefault(item =>
                string.Equals(ResolveComparablePath(item["TokenFilePath"]?.ToString()), ResolveComparablePath(config.TokenFilePath), StringComparison.OrdinalIgnoreCase) ||
                string.Equals(item["MallId"]?.ToString(), config.MallId, StringComparison.OrdinalIgnoreCase) ||
                string.Equals(item["DisplayName"]?.ToString(), config.DisplayName, StringComparison.OrdinalIgnoreCase));

        if (target != null)
            return target;

        target = new JObject();
        markets.Add(target);
        return target;
    }

    private static string ResolveComparablePath(string? path)
    {
        if (string.IsNullOrWhiteSpace(path)) return "";

        var expandedPath = Environment.ExpandEnvironmentVariables(path);
        return Path.IsPathRooted(expandedPath)
            ? expandedPath
            : Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, expandedPath));
    }
}
