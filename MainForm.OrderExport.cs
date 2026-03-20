using System.Globalization;
using System.Text;
using ClosedXML.Excel;
using Cafe24ShipmentManager.Models;

namespace Cafe24ShipmentManager;

public partial class MainForm
{
    private const string OrderSelectColumnNameEx = "OrderSelectedEx";

    private Button? _btnOrderSelectAllEx;
    private Button? _btnOrderDeselectAllEx;
    private Button? _btnOrderExportEx;
    private Label? _lblOrderSelectionEx;
    private bool _orderExportUiInitializedEx;

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
            Text = "📋 선택 주문 내보내기",
            Width = 178,
            Height = 30,
            Location = new Point(312, 3),
            Enabled = false
        };
        _lblOrderSelectionEx = new Label
        {
            AutoSize = true,
            Location = new Point(502, 9),
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
        _btnOrderExportEx.Click += (_, _) => OpenSelectedOrdersExportDialogEx();

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

    private void UpdateOrderSelectionSummaryEx()
    {
        if (_lblOrderSelectionEx == null) return;

        var total = dgvData?.Rows.Cast<DataGridViewRow>().Count(r => !r.IsNewRow) ?? 0;
        var selected = dgvData?.Rows.Cast<DataGridViewRow>().Count(r => !r.IsNewRow && IsPreviewOrderCheckedEx(r)) ?? 0;
        _lblOrderSelectionEx.Text = $"선택 {selected} / {total}";

        if (_btnOrderExportEx != null)
            _btnOrderExportEx.Enabled = selected > 0;
    }

    private void OpenSelectedOrdersExportDialogEx()
    {
        var selectedOrders = GetCheckedPreviewOrdersEx();
        if (selectedOrders.Count == 0)
        {
            MessageBox.Show("먼저 데이터 프리뷰에서 주문을 체크하세요.", "알림", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        using var dialog = new SelectedOrdersExportDialogEx(selectedOrders);
        dialog.ShowDialog(this);
        _log.Info($"선택 주문 내보내기 창 열림: {selectedOrders.Count}건");
    }
}

internal sealed class SelectedOrdersExportDialogEx : Form
{
    private readonly List<OrderExportRowEx> _rows;
    private readonly TextBox _txtSpreadsheet;
    private readonly TextBox _txtSql;
    private readonly TextBox _txtTableName;

    public SelectedOrdersExportDialogEx(IEnumerable<Cafe24Order> orders)
    {
        _rows = orders.Select(OrderExportRowEx.FromOrder).ToList();

        Text = $"선택 주문 내보내기 ({_rows.Count}건)";
        Size = new Size(1180, 780);
        StartPosition = FormStartPosition.CenterParent;

        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 3,
            Padding = new Padding(10)
        };
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100f));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));

        var top = new Panel { Dock = DockStyle.Top, Height = 36 };
        var lblInfo = new Label
        {
            Text = $"선택 주문: {_rows.Count}건",
            AutoSize = true,
            Location = new Point(4, 9)
        };
        var lblTable = new Label
        {
            Text = "SQL 테이블:",
            AutoSize = true,
            Location = new Point(180, 9)
        };
        _txtTableName = new TextBox
        {
            Text = "your_table_name",
            Location = new Point(255, 5),
            Width = 220
        };
        top.Controls.AddRange(new Control[] { lblInfo, lblTable, _txtTableName });

        var tabs = new TabControl { Dock = DockStyle.Fill };
        _txtSpreadsheet = CreateOutputTextBox();
        _txtSql = CreateOutputTextBox();

        var tabSheet = new TabPage("스프레드시트 붙여넣기");
        tabSheet.Controls.Add(_txtSpreadsheet);
        var tabSql = new TabPage("SQL 붙여넣기");
        tabSql.Controls.Add(_txtSql);

        tabs.TabPages.Add(tabSheet);
        tabs.TabPages.Add(tabSql);

        var btnPanel = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            AutoSize = true,
            FlowDirection = FlowDirection.RightToLeft
        };
        var btnClose = new Button { Text = "닫기", Width = 90, Height = 32, DialogResult = DialogResult.OK };
        var btnSaveExcel = new Button { Text = "엑셀 저장", Width = 90, Height = 32 };
        var btnCopySql = new Button { Text = "SQL 복사", Width = 90, Height = 32 };
        var btnCopySheet = new Button { Text = "시트 복사", Width = 90, Height = 32 };

        btnCopySheet.Click += (_, _) =>
        {
            Clipboard.SetText(_txtSpreadsheet.Text);
            MessageBox.Show(this, "스프레드시트 붙여넣기용 텍스트를 클립보드에 복사했습니다.", "복사 완료",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
        };
        btnCopySql.Click += (_, _) =>
        {
            Clipboard.SetText(_txtSql.Text);
            MessageBox.Show(this, "SQL 붙여넣기용 텍스트를 클립보드에 복사했습니다.", "복사 완료",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
        };
        btnSaveExcel.Click += (_, _) => SaveExcel();
        _txtTableName.TextChanged += (_, _) => RefreshOutputs();

        btnPanel.Controls.AddRange(new Control[] { btnClose, btnSaveExcel, btnCopySql, btnCopySheet });

        root.Controls.Add(top, 0, 0);
        root.Controls.Add(tabs, 0, 1);
        root.Controls.Add(btnPanel, 0, 2);
        Controls.Add(root);

        AcceptButton = btnClose;
        RefreshOutputs();
    }

    private static TextBox CreateOutputTextBox()
    {
        return new TextBox
        {
            Dock = DockStyle.Fill,
            Multiline = true,
            ScrollBars = ScrollBars.Both,
            WordWrap = false,
            AcceptsReturn = true,
            AcceptsTab = true,
            ShortcutsEnabled = true,
            Font = new Font("Consolas", 10f)
        };
    }

    private void RefreshOutputs()
    {
        _txtSpreadsheet.Text = OrderExportFormatterEx.BuildSpreadsheetText(_rows);
        _txtSql.Text = OrderExportFormatterEx.BuildSqlInsert(_rows, _txtTableName.Text);
    }

    private void SaveExcel()
    {
        using var dialog = new SaveFileDialog
        {
            Filter = "Excel Workbook (*.xlsx)|*.xlsx",
            DefaultExt = "xlsx",
            AddExtension = true,
            FileName = $"cafe24_selected_orders_{DateTime.Now:yyyyMMdd_HHmmss}.xlsx"
        };

        if (dialog.ShowDialog(this) != DialogResult.OK) return;

        using var workbook = new XLWorkbook();
        var sheet = workbook.Worksheets.Add("selected_orders");
        OrderExportFormatterEx.WriteWorksheet(sheet, _rows);
        workbook.SaveAs(dialog.FileName);

        MessageBox.Show(this, $"엑셀 파일을 저장했습니다.\n{dialog.FileName}", "저장 완료",
            MessageBoxButtons.OK, MessageBoxIcon.Information);
    }
}

internal sealed class OrderExportRowEx
{
    public string OrderId { get; init; } = "";
    public string OrderItemCode { get; init; } = "";
    public string OrderDate { get; init; } = "";
    public string OrderStatus { get; init; } = "";
    public string ProductName { get; init; } = "";
    public int Quantity { get; init; }
    public decimal OrderAmount { get; init; }
    public string RecipientName { get; init; } = "";
    public string RecipientCellPhone { get; init; } = "";
    public string RecipientPhone { get; init; } = "";
    public string ShippingCode { get; init; } = "";

    public static OrderExportRowEx FromOrder(Cafe24Order order)
    {
        return new OrderExportRowEx
        {
            OrderId = order.OrderId,
            OrderItemCode = order.OrderItemCode,
            OrderDate = order.OrderDate,
            OrderStatus = order.OrderStatus,
            ProductName = order.ProductName,
            Quantity = order.Quantity,
            OrderAmount = order.OrderAmount,
            RecipientName = order.RecipientName,
            RecipientCellPhone = order.RecipientCellPhone,
            RecipientPhone = order.RecipientPhone,
            ShippingCode = order.ShippingCode
        };
    }
}

internal static class OrderExportFormatterEx
{
    private static readonly string[] SpreadsheetHeaders =
    {
        "주문번호",
        "주문상품코드",
        "주문일시",
        "주문상태",
        "상품명",
        "수량",
        "상품금액",
        "수령인명",
        "휴대폰",
        "일반전화",
        "배송코드"
    };

    public static string BuildSpreadsheetText(IReadOnlyList<OrderExportRowEx> rows)
    {
        var sb = new StringBuilder();
        sb.AppendLine(string.Join("\t", SpreadsheetHeaders));

        foreach (var row in rows)
        {
            var values = new[]
            {
                CleanCell(row.OrderId),
                CleanCell(row.OrderItemCode),
                CleanCell(row.OrderDate),
                CleanCell(row.OrderStatus),
                CleanCell(row.ProductName),
                row.Quantity.ToString(CultureInfo.InvariantCulture),
                row.OrderAmount.ToString("0.##", CultureInfo.InvariantCulture),
                CleanCell(row.RecipientName),
                CleanCell(row.RecipientCellPhone),
                CleanCell(row.RecipientPhone),
                CleanCell(row.ShippingCode)
            };
            sb.AppendLine(string.Join("\t", values));
        }

        return sb.ToString().TrimEnd();
    }

    public static string BuildSqlInsert(IReadOnlyList<OrderExportRowEx> rows, string tableName)
    {
        var sb = new StringBuilder();
        var targetTable = SanitizeSqlIdentifier(tableName);

        sb.AppendLine($"INSERT INTO {targetTable} (");
        sb.AppendLine("    order_id,");
        sb.AppendLine("    order_item_code,");
        sb.AppendLine("    order_date,");
        sb.AppendLine("    order_status,");
        sb.AppendLine("    product_name,");
        sb.AppendLine("    quantity,");
        sb.AppendLine("    order_amount,");
        sb.AppendLine("    recipient_name,");
        sb.AppendLine("    recipient_cellphone,");
        sb.AppendLine("    recipient_phone,");
        sb.AppendLine("    shipping_code");
        sb.AppendLine(") VALUES");

        for (int i = 0; i < rows.Count; i++)
        {
            var row = rows[i];
            sb.Append("    (");
            sb.Append(SqlString(row.OrderId));
            sb.Append(", ");
            sb.Append(SqlString(row.OrderItemCode));
            sb.Append(", ");
            sb.Append(SqlString(row.OrderDate));
            sb.Append(", ");
            sb.Append(SqlString(row.OrderStatus));
            sb.Append(", ");
            sb.Append(SqlString(row.ProductName));
            sb.Append(", ");
            sb.Append(row.Quantity.ToString(CultureInfo.InvariantCulture));
            sb.Append(", ");
            sb.Append(row.OrderAmount.ToString("0.##", CultureInfo.InvariantCulture));
            sb.Append(", ");
            sb.Append(SqlString(row.RecipientName));
            sb.Append(", ");
            sb.Append(SqlString(row.RecipientCellPhone));
            sb.Append(", ");
            sb.Append(SqlString(row.RecipientPhone));
            sb.Append(", ");
            sb.Append(SqlString(row.ShippingCode));
            sb.Append(')');
            sb.AppendLine(i < rows.Count - 1 ? "," : ";");
        }

        return sb.ToString();
    }

    public static void WriteWorksheet(IXLWorksheet sheet, IReadOnlyList<OrderExportRowEx> rows)
    {
        for (int col = 0; col < SpreadsheetHeaders.Length; col++)
            sheet.Cell(1, col + 1).Value = SpreadsheetHeaders[col];

        for (int rowIndex = 0; rowIndex < rows.Count; rowIndex++)
        {
            var row = rows[rowIndex];
            var excelRow = rowIndex + 2;

            sheet.Cell(excelRow, 1).Value = row.OrderId;
            sheet.Cell(excelRow, 2).Value = row.OrderItemCode;
            sheet.Cell(excelRow, 3).Value = row.OrderDate;
            sheet.Cell(excelRow, 4).Value = row.OrderStatus;
            sheet.Cell(excelRow, 5).Value = row.ProductName;
            sheet.Cell(excelRow, 6).Value = row.Quantity;
            sheet.Cell(excelRow, 7).Value = row.OrderAmount;
            sheet.Cell(excelRow, 8).Value = row.RecipientName;
            sheet.Cell(excelRow, 9).Value = row.RecipientCellPhone;
            sheet.Cell(excelRow, 10).Value = row.RecipientPhone;
            sheet.Cell(excelRow, 11).Value = row.ShippingCode;
        }

        var headerRange = sheet.Range(1, 1, 1, SpreadsheetHeaders.Length);
        headerRange.Style.Font.Bold = true;
        headerRange.Style.Fill.BackgroundColor = XLColor.LightGray;
        sheet.SheetView.FreezeRows(1);
        sheet.Columns().AdjustToContents();
    }

    private static string CleanCell(string value)
    {
        return (value ?? string.Empty)
            .Replace("\r", " ")
            .Replace("\n", " ")
            .Replace("\t", " ")
            .Trim();
    }

    private static string SqlString(string value)
    {
        return $"'{CleanCell(value).Replace("'", "''")}'";
    }

    private static string SanitizeSqlIdentifier(string value)
    {
        var cleaned = new string((value ?? string.Empty)
            .Trim()
            .Select(ch => char.IsLetterOrDigit(ch) || ch == '_' ? ch : '_')
            .ToArray());

        return string.IsNullOrWhiteSpace(cleaned) ? "your_table_name" : cleaned;
    }
}
