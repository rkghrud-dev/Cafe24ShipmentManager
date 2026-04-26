using Google.Apis.Auth.OAuth2;
using Google.Apis.Auth.OAuth2.Flows;
using Google.Apis.Auth.OAuth2.Responses;
using Google.Apis.Services;
using Google.Apis.Sheets.v4;
using Google.Apis.Util.Store;
using Cafe24ShipmentManager.Models;
using Newtonsoft.Json;
using System.Text.RegularExpressions;

namespace Cafe24ShipmentManager.Services;

public class GoogleSheetsReader
{
    private static readonly string[] GoogleSheetsScopes = { SheetsService.Scope.Spreadsheets };

    private readonly SheetsService _service;
    private readonly AppLogger _log;

    private static readonly string[] PhoneKeywords = {
        "휴대폰", "핸드폰", "수령인휴대폰", "수취인휴대폰",
        "수령인연락처", "수취인연락처", "연락처", "수취인전화",
        "수령인전화", "HP", "Phone", "CellPhone", "전화번호",
        "수령인HP", "수취인HP", "휴대전화"
    };

    private static readonly string[] ShippingCompanyKeywords = {
        "택배사", "배송사", "운송사", "택배", "배송업체", "운송업체"
    };

    private static readonly string[] TrackingKeywords = {
        "진열입고산입송장번호", "진열입고산입송장", "송장번호", "운송장번호", "운송장", "송장"
    };

    private static readonly Regex DateMarkerPattern = new(@"^20\d{2}\.\d{1,2}\.\d{1,2}$", RegexOptions.Compiled);


    private GoogleSheetsReader(SheetsService service, AppLogger logger)
    {
        _service = service;
        _log = logger;
    }

    /// <summary>
    /// OAuth2 인증으로 GoogleSheetsReader 생성 (브라우저 로그인)
    /// </summary>
    public static async Task<GoogleSheetsReader> CreateAsync(string credentialJsonPath, AppLogger logger)
    {
        var tokenPath = Path.Combine(
            Path.GetDirectoryName(credentialJsonPath) ?? ".", "token");

        UserCredential credential;
        try
        {
            credential = await AuthorizeAsync(credentialJsonPath, tokenPath, forceConsent: false);
            await credential.GetAccessTokenForRequestAsync();
        }
        catch (TokenResponseException ex) when (IsInvalidGrant(ex))
        {
            logger.Warn("저장된 Google 토큰이 만료 또는 폐기되었습니다. 토큰 캐시를 삭제하고 재인증합니다.");
            ResetTokenCache(tokenPath, logger);

            credential = await AuthorizeAsync(credentialJsonPath, tokenPath, forceConsent: true);
            await credential.GetAccessTokenForRequestAsync();
        }

        var service = CreateSheetsService(credential);

        logger.Info("Google OAuth2 자격 확인 완료");
        return new GoogleSheetsReader(service, logger);
    }

    private static async Task<UserCredential> AuthorizeAsync(
        string credentialJsonPath,
        string tokenPath,
        bool forceConsent)
    {
        using var stream = new FileStream(credentialJsonPath, FileMode.Open, FileAccess.Read);
        var secrets = GoogleClientSecrets.FromStream(stream).Secrets;
        var dataStore = new FileDataStore(tokenPath, true);

        if (!forceConsent)
        {
            return await GoogleWebAuthorizationBroker.AuthorizeAsync(
                secrets,
                GoogleSheetsScopes,
                "user",
                CancellationToken.None,
                dataStore);
        }

        var initializer = new GoogleAuthorizationCodeFlow.Initializer
        {
            ClientSecrets = secrets,
            Prompt = "consent"
        };

        return await GoogleWebAuthorizationBroker.AuthorizeAsync(
            initializer,
            GoogleSheetsScopes,
            "user",
            CancellationToken.None,
            dataStore);
    }

    private static SheetsService CreateSheetsService(UserCredential credential)
    {
        return new SheetsService(new BaseClientService.Initializer
        {
            HttpClientInitializer = credential,
            ApplicationName = "Cafe24ShipmentManager"
        });
    }

    private static bool IsInvalidGrant(TokenResponseException ex)
    {
        return string.Equals(ex.Error?.Error, "invalid_grant", StringComparison.OrdinalIgnoreCase);
    }

    private static void ResetTokenCache(string tokenPath, AppLogger logger)
    {
        if (!Directory.Exists(tokenPath))
            return;

        try
        {
            foreach (var filePath in Directory.EnumerateFiles(tokenPath, "*", SearchOption.AllDirectories))
                File.SetAttributes(filePath, FileAttributes.Normal);

            Directory.Delete(tokenPath, true);
            logger.Info($"Google 토큰 캐시 초기화: {tokenPath}");
        }
        catch (Exception ex)
        {
            logger.Warn($"Google 토큰 캐시 초기화 실패: {ex.Message}");
        }
    }

    public List<(string title, int sheetId)> GetSheetList(string spreadsheetId)
    {
        var spreadsheet = _service.Spreadsheets.Get(spreadsheetId).Execute();
        return spreadsheet.Sheets
            .Select(s => (s.Properties.Title, s.Properties.SheetId ?? 0))
            .ToList();
    }

    /// <summary>
    /// C열(발주사명)만 빠르게 가져와서 유니크 목록 반환
    /// </summary>
    public List<string> FetchVendorList(string spreadsheetId, string sheetName)
    {
        var range = $"'{sheetName}'!C:C";
        var request = _service.Spreadsheets.Values.Get(spreadsheetId, range);
        request.ValueRenderOption = SpreadsheetsResource.ValuesResource.GetRequest.ValueRenderOptionEnum.FORMATTEDVALUE;
        var response = request.Execute();
        var values = response.Values;
        if (values == null || values.Count < 2) return new();

        var vendors = new HashSet<string>();
        for (int i = 1; i < values.Count; i++) // 1행(헤더) 스킵
        {
            var v = values[i].Count > 0 ? values[i][0]?.ToString()?.Trim() ?? "" : "";
            if (!string.IsNullOrEmpty(v)) vendors.Add(v);
        }

        _log.Info($"발주사 목록 로드: {vendors.Count}개 ('{sheetName}')");
        return vendors.OrderBy(v => v).ToList();
    }

    /// <summary>
    /// 특정 발주사 + 날짜 범위로 필터링해서 읽기
    /// </summary>
    public ExcelReadResult ReadSheetFiltered(string spreadsheetId, string sheetName,
        HashSet<string> selectedVendors, DateTime? startDate, DateTime? endDate)
    {
        var full = ReadSheet(spreadsheetId, sheetName);

        // 발주사 필터
        if (selectedVendors.Count > 0)
            full.Rows = full.Rows.Where(r => selectedVendors.Contains(r.VendorName)).ToList();

        // D열 발주일 기준 날짜 필터
        if (startDate.HasValue || endDate.HasValue)
        {
            var start = startDate?.Date ?? DateTime.MinValue;
            var end = endDate?.Date ?? DateTime.MaxValue;

            full.Rows = full.Rows.Where(r =>
            {
                if (string.IsNullOrEmpty(r.OrderDate)) return false;
                if (DateTime.TryParse(r.OrderDate, out var dt))
                    return dt.Date >= start && dt.Date <= end;
                return false;
            }).ToList();
        }

        full.Vendors = full.Rows.Select(r => r.VendorName).Distinct().OrderBy(v => v).ToList();
        _log.Info($"필터 적용: {full.Rows.Count}행 (발주사 {selectedVendors.Count}개, 기간 {startDate:yyyy-MM-dd}~{endDate:yyyy-MM-dd})");
        return full;
    }

    // 실제 헤더 행을 찾기 위한 키워드 (이 중 2개 이상 포함되면 헤더 행으로 판단)
    private static readonly string[] HeaderKeywords = {
        "발주사", "상품코드", "수령인", "송장", "택배", "주문", "발주일",
        "수취인", "상품명", "연락처", "휴대폰", "배송", "운송장"
    };

    public ExcelReadResult ReadSheet(string spreadsheetId, string sheetName, int? phoneColumnOverride = null)
    {
        var result = new ExcelReadResult();

        var range = $"'{sheetName}'";
        var request = _service.Spreadsheets.Values.Get(spreadsheetId, range);
        request.ValueRenderOption = SpreadsheetsResource.ValuesResource.GetRequest.ValueRenderOptionEnum.FORMATTEDVALUE;

        var response = request.Execute();
        var values = response.Values;

        if (values == null || values.Count < 2)
        {
            _log.Warn($"시트 '{sheetName}'에 데이터가 없거나 부족합니다.");
            return result;
        }

        // 헤더 행 자동 탐지: 처음 10행 중 HeaderKeywords 2개 이상 포함된 행을 헤더로 사용
        int headerRowIndex = 0;
        int maxKeywordHits = 0;
        for (int r = 0; r < Math.Min(values.Count, 10); r++)
        {
            var rowText = string.Join(" ", values[r].Select(c => c?.ToString() ?? ""));
            int hits = HeaderKeywords.Count(k => rowText.Contains(k, StringComparison.OrdinalIgnoreCase));
            if (hits > maxKeywordHits)
            {
                maxKeywordHits = hits;
                headerRowIndex = r;
            }
        }

        if (headerRowIndex > 0)
            _log.Info($"헤더 행 자동탐지: {headerRowIndex + 1}행 (키워드 {maxKeywordHits}개 매칭, 0~{headerRowIndex - 1}행 스킵)");

        // 헤더
        var headerRow = values[headerRowIndex];
        for (int i = 0; i < headerRow.Count; i++)
            result.Headers.Add(headerRow[i]?.ToString()?.Trim() ?? "");

        // 휴대폰 컬럼 찾기
        if (phoneColumnOverride.HasValue)
        {
            result.PhoneColumnIndex = phoneColumnOverride.Value;
            result.PhoneColumnName = result.Headers.Count > phoneColumnOverride.Value
                ? result.Headers[phoneColumnOverride.Value] : $"Column {phoneColumnOverride.Value + 1}";
        }
        else
        {
            for (int i = 0; i < result.Headers.Count; i++)
            {
                var h = result.Headers[i].Replace(" ", "");
                if (PhoneKeywords.Any(k => h.Contains(k, StringComparison.OrdinalIgnoreCase)))
                {
                    result.PhoneColumnIndex = i;
                    result.PhoneColumnName = result.Headers[i];
                    break;
                }
            }

            // 자동 탐색 실패 시 로그 + 폴백
            if (result.PhoneColumnIndex < 0)
            {
                // 디버깅: 모든 헤더 출력
                _log.Warn($"휴대폰 컬럼 자동탐색 실패! 헤더 목록:");
                for (int i = 0; i < result.Headers.Count; i++)
                    _log.Info($"  [{(char)('A' + (i < 26 ? i : 0))}열/{i}] \"{result.Headers[i]}\"");

                // 부분 매칭으로 재탐색 (전화, 폰, 휴대, HP, phone, cell 등)
                var fallbackKeywords = new[] { "전화", "폰", "휴대", "HP", "Phone", "Cell", "연락" };
                for (int i = 0; i < result.Headers.Count; i++)
                {
                    var h = result.Headers[i].Replace(" ", "");
                    if (fallbackKeywords.Any(k => h.Contains(k, StringComparison.OrdinalIgnoreCase)))
                    {
                        result.PhoneColumnIndex = i;
                        result.PhoneColumnName = result.Headers[i] + " (부분매칭)";
                        _log.Info($"휴대폰 컬럼 부분매칭 성공 → {i}열: {result.Headers[i]}");
                        break;
                    }
                }

                // 그래도 실패 시 G열 폴백
                if (result.PhoneColumnIndex < 0 && result.Headers.Count > 6)
                {
                    result.PhoneColumnIndex = 6;
                    result.PhoneColumnName = result.Headers[6] + " (G열 기본값)";
                    _log.Warn($"최종 폴백 → G열 사용: \"{result.Headers[6]}\"");
                }
            }
        }

        // 택배사 컬럼 찾기
        for (int i = 0; i < result.Headers.Count; i++)
        {
            var h = result.Headers[i].Replace(" ", "");
            if (ShippingCompanyKeywords.Any(k => h.Contains(k, StringComparison.OrdinalIgnoreCase)))
            {
                result.ShippingCompanyColumnIndex = i;
                break;
            }
        }

        var trackingColumnIndex = DetectTrackingColumn(result.Headers);

        var vendorSet = new HashSet<string>();

        for (int row = headerRowIndex + 1; row < values.Count; row++)
        {
            var cells = values[row];
            string GetCell(int col) => col < cells.Count ? cells[col]?.ToString()?.Trim() ?? "" : "";

            var vendor = GetCell(2); // C열
            if (string.IsNullOrEmpty(vendor)) continue;

            var tracking = GetCell(trackingColumnIndex >= 0 ? trackingColumnIndex : 11); // L열 기본, cj발주서 진열입고산입 송장번호 우선
            var productCode = GetCell(1);       // B열: 상품코드
            var orderDate = GetCell(3);          // D열: 발주일
            var recipientName = GetCell(5);     // F열: 수령인명
            var phone = result.PhoneColumnIndex >= 0 ? GetCell(result.PhoneColumnIndex) : GetCell(6); // G열: 수령인 휴대폰
            var shippingCompany = result.ShippingCompanyColumnIndex >= 0
                ? GetCell(result.ShippingCompanyColumnIndex) : "";

            var rawDict = new Dictionary<string, string>();
            for (int col = 0; col < result.Headers.Count; col++)
                rawDict[result.Headers[col]] = GetCell(col);

            var normalizedPhone = PhoneNormalizer.Normalize(phone);
            var sourceKey = $"{vendor}|{normalizedPhone}|{tracking}";

            result.Rows.Add(new ShipmentSourceRow
            {
                SourceRowKey = sourceKey,
                VendorName = vendor,
                TrackingNumber = tracking,
                RecipientPhone = normalizedPhone,
                RecipientName = recipientName,
                ProductCode = productCode,
                OrderDate = orderDate,
                ShippingCompany = shippingCompany,
                RawData = JsonConvert.SerializeObject(rawDict),
                ProcessStatus = "pending",
                ImportedAt = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")
            });

            vendorSet.Add(vendor);
        }

        result.Vendors = vendorSet.OrderBy(v => v).ToList();
        _log.Info($"구글시트 '{sheetName}' 읽기 완료: {result.Rows.Count}행, 발주사 {result.Vendors.Count}개");
        return result;
    }
    public PendingShipmentSheetReadResult ReadPendingShipmentSheet(string spreadsheetId, string sheetName)
    {
        var result = new PendingShipmentSheetReadResult();

        var range = $"'{sheetName}'!A1:ZZ30000";
        var request = _service.Spreadsheets.Values.Get(spreadsheetId, range);
        request.ValueRenderOption = SpreadsheetsResource.ValuesResource.GetRequest.ValueRenderOptionEnum.FORMATTEDVALUE;

        var response = request.Execute();
        var values = response.Values;
        if (values == null || values.Count < 2)
        {
            _log.Warn($"미출고 시트 '{sheetName}'에 데이터가 없거나 부족합니다.");
            return result;
        }

        var rows = values
            .Select(row => row.Select(cell => CleanValue(cell?.ToString())).ToList())
            .ToList();

        var headerRowIndex = DetectHeaderRow(rows);
        var headerRow = rows[headerRowIndex];
        var phoneColumnIndex = DetectPhoneColumn(headerRow);
        var shippingCompanyColumnIndex = DetectShippingCompanyColumn(headerRow);
        var trackingColumnIndex = DetectTrackingColumn(headerRow);
        var pendingWindow = DetectPendingWindow(rows, headerRowIndex);

        result.PreviousDateLabel = pendingWindow.PreviousDateLabel;
        result.LatestDateLabel = pendingWindow.LatestDateLabel;
        result.WindowLabel = pendingWindow.WindowLabel;

        if (!pendingWindow.HasWindow)
        {
            _log.Warn($"미출고 시트 '{sheetName}'에서 최신 날짜 구간을 찾지 못했습니다.");
            return result;
        }

        for (int rowIndex = headerRowIndex + 1; rowIndex < rows.Count; rowIndex++)
        {
            if (!pendingWindow.Contains(rowIndex))
                continue;

            var cells = rows[rowIndex];
            string GetCell(int col) => GetCellValue(cells, col);

            var vendor = GetCell(2);
            if (string.IsNullOrWhiteSpace(vendor))
                continue;

            var tracking = NormalizeTracking(GetCell(trackingColumnIndex >= 0 ? trackingColumnIndex : 11));
            var recipientName = GetCell(5);
            var phone = phoneColumnIndex >= 0 ? GetCell(phoneColumnIndex) : GetCell(6);
            var shippingCompany = shippingCompanyColumnIndex >= 0 ? GetCell(shippingCompanyColumnIndex) : "";
            var normalizedPhone = PhoneNormalizer.Normalize(phone);
            var sourceKey = $"{vendor}|{normalizedPhone}|{recipientName}|{tracking}|{rowIndex}";

            result.Rows.Add(new ShipmentSourceRow
            {
                SourceRowKey = sourceKey,
                VendorName = vendor,
                TrackingNumber = tracking,
                RecipientPhone = normalizedPhone,
                RecipientName = recipientName,
                ShippingCompany = shippingCompany,
                ProcessStatus = "pending_shipment",
                ImportedAt = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss"),
                SheetRowIndex = rowIndex,
                PendingShipment = true,
                PendingShipmentDateLabel = pendingWindow.LatestDateLabel
            });
        }

        _log.Info($"미출고 시트 '{sheetName}' 읽기 완료: 최신 구간 {result.WindowLabel}, 대상 {result.Rows.Count}행");
        return result;
    }

    private static int DetectHeaderRow(List<List<string>> rows)
    {
        int headerRowIndex = 0;
        int maxKeywordHits = -1;

        for (int rowIndex = 0; rowIndex < Math.Min(rows.Count, 10); rowIndex++)
        {
            var rowText = string.Join(" ", rows[rowIndex]);
            var hits = HeaderKeywords.Count(keyword => rowText.Contains(keyword, StringComparison.OrdinalIgnoreCase));
            if (hits > maxKeywordHits)
            {
                maxKeywordHits = hits;
                headerRowIndex = rowIndex;
            }
        }

        return headerRowIndex;
    }

    private static int DetectPhoneColumn(IReadOnlyList<string> headers)
    {
        for (int index = 0; index < headers.Count; index++)
        {
            var normalized = NormalizeHeader(headers[index]);
            if (PhoneKeywords.Any(keyword => normalized.Contains(NormalizeHeader(keyword), StringComparison.OrdinalIgnoreCase)))
                return index;
        }

        var fallbackKeywords = new[] { "전화", "폰", "휴대", "HP", "Phone", "Cell", "연락" };
        for (int index = 0; index < headers.Count; index++)
        {
            var normalized = NormalizeHeader(headers[index]);
            if (fallbackKeywords.Any(keyword => normalized.Contains(NormalizeHeader(keyword), StringComparison.OrdinalIgnoreCase)))
                return index;
        }

        return headers.Count > 6 ? 6 : -1;
    }

    private static int DetectShippingCompanyColumn(IReadOnlyList<string> headers)
    {
        for (int index = 0; index < headers.Count; index++)
        {
            var normalized = NormalizeHeader(headers[index]);
            if (ShippingCompanyKeywords.Any(keyword => normalized.Contains(NormalizeHeader(keyword), StringComparison.OrdinalIgnoreCase)))
                return index;
        }

        return -1;
    }

    private static int DetectTrackingColumn(IReadOnlyList<string> headers)
    {
        for (int index = 0; index < headers.Count; index++)
        {
            var normalized = NormalizeHeader(headers[index]);
            if (TrackingKeywords.Any(keyword => normalized.Contains(NormalizeHeader(keyword), StringComparison.OrdinalIgnoreCase)))
                return index;
        }

        return headers.Count > 11 ? 11 : -1;
    }

    private static PendingShipmentWindow DetectPendingWindow(List<List<string>> rows, int headerRowIndex)
    {
        var latestDate = "";
        var latestDateRowIndex = -1;

        for (int rowIndex = rows.Count - 1; rowIndex > headerRowIndex; rowIndex--)
        {
            var dateMarker = ExtractDateMarker(rows[rowIndex]);
            if (string.IsNullOrWhiteSpace(dateMarker))
                continue;

            if (latestDateRowIndex < 0)
            {
                latestDate = dateMarker;
                latestDateRowIndex = rowIndex;
                continue;
            }

            if (!string.Equals(latestDate, dateMarker, StringComparison.OrdinalIgnoreCase))
                return new PendingShipmentWindow(dateMarker, latestDate, rowIndex, latestDateRowIndex);
        }

        return PendingShipmentWindow.None;
    }

    private static string ExtractDateMarker(IReadOnlyList<string> row)
    {
        var cell = GetCellValue(row, 0);
        return IsDateMarker(cell) ? cell : "";
    }

    private static bool IsDateMarker(string value)
        => DateMarkerPattern.IsMatch(CleanValue(value));

    private static string NormalizeHeader(string value)
    {
        return CleanValue(value)
            .ToLowerInvariant()
            .Replace(" ", "")
            .Replace("_", "")
            .Replace("-", "")
            .Replace("/", "")
            .Replace("(", "")
            .Replace(")", "")
            .Replace(".", "");
    }

    private static string NormalizeTracking(string value)
        => new(CleanValue(value).Where(char.IsLetterOrDigit).ToArray());

    private static string GetCellValue(IReadOnlyList<string> row, int index)
        => index >= 0 && index < row.Count ? CleanValue(row[index]) : "";

    private static string CleanValue(string? value)
        => value?.Trim() ?? "";
    public RawSheetData ReadRawSheet(string spreadsheetId, string sheetName, int maxRows = 500)
    {
        var result = new RawSheetData();

        var range = $"'{sheetName}'!A1:ZZ{maxRows}";
        var request = _service.Spreadsheets.Values.Get(spreadsheetId, range);
        request.ValueRenderOption = SpreadsheetsResource.ValuesResource.GetRequest.ValueRenderOptionEnum.FORMATTEDVALUE;

        var response = request.Execute();
        var values = response.Values;
        if (values == null || values.Count == 0)
        {
            _log.Warn($"시트 '{sheetName}'에 데이터가 없습니다.");
            return result;
        }

        var maxCols = values.Max(r => r.Count);
        for (int i = 0; i < maxCols; i++)
        {
            var header = values[0].Count > i ? values[0][i]?.ToString()?.Trim() ?? "" : "";
            if (string.IsNullOrWhiteSpace(header))
                header = $"Column {i + 1}";
            result.Headers.Add(header);
        }

        for (int row = 1; row < values.Count; row++)
        {
            var list = new List<string>(maxCols);
            for (int col = 0; col < maxCols; col++)
                list.Add(values[row].Count > col ? values[row][col]?.ToString()?.Trim() ?? "" : "");
            result.Rows.Add(list);
        }

        _log.Info($"재고 시트 '{sheetName}' 로드: {result.Rows.Count}행");
        return result;
    }

    public void UpdateCell(string spreadsheetId, string sheetName, string a1Cell, string value)
    {
        var range = $"'{sheetName}'!{a1Cell}";
        var body = new Google.Apis.Sheets.v4.Data.ValueRange
        {
            Values = new List<IList<object>>
            {
                new List<object> { value }
            }
        };

        var request = _service.Spreadsheets.Values.Update(body, spreadsheetId, range);
        request.ValueInputOption = SpreadsheetsResource.ValuesResource.UpdateRequest.ValueInputOptionEnum.USERENTERED;
        request.Execute();

        _log.Info($"시트 값 업데이트: {sheetName} {a1Cell} = {value}");
    }

}
public class PendingShipmentSheetReadResult
{
    public List<ShipmentSourceRow> Rows { get; set; } = new();
    public string PreviousDateLabel { get; set; } = "";
    public string LatestDateLabel { get; set; } = "";
    public string WindowLabel { get; set; } = "";
}

internal sealed class PendingShipmentWindow
{
    public static readonly PendingShipmentWindow None = new("", "", -1, -1);

    public PendingShipmentWindow(string previousDateLabel, string latestDateLabel, int previousDateRowIndex, int latestDateRowIndex)
    {
        PreviousDateLabel = previousDateLabel;
        LatestDateLabel = latestDateLabel;
        PreviousDateRowIndex = previousDateRowIndex;
        LatestDateRowIndex = latestDateRowIndex;
    }

    public string PreviousDateLabel { get; }
    public string LatestDateLabel { get; }
    public int PreviousDateRowIndex { get; }
    public int LatestDateRowIndex { get; }
    public bool HasWindow => PreviousDateRowIndex >= 0 && LatestDateRowIndex >= 0;
    public string WindowLabel => string.IsNullOrWhiteSpace(PreviousDateLabel) || string.IsNullOrWhiteSpace(LatestDateLabel)
        ? ""
        : $"{PreviousDateLabel} ~ {LatestDateLabel}";

    public bool Contains(int rowIndex)
        => HasWindow && rowIndex > PreviousDateRowIndex && rowIndex < LatestDateRowIndex;
}

public class RawSheetData
{
    public List<string> Headers { get; set; } = new();
    public List<List<string>> Rows { get; set; } = new();
}






