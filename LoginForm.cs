using Cafe24ShipmentManager.Models;
using Cafe24ShipmentManager.Services;

namespace Cafe24ShipmentManager;

public sealed class LoginForm : Form
{
    private readonly AuthService _authService;
    private readonly UserSettingsService _userSettingsService;
    private readonly TextBox _txtUserName = new() { Dock = DockStyle.Fill };
    private readonly TextBox _txtPassword = new() { Dock = DockStyle.Fill, UseSystemPasswordChar = true };
    private readonly CheckBox _chkSavePassword = new() { Text = "비밀번호 저장", AutoSize = true };
    private readonly CheckBox _chkAutoLogin = new() { Text = "자동 로그인", AutoSize = true };
    private readonly Label _lblMessage = new() { AutoSize = true, ForeColor = Color.Firebrick };

    public AppUser? AuthenticatedUser { get; private set; }

    public LoginForm(AuthService authService, UserSettingsService userSettingsService)
    {
        _authService = authService;
        _userSettingsService = userSettingsService;
        InitializeUi();
        LoadSavedState();
    }

    private void InitializeUi()
    {
        Text = "로그인";
        ClientSize = new Size(460, 260);
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        ShowInTaskbar = false;
        Font = new Font("맑은 고딕", 9f);

        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(18),
            ColumnCount = 2,
            RowCount = 7
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 92));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));

        var lblTitle = new Label
        {
            Text = "Cafe24 Shipment Manager",
            AutoSize = true,
            Font = new Font("맑은 고딕", 12f, FontStyle.Bold)
        };
        var lblHint = new Label
        {
            Text = "초기 계정: admin / admin",
            AutoSize = true,
            ForeColor = Color.DimGray,
            Margin = new Padding(0, 4, 0, 10)
        };

        layout.Controls.Add(lblTitle, 0, 0);
        layout.SetColumnSpan(lblTitle, 2);
        layout.Controls.Add(lblHint, 0, 1);
        layout.SetColumnSpan(lblHint, 2);

        layout.Controls.Add(new Label { Text = "아이디", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 2);
        layout.Controls.Add(_txtUserName, 1, 2);
        layout.Controls.Add(new Label { Text = "비밀번호", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 3);
        layout.Controls.Add(_txtPassword, 1, 3);

        var optionPanel = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.LeftToRight,
            AutoSize = true,
            WrapContents = false,
            Margin = new Padding(0, 8, 0, 0)
        };
        optionPanel.Controls.Add(_chkSavePassword);
        optionPanel.Controls.Add(_chkAutoLogin);
        layout.Controls.Add(optionPanel, 1, 4);

        layout.Controls.Add(_lblMessage, 1, 5);

        var buttonPanel = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.RightToLeft,
            AutoSize = true,
            WrapContents = false,
            Margin = new Padding(0, 12, 0, 0)
        };
        var btnLogin = new Button { Text = "로그인", Width = 90, Height = 32 };
        var btnRegister = new Button { Text = "신규 사용자 등록", Width = 130, Height = 32 };
        var btnCancel = new Button { Text = "종료", Width = 90, Height = 32, DialogResult = DialogResult.Cancel };
        btnLogin.Click += (_, _) => HandleLogin();
        btnRegister.Click += (_, _) => HandleRegister();
        buttonPanel.Controls.Add(btnLogin);
        buttonPanel.Controls.Add(btnRegister);
        buttonPanel.Controls.Add(btnCancel);
        layout.Controls.Add(buttonPanel, 1, 6);

        AcceptButton = btnLogin;
        CancelButton = btnCancel;
        Controls.Add(layout);

        Shown += (_, _) =>
        {
            if (string.IsNullOrWhiteSpace(_txtUserName.Text))
            {
                _txtUserName.Focus();
                return;
            }

            _txtPassword.Focus();
            _txtPassword.SelectAll();
        };
    }

    private void LoadSavedState()
    {
        var preferences = _authService.GetLoginPreferences();
        _txtUserName.Text = string.IsNullOrWhiteSpace(preferences.LastUserName) ? "admin" : preferences.LastUserName;
        _txtPassword.Text = _authService.GetSavedPassword(preferences);

        if (string.IsNullOrWhiteSpace(_txtPassword.Text) &&
            string.Equals(_txtUserName.Text, "admin", StringComparison.OrdinalIgnoreCase))
        {
            _txtPassword.Text = "admin";
        }

        _chkSavePassword.Checked = preferences.SavePassword;
        _chkAutoLogin.Checked = preferences.AutoLogin;
    }

    private void HandleLogin()
    {
        _lblMessage.Text = "";

        try
        {
            var (success, user, errorMessage) = _authService.Login(
                _txtUserName.Text,
                _txtPassword.Text,
                _chkSavePassword.Checked,
                _chkAutoLogin.Checked);

            if (!success || user == null)
            {
                _lblMessage.Text = errorMessage;
                return;
            }

            AuthenticatedUser = user;
            DialogResult = DialogResult.OK;
            Close();
        }
        catch (Exception ex)
        {
            _lblMessage.Text = ex.Message;
        }
    }

    private void HandleRegister()
    {
        _lblMessage.Text = "";

        using var profileForm = new UserProfileForm(_authService, _userSettingsService);
        if (profileForm.ShowDialog(this) != DialogResult.OK || profileForm.RegisteredUser == null)
            return;

        _txtUserName.Text = profileForm.RegisteredUser.UserName;
        _txtPassword.Text = profileForm.RegisteredPassword;
        _chkSavePassword.Checked = true;
        _chkAutoLogin.Checked = false;
        _lblMessage.Text = "등록 완료. 로그인하세요.";
        _txtPassword.Focus();
        _txtPassword.SelectAll();
    }
}
