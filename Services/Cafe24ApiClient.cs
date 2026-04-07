using System.Net;
using System.Net.Http.Headers;
using System.Text;
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
    // ngrok 불필요 — 프로그램 내장 로컬 콜백 서버 사용
    // Cafe24 개발자 앱 설정에서 리디렉션 URI를 아래 값으로 1회 등록 필요
    private const string LocalCallbackPrefix = "http://localhost:19521/oauth/callback/";
    private const string LocalCallbackUri    = "http://localhost:19521/oauth/callback";
    private const string SharedOAuthScope =
        "mall.read_order,mall.write_order,mall.read_shipping,mall.write_shipping,mall.read_product,mall.write_product";

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
        _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", config.AccessToken);
        _http.DefaultRequestHeaders.Add("X-Cafe24-Api-Version", config.ApiVersion);
        _http.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
    }
    public string SourceKey => _config.MallId;
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

    public async Task<(bool success, string responseBody, int statusCode)> PushTrackingNumber(
        string orderId, string orderItemCode, string trackingNumber, string shippingCompanyCode)
    {
        // Cafe24 배송 정보 업데이트 API
        // PUT /api/v2/admin/orders/{order_id}/items/{order_item_code}/shipments
        // 먼저 shipment 조회 후 업데이트, 없으면 생성

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
                    status = "shipping"
                }
            };

            var jsonPayload = JsonConvert.SerializeObject(payload);
            _log.Info($"[{ResolveMarketDisplayName()}] 송장 반영 요청: {orderId} → {shipmentsUrl}, body={jsonPayload}");

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
                _log.Info($"[{ResolveMarketDisplayName()}] 송장 반영 성공: {orderId} → {trackingNumber}");
            else
                _log.Error($"[{ResolveMarketDisplayName()}] 송장 반영 실패: {orderId} → {statusCode}: {respBody}");

            return (success, respBody, statusCode);
        }
        catch (Exception ex)
        {
            _log.Error($"[{ResolveMarketDisplayName()}] 송장 반영 예외: {orderId}", ex);
            return (false, ex.Message, 0);
        }
    }

    private async Task<HttpResponseMessage?> ExecuteWithRetry(Func<Task<HttpResponseMessage>> action)
    {
        var reauthorizedForScope = false;

        for (int i = 0; i < MaxRetries; i++)
        {
            try
            {
                var resp = await action();

                // 401 → 토큰 갱신 후 재시도
                if (resp.StatusCode == HttpStatusCode.Unauthorized && i == 0)
                {
                    _log.Warn("Access Token 만료 감지, 자동 갱신 시도...");
                    if (await RefreshAccessTokenAsync())
                    {
                        resp = await action();
                        return resp;
                    }
                    return resp;
                }

                if (resp.StatusCode == HttpStatusCode.Forbidden && !reauthorizedForScope)
                {
                    var body = await resp.Content.ReadAsStringAsync();
                    if (body.Contains("insufficient_scope", StringComparison.OrdinalIgnoreCase))
                    {
                        _log.Warn("Access Token scope 부족 감지, OAuth 재인증 시도...");
                        if (await ReauthorizeViaOAuthAsync())
                        {
                            reauthorizedForScope = true;
                            resp = await action();
                        }

                        return resp;
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
    }    /// <summary>
    /// Refresh Token으로 Access Token 자동 갱신 + appsettings.json 저장
    /// </summary>
    private async Task<bool> RefreshAccessTokenAsync()
    {
        if (string.IsNullOrEmpty(_config.ClientId) || string.IsNullOrEmpty(_config.ClientSecret) ||
            string.IsNullOrEmpty(_config.RefreshToken))
        {
            _log.Error("토큰 갱신 불가: ClientId, ClientSecret, RefreshToken 설정을 확인하세요.");
            return false;
        }

        try
        {
            var tokenUrl = $"https://{_config.MallId}.cafe24api.com/api/v2/oauth/token";
            var authBytes = Encoding.ASCII.GetBytes($"{_config.ClientId}:{_config.ClientSecret}");
            var authHeader = Convert.ToBase64String(authBytes);

            using var tokenHttp = new HttpClient();
            var request = new HttpRequestMessage(HttpMethod.Post, tokenUrl)
            {
                Headers = { Authorization = new AuthenticationHeaderValue("Basic", authHeader) },
                Content = new FormUrlEncodedContent(new Dictionary<string, string>
                {
                    { "grant_type", "refresh_token" },
                    { "refresh_token", _config.RefreshToken }
                })
            };

            var resp = await tokenHttp.SendAsync(request);
            var body = await resp.Content.ReadAsStringAsync();

            // 일부 환경에서는 Basic 대신 body(client_id/client_secret) 방식만 허용될 수 있음
            if (!resp.IsSuccessStatusCode && body.Contains("Invalid client_secret", StringComparison.OrdinalIgnoreCase))
            {
                _log.Warn("토큰 갱신 재시도: client_id/client_secret 본문 전송 방식");
                var retryRequest = new HttpRequestMessage(HttpMethod.Post, tokenUrl)
                {
                    Content = new FormUrlEncodedContent(new Dictionary<string, string>
                    {
                        { "grant_type", "refresh_token" },
                        { "refresh_token", _config.RefreshToken },
                        { "client_id", _config.ClientId },
                        { "client_secret", _config.ClientSecret }
                    })
                };

                resp = await tokenHttp.SendAsync(retryRequest);
                body = await resp.Content.ReadAsStringAsync();
            }

            if (!resp.IsSuccessStatusCode)
            {
                _log.Error($"토큰 갱신 실패 ({resp.StatusCode}): {body}");
                _log.Info("OAuth 브라우저 재인증을 시도합니다...");
                return await ReauthorizeViaOAuthAsync();
            }

            var json = JObject.Parse(body);
            var newAccessToken = json["access_token"]?.ToString();
            var newRefreshToken = json["refresh_token"]?.ToString();

            if (string.IsNullOrEmpty(newAccessToken))
            {
                _log.Error($"토큰 갱신 응답에 access_token 없음: {body}");
                return false;
            }

            // 메모리 갱신
            _config.AccessToken = newAccessToken;
            if (!string.IsNullOrEmpty(newRefreshToken))
                _config.RefreshToken = newRefreshToken;

            // HttpClient 헤더 갱신
            _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", newAccessToken);

            _log.Info("Access Token 자동 갱신 성공");

            // appsettings.json 저장
            SaveTokensToConfig();

            return true;
        }
        catch (Exception ex)
        {
            _log.Error("토큰 갱신 예외", ex);
            return false;
        }
    }
    /// <summary>
    /// Refresh Token 만료 시 브라우저 OAuth 재인증으로 새 토큰 발급.
    /// 1순위: localhost:19521 내장 서버로 자동 수신 (ngrok 불필요)
    /// 2순위: 사용자가 브라우저 주소창 URL을 수동으로 붙여넣기
    /// </summary>
    public async Task<bool> ReauthorizeViaOAuthAsync()
    {
        if (string.IsNullOrEmpty(_config.ClientId))
        {
            _log.Error("OAuth 재인증 불가: ClientId가 비어있음");
            return false;
        }

        var redirectUri = ResolveOAuthRedirectUri();
        var state = Guid.NewGuid().ToString("N")[..8];
        var authUrl = $"https://{_config.MallId}.cafe24api.com/api/v2/oauth/authorize" +
            $"?response_type=code&client_id={_config.ClientId}" +
            $"&redirect_uri={Uri.EscapeDataString(redirectUri)}" +
            $"&scope={SharedOAuthScope}&state={state}";

        _log.Info($"OAuth 재인증 RedirectUri: {redirectUri}");

        var useLocalhostCapture = CanCaptureOAuthCodeViaLocalhost(redirectUri);

        // 1순위: localhost 내장 서버로 자동 수신
        string? code = null;
        bool browserOpened = false;
        if (useLocalhostCapture)
        {
            try
            {
                (code, browserOpened) = await TryCaptureOAuthCodeViaLocalhost(authUrl, state);
            }
            catch (Exception ex)
            {
                _log.Warn($"localhost 콜백 자동수신 실패: {ex.Message}");
            }
        }

        // 2순위: 수동 붙여넣기 fallback
        if (string.IsNullOrEmpty(code))
        {
            if (!browserOpened)
                System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(authUrl) { UseShellExecute = true });

            if (!useLocalhostCapture)
                _log.Info("현재 RedirectUri는 외부 주소이므로 브라우저 주소창 URL 전체를 바로 붙여넣어 주세요.");

            code = PromptForAuthorizationCode();
        }

        if (string.IsNullOrEmpty(code))
        {
            _log.Warn("OAuth 재인증 취소됨");
            return false;
        }

        return await ExchangeCodeForTokenAsync(code);
    }

    /// <summary>
    /// localhost:19521에 임시 HTTP 서버를 열어 OAuth 콜백 코드를 자동 수신
    /// </summary>
    private async Task<(string? code, bool browserOpened)> TryCaptureOAuthCodeViaLocalhost(string authUrl, string expectedState)
    {
        var listener = new HttpListener();
        try
        {
            listener.Prefixes.Add(LocalCallbackPrefix);
            listener.Start();
        }
        catch (HttpListenerException ex)
        {
            _log.Warn($"localhost:19521 포트 사용 불가 ({ex.ErrorCode}), 수동 입력으로 전환");
            return (null, false);
        }

        System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(authUrl) { UseShellExecute = true });
        _log.Info("브라우저에서 Cafe24 로그인 완료 시 자동으로 인증 코드가 수신됩니다 (최대 3분 대기)...");

        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromMinutes(3));
            HttpListenerContext ctx;
            try
            {
                var ctxTask = listener.GetContextAsync();
                await Task.WhenAny(ctxTask, Task.Delay(Timeout.Infinite, cts.Token));
                if (!ctxTask.IsCompleted)
                {
                    _log.Warn("OAuth 콜백 대기 시간 초과 (3분), 수동 입력으로 전환");
                    return (null, true);
                }
                ctx = await ctxTask;
            }
            catch (OperationCanceledException)
            {
                _log.Warn("OAuth 콜백 대기 시간 초과 (3분), 수동 입력으로 전환");
                return (null, true);
            }

            // 쿼리 파라미터 파싱
            var query = ctx.Request.Url?.Query?.TrimStart('?') ?? "";
            string? code = null, returnedState = null;
            foreach (var pair in query.Split('&'))
            {
                var kv = pair.Split('=', 2);
                if (kv.Length != 2) continue;
                var key = Uri.UnescapeDataString(kv[0]);
                var val = Uri.UnescapeDataString(kv[1]);
                if (key == "code")  code = val;
                if (key == "state") returnedState = val;
            }

            // 브라우저에 결과 페이지 전달
            var success = !string.IsNullOrEmpty(code) && returnedState == expectedState;
            var html = success
                ? "<html><head><meta charset='utf-8'></head><body style='font-family:sans-serif;text-align:center;padding-top:60px'>" +
                  "<h2 style='color:#2e7d32'>인증 완료!</h2><p>이 창을 닫고 프로그램으로 돌아가세요.</p>" +
                  "<script>setTimeout(()=>window.close(),2000)</script></body></html>"
                : "<html><head><meta charset='utf-8'></head><body style='font-family:sans-serif;text-align:center;padding-top:60px'>" +
                  "<h2 style='color:#c62828'>인증 실패</h2><p>코드 또는 state가 올바르지 않습니다. 창을 닫고 다시 시도하세요.</p></body></html>";
            var bytes = Encoding.UTF8.GetBytes(html);
            ctx.Response.ContentType = "text/html; charset=utf-8";
            ctx.Response.ContentLength64 = bytes.Length;
            await ctx.Response.OutputStream.WriteAsync(bytes);
            ctx.Response.Close();

            if (!success)
            {
                _log.Warn($"OAuth state 불일치 또는 코드 없음 (예상={expectedState}, 수신={returnedState})");
                return (null, true);
            }

            _log.Info("OAuth 콜백 자동 수신 완료");
            return (code, true);
        }
        finally
        {
            listener.Stop();
        }
    }

    private async Task<bool> ExchangeCodeForTokenAsync(string code)
    {
        try
        {
            var redirectUri = ResolveOAuthRedirectUri();
            var tokenUrl = $"https://{_config.MallId}.cafe24api.com/api/v2/oauth/token";
            var authBytes = Encoding.ASCII.GetBytes($"{_config.ClientId}:{_config.ClientSecret}");
            var authHeader = Convert.ToBase64String(authBytes);

            using var tokenHttp = new HttpClient();
            var request = new HttpRequestMessage(HttpMethod.Post, tokenUrl)
            {
                Headers = { Authorization = new AuthenticationHeaderValue("Basic", authHeader) },
                Content = new FormUrlEncodedContent(new Dictionary<string, string>
                {
                    { "grant_type", "authorization_code" },
                    { "code", code },
                    { "redirect_uri", redirectUri }
                })
            };

            var resp = await tokenHttp.SendAsync(request);
            var body = await resp.Content.ReadAsStringAsync();

            if (!resp.IsSuccessStatusCode)
            {
                _log.Error($"OAuth 토큰 교환 실패 ({resp.StatusCode}): {body}");
                MessageBox.Show($"토큰 발급 실패:\n{body}", "오류", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return false;
            }

            var json = JObject.Parse(body);
            var newAccessToken = json["access_token"]?.ToString();
            var newRefreshToken = json["refresh_token"]?.ToString();

            if (string.IsNullOrEmpty(newAccessToken))
            {
                _log.Error($"토큰 응답에 access_token 없음: {body}");
                return false;
            }

            _config.AccessToken = newAccessToken;
            if (!string.IsNullOrEmpty(newRefreshToken))
                _config.RefreshToken = newRefreshToken;
            _config.RedirectUri = redirectUri;

            _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", newAccessToken);
            _log.Info("OAuth 재인증 성공 — 새 토큰 발급 완료");

            SaveTokensToConfig();
            return true;
        }
        catch (Exception ex)
        {
            _log.Error("OAuth 토큰 교환 예외", ex);
            return false;
        }
    }

    private static string? PromptForAuthorizationCode()
    {
        using var dlg = new Form
        {
            Text = "Cafe24 OAuth 재인증 (수동)",
            Size = new Size(600, 230),
            StartPosition = FormStartPosition.CenterScreen,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false, MinimizeBox = false
        };

        var lbl = new Label
        {
            Text = "브라우저에서 Cafe24 로그인 후 페이지 오류가 떠도 괜찮습니다.\n" +
                   "브라우저 주소창의 URL 전체를 복사하여 아래에 붙여넣으세요.\n" +
                   "주소창에 ?code=XXXXX&state=... 가 포함되어 있으면 됩니다.",
            Location = new Point(12, 12), AutoSize = true
        };
        var txt = new TextBox { Location = new Point(12, 88), Width = 565, Height = 24 };
        var btnOk = new Button { Text = "확인", DialogResult = DialogResult.OK, Location = new Point(415, 130), Width = 80, Height = 30 };
        var btnCancel = new Button { Text = "취소", DialogResult = DialogResult.Cancel, Location = new Point(499, 130), Width = 80, Height = 30 };
        dlg.AcceptButton = btnOk;
        dlg.CancelButton = btnCancel;
        dlg.Controls.AddRange(new Control[] { lbl, txt, btnOk, btnCancel });

        if (dlg.ShowDialog() != DialogResult.OK || string.IsNullOrWhiteSpace(txt.Text))
            return null;

        var input = txt.Text.Trim();
        if (input.Contains("code="))
        {
            try
            {
                var queryStr = input.Contains('?') ? input[(input.IndexOf('?') + 1)..] : input;
                foreach (var pair in queryStr.Split('&'))
                {
                    var kv = pair.Split('=', 2);
                    if (kv.Length == 2 && kv[0] == "code")
                        return Uri.UnescapeDataString(kv[1]);
                }
            }
            catch { }
        }
        return input; // 코드 직접 입력
    }

    private static bool CanCaptureOAuthCodeViaLocalhost(string redirectUri)
    {
        if (!Uri.TryCreate(redirectUri, UriKind.Absolute, out var uri))
            return false;

        return string.Equals(uri.Scheme, Uri.UriSchemeHttp, StringComparison.OrdinalIgnoreCase) &&
               (string.Equals(uri.Host, "localhost", StringComparison.OrdinalIgnoreCase) || uri.Host == "127.0.0.1") &&
               uri.Port == 19521 &&
               string.Equals(uri.AbsolutePath.TrimEnd('/'), "/oauth/callback", StringComparison.OrdinalIgnoreCase);
    }

    private string ResolveOAuthRedirectUri()
    {
        return string.IsNullOrWhiteSpace(_config.RedirectUri)
            ? LocalCallbackUri
            : _config.RedirectUri.Trim();
    }

    private string ResolveMarketDisplayName()
    {
        return string.IsNullOrWhiteSpace(_config.DisplayName) ? _config.MallId : _config.DisplayName;
    }

    private void SaveTokensToConfig()
    {
        try
        {
            Cafe24SharedTokenStore.Save(_config);
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
                marketConfig["TokenFilePath"] = _config.TokenFilePath;
            }
            else
            {
                cafe24["DisplayName"] = ResolveMarketDisplayName();
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

