using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using ClosedXML.Excel;
using Cafe24ShipmentManager.Models;
using Cafe24ShipmentManager.Services;
using Newtonsoft.Json.Linq;

namespace Cafe24ShipmentManager;

public partial class MainForm
{
    private const string OrderSelectColumnNameEx = "OrderSelectedEx";
    private const string OrderProgressColumnNameEx = "OrderProgressEx";

    private Button? _btnOrderSelectAllEx;
    private Button? _btnOrderDeselectAllEx;
    private Button? _btnOrderExportEx;
    private Label? _lblOrderSelectionEx;
    private ComboBox? _cboOrderExportMarketEx;
    private DateTimePicker? _dtpOrderExportDateEx;
    private CheckBox? _chkOrderExportSupplierProductEx;
    private CheckBox? _chkOrderExportOptionEx;
    private Label? _lblOrderExportMarketColumnEx;
    private Label? _lblOrderExportDateColumnEx;
    private Label? _lblOrderExportSummaryEx;
    private ContextMenuStrip? _menuOrderExportEx;
    private ToolStripMenuItem? _itemOrderExportCopyEx;
    private ToolStripMenuItem? _itemOrderExportSaveEx;
    private bool _orderExportUiInitializedEx;
    private bool _orderExportDateDropdownOpenEx;
    private readonly Dictionary<string, string> _orderProgressStateByKeyEx = new(StringComparer.OrdinalIgnoreCase);

    private void EnsureOrderExportUi()
    {
        if (_orderExportUiInitializedEx) return;
        if (tabShipSub == null || dgvData == null) return;

        var subData = tabShipSub.TabPages.Cast<TabPage>().FirstOrDefault(p => p.Text.Contains("데이터"));
        var panel = subData?.Controls.OfType<Panel>().FirstOrDefault(p => p.Dock == DockStyle.Top);
        if (panel == null) return;

        _orderExportUiInitializedEx = true;

        _btnOrderSelectAllEx = new Button
        {
            Name = "btnOrderSelectAllEx",
            Text = "전체선택",
            Width = 82,
            Height = 30,
            Location = new Point(140, 3)
        };
        _btnOrderDeselectAllEx = new Button
        {
            Name = "btnOrderDeselectAllEx",
            Text = "전체해제",
            Width = 82,
            Height = 30,
            Location = new Point(226, 3)
        };
        _btnOrderExportEx = new Button
        {
            Name = "btnOrderExportEx",
            Text = "출고용",
            Width = 96,
            Height = 30,
            Location = new Point(312, 3),
            Enabled = false
        };
        _lblOrderSelectionEx = new Label
        {
            AutoSize = true,
            Location = new Point(420, 9),
            ForeColor = Color.DimGray,
            Text = "선택 0 / 0"
        };

        panel.Controls.AddRange(new Control[]
        {
            _btnOrderSelectAllEx,
            _btnOrderDeselectAllEx,
            _btnOrderExportEx,
            _lblOrderSelectionEx
        });

        _btnOrderSelectAllEx.Click += (_, _) => SetAllPreviewOrdersCheckedEx(true);
        _btnOrderDeselectAllEx.Click += (_, _) => SetAllPreviewOrdersCheckedEx(false);
        _btnOrderExportEx.Click += (_, _) => ShowOrderExportPopupMenuEx();

        dgvData.CurrentCellDirtyStateChanged += (_, _) =>
        {
            if (dgvData.IsCurrentCellDirty && dgvData.CurrentCell?.OwningColumn?.Name == OrderSelectColumnNameEx)
                dgvData.CommitEdit(DataGridViewDataErrorContexts.Commit);
        };
        dgvData.CellValueChanged += (_, e) =>
        {
            if (e.RowIndex >= 0 && e.ColumnIndex >= 0 && dgvData.Columns[e.ColumnIndex].Name == OrderSelectColumnNameEx)
                UpdateOrderSelectionSummaryEx();
        };

        EnsureOrderExportPopupMenuEx();
        EnsureOrderExportHistoryUi();
        UpdateOrderSelectionSummaryEx();
    }

    private void EnsureOrderSelectionColumnEx()
    {
        if (dgvData.Columns.Contains(OrderSelectColumnNameEx)) return;

        var col = new DataGridViewCheckBoxColumn
        {
            Name = OrderSelectColumnNameEx,
            HeaderText = "선택",
            Width = 50,
            TrueValue = true,
            FalseValue = false,
            AutoSizeMode = DataGridViewAutoSizeColumnMode.None
        };
        dgvData.Columns.Insert(0, col);
    }

    private void EnsureOrderExportPopupMenuEx()
    {
        if (_menuOrderExportEx != null) return;

        _cboOrderExportMarketEx = new ComboBox
        {
            DropDownStyle = ComboBoxStyle.DropDown,
            Width = 150
        };
        _cboOrderExportMarketEx.Items.AddRange(new object[]
        {
            ShipmentRequestOrderExportFormatterEx.DefaultMarketName,
            "반짝세일",
            "스마트스토어"
        });
        _cboOrderExportMarketEx.Text = ShipmentRequestOrderExportFormatterEx.DefaultMarketName;

        _dtpOrderExportDateEx = new DateTimePicker
        {
            Width = 120,
            Format = DateTimePickerFormat.Custom,
            CustomFormat = "yyyy-MM-dd",
            Value = DateTime.Today
        };
        _chkOrderExportSupplierProductEx = new CheckBox
        {
            AutoSize = true,
            Text = "공급사상품명",
            Margin = new Padding(0, 4, 12, 0)
        };
        _chkOrderExportOptionEx = new CheckBox
        {
            AutoSize = true,
            Text = "옵션",
            Margin = new Padding(0, 4, 0, 0)
        };
        var optionalColumnsPanel = new FlowLayoutPanel
        {
            AutoSize = true,
            FlowDirection = FlowDirection.LeftToRight,
            Margin = Padding.Empty,
            Padding = Padding.Empty,
            WrapContents = false
        };
        optionalColumnsPanel.Controls.AddRange(new Control[] { _chkOrderExportSupplierProductEx, _chkOrderExportOptionEx });

        _lblOrderExportMarketColumnEx = new Label { AutoSize = true, Text = "B열 마켓명:", Margin = new Padding(0, 6, 8, 4) };
        _lblOrderExportDateColumnEx = new Label { AutoSize = true, Text = "C열 날짜:", Margin = new Padding(0, 8, 8, 4) };

        _lblOrderExportSummaryEx = new Label
        {
            AutoSize = true,
            ForeColor = Color.DimGray,
            MaximumSize = new Size(280, 0),
            Text = "선택 주문을 체크하면 출고용 작업 메뉴가 열립니다."
        };

        _cboOrderExportMarketEx.TextChanged += (_, _) => RefreshOrderExportPopupSummaryEx();
        _dtpOrderExportDateEx.ValueChanged += (_, _) => RefreshOrderExportPopupSummaryEx();
        _dtpOrderExportDateEx.DropDown += (_, _) =>
        {
            _orderExportDateDropdownOpenEx = true;
            if (_menuOrderExportEx != null)
                _menuOrderExportEx.AutoClose = false;
        };
        _dtpOrderExportDateEx.CloseUp += (_, _) =>
        {
            _orderExportDateDropdownOpenEx = false;
            BeginInvoke(new Action(() =>
            {
                if (_menuOrderExportEx != null)
                    _menuOrderExportEx.AutoClose = true;
            }));
            RefreshOrderExportPopupSummaryEx();
        };
        _chkOrderExportSupplierProductEx.CheckedChanged += (_, _) => RefreshOrderExportPopupSummaryEx();
        _chkOrderExportOptionEx.CheckedChanged += (_, _) => RefreshOrderExportPopupSummaryEx();

        var contentPanel = new TableLayoutPanel
        {
            AutoSize = true,
            ColumnCount = 2,
            RowCount = 4,
            Padding = new Padding(10),
            Margin = Padding.Empty
        };
        contentPanel.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        contentPanel.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        contentPanel.Controls.Add(new Label { AutoSize = true, Text = "앞열 추가:", Margin = new Padding(0, 6, 8, 4) }, 0, 0);
        contentPanel.Controls.Add(optionalColumnsPanel, 1, 0);
        contentPanel.Controls.Add(_lblOrderExportMarketColumnEx, 0, 1);
        contentPanel.Controls.Add(_cboOrderExportMarketEx, 1, 1);
        contentPanel.Controls.Add(_lblOrderExportDateColumnEx, 0, 2);
        contentPanel.Controls.Add(_dtpOrderExportDateEx, 1, 2);
        contentPanel.Controls.Add(_lblOrderExportSummaryEx, 0, 3);
        contentPanel.SetColumnSpan(_lblOrderExportSummaryEx, 2);

        var host = new ToolStripControlHost(contentPanel)
        {
            AutoSize = false,
            Margin = Padding.Empty,
            Padding = Padding.Empty,
            Size = contentPanel.GetPreferredSize(Size.Empty)
        };

        _itemOrderExportCopyEx = new ToolStripMenuItem("클립보드 복사");
        _itemOrderExportSaveEx = new ToolStripMenuItem("엑셀 저장");
        _itemOrderExportCopyEx.Click += (_, _) =>
        {
            _menuOrderExportEx?.Close();
            CopySelectedOrdersToClipboardEx();
        };
        _itemOrderExportSaveEx.Click += (_, _) =>
        {
            _menuOrderExportEx?.Close();
            SaveSelectedOrdersWorkbookEx();
        };

        _menuOrderExportEx = new ContextMenuStrip
        {
            ShowImageMargin = false,
            ShowCheckMargin = false,
            AutoClose = true
        };
        _menuOrderExportEx.Closing += (_, e) =>
        {
            if (_orderExportDateDropdownOpenEx)
                e.Cancel = true;
        };
        _menuOrderExportEx.Items.Add(host);
        _menuOrderExportEx.Items.Add(new ToolStripSeparator());
        _menuOrderExportEx.Items.Add(_itemOrderExportCopyEx);
        _menuOrderExportEx.Items.Add(_itemOrderExportSaveEx);
    }

    private void SetAllPreviewOrdersCheckedEx(bool isChecked)
    {
        if (!dgvData.Columns.Contains(OrderSelectColumnNameEx)) return;

        foreach (DataGridViewRow row in dgvData.Rows)
        {
            if (row.IsNewRow) continue;
            row.Cells[OrderSelectColumnNameEx].Value = isChecked;
        }

        UpdateOrderSelectionSummaryEx();
    }

    private List<Cafe24Order> GetCheckedPreviewOrdersEx()
    {
        var result = new List<Cafe24Order>();
        if (!dgvData.Columns.Contains(OrderSelectColumnNameEx)) return result;

        foreach (DataGridViewRow row in dgvData.Rows)
        {
            if (row.IsNewRow) continue;
            if (!IsPreviewOrderCheckedEx(row)) continue;
            if (row.Tag is Cafe24Order order)
                result.Add(order);
        }

        return result;
    }

    private bool IsPreviewOrderCheckedEx(DataGridViewRow row)
    {
        var value = row.Cells[OrderSelectColumnNameEx].Value;
        return value is bool flag && flag;
    }

    private void MarkCheckedPreviewOrdersAsCopiedEx()
    {
        if (!dgvData.Columns.Contains(OrderProgressColumnNameEx)) return;

        var changed = false;
        foreach (DataGridViewRow row in dgvData.Rows)
        {
            if (row.IsNewRow) continue;
            if (!IsPreviewOrderCheckedEx(row)) continue;
            if (row.Tag is not Cafe24Order order) continue;

            SetPersistedOrderProgressCodeEx(order, "copied", saveState: false);
            ApplyPreviewOrderProgressEx(row, order);
            changed = true;
        }

        if (changed)
            SaveEnhancedState();
    }

    private static string BuildOrderStateKeyEx(Cafe24Order order)
    {
        return string.Join("|", new[]
        {
            order.MallId?.Trim() ?? "",
            order.OrderId?.Trim() ?? "",
            order.OrderItemCode?.Trim() ?? ""
        });
    }

    private string GetPersistedOrderProgressCodeEx(Cafe24Order order)
    {
        var key = BuildOrderStateKeyEx(order);
        return _orderProgressStateByKeyEx.TryGetValue(key, out var code)
            ? NormalizeOrderProgressCodeEx(code)
            : "";
    }

    private void SetPersistedOrderProgressCodeEx(Cafe24Order order, string code, bool saveState = true)
    {
        var normalized = NormalizeOrderProgressCodeEx(code);
        var key = BuildOrderStateKeyEx(order);
        if (string.IsNullOrWhiteSpace(key))
            return;

        if (string.IsNullOrWhiteSpace(normalized))
            _orderProgressStateByKeyEx.Remove(key);
        else
            _orderProgressStateByKeyEx[key] = normalized;

        if (saveState)
            SaveEnhancedState();
    }

    private static string NormalizeOrderProgressCodeEx(string? code)
    {
        return code?.Trim() switch
        {
            "copied" => "copied",
            "delivery_waiting" => "delivery_waiting",
            "pushed" => "pushed",
            _ => ""
        };
    }

    private string ResolveOrderProgressLabelEx(Cafe24Order order)
    {
        if (order.PendingShipment)
            return string.IsNullOrWhiteSpace(order.PendingShipmentDateLabel)
                ? "미출고 확인"
                : $"미출고 {order.PendingShipmentDateLabel}";

        return GetPersistedOrderProgressCodeEx(order) switch
        {
            "copied" => "복사완료",
            "delivery_waiting" => "배송대기중",
            "pushed" => "반영완료",
            _ => "조회완료"
        };
    }

    private Color ResolvePreviewOrderRowBackColorEx(Cafe24Order order)
    {
        if (order.PendingShipment)
            return UiPalette.PendingSurface;

        return GetPersistedOrderProgressCodeEx(order) switch
        {
            "copied" => UiPalette.CopiedSurface,
            "delivery_waiting" => UiPalette.WaitingSurface,
            "pushed" => UiPalette.SuccessSurface,
            _ => Color.White
        };
    }

    private void ApplyPreviewOrderProgressEx(DataGridViewRow row, Cafe24Order order)
    {
        row.Cells[OrderProgressColumnNameEx].Value = ResolveOrderProgressLabelEx(order);
        row.DefaultCellStyle.BackColor = ResolvePreviewOrderRowBackColorEx(order);
        row.DefaultCellStyle.ForeColor = UiPalette.Text;
    }

    private void RefreshDataPreviewProgressStatesEx()
    {
        if (dgvData == null || !dgvData.Columns.Contains(OrderProgressColumnNameEx))
            return;

        foreach (DataGridViewRow row in dgvData.Rows)
        {
            if (row.IsNewRow) continue;
            if (row.Tag is Cafe24Order order)
                ApplyPreviewOrderProgressEx(row, order);
        }
    }

    private void UpdateOrderSelectionSummaryEx()
    {
        if (_lblOrderSelectionEx == null) return;

        var total = dgvData?.Rows.Cast<DataGridViewRow>().Count(r => !r.IsNewRow) ?? 0;
        var selected = dgvData?.Rows.Cast<DataGridViewRow>().Count(r => !r.IsNewRow && IsPreviewOrderCheckedEx(r)) ?? 0;
        _lblOrderSelectionEx.Text = $"선택 {selected} / {total}";

        if (_btnOrderExportEx != null)
            _btnOrderExportEx.Enabled = selected > 0;

        RefreshOrderExportPopupSummaryEx();
    }

    private void ShowOrderExportPopupMenuEx()
    {
        var selectedOrders = GetCheckedPreviewOrdersEx();
        if (selectedOrders.Count == 0)
        {
            MessageBox.Show(this, "먼저 데이터 프리뷰에서 주문을 체크하세요.", "알림", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        EnsureOrderExportPopupMenuEx();
        ApplyOrderExportDefaultsEx(selectedOrders);
        RefreshOrderExportPopupSummaryEx(selectedOrders);

        if (_btnOrderExportEx != null && _menuOrderExportEx != null)
            _menuOrderExportEx.Show(_btnOrderExportEx, new Point(0, _btnOrderExportEx.Height));

        _log.Info($"선택 주문 출고용 팝업 메뉴 열림: {selectedOrders.Count}건");
    }

    private void ApplyOrderExportDefaultsEx(IReadOnlyCollection<Cafe24Order> selectedOrders)
    {
        if (_dtpOrderExportDateEx != null)
            _dtpOrderExportDateEx.Value = ShipmentRequestOrderExportFormatterEx.ResolveDefaultDate(selectedOrders);

        if (_cboOrderExportMarketEx != null && string.IsNullOrWhiteSpace(_cboOrderExportMarketEx.Text))
            _cboOrderExportMarketEx.Text = ShipmentRequestOrderExportFormatterEx.DefaultMarketName;
    }

    private void RefreshOrderExportPopupSummaryEx(IReadOnlyCollection<Cafe24Order>? selectedOrders = null)
    {
        RefreshOrderExportColumnLabelsEx();
        if (_lblOrderExportSummaryEx == null) return;

        var orders = selectedOrders ?? GetCheckedPreviewOrdersEx();
        if (orders.Count == 0)
        {
            _lblOrderExportSummaryEx.Text = "선택 주문을 체크하면 출고용 작업 메뉴가 열립니다.";
            if (_itemOrderExportCopyEx != null) _itemOrderExportCopyEx.Enabled = false;
            if (_itemOrderExportSaveEx != null) _itemOrderExportSaveEx.Enabled = false;
            return;
        }

        var rows = BuildOrderExportRowsEx(orders);
        var validCount = rows.Count(row => !string.IsNullOrWhiteSpace(row.ProductCode));
        var missingCount = rows.Count - validCount;
        var extraColumnCount = CurrentOrderExportLeadingColumnCountEx;
        var extraSummary = extraColumnCount > 0 ? $" / 앞열 추가 {extraColumnCount}칸" : "";
        _lblOrderExportSummaryEx.Text = $"선택 {orders.Count}건 / 코드 생성 {validCount}건 / 직접 입력 {missingCount}건{extraSummary}\n빈칸 건은 맨 위로 복사됩니다.";

        if (_itemOrderExportCopyEx != null) _itemOrderExportCopyEx.Enabled = true;
        if (_itemOrderExportSaveEx != null) _itemOrderExportSaveEx.Enabled = true;
    }

    private void RefreshOrderExportColumnLabelsEx()
    {
        var leadingColumns = CurrentOrderExportLeadingColumnCountEx;
        if (_lblOrderExportMarketColumnEx != null)
            _lblOrderExportMarketColumnEx.Text = $"{ToExcelColumnNameEx(2 + leadingColumns)}열 마켓명:";
        if (_lblOrderExportDateColumnEx != null)
            _lblOrderExportDateColumnEx.Text = $"{ToExcelColumnNameEx(3 + leadingColumns)}열 날짜:";
    }

    private static string ToExcelColumnNameEx(int oneBasedColumn)
    {
        var dividend = Math.Max(1, oneBasedColumn);
        var columnName = "";
        while (dividend > 0)
        {
            var modulo = (dividend - 1) % 26;
            columnName = Convert.ToChar('A' + modulo) + columnName;
            dividend = (dividend - modulo) / 26;
        }

        return columnName;
    }

    private void CopySelectedOrdersToClipboardEx()
    {
        var orders = GetCheckedPreviewOrdersEx();
        if (orders.Count == 0)
        {
            MessageBox.Show(this, "복사할 주문이 없습니다.", "알림", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        if (!TryPrepareOrderExportRowsEx(orders, out var rows))
            return;

        var clipboardText = ShipmentRequestOrderExportFormatterEx.BuildClipboardText(
            rows,
            CurrentOrderExportIncludeSupplierProductEx,
            CurrentOrderExportIncludeOptionEx);

        try
        {
            Clipboard.SetText(clipboardText, TextDataFormat.UnicodeText);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"클립보드 복사에 실패했습니다.\n{ex.Message}", "오류", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }

        string? historyError = null;
        try
        {
            SaveOrderExportHistoryEx(rows, "clipboard");
        }
        catch (Exception ex)
        {
            historyError = ex.Message;
            _log.Error("출고용 클립보드 이력 저장 실패", ex);
        }

        MarkCheckedPreviewOrdersAsCopiedEx();

        var missingCount = rows.Count(row => string.IsNullOrWhiteSpace(row.ProductCode));
        var message = $"클립보드에 {rows.Count}건을 복사했습니다.\n구글 시트의 상품코드 셀부터 바로 붙여넣으면 됩니다.";
        if (missingCount > 0)
            message += $"\n공급사 상품명 빈칸 {missingCount}건은 맨 위로 올려뒀습니다.";
        if (!string.IsNullOrWhiteSpace(historyError))
            message += $"\n출고용 이력 저장 실패: {historyError}";

        MessageBox.Show(this, message, "복사 완료", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    private void SaveSelectedOrdersWorkbookEx()
    {
        var orders = GetCheckedPreviewOrdersEx();
        if (orders.Count == 0)
        {
            MessageBox.Show(this, "저장할 주문이 없습니다.", "알림", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        using var dialog = new SaveFileDialog
        {
            Filter = "Excel Workbook (*.xlsx)|*.xlsx",
            DefaultExt = "xlsx",
            AddExtension = true,
            FileName = $"cafe24_selected_orders_{CurrentOrderExportDateValueEx:yyyyMMdd}_출고용.xlsx"
        };

        if (dialog.ShowDialog(this) != DialogResult.OK) return;

        if (!TryPrepareOrderExportRowsEx(orders, out var rows))
            return;

        ShipmentRequestOrderExportFormatterEx.SaveRowsAsWorkbook(
            rows,
            dialog.FileName,
            CurrentOrderExportIncludeSupplierProductEx,
            CurrentOrderExportIncludeOptionEx);

        string? historyError = null;
        try
        {
            SaveOrderExportHistoryEx(rows, "workbook", dialog.FileName);
        }
        catch (Exception ex)
        {
            historyError = ex.Message;
            _log.Error("출고용 엑셀 저장 이력 기록 실패", ex);
        }

        var message = $"출고용 엑셀 파일을 저장했습니다.\n{dialog.FileName}";
        if (!string.IsNullOrWhiteSpace(historyError))
            message += $"\n출고용 이력 저장 실패: {historyError}";

        MessageBox.Show(this, message, "저장 완료", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    private void SaveOrderExportHistoryEx(IReadOnlyList<ShipmentRequestOrderRowEx> rows, string savedFrom, string? filePath = null)
    {
        if (rows.Count == 0)
            return;

        var batch = new ShipmentRequestExportBatch
        {
            AppUserId = _currentUser.Id,
            UserName = _currentUser.EffectiveDisplayName,
            SavedFrom = savedFrom,
            MarketName = CurrentOrderExportMarketNameEx,
            ExportDate = CurrentOrderExportDateTextEx,
            RowCount = rows.Count,
            MissingProductCodeCount = rows.Count(row => string.IsNullOrWhiteSpace(row.ProductCode)),
            FilePath = filePath ?? string.Empty,
            CreatedAt = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture)
        };

        var historyRows = rows
            .Select((row, index) => new ShipmentRequestExportRow
            {
                RowNumber = index + 1,
                ProductCode = row.ProductCode,
                MarketName = row.MarketName,
                ExportDate = row.ExportDate,
                Quantity = row.Quantity,
                RecipientName = row.RecipientName,
                RecipientPhone = row.RecipientPhone,
                PostalCode = row.PostalCode,
                FullAddress = row.FullAddress,
                ShippingMessage = row.ShippingMessage,
                DetailAddress = row.DetailAddress
            })
            .ToList();

        var batchId = _db.InsertShipmentRequestExportBatch(batch, historyRows);
        _log.Info($"출고용 이력 저장: {batchId} / {savedFrom} / {rows.Count}건");
        RefreshOrderExportHistoryBatchesEx(batchId);
    }

    private List<ShipmentRequestOrderRowEx> BuildOrderExportRowsEx(IReadOnlyCollection<Cafe24Order> orders)
    {
        return ShipmentRequestOrderExportFormatterEx.BuildRows(
            orders,
            CurrentOrderExportMarketNameEx,
            CurrentOrderExportDateTextEx,
            LoadOrderExportProductCodeMappingsEx()).ToList();
    }

    private Dictionary<string, string> LoadOrderExportProductCodeMappingsEx()
    {
        try
        {
            return _db.GetShipmentRequestProductCodeMappings(_currentUser.Id)
                .Where(mapping => !string.IsNullOrWhiteSpace(mapping.ProductKey) &&
                                  !string.IsNullOrWhiteSpace(mapping.ProductCode))
                .GroupBy(mapping => mapping.ProductKey, StringComparer.OrdinalIgnoreCase)
                .ToDictionary(
                    group => group.Key,
                    group => group.Last().ProductCode.Trim().ToUpperInvariant(),
                    StringComparer.OrdinalIgnoreCase);
        }
        catch (Exception ex)
        {
            _log.Warn($"출고용 상품코드 매핑 로드 실패: {ex.Message}");
            return new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        }
    }

    private bool TryPrepareOrderExportRowsEx(IReadOnlyCollection<Cafe24Order> orders, out List<ShipmentRequestOrderRowEx> rows)
    {
        rows = BuildOrderExportRowsEx(orders);
        var missingRows = rows
            .Where(row => string.IsNullOrWhiteSpace(row.ProductCode) &&
                          !string.IsNullOrWhiteSpace(row.ProductMatchKey))
            .GroupBy(row => row.ProductMatchKey, StringComparer.OrdinalIgnoreCase)
            .Select(group => group.First())
            .ToList();

        if (missingRows.Count == 0)
            return true;

        var answer = MessageBox.Show(
            this,
            $"직접 입력이 필요한 상품 {missingRows.Count}건이 있습니다.\n지금 상품코드를 등록하면 다음부터 같은 상품명/옵션은 자동으로 채워집니다.",
            "직접입력 상품코드 등록",
            MessageBoxButtons.YesNoCancel,
            MessageBoxIcon.Question);

        if (answer == DialogResult.Cancel)
            return false;
        if (answer == DialogResult.No)
            return true;

        using var dialog = new OrderExportProductCodeMappingDialogEx(missingRows);
        if (dialog.ShowDialog(this) != DialogResult.OK)
            return true;

        var savedCount = 0;
        foreach (var input in dialog.GetMappings())
        {
            var productCode = input.ProductCode.Trim().ToUpperInvariant();
            if (string.IsNullOrWhiteSpace(input.ProductKey) || string.IsNullOrWhiteSpace(productCode))
                continue;

            _db.UpsertShipmentRequestProductCodeMapping(new ShipmentRequestProductCodeMapping
            {
                AppUserId = _currentUser.Id,
                ProductKey = input.ProductKey,
                SupplierProductName = input.SupplierProductName,
                ProductOption = input.ProductOption,
                ProductCode = productCode
            });
            savedCount++;
        }

        if (savedCount > 0)
        {
            _log.Info($"출고용 직접입력 상품코드 매핑 저장: {savedCount}건");
            rows = BuildOrderExportRowsEx(orders);
            RefreshOrderExportPopupSummaryEx(orders);
        }

        return true;
    }

    private string CurrentOrderExportMarketNameEx => string.IsNullOrWhiteSpace(_cboOrderExportMarketEx?.Text)
        ? ShipmentRequestOrderExportFormatterEx.DefaultMarketName
        : _cboOrderExportMarketEx.Text.Trim();

    private DateTime CurrentOrderExportDateValueEx => _dtpOrderExportDateEx?.Value ?? DateTime.Today;

    private string CurrentOrderExportDateTextEx => CurrentOrderExportDateValueEx.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);

    private bool CurrentOrderExportIncludeSupplierProductEx => _chkOrderExportSupplierProductEx?.Checked == true;

    private bool CurrentOrderExportIncludeOptionEx => _chkOrderExportOptionEx?.Checked == true;

    private int CurrentOrderExportLeadingColumnCountEx =>
        (CurrentOrderExportIncludeSupplierProductEx ? 1 : 0) +
        (CurrentOrderExportIncludeOptionEx ? 1 : 0);
}
internal sealed class ShipmentRequestOrderRowEx
{
    public string SupplierProductName { get; init; } = string.Empty;
    public string ProductOption { get; init; } = string.Empty;
    public string ProductMatchKey { get; init; } = string.Empty;
    public string ProductCode { get; init; } = string.Empty;
    public string MarketName { get; init; } = string.Empty;
    public string ExportDate { get; init; } = string.Empty;
    public int Quantity { get; init; }
    public string RecipientName { get; init; } = string.Empty;
    public string RecipientPhone { get; init; } = string.Empty;
    public string PostalCode { get; init; } = string.Empty;
    public string FullAddress { get; init; } = string.Empty;
    public string ShippingMessage { get; init; } = string.Empty;
    public string DetailAddress { get; init; } = string.Empty;
}

internal static class ShipmentRequestOrderExportFormatterEx
{
    public const string DefaultMarketName = "홈런마켓";

    private const string SupplierProductHeader = "공급사상품명";
    private const string ProductOptionHeader = "옵션";

    private static readonly string[] BaseHeaders =
    {
        "공급사 상품명(매입상품명)",
        "상품옵션",
        " ",
        "수량",
        "수령인",
        "수령인 휴대전화",
        "수령인 우편번호",
        "수령인 주소",
        "배송메시지",
        "수령인 상세 주소"
    };

    private const string DefaultOptionLetter = "A";
    private static readonly Regex AssignedOptionLetterRegex = new("=\\s*([A-Za-z])(?![A-Za-z])", RegexOptions.Compiled);
    private static readonly Regex StandaloneOptionLetterRegex = new("(?<![A-Za-z0-9])([A-Za-z])(?![A-Za-z0-9])", RegexOptions.Compiled);
    private static readonly Regex ProductCodeRegex = new("\\b([A-Z]{2,}\\d+[A-Z])\\b", RegexOptions.Compiled | RegexOptions.IgnoreCase);

    public static DateTime ResolveDefaultDate(IEnumerable<Cafe24Order> orders)
    {
        var parsed = orders
            .Select(order => TryParseOrderDate(order.OrderDate))
            .Where(value => value.HasValue)
            .Select(value => value!.Value.Date)
            .ToList();

        return parsed.Count > 0 ? parsed.Max() : DateTime.Today;
    }

    public static IReadOnlyList<ShipmentRequestOrderRowEx> BuildRows(
        IEnumerable<Cafe24Order> orders,
        string marketName,
        string orderDateText,
        IReadOnlyDictionary<string, string>? manualProductCodes = null)
    {
        var builtRows = orders.Select(order => BuildRow(order, marketName, orderDateText, manualProductCodes)).ToList();
        var blankRows = builtRows.Where(row => string.IsNullOrWhiteSpace(row.ProductCode)).ToList();
        var normalRows = builtRows.Where(row => !string.IsNullOrWhiteSpace(row.ProductCode)).ToList();
        return blankRows.Concat(normalRows).ToList();
    }

    public static string BuildPreview(
        IReadOnlyList<ShipmentRequestOrderRowEx> rows,
        bool includeSupplierProduct = false,
        bool includeOption = false)
    {
        return BuildDelimitedText(rows, true, includeSupplierProduct, includeOption);
    }

    public static string BuildClipboardText(
        IReadOnlyList<ShipmentRequestOrderRowEx> rows,
        bool includeSupplierProduct = false,
        bool includeOption = false)
    {
        return BuildDelimitedText(rows, false, includeSupplierProduct, includeOption);
    }

    public static void SaveAsWorkbook(
        IEnumerable<Cafe24Order> orders,
        string marketName,
        string orderDateText,
        string filePath,
        bool includeSupplierProduct = false,
        bool includeOption = false,
        IReadOnlyDictionary<string, string>? manualProductCodes = null)
    {
        var rows = BuildRows(orders, marketName, orderDateText, manualProductCodes).ToList();
        SaveRowsAsWorkbook(rows, filePath, includeSupplierProduct, includeOption);
    }

    public static void SaveRowsAsWorkbook(
        IReadOnlyList<ShipmentRequestOrderRowEx> rows,
        string filePath,
        bool includeSupplierProduct = false,
        bool includeOption = false)
    {
        using var workbook = new XLWorkbook();
        var sheet = workbook.Worksheets.Add("Sheet1");
        WriteWorksheet(sheet, rows, includeSupplierProduct, includeOption);
        workbook.SaveAs(filePath);
    }

    public static DateTime? TryParseOrderDate(string value)
    {
        if (DateTimeOffset.TryParse(value, out var dto))
            return dto.LocalDateTime;
        if (DateTime.TryParse(value, out var dt))
            return dt;
        return null;
    }

    private static string BuildDelimitedText(
        IReadOnlyList<ShipmentRequestOrderRowEx> rows,
        bool includeHeaders,
        bool includeSupplierProduct,
        bool includeOption)
    {
        var sb = new StringBuilder();
        if (includeHeaders)
            sb.AppendLine(string.Join("\t", BuildHeaders(includeSupplierProduct, includeOption)));

        foreach (var row in rows)
            sb.AppendLine(string.Join("\t", BuildValues(row, includeSupplierProduct, includeOption)));

        return sb.ToString().TrimEnd('\r', '\n');
    }

    private static string[] BuildHeaders(bool includeSupplierProduct, bool includeOption)
    {
        var values = new List<string>();
        if (includeSupplierProduct)
            values.Add(SupplierProductHeader);
        if (includeOption)
            values.Add(ProductOptionHeader);
        values.AddRange(BaseHeaders);
        return values.ToArray();
    }

    private static string[] BuildValues(ShipmentRequestOrderRowEx row, bool includeSupplierProduct, bool includeOption)
    {
        var values = new List<string>();
        if (includeSupplierProduct)
            values.Add(Clean(row.SupplierProductName));
        if (includeOption)
            values.Add(Clean(row.ProductOption));

        values.AddRange(new[]
        {
            Clean(row.ProductCode),
            Clean(row.MarketName),
            Clean(row.ExportDate),
            row.Quantity.ToString(CultureInfo.InvariantCulture),
            Clean(row.RecipientName),
            Clean(row.RecipientPhone),
            Clean(row.PostalCode),
            Clean(row.FullAddress),
            Clean(row.ShippingMessage),
            Clean(row.DetailAddress)
        });

        return values.ToArray();
    }

    private static ShipmentRequestOrderRowEx BuildRow(
        Cafe24Order order,
        string marketName,
        string orderDateText,
        IReadOnlyDictionary<string, string>? manualProductCodes)
    {
        if (MarketplaceSourceKey.IsCoupang(order.MallId))
            return BuildCoupangRow(order, marketName, orderDateText, manualProductCodes);

        var orderJson = ParseOrderJson(order.RawJson);
        var receiver = SelectReceiver(orderJson, order);
        var item = SelectItem(orderJson, order);

        var supplierProductName = ResolveSupplierProductName(item, order);
        var optionText = ResolveOptionText(item);
        var baseProductCode = ResolveBaseProductCode(item, order);
        var finalProductCode = ApplyOptionLetter(baseProductCode, optionText);
        var productMatchKey = BuildProductMappingKey(supplierProductName, optionText);
        finalProductCode = ApplyManualProductCode(finalProductCode, productMatchKey, manualProductCodes);

        var detailAddress = receiver?["address2"]?.ToString() ?? string.Empty;
        var fullAddress = CombineAddress(
            receiver?["address1"]?.ToString() ?? string.Empty,
            detailAddress,
            receiver?["address_full"]?.ToString() ?? string.Empty);

        return new ShipmentRequestOrderRowEx
        {
            SupplierProductName = supplierProductName,
            ProductOption = optionText,
            ProductMatchKey = productMatchKey,
            ProductCode = finalProductCode,
            MarketName = marketName,
            ExportDate = orderDateText,
            Quantity = order.Quantity,
            RecipientName = receiver?["name"]?.ToString() ?? order.RecipientName,
            RecipientPhone = ResolveRecipientPhone(receiver, order),
            PostalCode = receiver?["zipcode"]?.ToString() ?? string.Empty,
            FullAddress = fullAddress,
            ShippingMessage = receiver?["shipping_message"]?.ToString() ?? string.Empty,
            DetailAddress = detailAddress
        };
    }

    private static ShipmentRequestOrderRowEx BuildCoupangRow(
        Cafe24Order order,
        string marketName,
        string orderDateText,
        IReadOnlyDictionary<string, string>? manualProductCodes)
    {
        var orderJson = ParseOrderJson(order.RawJson);
        var receiver = orderJson?["receiver"] as JObject;
        var item = SelectCoupangItem(orderJson, order);

        var optionText = item?["sellerProductItemName"]?.ToString()
                         ?? item?["vendorItemName"]?.ToString()
                         ?? string.Empty;
        var supplierProductName = item?["sellerProductName"]?.ToString()
                                  ?? item?["vendorItemName"]?.ToString()
                                  ?? order.ProductName;
        var baseProductCode = ResolveCoupangProductCode(item, order);
        var finalProductCode = ApplyOptionLetter(baseProductCode, optionText);
        var productMatchKey = BuildProductMappingKey(supplierProductName, optionText);
        finalProductCode = ApplyManualProductCode(finalProductCode, productMatchKey, manualProductCodes);

        var detailAddress = receiver?["addr2"]?.ToString() ?? string.Empty;
        var fullAddress = CombineAddress(
            receiver?["addr1"]?.ToString() ?? string.Empty,
            detailAddress,
            string.Join(" ", new[]
            {
                receiver?["addr1"]?.ToString() ?? string.Empty,
                receiver?["addr2"]?.ToString() ?? string.Empty
            }.Where(value => !string.IsNullOrWhiteSpace(value))));

        return new ShipmentRequestOrderRowEx
        {
            SupplierProductName = supplierProductName,
            ProductOption = optionText,
            ProductMatchKey = productMatchKey,
            ProductCode = finalProductCode,
            MarketName = marketName,
            ExportDate = orderDateText,
            Quantity = order.Quantity,
            RecipientName = receiver?["name"]?.ToString() ?? order.RecipientName,
            RecipientPhone = ResolveCoupangRecipientPhone(receiver, order),
            PostalCode = receiver?["postCode"]?.ToString() ?? string.Empty,
            FullAddress = fullAddress,
            ShippingMessage = orderJson?["parcelPrintMessage"]?.ToString()
                              ?? orderJson?["deliveryInstruction"]?.ToString()
                              ?? string.Empty,
            DetailAddress = detailAddress
        };
    }

    private static JObject? SelectCoupangItem(JObject? orderJson, Cafe24Order order)
    {
        var items = orderJson?["orderItems"] as JArray;
        if (items == null || items.Count == 0) return null;

        return items
            .OfType<JObject>()
            .FirstOrDefault(item =>
                string.Equals(item["vendorItemId"]?.ToString(), order.OrderItemCode, StringComparison.OrdinalIgnoreCase) ||
                string.Equals(item["shipmentBoxId"]?.ToString(), order.ShippingCode, StringComparison.OrdinalIgnoreCase))
            ?? items.OfType<JObject>().FirstOrDefault();
    }

    private static string ResolveCoupangProductCode(JObject? item, Cafe24Order order)
    {
        foreach (var candidate in new[]
        {
            item?["externalVendorSkuCode"]?.ToString(),
            item?["sellerProductItemName"]?.ToString(),
            item?["sellerProductName"]?.ToString(),
            item?["vendorItemName"]?.ToString(),
            order.ProductName
        })
        {
            var extracted = ExtractProductCode(candidate);
            if (!string.IsNullOrWhiteSpace(extracted))
                return extracted;
        }

        return string.Empty;
    }

    private static string ResolveCoupangRecipientPhone(JObject? receiver, Cafe24Order order)
    {
        return receiver?["safeNumber"]?.ToString()
               ?? receiver?["receiverNumber"]?.ToString()
               ?? order.RecipientCellPhone
               ?? order.RecipientPhone
               ?? string.Empty;
    }

    private static JObject? ParseOrderJson(string rawJson)
    {
        if (string.IsNullOrWhiteSpace(rawJson)) return null;
        try
        {
            return JObject.Parse(rawJson);
        }
        catch
        {
            return null;
        }
    }

    private static JObject? SelectReceiver(JObject? orderJson, Cafe24Order order)
    {
        var receivers = orderJson?["receivers"] as JArray;
        if (receivers == null || receivers.Count == 0) return null;

        return receivers
            .OfType<JObject>()
            .FirstOrDefault(receiver => string.Equals(receiver["shipping_code"]?.ToString(), order.ShippingCode, StringComparison.OrdinalIgnoreCase))
            ?? receivers.OfType<JObject>().FirstOrDefault();
    }

    private static JObject? SelectItem(JObject? orderJson, Cafe24Order order)
    {
        var items = orderJson?["items"] as JArray;
        if (items == null || items.Count == 0) return null;

        return items
            .OfType<JObject>()
            .FirstOrDefault(item => string.Equals(item["order_item_code"]?.ToString(), order.OrderItemCode, StringComparison.OrdinalIgnoreCase))
            ?? items.OfType<JObject>().FirstOrDefault();
    }

    private static string ResolveOptionText(JObject? item)
    {
        var direct = item?["option_value"]?.ToString()
                     ?? item?["option_value_default"]?.ToString();
        if (!string.IsNullOrWhiteSpace(direct))
            return direct;

        var options = item?["options"] as JArray;
        if (options == null || options.Count == 0)
            return string.Empty;

        var parts = options
            .OfType<JObject>()
            .Select(option =>
            {
                var name = option["option_name"]?.ToString() ?? string.Empty;
                var text = option["option_value"]?["option_text"]?.ToString()
                           ?? option["option_value"]?.ToString()
                           ?? string.Empty;
                if (string.IsNullOrWhiteSpace(name)) return text;
                if (string.IsNullOrWhiteSpace(text)) return name;
                return $"{name}={text}";
            })
            .Where(text => !string.IsNullOrWhiteSpace(text))
            .ToList();

        return string.Join(" / ", parts);
    }

    private static string ResolveSupplierProductName(JObject? item, Cafe24Order order)
    {
        foreach (var candidate in new[]
        {
            item?["supplier_product_name"]?.ToString(),
            item?["product_name"]?.ToString(),
            order.ProductName
        })
        {
            if (!string.IsNullOrWhiteSpace(candidate))
                return candidate.Trim();
        }

        return string.Empty;
    }

    private static string ResolveBaseProductCode(JObject? item, Cafe24Order order)
    {
        var customProductCode = item?["custom_product_code"]?.ToString();
        if (!string.IsNullOrWhiteSpace(customProductCode))
            return customProductCode.Trim().ToUpperInvariant();

        foreach (var candidate in new[]
        {
            item?["supplier_product_name"]?.ToString(),
            item?["product_name"]?.ToString(),
            order.ProductName
        })
        {
            var extracted = ExtractProductCode(candidate);
            if (!string.IsNullOrWhiteSpace(extracted))
                return extracted;
        }

        return string.Empty;
    }

    private static string ExtractProductCode(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return string.Empty;
        var match = ProductCodeRegex.Match(text);
        return match.Success ? match.Groups[1].Value.ToUpperInvariant() : string.Empty;
    }

    public static string BuildProductMappingKey(string? supplierProductName, string? optionText)
    {
        var product = NormalizeProductMappingPart(supplierProductName);
        if (string.IsNullOrWhiteSpace(product))
            return string.Empty;

        return $"{product}|{NormalizeProductMappingPart(optionText)}";
    }

    private static string ApplyManualProductCode(
        string productCode,
        string productMatchKey,
        IReadOnlyDictionary<string, string>? manualProductCodes)
    {
        if (!string.IsNullOrWhiteSpace(productCode))
            return productCode;
        if (string.IsNullOrWhiteSpace(productMatchKey) || manualProductCodes == null)
            return string.Empty;

        return manualProductCodes.TryGetValue(productMatchKey, out var mappedCode)
            ? mappedCode.Trim().ToUpperInvariant()
            : string.Empty;
    }

    private static string NormalizeProductMappingPart(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return string.Empty;

        return Regex.Replace(value.Trim(), "\\s+", " ").ToUpperInvariant();
    }

    private static string ApplyOptionLetter(string baseProductCode, string optionText)
    {
        if (string.IsNullOrWhiteSpace(baseProductCode)) return string.Empty;

        var normalized = baseProductCode.Trim().ToUpperInvariant();
        var replacement = ExtractOptionLetter(optionText);
        if (!string.IsNullOrWhiteSpace(replacement) && normalized.Length > 0 && char.IsLetter(normalized[^1]))
            normalized = normalized[..^1] + replacement;

        return normalized;
    }

    private static string? ExtractOptionLetter(string optionText)
    {
        if (string.IsNullOrWhiteSpace(optionText))
            return DefaultOptionLetter;

        var assignedMatch = AssignedOptionLetterRegex.Match(optionText);
        if (assignedMatch.Success)
            return assignedMatch.Groups[1].Value.ToUpperInvariant();

        var standaloneMatches = StandaloneOptionLetterRegex.Matches(optionText);
        return standaloneMatches.Count > 0
            ? standaloneMatches[^1].Groups[1].Value.ToUpperInvariant()
            : null;
    }

    private static string ResolveRecipientPhone(JObject? receiver, Cafe24Order order)
    {
        return receiver?["cellphone"]?.ToString()
               ?? receiver?["phone"]?.ToString()
               ?? order.RecipientCellPhone
               ?? order.RecipientPhone
               ?? string.Empty;
    }

    private static string CombineAddress(string address1, string detailAddress, string addressFull)
    {
        var first = Clean(address1);
        var second = Clean(detailAddress);
        if (first.Length > 0 && second.Length > 0)
            return $"{first} {second}";
        if (first.Length > 0)
            return first;
        if (second.Length > 0)
            return second;
        return Clean(addressFull);
    }

    private static void WriteWorksheet(
        IXLWorksheet sheet,
        IReadOnlyList<ShipmentRequestOrderRowEx> rows,
        bool includeSupplierProduct,
        bool includeOption)
    {
        var headers = BuildHeaders(includeSupplierProduct, includeOption);
        for (int col = 0; col < headers.Length; col++)
            sheet.Cell(1, col + 1).Value = headers[col];

        var blankCount = 0;
        for (int rowIndex = 0; rowIndex < rows.Count; rowIndex++)
        {
            var row = rows[rowIndex];
            var excelRow = rowIndex + 2;
            if (string.IsNullOrWhiteSpace(row.ProductCode)) blankCount++;

            var col = 1;
            if (includeSupplierProduct)
                sheet.Cell(excelRow, col++).Value = row.SupplierProductName;
            if (includeOption)
                sheet.Cell(excelRow, col++).Value = row.ProductOption;

            sheet.Cell(excelRow, col++).Value = row.ProductCode;
            sheet.Cell(excelRow, col++).Value = row.MarketName;
            sheet.Cell(excelRow, col++).Value = row.ExportDate;
            sheet.Cell(excelRow, col++).Value = row.Quantity;
            sheet.Cell(excelRow, col++).Value = row.RecipientName;
            sheet.Cell(excelRow, col++).Value = row.RecipientPhone;
            if (int.TryParse(row.PostalCode, out var zipcode))
                sheet.Cell(excelRow, col++).Value = zipcode;
            else
                sheet.Cell(excelRow, col++).Value = row.PostalCode;
            sheet.Cell(excelRow, col++).Value = row.FullAddress;
            sheet.Cell(excelRow, col++).Value = string.IsNullOrWhiteSpace(row.ShippingMessage) ? null : row.ShippingMessage;
            sheet.Cell(excelRow, col).Value = row.DetailAddress;
        }

        if (blankCount > 0)
        {
            var blankRange = sheet.Range(2, 1, blankCount + 1, headers.Length);
            blankRange.Style.Fill.BackgroundColor = XLColor.FromHtml("#D9D9D9");
        }

        sheet.Columns().AdjustToContents();
    }

    private static string Clean(string? value)
    {
        return (value ?? string.Empty)
            .Replace("\r", " ")
            .Replace("\n", " ")
            .Replace("\t", " ")
            .Trim();
    }
}

internal sealed class OrderExportProductCodeMappingInputEx
{
    public string ProductKey { get; init; } = string.Empty;
    public string SupplierProductName { get; init; } = string.Empty;
    public string ProductOption { get; init; } = string.Empty;
    public string ProductCode { get; init; } = string.Empty;
}

internal sealed class OrderExportProductCodeMappingDialogEx : Form
{
    private readonly DataGridView _grid;

    public OrderExportProductCodeMappingDialogEx(IReadOnlyList<ShipmentRequestOrderRowEx> rows)
    {
        Text = "직접입력 상품코드 등록";
        StartPosition = FormStartPosition.CenterParent;
        Size = new Size(920, 460);
        MinimizeBox = false;
        MaximizeBox = false;
        BackColor = Color.White;

        var description = new Label
        {
            AutoSize = false,
            Dock = DockStyle.Top,
            Height = 48,
            Padding = new Padding(12, 10, 12, 4),
            Text = "상품코드를 입력해 저장하면 다음 출고용 생성부터 같은 상품명/옵션에 자동 적용됩니다.",
            ForeColor = Color.DimGray
        };

        _grid = new DataGridView
        {
            Dock = DockStyle.Fill,
            AllowUserToAddRows = false,
            AllowUserToDeleteRows = false,
            AutoSizeRowsMode = DataGridViewAutoSizeRowsMode.AllCells,
            SelectionMode = DataGridViewSelectionMode.CellSelect,
            BackgroundColor = Color.White,
            RowHeadersVisible = false
        };

        _grid.Columns.Add(new DataGridViewTextBoxColumn
        {
            Name = "SupplierProductName",
            HeaderText = "공급사상품명",
            ReadOnly = true,
            Width = 360
        });
        _grid.Columns.Add(new DataGridViewTextBoxColumn
        {
            Name = "ProductOption",
            HeaderText = "옵션",
            ReadOnly = true,
            Width = 240
        });
        _grid.Columns.Add(new DataGridViewTextBoxColumn
        {
            Name = "ProductCode",
            HeaderText = "상품코드 입력",
            Width = 180
        });

        foreach (var row in rows)
        {
            var index = _grid.Rows.Add(row.SupplierProductName, row.ProductOption, "");
            _grid.Rows[index].Tag = row.ProductMatchKey;
        }

        var buttons = new FlowLayoutPanel
        {
            Dock = DockStyle.Bottom,
            Height = 52,
            FlowDirection = FlowDirection.RightToLeft,
            Padding = new Padding(10),
            BackColor = Color.FromArgb(245, 247, 250)
        };

        var btnSave = new Button
        {
            Text = "저장 후 계속",
            Width = 120,
            Height = 30,
            DialogResult = DialogResult.OK
        };
        var btnSkip = new Button
        {
            Text = "이번엔 건너뛰기",
            Width = 130,
            Height = 30,
            DialogResult = DialogResult.Cancel
        };
        buttons.Controls.Add(btnSave);
        buttons.Controls.Add(btnSkip);

        AcceptButton = btnSave;
        CancelButton = btnSkip;
        Controls.Add(_grid);
        Controls.Add(description);
        Controls.Add(buttons);
    }

    public List<OrderExportProductCodeMappingInputEx> GetMappings()
    {
        var mappings = new List<OrderExportProductCodeMappingInputEx>();
        foreach (DataGridViewRow row in _grid.Rows)
        {
            if (row.IsNewRow) continue;
            var productCode = row.Cells["ProductCode"].Value?.ToString()?.Trim() ?? "";
            if (string.IsNullOrWhiteSpace(productCode))
                continue;

            mappings.Add(new OrderExportProductCodeMappingInputEx
            {
                ProductKey = row.Tag?.ToString() ?? "",
                SupplierProductName = row.Cells["SupplierProductName"].Value?.ToString() ?? "",
                ProductOption = row.Cells["ProductOption"].Value?.ToString() ?? "",
                ProductCode = productCode
            });
        }

        return mappings;
    }
}


