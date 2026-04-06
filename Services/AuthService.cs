using System.Linq;
using System.Security.Cryptography;
using System.Text;
using Cafe24ShipmentManager.Data;
using Cafe24ShipmentManager.Models;

namespace Cafe24ShipmentManager.Services;

public sealed class AuthService
{
    private const int SecretIterations = 100_000;
    private readonly DatabaseManager _db;
    private readonly AppLogger _log;

    public AuthService(DatabaseManager db, AppLogger log)
    {
        _db = db;
        _log = log;
        EnsureDefaultAdminUser();
    }

    public LoginPreferences GetLoginPreferences()
    {
        return _db.GetLoginPreferences();
    }

    public string GetSavedPassword(LoginPreferences preferences)
    {
        return Unprotect(preferences.ProtectedPassword);
    }

    public AppUser? TryAutoLogin()
    {
        var preferences = _db.GetLoginPreferences();
        if (!preferences.AutoLogin ||
            string.IsNullOrWhiteSpace(preferences.LastUserName) ||
            string.IsNullOrWhiteSpace(preferences.ProtectedRememberToken))
            return null;

        var user = _db.GetUserByUserName(preferences.LastUserName);
        var rememberToken = Unprotect(preferences.ProtectedRememberToken);

        if (user == null ||
            string.IsNullOrWhiteSpace(rememberToken) ||
            !VerifySecret(rememberToken, user.RememberTokenHash, user.RememberTokenSalt))
        {
            ClearAutoLogin(preferences, user);
            _log.Warn("자동 로그인 토큰이 유효하지 않아 로그인 화면으로 전환합니다.");
            return null;
        }

        _db.UpdateUserLoginState(user.Id, Now(), user.RememberTokenHash, user.RememberTokenSalt, user.RememberTokenIssuedAt);
        _log.Info($"자동 로그인 성공: {user.UserName}");
        return user;
    }

    public (bool success, AppUser? user, string errorMessage) Login(string userName, string password, bool savePassword, bool autoLogin)
    {
        userName = userName.Trim();

        if (string.IsNullOrWhiteSpace(userName))
            return (false, null, "아이디를 입력하세요.");

        if (string.IsNullOrWhiteSpace(password))
            return (false, null, "비밀번호를 입력하세요.");

        var user = _db.GetUserByUserName(userName);
        if (user == null || !user.IsActive)
            return (false, null, "아이디 또는 비밀번호가 올바르지 않습니다.");

        if (!VerifySecret(password, user.PasswordHash, user.PasswordSalt))
            return (false, null, "아이디 또는 비밀번호가 올바르지 않습니다.");

        var preferences = _db.GetLoginPreferences();
        if (!string.IsNullOrWhiteSpace(preferences.LastUserName))
        {
            var previousUser = _db.GetUserByUserName(preferences.LastUserName);
            if (previousUser != null &&
                (!autoLogin || !string.Equals(previousUser.UserName, user.UserName, StringComparison.OrdinalIgnoreCase)))
            {
                _db.ClearUserRememberToken(previousUser.Id);
            }
        }

        var rememberTokenHash = "";
        var rememberTokenSalt = "";
        var rememberTokenIssuedAt = "";
        var protectedRememberToken = "";

        if (autoLogin)
        {
            var rememberToken = CreateRandomSecret();
            rememberTokenSalt = CreateSalt();
            rememberTokenHash = HashSecret(rememberToken, rememberTokenSalt);
            rememberTokenIssuedAt = Now();
            protectedRememberToken = Protect(rememberToken);
        }

        _db.UpdateUserLoginState(user.Id, Now(), rememberTokenHash, rememberTokenSalt, rememberTokenIssuedAt);

        preferences.LastUserName = user.UserName;
        preferences.SavePassword = savePassword;
        preferences.AutoLogin = autoLogin;
        preferences.ProtectedPassword = savePassword ? Protect(password) : "";
        preferences.ProtectedRememberToken = protectedRememberToken;
        preferences.UpdatedAt = Now();
        _db.SaveLoginPreferences(preferences);

        _log.Info($"로그인 성공: {user.UserName}");
        return (true, _db.GetUserByUserName(user.UserName) ?? user, "");
    }

    public (bool success, AppUser? user, string errorMessage) RegisterUser(string userName, string displayName, string password, string confirmPassword)
    {
        userName = userName.Trim();
        displayName = displayName.Trim();

        if (string.IsNullOrWhiteSpace(userName))
            return (false, null, "아이디를 입력하세요.");

        if (userName.Any(char.IsWhiteSpace))
            return (false, null, "아이디에는 공백을 사용할 수 없습니다.");

        if (string.IsNullOrWhiteSpace(password))
            return (false, null, "비밀번호를 입력하세요.");

        if (!string.Equals(password, confirmPassword, StringComparison.Ordinal))
            return (false, null, "비밀번호 확인이 일치하지 않습니다.");

        if (_db.GetUserByUserName(userName) != null)
            return (false, null, "이미 사용 중인 아이디입니다.");

        var salt = CreateSalt();
        var user = new AppUser
        {
            UserName = userName,
            DisplayName = string.IsNullOrWhiteSpace(displayName) ? userName : displayName,
            PasswordSalt = salt,
            PasswordHash = HashSecret(password, salt),
            IsActive = true,
            CreatedAt = Now(),
            LastLoginAt = "",
            RememberTokenHash = "",
            RememberTokenSalt = "",
            RememberTokenIssuedAt = ""
        };

        user.Id = _db.InsertUser(user);
        _log.Info($"사용자 등록: {user.UserName}");
        return (true, user, "");
    }

    private void EnsureDefaultAdminUser()
    {
        if (_db.GetUserCount() > 0)
            return;

        var salt = CreateSalt();
        var adminUser = new AppUser
        {
            UserName = "admin",
            DisplayName = "관리자",
            PasswordSalt = salt,
            PasswordHash = HashSecret("admin", salt),
            IsActive = true,
            CreatedAt = Now(),
            LastLoginAt = "",
            RememberTokenHash = "",
            RememberTokenSalt = "",
            RememberTokenIssuedAt = ""
        };

        _db.InsertUser(adminUser);
        _log.Info("기본 로그인 계정 생성: admin / admin");
    }

    private void ClearAutoLogin(LoginPreferences preferences, AppUser? user)
    {
        if (user != null)
            _db.ClearUserRememberToken(user.Id);

        preferences.AutoLogin = false;
        preferences.ProtectedRememberToken = "";
        preferences.UpdatedAt = Now();
        _db.SaveLoginPreferences(preferences);
    }

    private static string CreateRandomSecret()
    {
        return Convert.ToBase64String(RandomNumberGenerator.GetBytes(32));
    }

    private static string CreateSalt()
    {
        return Convert.ToBase64String(RandomNumberGenerator.GetBytes(16));
    }

    private static string HashSecret(string secret, string salt)
    {
        if (string.IsNullOrWhiteSpace(secret) || string.IsNullOrWhiteSpace(salt))
            return "";

        var saltBytes = Convert.FromBase64String(salt);
        var hashBytes = Rfc2898DeriveBytes.Pbkdf2(
            Encoding.UTF8.GetBytes(secret),
            saltBytes,
            SecretIterations,
            HashAlgorithmName.SHA256,
            32);

        return Convert.ToBase64String(hashBytes);
    }

    private static bool VerifySecret(string secret, string storedHash, string storedSalt)
    {
        if (string.IsNullOrWhiteSpace(secret) ||
            string.IsNullOrWhiteSpace(storedHash) ||
            string.IsNullOrWhiteSpace(storedSalt))
            return false;

        var calculatedHash = HashSecret(secret, storedSalt);
        var calculatedBytes = Convert.FromBase64String(calculatedHash);
        var storedBytes = Convert.FromBase64String(storedHash);
        return CryptographicOperations.FixedTimeEquals(calculatedBytes, storedBytes);
    }

    private static string Protect(string value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return "";

        var plainBytes = Encoding.UTF8.GetBytes(value);
        var protectedBytes = ProtectedData.Protect(plainBytes, null, DataProtectionScope.CurrentUser);
        return Convert.ToBase64String(protectedBytes);
    }

    private static string Unprotect(string value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return "";

        try
        {
            var protectedBytes = Convert.FromBase64String(value);
            var plainBytes = ProtectedData.Unprotect(protectedBytes, null, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(plainBytes);
        }
        catch
        {
            return "";
        }
    }

    private static string Now()
    {
        return DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss");
    }
}
