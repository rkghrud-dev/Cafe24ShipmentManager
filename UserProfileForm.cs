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
    private readonly TextBox _txtDisplayName = new() { Dock = DockStyle.Fill };
    private readonly TextBox _txtPassword = new() { Dock = DockStyle.Fill, UseSystemPasswordChar = true };
    private readonly TextBox _txtPasswordConfirm = new() { Dock = DockStyle.Fill, UseSystemPasswordChar = true };
    private readonly TextBox _txtGoogleCredentialPath = new() { Dock = DockStyle.Fill };
    private readonly TextBox _txtGoogleSpreadsheetId = new() { Dock = DockStyle.Fill };
    private readonly TextBox _txtGoogleSheetName = new() { Dock = DockStyle.Fill };
    private readonly TextBox _txtCafe24Json = new() { Dock = DockStyle.Fill, Multiline = true, ScrollBars = ScrollBars.Both, Font = new Font("Consolas", 9f) };
    private readonly TextBox _txtCoupangJson = new() { Dock = DockStyle.Fill, Multiline = true, ScrollBars = ScrollBars.Both, Font = new Font("Consolas", 9f) };
    private readonly Label _lblMessage = new() { AutoSize = true, ForeColor = Color.Firebrick };

    public AppUser? RegisteredUser { get; private set; }
    public string RegisteredPassword { get; private set; } = "";

    public UserProfileForm(AuthService authService, UserSettingsService userSettingsService)
    {
        _authService = authService;
        _userSettingsService = userSettingsService;
        _registrationMode = true;
        _requireMarketplaceConfig = true;

        InitializeUi();
        LoadDraft(_userSettingsService.CreateDraftForRegistration());
    }

    public UserProfileForm(AppUser user, UserSettingsService userSettingsService, bool requireMarketplaceConfig)
    {
        _existingUser = user;
        _userSettingsService = userSettingsService;
        _registrationMode = false;
        _requireMarketplaceConfig = requireMarketplaceConfig;

        InitializeUi();
        LoadDraft(_userSettingsService.LoadDraft(user.Id));
    }

    private void InitializeUi()
    {
        Text = _registrationMode ? "신규 사용자 등록" : "내 키 설정";
        ClientSize = new Size(860, 760);
        StartPosition = FormStartPosition.CenterParent;
        FormBorderStyle = FormBorderStyle.Sizable;
        MinimizeBox = false;
        Font = new Font("맑은 고딕", 9f);

        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(12),
            ColumnCount = 1,
            RowCount = 5
        };
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100f));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));

        var lblTitle = new Label
        {
            Text = _registrationMode ? "아이디와 키를 먼저 등록한 뒤 로그인합니다." : "현재 로그인 계정의 키 설정을 수정합니다. 저장 후 다음 로그인부터 적용됩니다.",
            AutoSize = true,
            Font = new Font("맑은 고딕", 10f, FontStyle.Bold)
        };
        root.Controls.Add(lblTitle, 0, 0);

        var accountGroup = BuildAccountGroup();
        accountGroup.Visible = _registrationMode;
        root.Controls.Add(accountGroup, 0, 1);

        var googleGroup = BuildGoogleGroup();
        root.Controls.Add(googleGroup, 0, 2);

        var marketTabs = new TabControl { Dock = DockStyle.Fill };
        var cafe24Tab = new TabPage("Cafe24 설정 JSON");
        var coupangTab = new TabPage("쿠팡 설정 JSON");
        cafe24Tab.Controls.Add(_txtCafe24Json);
        coupangTab.Controls.Add(_txtCoupangJson);
        marketTabs.TabPages.Add(cafe24Tab);
        marketTabs.TabPages.Add(coupangTab);
        root.Controls.Add(marketTabs, 0, 3);

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
        root.Controls.Add(bottomPanel, 0, 4);

        AcceptButton = btnSave;
        CancelButton = btnCancel;
        Controls.Add(root);

        if (!_registrationMode && _existingUser != null)
        {
            _txtUserName.Text = _existingUser.UserName;
            _txtDisplayName.Text = _existingUser.DisplayName;
        }
    }

    private Control BuildAccountGroup()
    {
        var group = new GroupBox { Text = "사용자 계정", Dock = DockStyle.Top, AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink };
        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(10), ColumnCount = 2, AutoSize = true };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 120));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));

        layout.Controls.Add(new Label { Text = "아이디", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 0);
        layout.Controls.Add(_txtUserName, 1, 0);
        layout.Controls.Add(new Label { Text = "표시 이름", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 1);
        layout.Controls.Add(_txtDisplayName, 1, 1);
        layout.Controls.Add(new Label { Text = "비밀번호", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 2);
        layout.Controls.Add(_txtPassword, 1, 2);
        layout.Controls.Add(new Label { Text = "비밀번호 확인", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 3);
        layout.Controls.Add(_txtPasswordConfirm, 1, 3);

        group.Controls.Add(layout);
        return group;
    }

    private Control BuildGoogleGroup()
    {
        var group = new GroupBox { Text = "Google Sheets 설정", Dock = DockStyle.Top, AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink };
        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(10), ColumnCount = 2, AutoSize = true };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 120));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));

        layout.Controls.Add(new Label { Text = "CredentialPath", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 0);
        layout.Controls.Add(_txtGoogleCredentialPath, 1, 0);
        layout.Controls.Add(new Label { Text = "SpreadsheetId", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 1);
        layout.Controls.Add(_txtGoogleSpreadsheetId, 1, 1);
        layout.Controls.Add(new Label { Text = "기본 시트명", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 2);
        layout.Controls.Add(_txtGoogleSheetName, 1, 2);

        group.Controls.Add(layout);
        return group;
    }

    private void LoadDraft(UserSettingsDraft draft)
    {
        _txtGoogleCredentialPath.Text = draft.GoogleCredentialPath;
        _txtGoogleSpreadsheetId.Text = draft.GoogleSpreadsheetId;
        _txtGoogleSheetName.Text = draft.GoogleDefaultSheetName;
        _txtCafe24Json.Text = draft.Cafe24Json;
        _txtCoupangJson.Text = draft.CoupangJson;
    }

    private UserSettingsDraft ReadDraft()
    {
        return new UserSettingsDraft
        {
            GoogleCredentialPath = _txtGoogleCredentialPath.Text,
            GoogleSpreadsheetId = _txtGoogleSpreadsheetId.Text,
            GoogleDefaultSheetName = _txtGoogleSheetName.Text,
            Cafe24Json = _txtCafe24Json.Text,
            CoupangJson = _txtCoupangJson.Text
        };
    }

    private void HandleSave()
    {
        _lblMessage.Text = "";

        try
        {
            var draft = _userSettingsService.ValidateDraft(ReadDraft(), _registrationMode || _requireMarketplaceConfig);

            if (_registrationMode)
            {
                var (success, user, errorMessage) = _authService!.RegisterUser(
                    _txtUserName.Text,
                    _txtDisplayName.Text,
                    _txtPassword.Text,
                    _txtPasswordConfirm.Text);

                if (!success || user == null)
                {
                    _lblMessage.Text = errorMessage;
                    return;
                }

                _userSettingsService.SaveUserSettings(user.Id, draft, requireMarketplaceConfig: true);
                RegisteredUser = user;
                RegisteredPassword = _txtPassword.Text;
                DialogResult = DialogResult.OK;
                Close();
                return;
            }

            if (_existingUser == null)
                throw new InvalidOperationException("사용자 정보가 없습니다.");

            _userSettingsService.SaveUserSettings(_existingUser.Id, draft, _requireMarketplaceConfig);
            DialogResult = DialogResult.OK;
            Close();
        }
        catch (Exception ex)
        {
            _lblMessage.Text = ex.Message;
        }
    }
}
