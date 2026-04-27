using System.Text;
using System.Reflection;
using Cafe24ShipmentManager.Data;
using Cafe24ShipmentManager.Models;
using Cafe24ShipmentManager.Services;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace Cafe24ShipmentManager;

public partial class MainForm : Form
{
    private static readonly string AppVersion =
        typeof(MainForm).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion
        ?? typeof(MainForm).Assembly.GetName().Version?.ToString()
        ?? "local";

    // ── Services ──
    private readonly DatabaseManager _db;
    private readonly AppLogger _log;
    private readonly AuthService _authService;
    private readonly IReadOnlyList<IMarketplaceApiClient> _marketClients;
    private readonly Dictionary<string, IMarketplaceApiClient> _apiBySourceKey;
    private readonly MatchingEngine _matcher;
    private readonly IMarketplaceApiClient? _primaryMarketClient;
    private GoogleSheetsReader? _sheetsReader;
    private readonly string _credentialPath;
    private readonly string _spreadsheetId;
    private readonly string _defaultSheetName;
    private readonly AppUser _currentUser;
    private readonly UserSettingsService _userSettingsService;
    private readonly Cafe24TokenRefreshService _tokenRefreshService;
    private bool _tokenWarningShown;
    private const string StockSpreadsheetId = "1HWR8zdvx0DYbl4ac9hmGuaaIA47nMO0v1CtO99PyC6w";
    private const int StockDefaultSheetGid = 2073400281;
    private const string PendingShipmentSheetName = "미출고 정보";
    private const string PreferredShipmentSheetName = "cj발주서";
    private string _pendingShipmentWindowLabel = "";

    // ── State ──
    private ExcelReadResult? _excelResult;
    private List<ShipmentSourceRow> _filteredRows = new();
    private List<Cafe24Order> _cafe24Orders = new();
    private List<MatchResult> _matchResults = new();
    private readonly Dictionary<string, CheckBox> _sourceFilterBoxes = new(StringComparer.OrdinalIgnoreCase);

    // ── Top Bar (공통) ──
    private ComboBox cboSheet = null!;
    private Label lblStatus = null!;
    private Button btnUserSettings = null!;
    private Button btnLogout = null!;

    // ── 출고 탭 컨트롤 ──
    private CheckedListBox clbVendors = null!;
    private Button btnSelectAll = null!;
    private Button btnDeselectAll = null!;
    private DateTimePicker dtpStart = null!;
    private DateTimePicker dtpEnd = null!;
    private Button btnFetch = null!;
    private Button btnOrderStatusFilter = null!;
    private ContextMenuStrip? _orderStatusFilterMenu;
    private CheckedListBox? _clbOrderStatusFilter;
    private ComboBox cboShippingCompany = null!;

    // ── Main Tabs ──
    private TabControl tabMain = null!;
    private TabPage tabShipment = null!;
    private TabPage tabPopular = null!;
    private TabPage tabProduct = null!;
    private TabPage tabStock = null!;
    private TabPage tabToken = null!;
    private ComboBox cboStockSheet = null!;
    private Button btnStockLoad = null!;
    private DataGridView dgvStock = null!;
    private DataGridView dgvTokens = null!;
    private Button btnTokenRefresh = null!;
    private Button btnTokenReload = null!;
    private Button btnTokenReauth = null!;

    // ── 출고 탭 내부 서브탭 ──
    private TabControl tabShipSub = null!;
    private DataGridView dgvData = null!;
    private DataGridView dgvMatch = null!;
    private DataGridView dgvResult = null!;
    private Button btnMatch = null!;
    private Button btnMatchSelectAll = null!;
    private Button btnMatchDeselectAll = null!;
    private Button btnPush = null!;
    private Button btnDeliveryWaiting = null!;
    private Button btnExportFailed = null!;
    private bool _matchConfirmDragActive;
    private bool _matchConfirmDragValue;
    private int _matchLastConfirmRowIndex = -1;

    // ── Log ──
    private TextBox txtLog = null!;

    public MainForm(DatabaseManager db, AppLogger log, AuthService authService, IReadOnlyList<IMarketplaceApiClient> marketClients,
                    string credentialPath, string spreadsheetId, string defaultSheetName, AppUser currentUser,
                    UserSettingsService userSettingsService)
    {
        _db = db;
        _log = log;
        _authService = authService;
        _marketClients = marketClients
            .Where(client => !string.IsNullOrWhiteSpace(client.SourceKey))
            .ToList();
        _primaryMarketClient = _marketClients.FirstOrDefault();
        _apiBySourceKey = _marketClients.ToDictionary(client => client.SourceKey, StringComparer.OrdinalIgnoreCase);
        _matcher = new MatchingEngine(db, log);
        _credentialPath = credentialPath;
        _spreadsheetId = spreadsheetId;
        _defaultSheetName = defaultSheetName;
        _currentUser = currentUser;
        _userSettingsService = userSettingsService;
        _tokenRefreshService = new Cafe24TokenRefreshService(log);

        InitializeUI();
        WireEvents();
        InitEnhancedState();
        ApplyTheme();

        _log.OnLog += msg =>
        {
            if (txtLog.InvokeRequired)
                txtLog.Invoke(() => AppendLog(msg));
            else
                AppendLog(msg);
        };

        _log.Info($"프로그램 시작 v{AppVersion}");
        LoadTokenStatuses(showWarning: true);
        _ = InitAsync();
    }

    private void AppendLog(string msg)
    {
        txtLog.AppendText(msg + Environment.NewLine);
        txtLog.SelectionStart = txtLog.TextLength;
        txtLog.ScrollToCaret();
    }

    private string ResolveMarketDisplayName(IMarketplaceApiClient client)
    {
        return client.DisplayName;
    }

    private static string ResolveSourceTypeLabel(string? sourceKey)
    {
        return MarketplaceSourceKey.IsCoupang(sourceKey) ? "쿠팡" : "Cafe24";
    }

    private static string ResolveSourceTypeLabel(Cafe24Order order)
    {
        return ResolveSourceTypeLabel(order.MallId);
    }

    private string ResolveSourceFilterLabel(IMarketplaceApiClient client)
    {
        return $"{client.DisplayName} / {ResolveSourceTypeLabel(client.SourceKey)}";
    }

    private IReadOnlyList<IMarketplaceApiClient> GetSelectedMarketClients()
    {
        if (_sourceFilterBoxes.Count == 0)
            return _marketClients;

        var selectedKeys = _sourceFilterBoxes
            .Where(entry => entry.Value.Checked)
            .Select(entry => entry.Key)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);

        return _marketClients
            .Where(client => selectedKeys.Contains(client.SourceKey))
            .ToList();
    }

    private static string ResolveMarketDisplayName(Cafe24Order order)
    {
        return string.IsNullOrWhiteSpace(order.MarketName) ? order.MallId : order.MarketName;
    }

    private static string ResolveMarketDisplayName(MatchResult matchResult)
    {
        return string.IsNullOrWhiteSpace(matchResult.Cafe24MarketName) ? matchResult.Cafe24MallId : matchResult.Cafe24MarketName;
    }

    private IMarketplaceApiClient? FindApiClient(string sourceKey)
    {
        if (string.IsNullOrWhiteSpace(sourceKey)) return null;
        return _apiBySourceKey.TryGetValue(sourceKey, out var api) ? api : null;
    }

    private static DateTime ParseOrderDateForSort(Cafe24Order order)
    {
        if (DateTimeOffset.TryParse(order.OrderDate, out var dto))
            return dto.LocalDateTime;
        if (DateTime.TryParse(order.OrderDate, out var dt))
            return dt;
        return DateTime.MinValue;
    }

    private static Color Blend(Color baseColor, Color overlayColor, double overlayRatio)
    {
        overlayRatio = Math.Max(0, Math.Min(1, overlayRatio));
        var baseRatio = 1d - overlayRatio;
        return Color.FromArgb(
            (int)Math.Round(baseColor.R * baseRatio + overlayColor.R * overlayRatio),
            (int)Math.Round(baseColor.G * baseRatio + overlayColor.G * overlayRatio),
            (int)Math.Round(baseColor.B * baseRatio + overlayColor.B * overlayRatio));
    }

    private static class UiPalette
    {
        public static readonly Color Background = ColorTranslator.FromHtml("#F1FFFA");
        public static readonly Color Surface = ColorTranslator.FromHtml("#CCFCCB");
        public static readonly Color Accent = ColorTranslator.FromHtml("#96E6B3");
        public static readonly Color Primary = ColorTranslator.FromHtml("#568259");
        public static readonly Color Text = ColorTranslator.FromHtml("#464E47");
        public static readonly Color PendingSurface = Blend(Primary, Color.White, 0.78d);
        public static readonly Color CopiedSurface = Blend(Surface, Color.White, 0.35d);
        public static readonly Color WaitingSurface = Blend(Accent, Color.White, 0.22d);
        public static readonly Color SuccessSurface = Blend(Accent, Color.White, 0.40d);
        public static readonly Color CandidateSurface = Blend(Accent, Color.White, 0.58d);
        public static readonly Color WarningSurface = Blend(Surface, Color.White, 0.15d);
        public static readonly Color DangerSurface = Blend(Text, Color.White, 0.82d);
    }

    private void ApplyTheme()
    {
        BackColor = UiPalette.Background;
        ForeColor = UiPalette.Text;
        ApplyThemeToControlTree(this);

        if (txtLog != null)
        {
            txtLog.BackColor = UiPalette.Text;
            txtLog.ForeColor = UiPalette.Background;
        }
    }

    private void ApplyThemeToControlTree(Control parent)
    {
        foreach (Control control in parent.Controls)
        {
            switch (control)
            {
                case Button button:
                    ApplyButtonTheme(button);
                    break;
                case DataGridView grid:
                    ApplyGridTheme(grid);
                    break;
                case Label label when !ReferenceEquals(label, lblStatus):
                    label.ForeColor = UiPalette.Text;
                    break;
                case TabPage tabPage:
                    tabPage.BackColor = UiPalette.Background;
                    break;
                case CheckedListBox checkedListBox:
                    checkedListBox.BackColor = Color.White;
                    checkedListBox.ForeColor = UiPalette.Text;
                    break;
                case ComboBox comboBox:
                    comboBox.BackColor = Color.White;
                    comboBox.ForeColor = UiPalette.Text;
                    break;
                case TextBox textBox when !ReferenceEquals(textBox, txtLog):
                    textBox.BackColor = Color.White;
                    textBox.ForeColor = UiPalette.Text;
                    break;
            }

            ApplyThemeToControlTree(control);
        }
    }

    private void ApplyButtonTheme(Button button)
    {
        button.FlatStyle = FlatStyle.Flat;
        button.FlatAppearance.BorderColor = UiPalette.Primary;
        button.BackColor = UiPalette.Accent;
        button.ForeColor = UiPalette.Text;
    }

    private void ApplyGridTheme(DataGridView grid)
    {
        grid.EnableHeadersVisualStyles = false;
        grid.BackgroundColor = UiPalette.Background;
        grid.GridColor = UiPalette.Accent;
        grid.DefaultCellStyle.BackColor = Color.White;
        grid.DefaultCellStyle.ForeColor = UiPalette.Text;
        grid.DefaultCellStyle.SelectionBackColor = UiPalette.Primary;
        grid.DefaultCellStyle.SelectionForeColor = Color.White;
        grid.ColumnHeadersDefaultCellStyle.BackColor = UiPalette.Surface;
        grid.ColumnHeadersDefaultCellStyle.ForeColor = UiPalette.Text;
        grid.ColumnHeadersDefaultCellStyle.SelectionBackColor = UiPalette.Primary;
        grid.ColumnHeadersDefaultCellStyle.SelectionForeColor = Color.White;
    }

    private static string ResolveSourceDisplayLabel(Cafe24Order order)
    {
        var marketName = ResolveMarketDisplayName(order);
        var sourceType = ResolveSourceTypeLabel(order);
        return string.IsNullOrWhiteSpace(marketName) ? sourceType : $"{marketName} / {sourceType}";
    }

    private async Task<int> ApplyPendingShipmentFlagsAsync(List<Cafe24Order> orders)
    {
        _pendingShipmentWindowLabel = "";
        foreach (var order in orders)
        {
            order.PendingShipment = false;
            order.PendingShipmentMessage = "";
            order.PendingShipmentDateLabel = "";
        }

        if (_sheetsReader == null || orders.Count == 0)
            return 0;

        PendingShipmentSheetReadResult pendingResult;
        try
        {
            pendingResult = await Task.Run(() => _sheetsReader.ReadPendingShipmentSheet(_spreadsheetId, PendingShipmentSheetName));
        }
        catch (Exception ex)
        {
            _log.Warn($"미출고 시트 로드 실패: {ex.Message}");
            return 0;
        }

        _pendingShipmentWindowLabel = pendingResult.WindowLabel;
        if (pendingResult.Rows.Count == 0)
            return 0;

        var phoneIndex = new Dictionary<string, List<ShipmentSourceRow>>(StringComparer.OrdinalIgnoreCase);
        foreach (var row in pendingResult.Rows)
        {
            if (string.IsNullOrWhiteSpace(row.RecipientPhone))
                continue;

            if (!phoneIndex.TryGetValue(row.RecipientPhone, out var bucket))
            {
                bucket = new List<ShipmentSourceRow>();
                phoneIndex[row.RecipientPhone] = bucket;
            }

            bucket.Add(row);
        }

        var pendingCount = 0;
        foreach (var order in orders)
        {
            if (TryApplyPendingShipmentFlag(order, phoneIndex, pendingResult.LatestDateLabel))
                pendingCount++;
        }

        if (pendingCount > 0)
        {
            var label = string.IsNullOrWhiteSpace(_pendingShipmentWindowLabel) ? "" : $" ({_pendingShipmentWindowLabel})";
            _log.Info($"미출고 확인 {pendingCount}건 적용{label}");
        }

        return pendingCount;
    }

    private static bool TryApplyPendingShipmentFlag(
        Cafe24Order order,
        IReadOnlyDictionary<string, List<ShipmentSourceRow>> phoneIndex,
        string latestDateLabel)
    {
        var candidates = new List<ShipmentSourceRow>();
        AddPendingShipmentCandidates(candidates, phoneIndex, order.RecipientCellPhone);
        AddPendingShipmentCandidates(candidates, phoneIndex, order.RecipientPhone);

        candidates = candidates
            .DistinctBy(candidate => candidate.SourceRowKey)
            .OrderBy(candidate => candidate.SheetRowIndex)
            .ToList();
        if (candidates.Count == 0)
            return false;

        var withTracking = candidates
            .Where(candidate => !string.IsNullOrWhiteSpace(candidate.TrackingNumber))
            .ToList();
        if (withTracking.Count == 0)
            return false;

        var marketMatches = FindPendingShipmentMarketMatches(order.MarketName, withTracking);
        if (marketMatches.Count == 0)
            return false;

        var nameMatches = FindPendingShipmentNameMatches(order.RecipientName, marketMatches);
        if (nameMatches.Count != 1)
            return false;

        var matched = nameMatches[0];
        order.PendingShipment = true;
        order.PendingShipmentDateLabel = string.IsNullOrWhiteSpace(matched.PendingShipmentDateLabel)
            ? latestDateLabel
            : matched.PendingShipmentDateLabel;
        order.PendingShipmentMessage = string.IsNullOrWhiteSpace(order.PendingShipmentDateLabel)
            ? "미출고 확인 필요"
            : $"미출고 확인 필요 ({order.PendingShipmentDateLabel})";
        return true;
    }

    private static void AddPendingShipmentCandidates(
        ICollection<ShipmentSourceRow> target,
        IReadOnlyDictionary<string, List<ShipmentSourceRow>> phoneIndex,
        string? phone)
    {
        var normalizedPhone = PhoneNormalizer.Normalize(phone ?? "");
        if (string.IsNullOrWhiteSpace(normalizedPhone))
            return;

        if (phoneIndex.TryGetValue(normalizedPhone, out var rows))
        {
            foreach (var row in rows)
                target.Add(row);
        }
    }

    private static List<ShipmentSourceRow> FindPendingShipmentMarketMatches(string marketName, IEnumerable<ShipmentSourceRow> candidates)
    {
        var normalizedMarket = NormalizeMarketNameEx(marketName);
        if (string.IsNullOrWhiteSpace(normalizedMarket))
            return candidates.ToList();

        return candidates
            .Where(candidate =>
            {
                var normalizedVendor = NormalizeMarketNameEx(candidate.VendorName);
                return !string.IsNullOrWhiteSpace(normalizedVendor) &&
                       (normalizedVendor.Contains(normalizedMarket, StringComparison.OrdinalIgnoreCase) ||
                        normalizedMarket.Contains(normalizedVendor, StringComparison.OrdinalIgnoreCase));
            })
            .ToList();
    }

    private static List<ShipmentSourceRow> FindPendingShipmentNameMatches(string orderName, IEnumerable<ShipmentSourceRow> candidates)
    {
        if (string.IsNullOrWhiteSpace(orderName))
            return new List<ShipmentSourceRow>();

        var normalizedOrderName = orderName.Trim();
        return candidates
            .Where(candidate => !string.IsNullOrWhiteSpace(candidate.RecipientName))
            .Where(candidate =>
                normalizedOrderName.Contains(candidate.RecipientName, StringComparison.OrdinalIgnoreCase) ||
                candidate.RecipientName.Contains(normalizedOrderName, StringComparison.OrdinalIgnoreCase))
            .ToList();
    }

    private static string NormalizeMarketNameEx(string? value)
    {
        return (value ?? "")
            .Trim()
            .ToLowerInvariant()
            .Replace(" ", "")
            .Replace("_", "")
            .Replace("-", "")
            .Replace("/", "")
            .Replace("(", "")
            .Replace(")", "")
            .Replace(".", "")
            .Replace("cafe24", "");
    }

    private void ApplyMatchResultOrderFlags()
    {
        foreach (var matchResult in _matchResults)
        {
            var order = FindOrderForMatch(matchResult);
            matchResult.PendingShipment = order?.PendingShipment == true;
            matchResult.PendingShipmentMessage = order?.PendingShipmentMessage ?? "";
        }

        _matchResults = _matchResults
            .OrderByDescending(matchResult => matchResult.PendingShipment)
            .ThenBy(matchResult => matchResult.MatchStatus == "unmatched" ? 1 : 0)
            .ThenBy(matchResult => matchResult.Cafe24OrderId, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }
    // ═══════════════════════════════════════
    // 초기화: 인증 → 시트목록 → 발주사 자동 로드
    // ═══════════════════════════════════════
    private async Task InitAsync()
    {
        try
        {
            lblStatus.Text = "Google 인증 중...";
            lblStatus.ForeColor = Color.Blue;

            _sheetsReader = await GoogleSheetsReader.CreateAsync(_credentialPath, _log);

            // 시트 목록
            var sheets = _sheetsReader.GetSheetList(_spreadsheetId);
            cboSheet.Items.Clear();
            foreach (var (title, _) in sheets)
                cboSheet.Items.Add(title);

            // 기본 시트 "cj발주서" 우선 선택
            var defaultIdx = sheets.FindIndex(s => string.Equals(s.title, PreferredShipmentSheetName, StringComparison.OrdinalIgnoreCase));
            if (defaultIdx < 0 && !string.IsNullOrWhiteSpace(_defaultSheetName))
                defaultIdx = sheets.FindIndex(s => string.Equals(s.title, _defaultSheetName, StringComparison.OrdinalIgnoreCase));
            if (defaultIdx < 0)
                defaultIdx = sheets.FindIndex(s => s.title.Contains(PreferredShipmentSheetName, StringComparison.OrdinalIgnoreCase));
            if (defaultIdx < 0)
                defaultIdx = sheets.FindIndex(s => s.title.Contains("출고", StringComparison.OrdinalIgnoreCase));
            cboSheet.SelectedIndex = defaultIdx >= 0 ? defaultIdx : 0;

            lblStatus.Text = $"✅ 연결 완료 ({sheets.Count}개 시트)";
            lblStatus.ForeColor = Color.DarkGreen;

            // 발주사 자동 로드
            await LoadVendorsAsync();

            // 재고관리 확장 초기화
            await InitEnhancedStockAsync();
        }
        catch (Exception ex)
        {
            _log.Error("초기화 실패", ex);
            lblStatus.Text = "❌ 연결 실패";
            lblStatus.ForeColor = Color.Red;
        }
    }

    private async Task LoadVendorsAsync()
    {
        if (_sheetsReader == null) return;
        var sheetName = cboSheet.SelectedItem?.ToString();
        if (string.IsNullOrEmpty(sheetName)) return;

        try
        {
            lblStatus.Text = $"발주사 목록 로딩 중...";
            lblStatus.ForeColor = Color.Blue;

            var vendors = await Task.Run(() =>
                _sheetsReader.FetchVendorList(_spreadsheetId, sheetName));

            clbVendors.Items.Clear();
            foreach (var v in ApplyShipmentVendorOrder(vendors))
                clbVendors.Items.Add(v);

            SyncStockVendorsFromShipment();

            lblStatus.Text = $"✅ '{sheetName}' — 발주사 {vendors.Count}개 로드됨";
            lblStatus.ForeColor = Color.DarkGreen;
        }
        catch (Exception ex)
        {
            _log.Error("발주사 로드 실패", ex);
        }
    }

    // ═══════════════════════════════════════
    // UI 구성
    // ═══════════════════════════════════════
    private void InitializeUI()
    {
        Text = $"마켓 출고/송장 관리 매니저 v{AppVersion} [{_currentUser.EffectiveDisplayName}]";
        Size = new Size(1400, 950);
        StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("맑은 고딕", 9f);

        // ═══ 최상단: 시트 선택 ═══
        var topBar = new Panel { Dock = DockStyle.Top, Height = 36 };
        var lblSheet = new Label { Text = "시트:", Location = new Point(8, 9), AutoSize = true };
        cboSheet = new ComboBox { Location = new Point(42, 5), Width = 180, DropDownStyle = ComboBoxStyle.DropDownList };
        lblStatus = new Label { Text = "초기화 중...", Location = new Point(235, 9), AutoSize = true, ForeColor = Color.Gray };
        var topButtonPanel = new FlowLayoutPanel
        {
            Dock = DockStyle.Right,
            Width = 220,
            Height = 36,
            Padding = new Padding(0, 4, 8, 0),
            FlowDirection = FlowDirection.RightToLeft,
            WrapContents = false
        };
        btnLogout = new Button
        {
            Text = "로그아웃",
            Width = 90,
            Height = 28
        };
        btnUserSettings = new Button
        {
            Text = "마켓 추가",
            Width = 100,
            Height = 28
        };
        topButtonPanel.Controls.Add(btnLogout);
        topButtonPanel.Controls.Add(btnUserSettings);
        topBar.Controls.Add(topButtonPanel);
        topBar.Controls.AddRange(new Control[] { lblSheet, cboSheet, lblStatus });

        // ═══ 메인 탭 ═══
        tabMain = new TabControl { Dock = DockStyle.Fill };

        tabShipment = new TabPage("📦 출고/송장");
        tabPopular = new TabPage("📊 발주 많은 상품");
        tabProduct = new TabPage("📋 상품정보");
        tabStock = new TabPage("🧮 재고관리");
        tabToken = new TabPage("🔑 토큰 관리");

        BuildShipmentTab();
        BuildPlaceholderTab(tabPopular, "발주 많은 상품 분석 — 추후 구현 예정");
        BuildPlaceholderTab(tabProduct, "상품정보 관리 — 추후 구현 예정");
        BuildStockTab();
        BuildTokenTab();

        tabMain.TabPages.AddRange(new[] { tabShipment, tabPopular, tabProduct, tabStock, tabToken });

        // ═══ 로그 ═══
        var logPanel = new Panel { Dock = DockStyle.Bottom, Height = 140 };
        var logHeader = new Panel { Dock = DockStyle.Top, Height = 22 };
        logHeader.Controls.Add(new Label { Text = "로그", Dock = DockStyle.Left, Padding = new Padding(4, 3, 0, 0) });
        var btnClear = new Button { Text = "클리어", Dock = DockStyle.Right, Width = 60, Height = 22 };
        btnClear.Click += (_, _) => txtLog.Clear();
        logHeader.Controls.Add(btnClear);

        txtLog = new TextBox
        {
            Dock = DockStyle.Fill, Multiline = true, ScrollBars = ScrollBars.Both,
            ReadOnly = true, WordWrap = false,
            BackColor = Color.FromArgb(30, 30, 30), ForeColor = Color.LightGreen,
            Font = new Font("Consolas", 9f)
        };
        logPanel.Controls.Add(txtLog);
        logPanel.Controls.Add(logHeader);

        // ═══ 조립 ═══
        Controls.Add(tabMain);
        Controls.Add(new Panel { Dock = DockStyle.Top, Height = 1, BackColor = Color.LightGray });
        Controls.Add(topBar);
        Controls.Add(logPanel);
    }

    private void BuildShipmentTab()
    {
        // ── 좌측 패널: 발주사 체크리스트 ──
        var leftPanel = new Panel { Dock = DockStyle.Left, Width = 220, Padding = new Padding(4) };

        var lblVendor = new Label { Text = "발주사 (복수 선택)", Dock = DockStyle.Top, Height = 20 };
        clbVendors = new CheckedListBox
        {
            Dock = DockStyle.Fill, CheckOnClick = true,
            Font = new Font("맑은 고딕", 9f)
        };

        var vendorBtnPanel = new Panel { Dock = DockStyle.Bottom, Height = 28 };
        btnSelectAll = new Button { Text = "전체선택", Width = 70, Height = 26, Location = new Point(0, 0) };
        btnDeselectAll = new Button { Text = "전체해제", Width = 70, Height = 26, Location = new Point(74, 0) };
        vendorBtnPanel.Controls.AddRange(new Control[] { btnSelectAll, btnDeselectAll });

        leftPanel.Controls.Add(clbVendors);
        leftPanel.Controls.Add(vendorBtnPanel);
        leftPanel.Controls.Add(lblVendor);

        // ── 상단: 날짜 + 조회 + 택배사 + 수집원 ──
        var filterBar = new Panel { Dock = DockStyle.Top, Height = 72 };
        var lblFrom = new Label { Text = "조회기간:", Location = new Point(8, 11), AutoSize = true };
        dtpStart = new DateTimePicker
        {
            Location = new Point(75, 7), Width = 120, Format = DateTimePickerFormat.Short,
            Value = DateTime.Now.AddDays(-(_primaryMarketClient?.DefaultOrderFetchDays ?? 14))
        };
        var lblTo = new Label { Text = "~", Location = new Point(200, 11), AutoSize = true };
        dtpEnd = new DateTimePicker
        {
            Location = new Point(215, 7), Width = 120, Format = DateTimePickerFormat.Short,
            Value = DateTime.Now
        };
        btnFetch = new Button { Text = "📥 조회", Location = new Point(345, 5), Width = 80, Height = 30 };
        btnOrderStatusFilter = new Button
        {
            Text = "배송준비중 ▼",
            Location = new Point(430, 5),
            Width = 110,
            Height = 30
        };
        btnOrderStatusFilter.Click += (_, _) => ShowOrderStatusFilterMenu();
        EnsureOrderStatusFilterMenu();
        var btnReset = new Button { Text = "🔄 초기화", Location = new Point(545, 5), Width = 80, Height = 30 };
        btnReset.Click += (_, _) => ResetAll();

        var lblShip = new Label { Text = "기본택배사:", Location = new Point(635, 11), AutoSize = true };
        cboShippingCompany = new ComboBox { Location = new Point(715, 7), Width = 160, DropDownStyle = ComboBoxStyle.DropDownList };
        foreach (var shippingCompany in new[] { "CJ대한통운", "한진택배", "롯데택배", "롯데글로벌로지스", "로젠택배", "우체국택배", "경동택배", "대신택배", "자체배송" })
            cboShippingCompany.Items.Add(shippingCompany);
        cboShippingCompany.SelectedItem = "CJ대한통운";
        if (cboShippingCompany.SelectedIndex < 0 && cboShippingCompany.Items.Count > 0)
            cboShippingCompany.SelectedIndex = 0;

        var lblSource = new Label { Text = "수집원:", Location = new Point(8, 45), AutoSize = true };
        var pnlSource = new FlowLayoutPanel
        {
            Location = new Point(75, 40),
            Width = 900,
            Height = 26,
            WrapContents = false,
            AutoScroll = true,
            FlowDirection = FlowDirection.LeftToRight
        };
        _sourceFilterBoxes.Clear();
        if (_marketClients.Count == 0)
        {
            pnlSource.Controls.Add(new Label
            {
                AutoSize = true,
                Text = "설정된 수집원 없음",
                ForeColor = Color.Gray,
                Margin = new Padding(0, 5, 12, 0)
            });
        }
        else
        {
            foreach (var client in _marketClients)
            {
                var chk = new CheckBox
                {
                    AutoSize = true,
                    Checked = true,
                    Text = ResolveSourceFilterLabel(client),
                    Tag = client.SourceKey,
                    Margin = new Padding(0, 3, 12, 0)
                };
                _sourceFilterBoxes[client.SourceKey] = chk;
                pnlSource.Controls.Add(chk);
            }
        }

        filterBar.Controls.AddRange(new Control[] { lblFrom, dtpStart, lblTo, dtpEnd, btnFetch, btnOrderStatusFilter, btnReset, lblShip, cboShippingCompany, lblSource, pnlSource });

        // ── 서브탭: 데이터/매칭/결과 ──
        tabShipSub = new TabControl { Dock = DockStyle.Fill };

        // 서브탭1: 데이터
        var subData = new TabPage("데이터 프리뷰");
        dgvData = CreateGridView();
        var dataBtnPanel = new Panel { Dock = DockStyle.Top, Height = 36 };
        btnMatch = new Button { Text = "🔍 매칭 실행", Width = 130, Height = 30, Location = new Point(4, 3) };
        dataBtnPanel.Controls.Add(btnMatch);
        subData.Controls.Add(dgvData);
        subData.Controls.Add(dataBtnPanel);

        // 서브탭2: 매칭/검토
        var subMatch = new TabPage("매칭/검토");
        dgvMatch = CreateGridView();
        WireMatchGridCheckInteractions();
        var matchBtnPanel = new Panel { Dock = DockStyle.Top, Height = 36 };
        btnMatchSelectAll = new Button { Text = "전체선택", Width = 82, Height = 30, Location = new Point(4, 3) };
        btnMatchDeselectAll = new Button { Text = "전체해제", Width = 82, Height = 30, Location = new Point(90, 3) };
        btnDeliveryWaiting = new Button { Text = "배송대기", Width = 90, Height = 30, Location = new Point(176, 3) };
        btnPush = new Button { Text = "배송중", Width = 90, Height = 30, Location = new Point(270, 3) };
        btnMatchSelectAll.Click += (_, _) => SetAllMatchRowsChecked(true);
        btnMatchDeselectAll.Click += (_, _) => SetAllMatchRowsChecked(false);
        btnDeliveryWaiting.Click += async (_, _) => await ExecuteDeliveryWaitingAsync();
        matchBtnPanel.Controls.AddRange(new Control[] { btnMatchSelectAll, btnMatchDeselectAll, btnDeliveryWaiting, btnPush });
        subMatch.Controls.Add(dgvMatch);
        subMatch.Controls.Add(matchBtnPanel);

        // 서브탭3: 반영 결과
        var subResult = new TabPage("반영 결과");
        dgvResult = CreateGridView();
        var resBtnPanel = new Panel { Dock = DockStyle.Top, Height = 36 };
        btnExportFailed = new Button { Text = "💾 실패/미매칭 Export", Width = 170, Height = 30, Location = new Point(4, 3) };
        resBtnPanel.Controls.Add(btnExportFailed);
        subResult.Controls.Add(dgvResult);
        subResult.Controls.Add(resBtnPanel);

        tabShipSub.TabPages.AddRange(new[] { subData, subMatch, subResult });

        // ── 조립 ──
        var rightPanel = new Panel { Dock = DockStyle.Fill };
        rightPanel.Controls.Add(tabShipSub);
        rightPanel.Controls.Add(filterBar);

        tabShipment.Controls.Add(rightPanel);
        tabShipment.Controls.Add(new Panel { Dock = DockStyle.Left, Width = 1, BackColor = Color.LightGray });
        tabShipment.Controls.Add(leftPanel);
    }


    private void BuildStockTab()
    {
        var top = new Panel { Dock = DockStyle.Top, Height = 42 };
        var lbl = new Label { Text = "재고 시트:", Location = new Point(8, 12), AutoSize = true };
        cboStockSheet = new ComboBox
        {
            Location = new Point(70, 8),
            Width = 360,
            DropDownStyle = ComboBoxStyle.DropDownList
        };
        btnStockLoad = new Button { Text = "📥 불러오기", Location = new Point(440, 6), Width = 100, Height = 30 };
        var lblInfo = new Label
        {
            Text = "기본 연결: 1HWR8zdvx0DYbl4ac9hmGuaaIA47nMO0v1CtO99PyC6w",
            Location = new Point(560, 12),
            AutoSize = true,
            ForeColor = Color.DimGray
        };
        top.Controls.AddRange(new Control[] { lbl, cboStockSheet, btnStockLoad, lblInfo });

        dgvStock = CreateGridView();
        dgvStock.ReadOnly = true;

        tabStock.Controls.Add(dgvStock);
        tabStock.Controls.Add(top);
    }

    private void BuildTokenTab()
    {
        var top = new Panel { Dock = DockStyle.Top, Height = 46 };
        var lbl = new Label
        {
            Text = "Cafe24 Refresh Token 수동 관리. 만료 5일 이내면 시작 시 경고를 표시합니다.",
            Location = new Point(8, 13),
            AutoSize = true,
            ForeColor = Color.DimGray
        };
        btnTokenRefresh = new Button { Text = "🔄 선택 마켓 갱신", Location = new Point(500, 8), Width = 140, Height = 30 };
        btnTokenReauth = new Button { Text = "🔐 재인증 도구", Location = new Point(650, 8), Width = 120, Height = 30 };
        btnTokenReload = new Button { Text = "↻ 새로고침", Location = new Point(780, 8), Width = 100, Height = 30 };
        btnTokenRefresh.Click += async (_, _) => await RefreshSelectedTokenAsync();
        btnTokenReauth.Click += (_, _) => OpenReauthToolForSelectedMarket();
        btnTokenReload.Click += (_, _) => LoadTokenStatuses(showWarning: false);
        top.Controls.AddRange(new Control[] { lbl, btnTokenRefresh, btnTokenReauth, btnTokenReload });

        dgvTokens = CreateGridView();
        dgvTokens.ReadOnly = true;
        dgvTokens.AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.None;
        dgvTokens.SelectionMode = DataGridViewSelectionMode.FullRowSelect;
        dgvTokens.MultiSelect = false;
        dgvTokens.Columns.Add("Market", "마켓명");
        dgvTokens.Columns.Add("MallId", "Mall ID");
        dgvTokens.Columns.Add("AccessExpiry", "Access 만료");
        dgvTokens.Columns.Add("RefreshExpiry", "Refresh 만료");
        dgvTokens.Columns.Add("Remain", "남은 기간");
        dgvTokens.Columns.Add("Status", "상태");
        dgvTokens.Columns.Add("Path", "JSON 파일");
        dgvTokens.Columns["Market"]!.Width = 140;
        dgvTokens.Columns["MallId"]!.Width = 130;
        dgvTokens.Columns["AccessExpiry"]!.Width = 150;
        dgvTokens.Columns["RefreshExpiry"]!.Width = 150;
        dgvTokens.Columns["Remain"]!.Width = 100;
        dgvTokens.Columns["Status"]!.Width = 180;
        dgvTokens.Columns["Path"]!.Width = 420;

        tabToken.Controls.Add(dgvTokens);
        tabToken.Controls.Add(top);
    }

    private async Task InitStockTabAsync()
    {
        if (_sheetsReader == null || cboStockSheet == null) return;

        try
        {
            var sheets = await Task.Run(() => _sheetsReader.GetSheetList(StockSpreadsheetId));
            cboStockSheet.Items.Clear();

            foreach (var (title, sheetId) in sheets)
                cboStockSheet.Items.Add(new StockSheetItem(title, sheetId));

            var defaultIdx = sheets.FindIndex(s => s.sheetId == StockDefaultSheetGid);
            cboStockSheet.SelectedIndex = defaultIdx >= 0 ? defaultIdx : (cboStockSheet.Items.Count > 0 ? 0 : -1);

            if (cboStockSheet.SelectedIndex >= 0)
                await LoadStockSheetPreviewAsync();
        }
        catch (Exception ex)
        {
            _log.Error("재고 시트 초기화 실패", ex);
        }
    }

    private async Task LoadStockSheetPreviewAsync()
    {
        if (_sheetsReader == null) return;
        if (cboStockSheet.SelectedItem is not StockSheetItem item) return;

        try
        {
            btnStockLoad.Enabled = false;
            btnStockLoad.Text = "로딩 중...";

            var raw = await Task.Run(() => _sheetsReader.ReadRawSheet(StockSpreadsheetId, item.Title, 500));

            dgvStock.Columns.Clear();
            dgvStock.Rows.Clear();

            for (int i = 0; i < raw.Headers.Count; i++)
                dgvStock.Columns.Add($"C{i}", raw.Headers[i]);

            foreach (var row in raw.Rows)
                dgvStock.Rows.Add(row.Cast<object>().ToArray());

            _log.Info($"재고관리 시트 '{item.Title}' 프리뷰 완료: {raw.Rows.Count}행");
        }
        catch (Exception ex)
        {
            _log.Error("재고 시트 로드 실패", ex);
            MessageBox.Show($"재고 시트 로드 오류:\n{ex.Message}", "오류", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        finally
        {
            btnStockLoad.Enabled = true;
            btnStockLoad.Text = "📥 불러오기";
        }
    }

    private sealed class StockSheetItem
    {
        public string Title { get; }
        public int SheetId { get; }

        public StockSheetItem(string title, int sheetId)
        {
            Title = title;
            SheetId = sheetId;
        }

        public override string ToString() => $"{Title} (gid:{SheetId})";
    }
    private void BuildPlaceholderTab(TabPage tab, string message)
    {
        var lbl = new Label
        {
            Text = $"🚧 {message}",
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleCenter,
            Font = new Font("맑은 고딕", 14f),
            ForeColor = Color.Gray
        };
        tab.Controls.Add(lbl);
    }

    private DataGridView CreateGridView()
    {
        var grid = new DataGridView
        {
            Dock = DockStyle.Fill, ReadOnly = false,
            AllowUserToAddRows = false, AllowUserToDeleteRows = false,
            AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill,
            SelectionMode = DataGridViewSelectionMode.FullRowSelect,
            BackgroundColor = UiPalette.Background, RowHeadersVisible = false,
            Font = new Font("맑은 고딕", 9f)
        };

        ApplyGridTheme(grid);
        return grid;
    }

    private void EnsureOrderStatusFilterMenu()
    {
        if (_orderStatusFilterMenu != null)
            return;

        _clbOrderStatusFilter = new CheckedListBox
        {
            CheckOnClick = true,
            BorderStyle = BorderStyle.None,
            IntegralHeight = false,
            Location = new Point(8, 8),
            Size = new Size(126, 86),
            Font = new Font("맑은 고딕", 9f)
        };
        foreach (var option in GetCafe24OrderStatusFilterOptions())
            _clbOrderStatusFilter.Items.Add(option, option.Code == "N20");
        _clbOrderStatusFilter.ItemCheck += (_, _) => BeginInvoke(new Action(UpdateOrderStatusFilterButtonText));

        var btnAll = new Button { Text = "전체", Location = new Point(8, 100), Width = 58, Height = 26 };
        var btnNone = new Button { Text = "해제", Location = new Point(72, 100), Width = 58, Height = 26 };
        btnAll.Click += (_, _) => SetAllOrderStatusFiltersChecked(true);
        btnNone.Click += (_, _) => SetAllOrderStatusFiltersChecked(false);

        var panel = new Panel
        {
            Size = new Size(142, 134),
            Padding = new Padding(8),
            BackColor = Color.White
        };
        panel.Controls.Add(_clbOrderStatusFilter);
        panel.Controls.Add(btnAll);
        panel.Controls.Add(btnNone);

        _orderStatusFilterMenu = new ContextMenuStrip
        {
            ShowImageMargin = false,
            ShowCheckMargin = false,
            Padding = Padding.Empty
        };
        _orderStatusFilterMenu.Items.Add(new ToolStripControlHost(panel)
        {
            AutoSize = false,
            Size = panel.Size,
            Margin = Padding.Empty,
            Padding = Padding.Empty
        });

        UpdateOrderStatusFilterButtonText();
    }

    private void ShowOrderStatusFilterMenu()
    {
        EnsureOrderStatusFilterMenu();
        _orderStatusFilterMenu?.Show(btnOrderStatusFilter, new Point(0, btnOrderStatusFilter.Height));
    }

    private void SetAllOrderStatusFiltersChecked(bool isChecked)
    {
        if (_clbOrderStatusFilter == null)
            return;

        for (var i = 0; i < _clbOrderStatusFilter.Items.Count; i++)
            _clbOrderStatusFilter.SetItemChecked(i, isChecked);

        UpdateOrderStatusFilterButtonText();
    }

    private void UpdateOrderStatusFilterButtonText()
    {
        if (btnOrderStatusFilter == null || _clbOrderStatusFilter == null)
            return;

        var selectedLabels = _clbOrderStatusFilter.CheckedItems
            .OfType<OrderStatusFilterOption>()
            .Select(option => option.Label)
            .ToList();

        btnOrderStatusFilter.Text = selectedLabels.Count switch
        {
            0 => "상태 선택 ▼",
            1 => $"{selectedLabels[0]} ▼",
            var count when count == _clbOrderStatusFilter.Items.Count => "전체조회 ▼",
            _ => $"{selectedLabels.Count}개 상태 ▼"
        };
    }

    private static IReadOnlyList<OrderStatusFilterOption> GetCafe24OrderStatusFilterOptions()
    {
        return new[]
        {
            new OrderStatusFilterOption("배송준비중", "N20"),
            new OrderStatusFilterOption("배송대기", "N21"),
            new OrderStatusFilterOption("배송중", "N30"),
            new OrderStatusFilterOption("배송완료", "N40")
        };
    }

    private void WireEvents()
    {
        cboSheet.SelectedIndexChanged += async (_, _) => await LoadVendorsAsync();
        btnSelectAll.Click += (_, _) => { for (int i = 0; i < clbVendors.Items.Count; i++) clbVendors.SetItemChecked(i, true); };
        btnDeselectAll.Click += (_, _) => { for (int i = 0; i < clbVendors.Items.Count; i++) clbVendors.SetItemChecked(i, false); };
        btnFetch.Click += async (_, _) => await FetchFilteredDataAsync();
        btnMatch.Click += async (_, _) => await ExecuteMatchingAsync();
        btnPush.Click += async (_, _) => await ExecutePushAsync();
        btnExportFailed.Click += (_, _) => ExportFailed();
        btnUserSettings.Click += (_, _) => OpenUserSettings();
        btnLogout.Click += (_, _) => LogoutCurrentUser();
        if (btnStockLoad != null) btnStockLoad.Click += async (_, _) => await LoadStockSheetPreviewAsync();
    }

    // ═══════════════════════════════════════
    // 조회: 선택 발주사 데이터 가져오기
    // ═══════════════════════════════════════
    private async Task FetchFilteredDataAsync()
    {
        try
        {
            btnFetch.Enabled = false;
            btnFetch.Text = "📥 조회";
            lblStatus.Text = "🔄 주문 조회 중...";
            lblStatus.ForeColor = Color.Gray;

            var mergedOrders = new List<Cafe24Order>();
            var marketSummaries = new List<string>();
            var selectedClients = GetSelectedMarketClients();
            if (selectedClients.Count == 0)
            {
                MessageBox.Show("설정된 수집원이 없습니다. 우측 상단 '마켓 설정'에서 JSON/API 키를 먼저 추가하세요.",
                    "마켓 설정 필요", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }

            var cafe24OrderStatuses = GetSelectedCafe24OrderStatusCodes();
            if (selectedClients.Any(client => client is Cafe24ApiClient) && cafe24OrderStatuses.Count == 0)
            {
                MessageBox.Show("조회할 주문상태를 1개 이상 체크하세요.", "주문상태 선택", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }

            foreach (var client in selectedClients)
            {
                var marketName = ResolveMarketDisplayName(client);
                var sourceLabel = ResolveSourceFilterLabel(client);
                lblStatus.Text = $"🔄 {sourceLabel} 조회 중...";

                try
                {
                    var progress = new Progress<string>(msg => _log.Info($"[{sourceLabel}] {msg}"));
                    var orders = new List<Cafe24Order>();
                    if (client is Cafe24ApiClient)
                    {
                        foreach (var statusCode in cafe24OrderStatuses)
                        {
                            var statusLabel = ResolveCafe24OrderStatusLabel(statusCode);
                            lblStatus.Text = $"🔄 {sourceLabel} {statusLabel} 조회 중...";
                            orders.AddRange(await client.FetchRecentOrders(dtpStart.Value, dtpEnd.Value, progress, statusCode));
                        }

                        orders = orders
                            .DistinctBy(order => string.Join("|", order.MallId, order.OrderId, order.OrderItemCode, order.OrderStatus), StringComparer.OrdinalIgnoreCase)
                            .ToList();
                    }
                    else
                    {
                        orders = await client.FetchRecentOrders(dtpStart.Value, dtpEnd.Value, progress, null);
                    }

                    foreach (var order in orders)
                    {
                        if (string.IsNullOrWhiteSpace(order.MallId))
                            order.MallId = client.SourceKey;
                        order.MarketName = marketName;
                    }

                    mergedOrders.AddRange(orders);
                    marketSummaries.Add($"{sourceLabel} {orders.Count}건");
                }
                catch (Exception ex)
                {
                    _log.Error($"[{sourceLabel}] 주문 조회 실패", ex);
                }
            }

            var pendingShipmentCount = await ApplyPendingShipmentFlagsAsync(mergedOrders);

            _cafe24Orders = mergedOrders
                .OrderByDescending(order => order.PendingShipment)
                .ThenByDescending(ParseOrderDateForSort)
                .ThenBy(order => order.OrderId, StringComparer.OrdinalIgnoreCase)
                .ToList();

            if (_cafe24Orders.Count == 0)
            {
                lblStatus.Text = "⚠️ 출고대상 주문이 없습니다.";
                lblStatus.ForeColor = Color.DarkOrange;
                _log.Info("출고대상 주문 0건");
                return;
            }

            _db.ClearOrderCache();
            foreach (var order in _cafe24Orders)
                _db.InsertOrderCache(order);

            ShowDataPreview();

            var summary = string.Join(" / ", marketSummaries);
            var pendingSummary = pendingShipmentCount > 0
                ? $" / 미출고 확인 {pendingShipmentCount}건" +
                  (string.IsNullOrWhiteSpace(_pendingShipmentWindowLabel) ? "" : $" ({_pendingShipmentWindowLabel})")
                : "";
            lblStatus.Text = $"✅ 출고대상 주문 {_cafe24Orders.Count}건 조회 완료" +
                             (string.IsNullOrWhiteSpace(summary) ? "" : $" ({summary})") +
                             pendingSummary;
            lblStatus.ForeColor = Color.DarkGreen;
            _log.Info($"출고대상 주문 {_cafe24Orders.Count}건 캐시 완료" +
                      (string.IsNullOrWhiteSpace(summary) ? "" : $" [{summary}]") +
                      (pendingShipmentCount > 0 ? $" / 미출고 확인 {pendingShipmentCount}건" : ""));

            tabShipSub.SelectedIndex = 0;
        }
        catch (Exception ex)
        {
            _log.Error("주문 조회 실패", ex);
            MessageBox.Show($"조회 오류:\n{ex.Message}", "오류", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        finally
        {
            btnFetch.Enabled = true;
            btnFetch.Text = "📥 조회";
        }
    }
    private void ResetAll()
    {
        _excelResult = null;
        _filteredRows = new();
        _cafe24Orders = new();
        _matchResults = new();

        dgvData.Columns.Clear(); dgvData.Rows.Clear();
        dgvMatch.Columns.Clear(); dgvMatch.Rows.Clear();
        dgvResult.Columns.Clear(); dgvResult.Rows.Clear();

        UpdateOrderSelectionSummaryEx();

        tabShipSub.SelectedIndex = 0;
        lblStatus.Text = "🔄 초기화 완료";
        lblStatus.ForeColor = Color.Gray;
        _log.Info("초기화 완료");
    }
    private void ShowDataPreview()
    {
        dgvData.Columns.Clear();
        dgvData.Rows.Clear();

        EnsureOrderSelectionColumnEx();

        dgvData.Columns.Add("Source", "수집원");
        dgvData.Columns.Add("No", "#");
        dgvData.Columns.Add("OrderId", "주문번호");
        dgvData.Columns.Add("Market", "마켓");
        dgvData.Columns.Add("OrderDate", "주문일");
        dgvData.Columns.Add("ProductName", "상품명");
        dgvData.Columns.Add("Qty", "수량");
        dgvData.Columns.Add("Name", "수령인명");
        dgvData.Columns.Add("Phone", "전화번호");
        dgvData.Columns.Add("OrderStatus", "주문상태");
        dgvData.Columns.Add(OrderProgressColumnNameEx, "진행사항");

        dgvData.Columns[OrderSelectColumnNameEx]!.Width = 50;
        dgvData.Columns["Source"]!.Width = 120;
        dgvData.Columns["No"]!.Width = 40;
        dgvData.Columns["OrderId"]!.Width = 130;
        dgvData.Columns["Market"]!.Width = 90;
        dgvData.Columns["OrderDate"]!.Width = 90;
        dgvData.Columns["ProductName"]!.Width = 190;
        dgvData.Columns["Qty"]!.Width = 45;
        dgvData.Columns["Name"]!.Width = 75;
        dgvData.Columns["Phone"]!.Width = 110;
        dgvData.Columns["OrderStatus"]!.Width = 80;
        dgvData.Columns[OrderProgressColumnNameEx]!.Width = 110;

        for (int i = 0; i < _cafe24Orders.Count; i++)
        {
            var order = _cafe24Orders[i];
            var phone = string.IsNullOrWhiteSpace(order.RecipientCellPhone) ? order.RecipientPhone : order.RecipientCellPhone;
            var marketName = ResolveMarketDisplayName(order);
            var sourceLabel = ResolveSourceDisplayLabel(order);
            var rowIndex = dgvData.Rows.Add(false, sourceLabel, i + 1, order.OrderId, marketName, order.OrderDate, order.ProductName,
                order.Quantity, order.RecipientName, phone, order.OrderStatus, ResolveOrderProgressLabelEx(order));
            dgvData.Rows[rowIndex].Tag = order;
            ApplyPreviewOrderProgressEx(dgvData.Rows[rowIndex], order);
        }

        RefreshDataPreviewProgressStatesEx();
        UpdateOrderSelectionSummaryEx();
    }
    private async Task ExecuteMatchingAsync()
    {
        if (_cafe24Orders.Count == 0) { MessageBox.Show("먼저 주문을 조회하세요.", "알림"); return; }
        if (_sheetsReader == null) { MessageBox.Show("시트 연결이 안 되어 있습니다.", "알림"); return; }

        var sheetName = cboSheet.SelectedItem?.ToString();
        if (string.IsNullOrEmpty(sheetName)) { MessageBox.Show("시트를 선택하세요.", "알림"); return; }

        try
        {
            btnMatch.Enabled = false;
            btnMatch.Text = "스프레드시트 로드 중...";

            // ③ 스프레드시트 로드 (발주사 선택 시 필터 적용)
            var selectedVendors = new HashSet<string>();
            foreach (var item in clbVendors.CheckedItems)
                selectedVendors.Add(NormalizeVendorLabel(item.ToString() ?? ""));

            // UI 컨트롤 값을 미리 캡처 (크로스스레드 방지)
            var startDate = dtpStart.Value;
            var endDate = dtpEnd.Value;

            _excelResult = await Task.Run(() =>
                selectedVendors.Count > 0
                    ? _sheetsReader.ReadSheetFiltered(_spreadsheetId, sheetName, selectedVendors, startDate, endDate)
                    : _sheetsReader.ReadSheet(_spreadsheetId, sheetName));

            _filteredRows = _excelResult.Rows;
            _log.Info($"스프레드시트 '{sheetName}' 로드: {_filteredRows.Count}행" +
                (selectedVendors.Count > 0 ? $" (발주사 {selectedVendors.Count}개 필터)" : " (전체)"));

            // DB 저장: 필터된 행만 단일 트랜잭션으로 처리
            await Task.Run(() => _db.BulkInsertSourceRows(_filteredRows));

            btnMatch.Text = "매칭 중...";

            // 역방향 매칭: Cafe24 주문 기준 → 스프레드시트 검색
            _matchResults = await Task.Run(() => _matcher.ExecuteReverseMatching(_cafe24Orders, _filteredRows));
            ApplyMatchResultOrderFlags();
            foreach (var mr in _matchResults)
            {
                if (mr.SourceRowId > 0)
                {
                    try { mr.Id = _db.InsertMatchResult(mr); }
                    catch { /* FK 무시 - 메모리 결과로 진행 */ }
                }
            }

            ShowMatchResults();
            tabShipSub.SelectedIndex = 1;
        }
        catch (Exception ex)
        {
            _log.Error("매칭 실행 오류", ex);
            MessageBox.Show($"매칭 오류:\n{ex.Message}", "오류", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        finally
        {
            btnMatch.Enabled = true;
            btnMatch.Text = "🔍 매칭 실행";
        }
    }

    private void WireMatchGridCheckInteractions()
    {
        dgvMatch.CellMouseDown += (_, e) =>
        {
            if (e.Button != MouseButtons.Left || !IsMatchConfirmCell(e.RowIndex, e.ColumnIndex))
                return;

            var nextValue = !IsMatchRowChecked(e.RowIndex);
            if ((ModifierKeys & Keys.Shift) == Keys.Shift && _matchLastConfirmRowIndex >= 0)
                SetMatchRowRangeChecked(_matchLastConfirmRowIndex, e.RowIndex, nextValue);
            else
                SetMatchRowChecked(e.RowIndex, nextValue);

            _matchLastConfirmRowIndex = e.RowIndex;
            _matchConfirmDragActive = true;
            _matchConfirmDragValue = nextValue;
        };

        dgvMatch.MouseMove += (_, e) =>
        {
            if (!_matchConfirmDragActive || e.Button != MouseButtons.Left)
                return;

            var hit = dgvMatch.HitTest(e.X, e.Y);
            if (hit.RowIndex >= 0)
                SetMatchRowChecked(hit.RowIndex, _matchConfirmDragValue);
        };

        dgvMatch.MouseUp += (_, _) => _matchConfirmDragActive = false;
        dgvMatch.Leave += (_, _) => _matchConfirmDragActive = false;
    }

    private bool IsMatchConfirmCell(int rowIndex, int columnIndex)
    {
        return rowIndex >= 0 &&
               columnIndex >= 0 &&
               columnIndex < dgvMatch.Columns.Count &&
               dgvMatch.Columns[columnIndex].Name == "Confirm";
    }

    private bool IsMatchRowChecked(int rowIndex)
    {
        if (rowIndex < 0 || rowIndex >= dgvMatch.Rows.Count)
            return false;

        return dgvMatch.Rows[rowIndex].Cells["Confirm"]?.Value is true;
    }

    private void SetMatchRowChecked(int rowIndex, bool isChecked)
    {
        if (rowIndex < 0 || rowIndex >= dgvMatch.Rows.Count)
            return;

        var row = dgvMatch.Rows[rowIndex];
        if (row.IsNewRow)
            return;

        row.Cells["Confirm"].Value = isChecked;
    }

    private void SetMatchRowRangeChecked(int startRowIndex, int endRowIndex, bool isChecked)
    {
        var start = Math.Min(startRowIndex, endRowIndex);
        var end = Math.Max(startRowIndex, endRowIndex);
        for (var rowIndex = start; rowIndex <= end; rowIndex++)
            SetMatchRowChecked(rowIndex, isChecked);
    }

    private void SetAllMatchRowsChecked(bool isChecked)
    {
        foreach (DataGridViewRow row in dgvMatch.Rows)
        {
            if (row.IsNewRow)
                continue;

            row.Cells["Confirm"].Value = isChecked;
        }
    }

    private bool TryGetMatchResultFromRow(DataGridViewRow row, out MatchResult matchResult)
    {
        matchResult = null!;
        var value = row.Cells["MrId"]?.Value;
        if (value == null || !int.TryParse(value.ToString(), out var mrIdx))
            return false;

        if (mrIdx < 0 || mrIdx >= _matchResults.Count)
            return false;

        matchResult = _matchResults[mrIdx];
        return true;
    }

    private void ShowMatchResults()
    {
        dgvMatch.Columns.Clear();
        dgvMatch.Rows.Clear();

        dgvMatch.Columns.Add(new DataGridViewCheckBoxColumn { Name = "Confirm", HeaderText = "선택", Width = 45, ReadOnly = true });
        dgvMatch.Columns.Add("MrId", "ID");
        dgvMatch.Columns.Add("SrcPhone", "출고-휴대폰");
        dgvMatch.Columns.Add("SrcName", "출고-수령인");
        dgvMatch.Columns.Add("SrcTracking", "송장번호");
        dgvMatch.Columns.Add("Cafe24Market", "마켓");
        dgvMatch.Columns.Add("Cafe24Order", "주문번호");
        dgvMatch.Columns.Add("OrdPhone", "주문-휴대폰");
        dgvMatch.Columns.Add("OrdName", "주문-수령인");
        dgvMatch.Columns.Add("OrdProduct", "상품명");
        dgvMatch.Columns.Add("Confidence", "확신도");
        dgvMatch.Columns.Add("MStatus", "상태");

        dgvMatch.Columns["MrId"]!.Width = 40;
        dgvMatch.Columns["Cafe24Market"]!.Width = 90;
        dgvMatch.Columns["Confidence"]!.Width = 70;
        dgvMatch.Columns["MStatus"]!.Width = 100;

        for (int i = 0; i < _matchResults.Count; i++)
        {
            var mr = _matchResults[i];
            var marketName = ResolveMarketDisplayName(mr);
            var auto = mr.MatchStatus == "auto_confirmed" && !mr.PendingShipment;
            var idx = dgvMatch.Rows.Add(auto, i, mr.SourcePhone, mr.SourceName,
                mr.SourceTracking, marketName, mr.Cafe24OrderId, mr.OrderPhone, mr.OrderName,
                mr.OrderProduct, ConfLabel(mr.Confidence), ResolveMatchStatusLabel(mr));

            dgvMatch.Rows[idx].DefaultCellStyle.BackColor = ResolveMatchRowBackColor(mr);
            dgvMatch.Rows[idx].DefaultCellStyle.ForeColor = UiPalette.Text;
        }

        _log.Info($"매칭: 전체 {_matchResults.Count} | " +
            $"확정 {_matchResults.Count(m => m.Confidence == "exact")} | " +
            $"유력 {_matchResults.Count(m => m.Confidence == "probable")} | " +
            $"후보 {_matchResults.Count(m => m.Confidence == "candidate")} | " +
            $"송장없음 {_matchResults.Count(m => m.Confidence == "no_tracking")} | " +
            $"미매칭 {_matchResults.Count(m => m.Confidence == "none")} | " +
            $"미출고확인 {_matchResults.Count(m => m.PendingShipment)}");
    }

    private static string ResolveMatchStatusLabel(MatchResult matchResult)
    {
        if (matchResult.PendingShipment)
            return "미출고확인";

        return StatLabel(matchResult.MatchStatus);
    }

    private static Color ResolveMatchRowBackColor(MatchResult matchResult)
    {
        if (matchResult.PendingShipment)
            return UiPalette.PendingSurface;

        return matchResult.Confidence switch
        {
            "exact" => UiPalette.SuccessSurface,
            "probable" => UiPalette.Surface,
            "candidate" => UiPalette.CandidateSurface,
            "no_tracking" => UiPalette.WarningSurface,
            _ => UiPalette.DangerSurface
        };
    }

    static string ConfLabel(string c) => c switch { "exact" => "✅ 확정", "probable" => "🟡 유력", "candidate" => "🟠 후보", "no_tracking" => "⚠️ 송장없음", _ => "❌ 없음" };
    static string StatLabel(string s) => s switch { "auto_confirmed" => "자동확정", "confirmed" => "수동확정", "pending" => "대기", "delivery_waiting" => "배송대기", "pushed" => "반영완료", "push_failed" => "반영실패", "unmatched" => "미매칭", "no_tracking" => "송장미등록", _ => s };

    private void ConfirmAllMatches()
    {
        for (int i = 0; i < dgvMatch.Rows.Count; i++)
        {
            var mrIdx = Convert.ToInt32(dgvMatch.Rows[i].Cells["MrId"]?.Value);
            if (mrIdx < 0 || mrIdx >= _matchResults.Count)
                continue;

            var matchResult = _matchResults[mrIdx];
            if (matchResult.PendingShipment || string.IsNullOrWhiteSpace(matchResult.Cafe24OrderId))
                continue;

            var confidence = dgvMatch.Rows[i].Cells["Confidence"]?.Value?.ToString() ?? "";
            if (!confidence.Contains("없음"))
                dgvMatch.Rows[i].Cells["Confirm"].Value = true;
        }
    }

    private void MarkSelectedMatchesAsDeliveryWaiting()
    {
        var changed = 0;
        for (int i = 0; i < dgvMatch.Rows.Count; i++)
        {
            if (dgvMatch.Rows[i].Cells["Confirm"]?.Value is not true)
                continue;

            var mrIdx = Convert.ToInt32(dgvMatch.Rows[i].Cells["MrId"]?.Value);
            if (mrIdx < 0 || mrIdx >= _matchResults.Count)
                continue;

            var matchResult = _matchResults[mrIdx];
            if (matchResult.PendingShipment)
                continue;

            var order = FindOrderForMatch(matchResult);
            if (order == null)
                continue;

            SetPersistedOrderProgressCodeEx(order, "delivery_waiting", saveState: false);
            matchResult.MatchStatus = "delivery_waiting";
            if (matchResult.Id > 0)
                _db.UpdateMatchStatus(matchResult.Id, "delivery_waiting", true);
            if (matchResult.SourceRowId > 0)
                _db.UpdateSourceRowStatus(matchResult.SourceRowId, "delivery_waiting", matchResult.Cafe24OrderId);
            changed++;
        }

        if (changed == 0)
        {
            MessageBox.Show("배송 대기중으로 표시할 확정 항목이 없습니다.", "알림");
            return;
        }

        SaveEnhancedState();
        RefreshDataPreviewProgressStatesEx();
        ShowMatchResults();
        tabShipSub.SelectedIndex = 1;
        MessageBox.Show($"{changed}건을 배송 대기중으로 표시했습니다.", "완료", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    private ShipmentSourceRow? FindSourceRowForMatch(MatchResult matchResult)
    {
        if (matchResult.SourceRowId > 0)
        {
            var byId = _filteredRows.FirstOrDefault(r => r.Id == matchResult.SourceRowId);
            if (byId != null) return byId;
        }

        if (string.IsNullOrWhiteSpace(matchResult.SourceTracking))
            return null;

        return _filteredRows.FirstOrDefault(r =>
            string.Equals(r.TrackingNumber, matchResult.SourceTracking, StringComparison.OrdinalIgnoreCase) &&
            (string.IsNullOrWhiteSpace(matchResult.SourcePhone) ||
             string.Equals(r.RecipientPhone, matchResult.SourcePhone, StringComparison.OrdinalIgnoreCase)) &&
            (string.IsNullOrWhiteSpace(matchResult.SourceName) ||
             string.Equals(r.RecipientName, matchResult.SourceName, StringComparison.OrdinalIgnoreCase)));
    }

    // ═══════════════════════════════════════
    // 반영 실행
    // ═══════════════════════════════════════
    private Task ExecutePushAsync()
    {
        return ExecuteShipmentApiActionAsync(ShipmentApiAction.Shipping);
    }

    private Task ExecuteDeliveryWaitingAsync()
    {
        return ExecuteShipmentApiActionAsync(ShipmentApiAction.DeliveryWaiting);
    }

    private enum ShipmentApiAction
    {
        DeliveryWaiting,
        Shipping
    }

    private async Task ExecuteShipmentApiActionAsync(ShipmentApiAction action)
    {
        var isDeliveryWaiting = action == ShipmentApiAction.DeliveryWaiting;
        var actionLabel = isDeliveryWaiting ? "배송대기" : "배송중";
        var actionButton = isDeliveryWaiting ? btnDeliveryWaiting : btnPush;
        var idleButtonText = actionLabel;
        var progressButtonText = $"{actionLabel} 반영 중...";
        var successMatchStatus = isDeliveryWaiting ? "delivery_waiting" : "pushed";
        var successSourceStatus = isDeliveryWaiting ? "delivery_waiting" : "pushed";
        var successProgressCode = isDeliveryWaiting ? "delivery_waiting" : "pushed";

        var confirmed = new List<MatchResult>();
        for (int i = 0; i < dgvMatch.Rows.Count; i++)
        {
            if (dgvMatch.Rows[i].Cells["Confirm"]?.Value is true)
            {
                var mrIdx = Convert.ToInt32(dgvMatch.Rows[i].Cells["MrId"]?.Value);
                if (mrIdx >= 0 && mrIdx < _matchResults.Count)
                {
                    var mr = _matchResults[mrIdx];
                    if (!string.IsNullOrEmpty(mr.Cafe24OrderId))
                        confirmed.Add(mr);
                }
            }
        }

        if (confirmed.Count == 0) { MessageBox.Show("선택된 항목이 없습니다.", "알림"); return; }

        var uploadBlocked = confirmed.Where(matchResult => matchResult.PendingShipment).ToList();
        var readyToPush = confirmed.Where(matchResult => !matchResult.PendingShipment).ToList();
        if (readyToPush.Count == 0)
        {
            MessageBox.Show("선택한 항목이 모두 미출고 확인 대상으로 막혀 있습니다.", "알림", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        var confirmationMessage = $"{readyToPush.Count}건을 {actionLabel} 상태로 API 반영합니다.";
        if (uploadBlocked.Count > 0)
            confirmationMessage += $"\n미출고 확인 {uploadBlocked.Count}건은 자동으로 제외됩니다.";
        confirmationMessage += "\n계속?";
        if (MessageBox.Show(confirmationMessage,
            "반영 확인", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;

        actionButton.Enabled = false;
        actionButton.Text = progressButtonText;

        dgvResult.Columns.Clear();
        dgvResult.Rows.Clear();
        dgvResult.Columns.Add("Market", "마켓");
        dgvResult.Columns.Add("OrderId", "주문번호");
        dgvResult.Columns.Add("Tracking", "송장번호");
        dgvResult.Columns.Add("Result", "결과");
        dgvResult.Columns.Add("Detail", "상세");

        dgvResult.Columns["Market"]!.Width = 90;
        dgvResult.Columns["OrderId"]!.Width = 130;
        dgvResult.Columns["Tracking"]!.Width = 120;
        dgvResult.Columns["Result"]!.Width = 95;

        var ok = 0;
        var fail = 0;

        try
        {
            foreach (var blocked in uploadBlocked)
            {
                var blockedMarket = ResolveMarketDisplayName(blocked);
                var blockedIdx = dgvResult.Rows.Add(blockedMarket, blocked.Cafe24OrderId, blocked.SourceTracking, "SKIP", blocked.PendingShipmentMessage);
                dgvResult.Rows[blockedIdx].DefaultCellStyle.BackColor = UiPalette.PendingSurface;
            }

            var duplicateTargetTrackings = readyToPush
                .Select(mr =>
                {
                    var api = FindApiClient(mr.Cafe24MallId);
                    var order = api == null ? null : FindOrderForMatch(mr);
                    var src = order == null ? null : FindSourceRowForMatch(mr);
                    var tracking = !string.IsNullOrWhiteSpace(mr.SourceTracking) ? mr.SourceTracking : src?.TrackingNumber ?? "";
                    var targetKey = api == null || order == null ? "" : BuildPushTargetKey(api, order);
                    return new
                    {
                        TargetKey = targetKey,
                        Tracking = NormalizeTrackingForPush(tracking)
                    };
                })
                .Where(x => !string.IsNullOrWhiteSpace(x.TargetKey) && !string.IsNullOrWhiteSpace(x.Tracking))
                .GroupBy(x => x.TargetKey, StringComparer.OrdinalIgnoreCase)
                .Where(g => g.Select(x => x.Tracking).Distinct(StringComparer.OrdinalIgnoreCase).Skip(1).Any())
                .ToDictionary(
                    g => g.Key,
                    g => g.Select(x => x.Tracking).Distinct(StringComparer.OrdinalIgnoreCase).OrderBy(x => x, StringComparer.OrdinalIgnoreCase).ToArray(),
                    StringComparer.OrdinalIgnoreCase);

            var attemptedTargets = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

            foreach (var mr in readyToPush)
            {
                var marketName = ResolveMarketDisplayName(mr);
                var api = FindApiClient(mr.Cafe24MallId);
                if (api == null)
                {
                    var missingIdx = dgvResult.Rows.Add(marketName, mr.Cafe24OrderId, "", "❌ 실패", "대상 마켓 API를 찾지 못했습니다.");
                    dgvResult.Rows[missingIdx].DefaultCellStyle.BackColor = Color.FromArgb(255, 220, 220);
                    _log.Error($"{actionLabel} 대상 API 없음: {marketName} ({mr.Cafe24MallId}) / 주문 {mr.Cafe24OrderId}");
                    fail++;
                    continue;
                }

                var order = FindOrderForMatch(mr);
                if (order == null)
                {
                    var missingOrderIdx = dgvResult.Rows.Add(marketName, mr.Cafe24OrderId, "", "❌ 실패", "캐시된 주문 원본을 찾지 못했습니다.");
                    dgvResult.Rows[missingOrderIdx].DefaultCellStyle.BackColor = UiPalette.DangerSurface;
                    _log.Error($"캐시 주문 없음: {marketName} ({mr.Cafe24MallId}) / 주문 {mr.Cafe24OrderId} / 아이템 {mr.Cafe24OrderItemCode}");
                    fail++;
                    continue;
                }

                if (order.PendingShipment)
                {
                    var blockedIdx = dgvResult.Rows.Add(marketName, mr.Cafe24OrderId, mr.SourceTracking, "SKIP", order.PendingShipmentMessage);
                    dgvResult.Rows[blockedIdx].DefaultCellStyle.BackColor = UiPalette.PendingSurface;
                    continue;
                }

                var src = FindSourceRowForMatch(mr);
                var tracking = !string.IsNullOrWhiteSpace(mr.SourceTracking) ? mr.SourceTracking : src?.TrackingNumber ?? "";
                var code = !string.IsNullOrWhiteSpace(src?.ShippingCompany)
                    ? ResolveShipCode(api, src.ShippingCompany)
                    : ResolveShipCode(api, GetSelectedShippingCompanyName());
                var sourceRowId = src?.Id ?? mr.SourceRowId;

                if (src != null &&
                    !string.IsNullOrWhiteSpace(mr.SourceTracking) &&
                    !string.Equals(src.TrackingNumber, mr.SourceTracking, StringComparison.OrdinalIgnoreCase))
                {
                    _log.Warn($"송장 원본 불일치 감지: SourceRowId={mr.SourceRowId}, Match={mr.SourceTracking}, Source={src.TrackingNumber}. 매칭 송장번호를 사용합니다.");
                }

                if (string.IsNullOrEmpty(tracking))
                {
                    var skipIdx = dgvResult.Rows.Add(marketName, mr.Cafe24OrderId, "", "SKIP", "송장번호 없음");
                    dgvResult.Rows[skipIdx].DefaultCellStyle.BackColor = Color.FromArgb(255, 250, 210);
                    continue;
                }

                var targetKey = BuildPushTargetKey(api, order);
                var normalizedTracking = NormalizeTrackingForPush(tracking);

                if (!string.IsNullOrWhiteSpace(targetKey) &&
                    duplicateTargetTrackings.TryGetValue(targetKey, out var conflictingTrackings))
                {
                    var detail = $"동일 출고 대상에 서로 다른 송장번호가 매칭되어 반영을 중단했습니다: {string.Join(", ", conflictingTrackings)}";
                    var conflictIdx = dgvResult.Rows.Add(marketName, mr.Cafe24OrderId, tracking, "❌ 실패", detail);
                    dgvResult.Rows[conflictIdx].DefaultCellStyle.BackColor = Color.FromArgb(255, 220, 220);
                    _log.Error($"중복 출고 대상 충돌: {marketName} / 주문 {mr.Cafe24OrderId} / 대상 {targetKey} / {detail}");
                    fail++;
                    continue;
                }

                if (!string.IsNullOrWhiteSpace(targetKey) &&
                    attemptedTargets.TryGetValue(targetKey, out var attemptedTracking))
                {
                    var detail = string.Equals(attemptedTracking, normalizedTracking, StringComparison.OrdinalIgnoreCase)
                        ? "동일 출고 대상/송장번호 중복으로 추가 반영을 생략했습니다."
                        : "동일 출고 대상은 이번 실행에서 1회만 처리합니다.";
                    var duplicateIdx = dgvResult.Rows.Add(marketName, mr.Cafe24OrderId, tracking, "SKIP", detail);
                    dgvResult.Rows[duplicateIdx].DefaultCellStyle.BackColor = Color.FromArgb(255, 250, 210);
                    _log.Warn($"중복 출고 대상 생략: {marketName} / 주문 {mr.Cafe24OrderId} / 대상 {targetKey} / 송장 {tracking}");
                    continue;
                }

                var (success, resp, status) = isDeliveryWaiting
                    ? await PushDeliveryWaitingAsync(api, order, tracking, code)
                    : await api.PushTrackingNumber(order, tracking, code);

                if (!string.IsNullOrWhiteSpace(targetKey))
                    attemptedTargets[targetKey] = normalizedTracking;

                _db.InsertPushLog(new PushLog
                {
                    MatchResultId = mr.Id,
                    Cafe24MallId = mr.Cafe24MallId,
                    Cafe24MarketName = marketName,
                    Cafe24OrderId = mr.Cafe24OrderId,
                    RequestBody = JsonConvert.SerializeObject(new { tracking, code, status = isDeliveryWaiting ? "standby" : "shipping" }),
                    ResponseBody = resp,
                    HttpStatusCode = status,
                    Result = success ? "success" : "fail",
                    ErrorMessage = success ? "" : resp,
                    PushedAt = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")
                });

                var nextStatus = success ? successMatchStatus : "push_failed";
                _db.UpdateMatchStatus(mr.Id, nextStatus, true);
                if (sourceRowId > 0)
                    _db.UpdateSourceRowStatus(sourceRowId, success ? successSourceStatus : "failed", mr.Cafe24OrderId);
                if (success)
                    SetPersistedOrderProgressCodeEx(order, successProgressCode, saveState: false);

                var idx = dgvResult.Rows.Add(marketName, mr.Cafe24OrderId, tracking,
                    success ? "✅ 성공" : $"❌ 실패 ({status})", resp.Length > 200 ? resp[..200] : resp);
                dgvResult.Rows[idx].DefaultCellStyle.BackColor = success ? UiPalette.SuccessSurface : UiPalette.DangerSurface;
                if (success) ok++; else fail++;
            }

            SaveEnhancedState();
            RefreshDataPreviewProgressStatesEx();
            _log.Info($"{actionLabel} 반영 완료: 성공 {ok}, 실패 {fail}");
            tabShipSub.SelectedIndex = 2;
        }
        catch (Exception ex)
        {
            _log.Error($"{actionLabel} 반영 오류", ex);
            MessageBox.Show($"{actionLabel} 반영 오류:\n{ex.Message}", "오류", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        finally
        {
            actionButton.Enabled = true;
            actionButton.Text = idleButtonText;
        }
    }

    private static Task<(bool success, string responseBody, int statusCode)> PushDeliveryWaitingAsync(
        IMarketplaceApiClient api,
        Cafe24Order order,
        string trackingNumber,
        string shippingCompanyCode)
    {
        if (api is Cafe24ApiClient cafe24Api)
            return cafe24Api.PushDeliveryWaiting(order, trackingNumber, shippingCompanyCode);

        return Task.FromResult((false, "배송대기 API는 Cafe24 수집원만 지원합니다.", 0));
    }

    private Cafe24Order? FindOrderForMatch(MatchResult matchResult)
    {
        return _cafe24Orders.FirstOrDefault(order =>
                   string.Equals(order.MallId, matchResult.Cafe24MallId, StringComparison.OrdinalIgnoreCase) &&
                   string.Equals(order.OrderId, matchResult.Cafe24OrderId, StringComparison.OrdinalIgnoreCase) &&
                   string.Equals(order.OrderItemCode, matchResult.Cafe24OrderItemCode, StringComparison.OrdinalIgnoreCase))
               ?? _cafe24Orders.FirstOrDefault(order =>
                   string.Equals(order.MallId, matchResult.Cafe24MallId, StringComparison.OrdinalIgnoreCase) &&
                   string.Equals(order.OrderId, matchResult.Cafe24OrderId, StringComparison.OrdinalIgnoreCase));
    }

    private void OpenReauthToolForSelectedMarket()
    {
        var status = FindSelectedTokenStatus();
        if (status == null)
        {
            MessageBox.Show("재인증할 마켓을 선택하세요.", "토큰 관리", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        var authToolPath = ResolveCafe24AuthPath();
        if (string.IsNullOrWhiteSpace(authToolPath))
        {
            MessageBox.Show(
                "Cafe24Auth 실행 파일을 찾을 수 없습니다.\n\n" +
                "설치형 버전에서는 메인 프로그램 옆의 Cafe24Auth 폴더에 함께 설치됩니다.\n" +
                "개발 중이라면 바탕화면 Cafe24Auth 프로젝트 빌드 경로도 함께 확인하세요.",
                "재인증 도구 없음", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }

        System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
        {
            FileName = authToolPath,
            WorkingDirectory = Path.GetDirectoryName(authToolPath) ?? AppContext.BaseDirectory,
            UseShellExecute = true
        });

        MessageBox.Show($"Cafe24Auth를 열었습니다.\n\n마켓: {status.DisplayName} ({status.MallId})\n선택 후 '🔐 재인증'을 진행하세요.",
            "재인증 도구 실행", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    private static string? ResolveCafe24AuthPath()
    {
        var desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
        var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        var envPath = Environment.GetEnvironmentVariable("CAFE24_AUTH_PATH");
        var candidates = new[]
        {
            envPath,
            Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "Cafe24Auth", "Cafe24Auth.exe")),
            Path.Combine(AppContext.BaseDirectory, "Cafe24Auth", "Cafe24Auth.exe"),
            Path.Combine(localAppData, "Programs", "Cafe24ShipmentManager", "Cafe24Auth", "Cafe24Auth.exe"),
            Path.Combine(desktop, "Cafe24Auth", "publish5", "Cafe24Auth.exe"),
            Path.Combine(desktop, "Cafe24Auth", "bin", "Release", "net6.0-windows", "Cafe24Auth.exe"),
            Path.Combine(desktop, "Cafe24Auth", "bin", "Debug", "net6.0-windows", "Cafe24Auth.exe")
        };

        foreach (var candidate in candidates)
        {
            if (!string.IsNullOrWhiteSpace(candidate) && File.Exists(candidate))
                return candidate;
        }

        return null;
    }

    private void LoadTokenStatuses(bool showWarning)
    {
        if (dgvTokens == null)
            return;

        var statuses = _tokenRefreshService
            .LoadStatuses(_marketClients.OfType<Cafe24ApiClient>().OrderBy(client => client.DisplayName, StringComparer.OrdinalIgnoreCase))
            .ToList();

        dgvTokens.Rows.Clear();
        foreach (var status in statuses)
        {
            var idx = dgvTokens.Rows.Add(
                status.DisplayName,
                status.MallId,
                FormatDateTime(status.AccessExpiresAt),
                FormatDateTime(status.RefreshExpiresAt),
                FormatRemaining(status.RefreshRemaining),
                status.StatusMessage,
                status.TokenFilePath);

            var row = dgvTokens.Rows[idx];
            row.Tag = status;
            if (!status.HasRefreshToken || status.RefreshRemaining.TotalDays <= 0)
                row.DefaultCellStyle.BackColor = UiPalette.DangerSurface;
            else if (status.NeedsRefreshWarning)
                row.DefaultCellStyle.BackColor = UiPalette.WarningSurface;
            else
                row.DefaultCellStyle.BackColor = UiPalette.SuccessSurface;
        }

        if (showWarning)
            ShowRefreshWarning(statuses);
    }

    private async Task RefreshSelectedTokenAsync()
    {
        var status = FindSelectedTokenStatus();
        if (status == null)
        {
            MessageBox.Show("갱신할 마켓을 선택하세요.", "토큰 관리", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        try
        {
            btnTokenRefresh.Enabled = false;
            var result = await _tokenRefreshService.RefreshAsync(status);
            if (!result.success)
            {
                MessageBox.Show(result.message, "토큰 갱신 실패", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            LoadTokenStatuses(showWarning: false);
            SelectTokenRow(status.TokenFilePath);
            MessageBox.Show("토큰 갱신이 완료되었습니다. Refresh 기준일도 현재 시각으로 갱신했습니다.",
                "토큰 갱신 완료", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        finally
        {
            btnTokenRefresh.Enabled = true;
        }
    }

    private Cafe24TokenStatus? FindSelectedTokenStatus()
    {
        return dgvTokens?.CurrentRow?.Tag as Cafe24TokenStatus;
    }

    private void SelectTokenRow(string tokenFilePath)
    {
        if (dgvTokens == null)
            return;

        foreach (DataGridViewRow row in dgvTokens.Rows)
        {
            if (row.Tag is Cafe24TokenStatus status &&
                string.Equals(status.TokenFilePath, tokenFilePath, StringComparison.OrdinalIgnoreCase))
            {
                row.Selected = true;
                dgvTokens.CurrentCell = row.Cells[0];
                return;
            }
        }
    }

    private void ShowRefreshWarning(IReadOnlyList<Cafe24TokenStatus> statuses)
    {
        if (_tokenWarningShown)
            return;

        var warningTargets = statuses
            .Where(status => status.HasRefreshToken && status.NeedsRefreshWarning)
            .OrderBy(status => status.RefreshRemaining)
            .ToList();
        if (warningTargets.Count == 0)
            return;

        _tokenWarningShown = true;
        var lines = warningTargets
            .Select(status => $"- {status.DisplayName} ({status.MallId}) : {FormatRemaining(status.RefreshRemaining)}")
            .ToArray();
        var message = "다음 마켓의 Refresh Token 만료가 5일 이내입니다.\n\n" +
                      string.Join("\n", lines) +
                      "\n\n'토큰 관리' 탭에서 수동 갱신하세요.";
        MessageBox.Show(message, "Refresh Token 경고", MessageBoxButtons.OK, MessageBoxIcon.Warning);
    }

    private static string FormatDateTime(DateTime value)
    {
        return value == DateTime.MinValue ? "-" : value.ToString("yyyy-MM-dd HH:mm");
    }

    private static string FormatRemaining(TimeSpan value)
    {
        if (value.TotalDays <= 0)
            return "만료";
        if (value.TotalDays >= 1)
            return $"{(int)Math.Ceiling(value.TotalDays)}일";
        if (value.TotalHours >= 1)
            return $"{(int)Math.Ceiling(value.TotalHours)}시간";
        return $"{Math.Max(1, (int)Math.Ceiling(value.TotalMinutes))}분";
    }

    private void OpenUserSettings()
    {
        using var profileForm = new UserProfileForm(
            _currentUser,
            _userSettingsService,
            requireMarketplaceConfig: !_userSettingsService.IsAdminUser(_currentUser));

        if (profileForm.ShowDialog(this) != DialogResult.OK)
            return;

        var restartNow = MessageBox.Show("마켓 설정이 저장되었습니다. 지금 다시 시작해 반영할까요?",
            "저장 완료", MessageBoxButtons.YesNo, MessageBoxIcon.Information);
        if (restartNow == DialogResult.Yes)
            RestartApplication();
    }

    private void LogoutCurrentUser()
    {
        var confirmed = MessageBox.Show("현재 계정에서 로그아웃하고 로그인 화면으로 돌아가시겠습니까?",
            "로그아웃", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
        if (confirmed != DialogResult.Yes)
            return;

        try
        {
            _authService.Logout(_currentUser);
            RestartApplication();
        }
        catch (Exception ex)
        {
            _log.Error("로그아웃 실패", ex);
            MessageBox.Show($"로그아웃 중 오류:\n{ex.Message}",
                "오류", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void RestartApplication()
    {
        BeginInvoke(new Action(() =>
        {
            Application.Restart();
            Close();
        }));
    }

    private string? GetSelectedShippingCompanyName()
    {
        return cboShippingCompany.SelectedItem?.ToString();
    }

    private IReadOnlyList<string> GetSelectedCafe24OrderStatusCodes()
    {
        if (_clbOrderStatusFilter == null)
            return new[] { "N20" };

        return _clbOrderStatusFilter.CheckedItems
            .OfType<OrderStatusFilterOption>()
            .Select(option => option.Code)
            .ToList();
    }

    private static string ResolveCafe24OrderStatusLabel(string statusCode)
    {
        return GetCafe24OrderStatusFilterOptions()
            .FirstOrDefault(option => string.Equals(option.Code, statusCode, StringComparison.OrdinalIgnoreCase))
            ?.Label ?? statusCode;
    }

    private sealed class OrderStatusFilterOption
    {
        public OrderStatusFilterOption(string label, string code)
        {
            Label = label;
            Code = code;
        }

        public string Label { get; }
        public string Code { get; }

        public override string ToString() => Label;
    }

    private string ResolveShipCode(IMarketplaceApiClient client, string? name)
    {
        return client.ResolveShippingCompanyCode(name);
    }

    private static string BuildPushTargetKey(IMarketplaceApiClient client, Cafe24Order order)
    {
        if (MarketplaceSourceKey.IsCoupang(order.MallId) && !string.IsNullOrWhiteSpace(order.ShippingCode))
            return $"{client.SourceKey}|shipment:{order.ShippingCode}";

        if (!string.IsNullOrWhiteSpace(order.OrderItemCode))
            return $"{client.SourceKey}|item:{order.OrderId}|{order.OrderItemCode}";

        return $"{client.SourceKey}|order:{order.OrderId}";
    }

    private static string NormalizeTrackingForPush(string tracking)
    {
        return string.IsNullOrWhiteSpace(tracking)
            ? string.Empty
            : new string(tracking.Where(char.IsLetterOrDigit).ToArray());
    }

    private void ExportFailed()
    {
        var failed = _matchResults.Where(m => m.MatchStatus is "unmatched" or "push_failed" or "pending").ToList();
        if (failed.Count == 0) { MessageBox.Show("미매칭/실패 항목이 없습니다.", "알림"); return; }

        using var sfd = new SaveFileDialog { Filter = "CSV|*.csv", FileName = $"failed_{DateTime.Now:yyyyMMdd_HHmmss}.csv" };
        if (sfd.ShowDialog() != DialogResult.OK) return;

        var sb = new StringBuilder();
        sb.AppendLine("SourceRowId,마켓,출고-휴대폰,출고-수령인,송장번호,Cafe24주문번호,주문-수령인,확신도,상태");
        foreach (var m in failed)
            sb.AppendLine($"{m.SourceRowId},{ResolveMarketDisplayName(m)},{m.SourcePhone},{m.SourceName},{m.SourceTracking},{m.Cafe24OrderId},{m.OrderName},{m.Confidence},{m.MatchStatus}");

        File.WriteAllText(sfd.FileName, sb.ToString(), Encoding.UTF8);
        _log.Info($"Export: {sfd.FileName} ({failed.Count}건)");
        MessageBox.Show($"{failed.Count}건 저장:\n{sfd.FileName}", "Export");
    }
}

// ═══════════════════════════════════════
// 컬럼 선택 다이얼로그
// ═══════════════════════════════════════
public class ColumnSelectDialog : Form
{
    public int SelectedIndex { get; private set; } = -1;
    private readonly ListBox _listBox;

    public ColumnSelectDialog(List<string> headers)
    {
        Text = "수령인 휴대폰 컬럼 선택"; Size = new Size(350, 400);
        StartPosition = FormStartPosition.CenterParent;
        FormBorderStyle = FormBorderStyle.FixedDialog; MaximizeBox = false;

        var label = new Label { Text = "휴대폰 컬럼을 선택하세요:", Dock = DockStyle.Top, Height = 30, Padding = new Padding(8) };
        _listBox = new ListBox { Dock = DockStyle.Fill };
        for (int i = 0; i < headers.Count; i++)
            _listBox.Items.Add($"[{(char)('A' + (i < 26 ? i : 0))}열] {headers[i]}");

        var btnOk = new Button { Text = "선택", DialogResult = DialogResult.OK, Dock = DockStyle.Bottom, Height = 35 };
        btnOk.Click += (_, _) => { SelectedIndex = _listBox.SelectedIndex; };

        Controls.Add(_listBox); Controls.Add(label); Controls.Add(btnOk);
        AcceptButton = btnOk;
    }
}
