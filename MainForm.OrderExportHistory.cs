using Cafe24ShipmentManager.Models;

namespace Cafe24ShipmentManager;

public partial class MainForm
{
    private bool _orderExportHistoryUiInitializedEx;
    private bool _updatingOrderExportHistoryFiltersEx;
    private DataGridView? _dgvOrderExportHistoryBatchesEx;
    private DataGridView? _dgvOrderExportHistoryRowsEx;
    private Label? _lblOrderExportHistorySummaryEx;
    private Label? _lblOrderExportHistoryDetailSummaryEx;
    private ComboBox? _cboOrderExportHistoryMarketEx;
    private DateTimePicker? _dtpOrderExportHistoryFromEx;
    private DateTimePicker? _dtpOrderExportHistoryToEx;
    private long _selectedOrderExportBatchIdEx;
    private List<ShipmentRequestExportBatch> _orderExportHistoryBatchesEx = new();

    private void EnsureOrderExportHistoryUi()
    {
        if (_orderExportHistoryUiInitializedEx || tabShipSub == null)
            return;

        var page = tabShipSub.TabPages.Cast<TabPage>()
            .FirstOrDefault(tab => string.Equals(tab.Text, "출고용 이력", StringComparison.OrdinalIgnoreCase));
        if (page == null)
        {
            page = new TabPage("출고용 이력");
            tabShipSub.TabPages.Add(page);
        }

        page.Controls.Clear();

        var split = new SplitContainer
        {
            Dock = DockStyle.Fill,
            Orientation = Orientation.Horizontal,
            SplitterDistance = 220
        };

        var batchPanel = new Panel { Dock = DockStyle.Top, Height = 72 };
        var btnRefresh = new Button
        {
            Name = "btnOrderExportHistoryRefreshEx",
            Text = "새로고침",
            Width = 88,
            Height = 30,
            Location = new Point(4, 4)
        };
        var btnReset = new Button
        {
            Name = "btnOrderExportHistoryResetEx",
            Text = "필터 초기화",
            Width = 94,
            Height = 30,
            Location = new Point(96, 4)
        };
        var lblMarket = new Label
        {
            AutoSize = true,
            Text = "마켓:",
            Location = new Point(202, 11)
        };
        _cboOrderExportHistoryMarketEx = new ComboBox
        {
            Name = "cboOrderExportHistoryMarketEx",
            Location = new Point(246, 7),
            Width = 120,
            DropDownStyle = ComboBoxStyle.DropDownList
        };
        _cboOrderExportHistoryMarketEx.Items.Add("전체");
        _cboOrderExportHistoryMarketEx.SelectedIndex = 0;

        var lblDate = new Label
        {
            AutoSize = true,
            Text = "출고일:",
            Location = new Point(382, 11)
        };
        _dtpOrderExportHistoryFromEx = new DateTimePicker
        {
            Name = "dtpOrderExportHistoryFromEx",
            Location = new Point(438, 7),
            Width = 126,
            Format = DateTimePickerFormat.Custom,
            CustomFormat = "yyyy-MM-dd",
            ShowCheckBox = true,
            Checked = false,
            Value = DateTime.Today
        };
        var lblRange = new Label
        {
            AutoSize = true,
            Text = "~",
            Location = new Point(572, 11)
        };
        _dtpOrderExportHistoryToEx = new DateTimePicker
        {
            Name = "dtpOrderExportHistoryToEx",
            Location = new Point(588, 7),
            Width = 126,
            Format = DateTimePickerFormat.Custom,
            CustomFormat = "yyyy-MM-dd",
            ShowCheckBox = true,
            Checked = false,
            Value = DateTime.Today
        };
        _lblOrderExportHistorySummaryEx = new Label
        {
            AutoSize = true,
            Location = new Point(8, 44),
            ForeColor = Color.DimGray,
            Text = "출고용 이력을 불러오는 중입니다."
        };
        batchPanel.Controls.AddRange(new Control[]
        {
            btnRefresh,
            btnReset,
            lblMarket,
            _cboOrderExportHistoryMarketEx,
            lblDate,
            _dtpOrderExportHistoryFromEx,
            lblRange,
            _dtpOrderExportHistoryToEx,
            _lblOrderExportHistorySummaryEx
        });

        _dgvOrderExportHistoryBatchesEx = CreateGridView();
        _dgvOrderExportHistoryBatchesEx.Name = "dgvOrderExportHistoryBatchesEx";
        _dgvOrderExportHistoryBatchesEx.ReadOnly = true;
        _dgvOrderExportHistoryBatchesEx.MultiSelect = false;
        _dgvOrderExportHistoryBatchesEx.SelectionMode = DataGridViewSelectionMode.FullRowSelect;
        _dgvOrderExportHistoryBatchesEx.AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill;
        ConfigureOrderExportHistoryBatchGridEx(_dgvOrderExportHistoryBatchesEx);

        split.Panel1.Controls.Add(_dgvOrderExportHistoryBatchesEx);
        split.Panel1.Controls.Add(batchPanel);

        var detailPanel = new Panel { Dock = DockStyle.Top, Height = 36 };
        _lblOrderExportHistoryDetailSummaryEx = new Label
        {
            AutoSize = true,
            Location = new Point(8, 9),
            ForeColor = Color.DimGray,
            Text = "배치를 선택하면 상세 행이 표시됩니다."
        };
        detailPanel.Controls.Add(_lblOrderExportHistoryDetailSummaryEx);

        _dgvOrderExportHistoryRowsEx = CreateGridView();
        _dgvOrderExportHistoryRowsEx.Name = "dgvOrderExportHistoryRowsEx";
        _dgvOrderExportHistoryRowsEx.ReadOnly = true;
        _dgvOrderExportHistoryRowsEx.MultiSelect = false;
        _dgvOrderExportHistoryRowsEx.SelectionMode = DataGridViewSelectionMode.FullRowSelect;
        _dgvOrderExportHistoryRowsEx.AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill;
        ConfigureOrderExportHistoryRowGridEx(_dgvOrderExportHistoryRowsEx);

        split.Panel2.Controls.Add(_dgvOrderExportHistoryRowsEx);
        split.Panel2.Controls.Add(detailPanel);

        page.Controls.Add(split);

        btnRefresh.Click += (_, _) => RefreshOrderExportHistoryBatchesEx(_selectedOrderExportBatchIdEx > 0 ? _selectedOrderExportBatchIdEx : null);
        btnReset.Click += (_, _) => ResetOrderExportHistoryFiltersEx();
        _cboOrderExportHistoryMarketEx.SelectedIndexChanged += (_, _) => ApplyOrderExportHistoryFiltersEx(_selectedOrderExportBatchIdEx > 0 ? _selectedOrderExportBatchIdEx : null);
        _dtpOrderExportHistoryFromEx.ValueChanged += (_, _) => ApplyOrderExportHistoryFiltersEx(_selectedOrderExportBatchIdEx > 0 ? _selectedOrderExportBatchIdEx : null);
        _dtpOrderExportHistoryToEx.ValueChanged += (_, _) => ApplyOrderExportHistoryFiltersEx(_selectedOrderExportBatchIdEx > 0 ? _selectedOrderExportBatchIdEx : null);
        _dgvOrderExportHistoryBatchesEx.SelectionChanged += (_, _) => LoadSelectedOrderExportHistoryRowsEx();

        _orderExportHistoryUiInitializedEx = true;
        RefreshOrderExportHistoryBatchesEx();
    }

    private static void ConfigureOrderExportHistoryBatchGridEx(DataGridView grid)
    {
        grid.Columns.Clear();
        grid.Columns.Add("CreatedAt", "저장시각");
        grid.Columns.Add("SavedFrom", "저장방식");
        grid.Columns.Add("MarketName", "마켓명");
        grid.Columns.Add("ExportDate", "출고일");
        grid.Columns.Add("RowCount", "행수");
        grid.Columns.Add("MissingProductCodeCount", "빈코드");
        grid.Columns.Add("UserName", "사용자");
        grid.Columns.Add("FilePath", "파일경로");

        grid.Columns["CreatedAt"]!.Width = 150;
        grid.Columns["SavedFrom"]!.Width = 90;
        grid.Columns["MarketName"]!.Width = 90;
        grid.Columns["ExportDate"]!.Width = 90;
        grid.Columns["RowCount"]!.Width = 55;
        grid.Columns["MissingProductCodeCount"]!.Width = 55;
        grid.Columns["UserName"]!.Width = 80;
        grid.Columns["FilePath"]!.Width = 280;
    }

    private static void ConfigureOrderExportHistoryRowGridEx(DataGridView grid)
    {
        grid.Columns.Clear();
        grid.Columns.Add("RowNumber", "순번");
        grid.Columns.Add("ProductCode", "상품코드");
        grid.Columns.Add("MarketName", "마켓명");
        grid.Columns.Add("ExportDate", "출고일");
        grid.Columns.Add("Quantity", "수량");
        grid.Columns.Add("RecipientName", "수령인");
        grid.Columns.Add("RecipientPhone", "전화번호");
        grid.Columns.Add("PostalCode", "우편번호");
        grid.Columns.Add("FullAddress", "주소");
        grid.Columns.Add("ShippingMessage", "배송메시지");
        grid.Columns.Add("DetailAddress", "상세주소");

        grid.Columns["RowNumber"]!.Width = 50;
        grid.Columns["ProductCode"]!.Width = 110;
        grid.Columns["MarketName"]!.Width = 90;
        grid.Columns["ExportDate"]!.Width = 90;
        grid.Columns["Quantity"]!.Width = 50;
        grid.Columns["RecipientName"]!.Width = 85;
        grid.Columns["RecipientPhone"]!.Width = 110;
        grid.Columns["PostalCode"]!.Width = 75;
        grid.Columns["FullAddress"]!.Width = 240;
        grid.Columns["ShippingMessage"]!.Width = 180;
        grid.Columns["DetailAddress"]!.Width = 180;
    }

    private void RefreshOrderExportHistoryBatchesEx(long? selectedBatchId = null)
    {
        if (!_orderExportHistoryUiInitializedEx || _dgvOrderExportHistoryBatchesEx == null)
            return;

        _orderExportHistoryBatchesEx = _db.GetRecentShipmentRequestExportBatches(_currentUser.Id, 200);
        RefreshOrderExportHistoryMarketOptionsEx();
        ApplyOrderExportHistoryFiltersEx(selectedBatchId);
    }

    private void RefreshOrderExportHistoryMarketOptionsEx()
    {
        if (_cboOrderExportHistoryMarketEx == null)
            return;

        var selectedMarket = _cboOrderExportHistoryMarketEx.SelectedItem?.ToString() ?? "전체";
        var markets = _orderExportHistoryBatchesEx
            .Select(batch => batch.MarketName?.Trim() ?? string.Empty)
            .Where(name => !string.IsNullOrWhiteSpace(name))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .OrderBy(name => name)
            .ToList();

        _updatingOrderExportHistoryFiltersEx = true;
        _cboOrderExportHistoryMarketEx.BeginUpdate();
        _cboOrderExportHistoryMarketEx.Items.Clear();
        _cboOrderExportHistoryMarketEx.Items.Add("전체");
        foreach (var market in markets)
            _cboOrderExportHistoryMarketEx.Items.Add(market);

        var selectedIndex = 0;
        if (!string.IsNullOrWhiteSpace(selectedMarket) && !string.Equals(selectedMarket, "전체", StringComparison.OrdinalIgnoreCase))
        {
            var matchIndex = markets.FindIndex(market => string.Equals(market, selectedMarket, StringComparison.OrdinalIgnoreCase));
            if (matchIndex >= 0)
                selectedIndex = matchIndex + 1;
        }

        _cboOrderExportHistoryMarketEx.SelectedIndex = selectedIndex;
        _cboOrderExportHistoryMarketEx.EndUpdate();
        _updatingOrderExportHistoryFiltersEx = false;
    }

    private void ResetOrderExportHistoryFiltersEx()
    {
        if (_cboOrderExportHistoryMarketEx == null || _dtpOrderExportHistoryFromEx == null || _dtpOrderExportHistoryToEx == null)
            return;

        _updatingOrderExportHistoryFiltersEx = true;
        if (_cboOrderExportHistoryMarketEx.Items.Count > 0)
            _cboOrderExportHistoryMarketEx.SelectedIndex = 0;
        _dtpOrderExportHistoryFromEx.Checked = false;
        _dtpOrderExportHistoryToEx.Checked = false;
        _updatingOrderExportHistoryFiltersEx = false;

        ApplyOrderExportHistoryFiltersEx();
    }

    private void ApplyOrderExportHistoryFiltersEx(long? selectedBatchId = null)
    {
        if (_updatingOrderExportHistoryFiltersEx || _dgvOrderExportHistoryBatchesEx == null)
            return;

        var targetBatchId = selectedBatchId ?? _selectedOrderExportBatchIdEx;
        var selectedMarket = _cboOrderExportHistoryMarketEx?.SelectedItem?.ToString() ?? "전체";

        DateTime? startDate = _dtpOrderExportHistoryFromEx?.Checked == true ? _dtpOrderExportHistoryFromEx.Value.Date : null;
        DateTime? endDate = _dtpOrderExportHistoryToEx?.Checked == true ? _dtpOrderExportHistoryToEx.Value.Date : null;
        if (startDate.HasValue && endDate.HasValue && startDate > endDate)
            (startDate, endDate) = (endDate, startDate);

        var filtered = _orderExportHistoryBatchesEx.Where(batch => MatchesOrderExportHistoryFiltersEx(batch, selectedMarket, startDate, endDate)).ToList();

        _dgvOrderExportHistoryBatchesEx.Rows.Clear();
        DataGridViewRow? rowToSelect = null;

        foreach (var batch in filtered)
        {
            var rowIndex = _dgvOrderExportHistoryBatchesEx.Rows.Add(
                batch.CreatedAt,
                ResolveOrderExportSavedFromLabelEx(batch.SavedFrom),
                batch.MarketName,
                batch.ExportDate,
                batch.RowCount,
                batch.MissingProductCodeCount,
                batch.UserName,
                string.IsNullOrWhiteSpace(batch.FilePath) ? "-" : batch.FilePath);

            var gridRow = _dgvOrderExportHistoryBatchesEx.Rows[rowIndex];
            gridRow.Tag = batch;

            if (targetBatchId > 0 && batch.Id == targetBatchId)
                rowToSelect = gridRow;
        }

        if (_lblOrderExportHistorySummaryEx != null)
        {
            var filterSummary = BuildOrderExportHistoryFilterSummaryEx(selectedMarket, startDate, endDate);
            _lblOrderExportHistorySummaryEx.Text = filtered.Count == _orderExportHistoryBatchesEx.Count
                ? $"현재 사용자 기준 최근 저장 배치 {_orderExportHistoryBatchesEx.Count}건"
                : $"현재 사용자 기준 최근 저장 배치 {_orderExportHistoryBatchesEx.Count}건 / 필터 결과 {filtered.Count}건 ({filterSummary})";
        }

        if (_dgvOrderExportHistoryBatchesEx.Rows.Count == 0)
        {
            _selectedOrderExportBatchIdEx = 0;
            ClearOrderExportHistoryRowsEx();
            return;
        }

        rowToSelect ??= _dgvOrderExportHistoryBatchesEx.Rows[0];
        _dgvOrderExportHistoryBatchesEx.ClearSelection();
        rowToSelect.Selected = true;
        if (rowToSelect.Cells.Count > 0)
            _dgvOrderExportHistoryBatchesEx.CurrentCell = rowToSelect.Cells[0];
    }

    private static bool MatchesOrderExportHistoryFiltersEx(
        ShipmentRequestExportBatch batch,
        string selectedMarket,
        DateTime? startDate,
        DateTime? endDate)
    {
        if (!string.IsNullOrWhiteSpace(selectedMarket) && !string.Equals(selectedMarket, "전체", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(batch.MarketName, selectedMarket, StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        if (!startDate.HasValue && !endDate.HasValue)
            return true;

        if (!DateTime.TryParse(batch.ExportDate, out var exportDate))
            return false;

        var normalized = exportDate.Date;
        if (startDate.HasValue && normalized < startDate.Value)
            return false;
        if (endDate.HasValue && normalized > endDate.Value)
            return false;

        return true;
    }

    private static string BuildOrderExportHistoryFilterSummaryEx(string selectedMarket, DateTime? startDate, DateTime? endDate)
    {
        var parts = new List<string>();

        if (!string.IsNullOrWhiteSpace(selectedMarket) && !string.Equals(selectedMarket, "전체", StringComparison.OrdinalIgnoreCase))
            parts.Add($"마켓 {selectedMarket}");

        if (startDate.HasValue || endDate.HasValue)
        {
            var from = startDate?.ToString("yyyy-MM-dd") ?? "-";
            var to = endDate?.ToString("yyyy-MM-dd") ?? "-";
            parts.Add($"출고일 {from}~{to}");
        }

        return parts.Count == 0 ? "전체" : string.Join(" / ", parts);
    }

    private void LoadSelectedOrderExportHistoryRowsEx()
    {
        if (_dgvOrderExportHistoryBatchesEx == null || _dgvOrderExportHistoryRowsEx == null)
            return;

        if (_dgvOrderExportHistoryBatchesEx.SelectedRows.Count == 0 ||
            _dgvOrderExportHistoryBatchesEx.SelectedRows[0].Tag is not ShipmentRequestExportBatch batch)
        {
            _selectedOrderExportBatchIdEx = 0;
            ClearOrderExportHistoryRowsEx();
            return;
        }

        _selectedOrderExportBatchIdEx = batch.Id;
        var rows = _db.GetShipmentRequestExportRows(batch.Id);

        _dgvOrderExportHistoryRowsEx.Rows.Clear();
        foreach (var row in rows)
        {
            _dgvOrderExportHistoryRowsEx.Rows.Add(
                row.RowNumber,
                row.ProductCode,
                row.MarketName,
                row.ExportDate,
                row.Quantity,
                row.RecipientName,
                row.RecipientPhone,
                row.PostalCode,
                row.FullAddress,
                row.ShippingMessage,
                row.DetailAddress);
        }

        if (_lblOrderExportHistoryDetailSummaryEx != null)
        {
            var pathSuffix = string.IsNullOrWhiteSpace(batch.FilePath) ? "" : $" / 파일 {batch.FilePath}";
            _lblOrderExportHistoryDetailSummaryEx.Text =
                $"배치 {batch.Id} / {ResolveOrderExportSavedFromLabelEx(batch.SavedFrom)} / {rows.Count}행 / 빈코드 {batch.MissingProductCodeCount}건{pathSuffix}";
        }
    }

    private void ClearOrderExportHistoryRowsEx()
    {
        _dgvOrderExportHistoryRowsEx?.Rows.Clear();
        if (_lblOrderExportHistoryDetailSummaryEx != null)
            _lblOrderExportHistoryDetailSummaryEx.Text = "배치를 선택하면 상세 행이 표시됩니다.";
    }

    private static string ResolveOrderExportSavedFromLabelEx(string savedFrom)
    {
        return savedFrom switch
        {
            "clipboard" => "클립보드",
            "workbook" => "엑셀 저장",
            _ => string.IsNullOrWhiteSpace(savedFrom) ? "-" : savedFrom
        };
    }
}
