using System.ComponentModel;
using Cafe24ShipmentManager.Models;
using Cafe24ShipmentManager.Services;

namespace Cafe24ShipmentManager;

public sealed class UserProfileForm : Form
{
    private readonly AuthService? _authService;
    private readonly UserSettingsService _userSettingsService;
    private readonly AppUser? _existingUser;
    private readonly bool _registrationMode;
    private readonly bool _requireMarketplaceConfig;
    private readonly TextBox _txtUserName = new() { Dock = DockStyle.Fill };
    private readonly TextBox _txtPassword = new() { Dock = DockStyle.Fill, UseSystemPasswordChar = true };
    private readonly BindingList<Cafe24MarketEntry> _markets = new();
    private readonly BindingSource _marketBindingSource = new();
    private readonly BindingList<CoupangMarketEntry> _coupangMarkets = new();
    private readonly BindingSource _coupangMarketBindingSource = new();
    private readonly DataGridView _dgvMarkets = new()
    {
        Dock = DockStyle.Fill,
        AllowUserToAddRows = false,
        AllowUserToDeleteRows = false,
        AutoGenerateColumns = false,
        MultiSelect = false,
        SelectionMode = DataGridViewSelectionMode.FullRowSelect,
        RowHeadersVisible = false,
        AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill,
        BackgroundColor = Color.White,
        BorderStyle = BorderStyle.FixedSingle
    };
    private readonly DataGridView _dgvCoupangMarkets = new()
    {
        Dock = DockStyle.Fill,
        AllowUserToAddRows = false,
        AllowUserToDeleteRows = false,
        AutoGenerateColumns = false,
        MultiSelect = false,
        SelectionMode = DataGridViewSelectionMode.FullRowSelect,
        RowHeadersVisible = false,
        AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill,
        BackgroundColor = Color.White,
        BorderStyle = BorderStyle.FixedSingle
    };
    private readonly Label _lblMessage = new() { AutoSize = true, ForeColor = Color.Firebrick };

    public AppUser? RegisteredUser { get; private set; }
    public string RegisteredPassword { get; private set; } = "";

    public UserProfileForm(AuthService authService, UserSettingsService userSettingsService)
    {
        _authService = authService;
        _userSettingsService = userSettingsService;
        _registrationMode = true;
        _requireMarketplaceConfig = false;

        InitializeUi();
    }

    public UserProfileForm(AppUser user, UserSettingsService userSettingsService, bool requireMarketplaceConfig)
    {
        _existingUser = user;
        _userSettingsService = userSettingsService;
        _registrationMode = false;
        _requireMarketplaceConfig = requireMarketplaceConfig;

        InitializeUi();
        LoadMarkets(_userSettingsService.LoadCafe24Markets(user.Id));
        var coupangMarkets = _userSettingsService.LoadCoupangMarkets(user.Id);
        LoadCoupangMarkets(coupangMarkets.Count == 0 ? GetDefaultCoupangMarkets() : coupangMarkets);
    }

    private void InitializeUi()
    {
        Text = _registrationMode ? "신규 사용자 등록" : "마켓 설정";
        ClientSize = _registrationMode ? new Size(520, 240) : new Size(920, 560);
        StartPosition = FormStartPosition.CenterParent;
        FormBorderStyle = _registrationMode ? FormBorderStyle.FixedDialog : FormBorderStyle.Sizable;
        MaximizeBox = !_registrationMode;
        MinimizeBox = false;
        Font = new Font("맑은 고딕", 9f);

        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(12),
            ColumnCount = 1,
            RowCount = 3
        };
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100f));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));

        var lblTitle = new Label
        {
            Text = _registrationMode
                ? "계정은 아이디와 비밀번호만 등록합니다. 마켓은 로그인 후 추가하세요."
                : "Cafe24 JSON 키 파일과 쿠팡 Wing API txt 키 파일을 각각 연결합니다. 마켓명은 시트 조회 기준으로 사용됩니다.",
            AutoSize = true,
            Font = new Font("맑은 고딕", 10f, FontStyle.Bold)
        };
        root.Controls.Add(lblTitle, 0, 0);

        root.Controls.Add(_registrationMode ? BuildRegistrationContent() : BuildMarketContent(), 0, 1);

        var bottomPanel = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, AutoSize = true };
        bottomPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
        bottomPanel.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        bottomPanel.Controls.Add(_lblMessage, 0, 0);

        var buttonPanel = new FlowLayoutPanel { AutoSize = true, FlowDirection = FlowDirection.RightToLeft, WrapContents = false };
        var btnSave = new Button { Text = _registrationMode ? "등록" : "저장", Width = 90, Height = 32 };
        var btnCancel = new Button { Text = "취소", Width = 90, Height = 32, DialogResult = DialogResult.Cancel };
        btnSave.Click += (_, _) => HandleSave();
        buttonPanel.Controls.Add(btnSave);
        buttonPanel.Controls.Add(btnCancel);
        bottomPanel.Controls.Add(buttonPanel, 1, 0);
        root.Controls.Add(bottomPanel, 0, 2);

        AcceptButton = btnSave;
        CancelButton = btnCancel;
        Controls.Add(root);
    }

    private Control BuildRegistrationContent()
    {
        var group = new GroupBox
        {
            Text = "계정 등록",
            Dock = DockStyle.Top,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink
        };

        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(10),
            ColumnCount = 2,
            AutoSize = true
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 120));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));

        layout.Controls.Add(new Label { Text = "아이디", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 0);
        layout.Controls.Add(_txtUserName, 1, 0);
        layout.Controls.Add(new Label { Text = "비밀번호", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 1);
        layout.Controls.Add(_txtPassword, 1, 1);

        var lblHint = new Label
        {
            Text = "등록 후 로그인하면 마켓 추가에서 Cafe24 JSON 또는 쿠팡 Wing API 키 파일을 연결할 수 있습니다.",
            AutoSize = true,
            ForeColor = Color.DimGray,
            Margin = new Padding(0, 8, 0, 0)
        };
        layout.Controls.Add(lblHint, 1, 2);

        group.Controls.Add(layout);
        return group;
    }

    private Control BuildMarketContent()
    {
        ConfigureMarketGrid();
        ConfigureCoupangMarketGrid();

        var panel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 2
        };
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        panel.RowStyles.Add(new RowStyle(SizeType.Percent, 100f));

        var lblUser = new Label
        {
            Text = _existingUser == null
                ? ""
                : $"로그인 계정: {_existingUser.UserName}",
            AutoSize = true,
            ForeColor = Color.DimGray,
            Margin = new Padding(0, 0, 0, 6)
        };
        panel.Controls.Add(lblUser, 0, 0);

        var tabs = new TabControl { Dock = DockStyle.Fill };
        var cafe24Tab = new TabPage("Cafe24");
        cafe24Tab.Controls.Add(BuildCafe24MarketTab());
        var coupangTab = new TabPage("쿠팡");
        coupangTab.Controls.Add(BuildCoupangMarketTab());
        tabs.TabPages.Add(cafe24Tab);
        tabs.TabPages.Add(coupangTab);
        panel.Controls.Add(tabs, 0, 1);

        return panel;
    }

    private Control BuildCafe24MarketTab()
    {
        var panel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 2,
            Padding = new Padding(8)
        };
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        panel.RowStyles.Add(new RowStyle(SizeType.Percent, 100f));

        var toolbar = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            WrapContents = false,
            Margin = new Padding(0, 0, 0, 8)
        };
        var btnAdd = new Button { Text = "Cafe24 추가", Width = 100, Height = 30 };
        var btnRemove = new Button { Text = "삭제", Width = 90, Height = 30 };
        var btnBrowse = new Button { Text = "JSON 선택", Width = 110, Height = 30 };
        btnAdd.Click += (_, _) => AddMarketRow();
        btnRemove.Click += (_, _) => RemoveSelectedMarket();
        btnBrowse.Click += (_, _) => BrowseSelectedMarketFile();
        toolbar.Controls.Add(btnAdd);
        toolbar.Controls.Add(btnRemove);
        toolbar.Controls.Add(btnBrowse);
        panel.Controls.Add(toolbar, 0, 0);

        var gridHost = new Panel { Dock = DockStyle.Fill };
        gridHost.Controls.Add(_dgvMarkets);
        panel.Controls.Add(gridHost, 0, 1);

        return panel;
    }

    private Control BuildCoupangMarketTab()
    {
        var panel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 2,
            Padding = new Padding(8)
        };
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        panel.RowStyles.Add(new RowStyle(SizeType.Percent, 100f));

        var toolbar = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            WrapContents = false,
            Margin = new Padding(0, 0, 0, 8)
        };
        var btnAdd = new Button { Text = "쿠팡 추가", Width = 90, Height = 30 };
        var btnRemove = new Button { Text = "삭제", Width = 90, Height = 30 };
        var btnBrowse = new Button { Text = "txt 선택", Width = 110, Height = 30 };
        btnAdd.Click += (_, _) => AddCoupangMarketRow();
        btnRemove.Click += (_, _) => RemoveSelectedCoupangMarket();
        btnBrowse.Click += (_, _) => BrowseSelectedCoupangMarketFile();
        toolbar.Controls.Add(btnAdd);
        toolbar.Controls.Add(btnRemove);
        toolbar.Controls.Add(btnBrowse);
        panel.Controls.Add(toolbar, 0, 0);

        var gridHost = new Panel { Dock = DockStyle.Fill };
        gridHost.Controls.Add(_dgvCoupangMarkets);
        panel.Controls.Add(gridHost, 0, 1);

        return panel;
    }

    private void ConfigureMarketGrid()
    {
        if (_dgvMarkets.Columns.Count > 0)
            return;

        _marketBindingSource.DataSource = _markets;
        _dgvMarkets.DataSource = _marketBindingSource;
        _dgvMarkets.Columns.Add(new DataGridViewTextBoxColumn
        {
            DataPropertyName = nameof(Cafe24MarketEntry.DisplayName),
            HeaderText = "마켓명",
            FillWeight = 30,
            MinimumWidth = 180
        });
        _dgvMarkets.Columns.Add(new DataGridViewTextBoxColumn
        {
            DataPropertyName = nameof(Cafe24MarketEntry.TokenFilePath),
            HeaderText = "JSON 키 파일",
            FillWeight = 70,
            MinimumWidth = 420
        });
    }

    private void ConfigureCoupangMarketGrid()
    {
        if (_dgvCoupangMarkets.Columns.Count > 0)
            return;

        _coupangMarketBindingSource.DataSource = _coupangMarkets;
        _dgvCoupangMarkets.DataSource = _coupangMarketBindingSource;
        _dgvCoupangMarkets.Columns.Add(new DataGridViewTextBoxColumn
        {
            DataPropertyName = nameof(CoupangMarketEntry.DisplayName),
            HeaderText = "마켓명",
            FillWeight = 30,
            MinimumWidth = 180
        });
        _dgvCoupangMarkets.Columns.Add(new DataGridViewTextBoxColumn
        {
            DataPropertyName = nameof(CoupangMarketEntry.KeyFilePath),
            HeaderText = "Wing API 키 파일",
            FillWeight = 70,
            MinimumWidth = 420
        });
    }

    private void LoadMarkets(IReadOnlyList<Cafe24MarketEntry> markets)
    {
        _markets.Clear();
        foreach (var market in markets)
        {
            _markets.Add(new Cafe24MarketEntry
            {
                DisplayName = market.DisplayName,
                TokenFilePath = market.TokenFilePath
            });
        }

        _marketBindingSource.ResetBindings(false);
    }

    private void LoadCoupangMarkets(IReadOnlyList<CoupangMarketEntry> markets)
    {
        _coupangMarkets.Clear();
        foreach (var market in markets)
        {
            _coupangMarkets.Add(new CoupangMarketEntry
            {
                DisplayName = market.DisplayName,
                KeyFilePath = market.KeyFilePath
            });
        }

        _coupangMarketBindingSource.ResetBindings(false);
    }

    private void AddMarketRow()
    {
        _lblMessage.Text = "";
        var market = new Cafe24MarketEntry();
        _markets.Add(market);
        _marketBindingSource.ResetBindings(false);

        if (_dgvMarkets.Rows.Count == 0)
            return;

        var rowIndex = _dgvMarkets.Rows.Count - 1;
        _dgvMarkets.ClearSelection();
        _dgvMarkets.Rows[rowIndex].Selected = true;
        _dgvMarkets.CurrentCell = _dgvMarkets.Rows[rowIndex].Cells[0];
        _dgvMarkets.BeginEdit(true);
    }

    private void AddCoupangMarketRow()
    {
        _lblMessage.Text = "";
        var market = _coupangMarkets.Count == 0
            ? GetDefaultCoupangMarkets().FirstOrDefault() ?? new CoupangMarketEntry()
            : new CoupangMarketEntry();
        _coupangMarkets.Add(market);
        _coupangMarketBindingSource.ResetBindings(false);

        if (_dgvCoupangMarkets.Rows.Count == 0)
            return;

        var rowIndex = _dgvCoupangMarkets.Rows.Count - 1;
        _dgvCoupangMarkets.ClearSelection();
        _dgvCoupangMarkets.Rows[rowIndex].Selected = true;
        _dgvCoupangMarkets.CurrentCell = _dgvCoupangMarkets.Rows[rowIndex].Cells[0];
        _dgvCoupangMarkets.BeginEdit(true);
    }

    private void RemoveSelectedMarket()
    {
        _lblMessage.Text = "";
        if (_dgvMarkets.CurrentRow?.DataBoundItem is not Cafe24MarketEntry market)
        {
            _lblMessage.Text = "삭제할 마켓을 선택하세요.";
            return;
        }

        _markets.Remove(market);
        _marketBindingSource.ResetBindings(false);
    }

    private void RemoveSelectedCoupangMarket()
    {
        _lblMessage.Text = "";
        if (_dgvCoupangMarkets.CurrentRow?.DataBoundItem is not CoupangMarketEntry market)
        {
            _lblMessage.Text = "삭제할 쿠팡 마켓을 선택하세요.";
            return;
        }

        _coupangMarkets.Remove(market);
        _coupangMarketBindingSource.ResetBindings(false);
    }

    private void BrowseSelectedMarketFile()
    {
        _lblMessage.Text = "";
        if (_dgvMarkets.CurrentRow?.DataBoundItem is not Cafe24MarketEntry market)
        {
            _lblMessage.Text = "먼저 마켓을 추가하거나 선택하세요.";
            return;
        }

        using var dialog = new OpenFileDialog
        {
            Title = "Cafe24 JSON 키 파일 선택",
            Filter = "JSON 파일 (*.json)|*.json|모든 파일 (*.*)|*.*",
            CheckFileExists = true,
            Multiselect = false
        };

        var currentPath = Environment.ExpandEnvironmentVariables(market.TokenFilePath ?? "");
        if (!string.IsNullOrWhiteSpace(currentPath))
        {
            var directory = Path.GetDirectoryName(currentPath);
            if (!string.IsNullOrWhiteSpace(directory) && Directory.Exists(directory))
                dialog.InitialDirectory = directory;

            if (File.Exists(currentPath))
                dialog.FileName = Path.GetFileName(currentPath);
        }
        else
        {
            var defaultDirectory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Desktop), "key");
            if (Directory.Exists(defaultDirectory))
                dialog.InitialDirectory = defaultDirectory;
        }

        if (dialog.ShowDialog(this) != DialogResult.OK)
            return;

        market.TokenFilePath = dialog.FileName;
        _marketBindingSource.ResetBindings(false);
    }

    private void BrowseSelectedCoupangMarketFile()
    {
        _lblMessage.Text = "";
        if (_dgvCoupangMarkets.CurrentRow?.DataBoundItem is not CoupangMarketEntry market)
        {
            _lblMessage.Text = "먼저 쿠팡 마켓을 추가하거나 선택하세요.";
            return;
        }

        using var dialog = new OpenFileDialog
        {
            Title = "쿠팡 Wing API 키 파일 선택",
            Filter = "텍스트 파일 (*.txt)|*.txt|모든 파일 (*.*)|*.*",
            CheckFileExists = true,
            Multiselect = false
        };

        var currentPath = Environment.ExpandEnvironmentVariables(market.KeyFilePath ?? "");
        if (!string.IsNullOrWhiteSpace(currentPath))
        {
            var directory = Path.GetDirectoryName(currentPath);
            if (!string.IsNullOrWhiteSpace(directory) && Directory.Exists(directory))
                dialog.InitialDirectory = directory;

            if (File.Exists(currentPath))
                dialog.FileName = Path.GetFileName(currentPath);
        }
        else
        {
            var defaultDirectory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Desktop), "key");
            if (Directory.Exists(defaultDirectory))
                dialog.InitialDirectory = defaultDirectory;
        }

        if (dialog.ShowDialog(this) != DialogResult.OK)
            return;

        market.KeyFilePath = dialog.FileName;
        _coupangMarketBindingSource.ResetBindings(false);
    }

    private IReadOnlyList<Cafe24MarketEntry> ReadMarkets()
    {
        _dgvMarkets.EndEdit();
        _marketBindingSource.EndEdit();

        return _markets
            .Select(market => new Cafe24MarketEntry
            {
                DisplayName = market.DisplayName,
                TokenFilePath = market.TokenFilePath
            })
            .ToList();
    }

    private IReadOnlyList<CoupangMarketEntry> ReadCoupangMarkets()
    {
        _dgvCoupangMarkets.EndEdit();
        _coupangMarketBindingSource.EndEdit();

        return _coupangMarkets
            .Select(market => new CoupangMarketEntry
            {
                DisplayName = market.DisplayName,
                KeyFilePath = market.KeyFilePath
            })
            .ToList();
    }

    private static IReadOnlyList<CoupangMarketEntry> GetDefaultCoupangMarkets()
    {
        var defaultPath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
            "key",
            "coupang_wing_api.txt");

        if (!File.Exists(defaultPath))
            return Array.Empty<CoupangMarketEntry>();

        return new[]
        {
            new CoupangMarketEntry
            {
                DisplayName = "홈런마켓",
                KeyFilePath = defaultPath
            }
        };
    }

    private void HandleSave()
    {
        _lblMessage.Text = "";

        try
        {
            if (_registrationMode)
            {
                var (success, user, errorMessage) = _authService!.RegisterUser(
                    _txtUserName.Text,
                    _txtUserName.Text,
                    _txtPassword.Text,
                    _txtPassword.Text);

                if (!success || user == null)
                {
                    _lblMessage.Text = errorMessage;
                    return;
                }

                RegisteredUser = user;
                RegisteredPassword = _txtPassword.Text;
                DialogResult = DialogResult.OK;
                Close();
                return;
            }

            if (_existingUser == null)
                throw new InvalidOperationException("사용자 정보가 없습니다.");

            _userSettingsService.SaveMarketplaceMarkets(_existingUser.Id, ReadMarkets(), ReadCoupangMarkets(), _requireMarketplaceConfig);
            DialogResult = DialogResult.OK;
            Close();
        }
        catch (Exception ex)
        {
            _lblMessage.Text = ex.Message;
        }
    }
}
