using Cafe24ShipmentManager.Models;
using Dapper;
using Microsoft.Data.Sqlite;

namespace Cafe24ShipmentManager.Data;

public partial class DatabaseManager
{
    private void InitializeAuthTables(SqliteConnection conn)
    {
        conn.Execute(@"
            CREATE TABLE IF NOT EXISTS app_users (
                Id INTEGER PRIMARY KEY AUTOINCREMENT,
                UserName TEXT NOT NULL UNIQUE,
                DisplayName TEXT DEFAULT '',
                PasswordHash TEXT NOT NULL,
                PasswordSalt TEXT NOT NULL,
                IsActive INTEGER NOT NULL DEFAULT 1,
                CreatedAt TEXT NOT NULL,
                LastLoginAt TEXT DEFAULT '',
                RememberTokenHash TEXT DEFAULT '',
                RememberTokenSalt TEXT DEFAULT '',
                RememberTokenIssuedAt TEXT DEFAULT ''
            );

            CREATE TABLE IF NOT EXISTS app_login_settings (
                Id INTEGER PRIMARY KEY CHECK (Id = 1),
                LastUserName TEXT DEFAULT '',
                SavePassword INTEGER NOT NULL DEFAULT 0,
                AutoLogin INTEGER NOT NULL DEFAULT 0,
                ProtectedPassword TEXT DEFAULT '',
                ProtectedRememberToken TEXT DEFAULT '',
                UpdatedAt TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS app_user_settings (
                UserId INTEGER PRIMARY KEY,
                ProtectedGoogleCredentialPath TEXT DEFAULT '',
                GoogleSpreadsheetId TEXT DEFAULT '',
                GoogleDefaultSheetName TEXT DEFAULT '',
                ProtectedCafe24Json TEXT DEFAULT '',
                ProtectedCoupangJson TEXT DEFAULT '',
                UpdatedAt TEXT NOT NULL,
                FOREIGN KEY (UserId) REFERENCES app_users(Id)
            );

            CREATE INDEX IF NOT EXISTS idx_app_users_username ON app_users(UserName);
        ");
    }

    private void EnsureAuthSchemaUpgrades(SqliteConnection conn)
    {
        EnsureColumnExists(conn, "app_users", "DisplayName", "TEXT DEFAULT ''");
        EnsureColumnExists(conn, "app_users", "LastLoginAt", "TEXT DEFAULT ''");
        EnsureColumnExists(conn, "app_users", "RememberTokenHash", "TEXT DEFAULT ''");
        EnsureColumnExists(conn, "app_users", "RememberTokenSalt", "TEXT DEFAULT ''");
        EnsureColumnExists(conn, "app_users", "RememberTokenIssuedAt", "TEXT DEFAULT ''");
        EnsureColumnExists(conn, "app_login_settings", "LastUserName", "TEXT DEFAULT ''");
        EnsureColumnExists(conn, "app_login_settings", "SavePassword", "INTEGER NOT NULL DEFAULT 0");
        EnsureColumnExists(conn, "app_login_settings", "AutoLogin", "INTEGER NOT NULL DEFAULT 0");
        EnsureColumnExists(conn, "app_login_settings", "ProtectedPassword", "TEXT DEFAULT ''");
        EnsureColumnExists(conn, "app_login_settings", "ProtectedRememberToken", "TEXT DEFAULT ''");
        EnsureColumnExists(conn, "app_login_settings", "UpdatedAt", "TEXT NOT NULL DEFAULT ''");
        EnsureColumnExists(conn, "app_user_settings", "ProtectedGoogleCredentialPath", "TEXT DEFAULT ''");
        EnsureColumnExists(conn, "app_user_settings", "GoogleSpreadsheetId", "TEXT DEFAULT ''");
        EnsureColumnExists(conn, "app_user_settings", "GoogleDefaultSheetName", "TEXT DEFAULT ''");
        EnsureColumnExists(conn, "app_user_settings", "ProtectedCafe24Json", "TEXT DEFAULT ''");
        EnsureColumnExists(conn, "app_user_settings", "ProtectedCoupangJson", "TEXT DEFAULT ''");
        EnsureColumnExists(conn, "app_user_settings", "UpdatedAt", "TEXT NOT NULL DEFAULT ''");
    }

    public int GetUserCount()
    {
        using var conn = GetConnection();
        return conn.ExecuteScalar<int>("SELECT COUNT(*) FROM app_users");
    }

    public AppUser? GetUserByUserName(string userName)
    {
        using var conn = GetConnection();
        return conn.QueryFirstOrDefault<AppUser>(
            "SELECT * FROM app_users WHERE UserName = @userName COLLATE NOCASE LIMIT 1",
            new { userName });
    }

    public long InsertUser(AppUser user)
    {
        using var conn = GetConnection();
        return conn.ExecuteScalar<long>(@"
            INSERT INTO app_users
                (UserName, DisplayName, PasswordHash, PasswordSalt, IsActive, CreatedAt, LastLoginAt, RememberTokenHash, RememberTokenSalt, RememberTokenIssuedAt)
            VALUES
                (@UserName, @DisplayName, @PasswordHash, @PasswordSalt, @IsActive, @CreatedAt, @LastLoginAt, @RememberTokenHash, @RememberTokenSalt, @RememberTokenIssuedAt);
            SELECT last_insert_rowid();", user);
    }

    public void UpdateUserLoginState(long userId, string lastLoginAt, string rememberTokenHash, string rememberTokenSalt, string rememberTokenIssuedAt)
    {
        using var conn = GetConnection();
        conn.Execute(@"
            UPDATE app_users
            SET LastLoginAt = @lastLoginAt,
                RememberTokenHash = @rememberTokenHash,
                RememberTokenSalt = @rememberTokenSalt,
                RememberTokenIssuedAt = @rememberTokenIssuedAt
            WHERE Id = @userId",
            new { userId, lastLoginAt, rememberTokenHash, rememberTokenSalt, rememberTokenIssuedAt });
    }

    public void ClearUserRememberToken(long userId)
    {
        using var conn = GetConnection();
        conn.Execute(@"
            UPDATE app_users
            SET RememberTokenHash = '',
                RememberTokenSalt = '',
                RememberTokenIssuedAt = ''
            WHERE Id = @userId",
            new { userId });
    }

    public LoginPreferences GetLoginPreferences()
    {
        using var conn = GetConnection();
        return conn.QueryFirstOrDefault<LoginPreferences>("SELECT * FROM app_login_settings WHERE Id = 1")
            ?? new LoginPreferences { Id = 1, UpdatedAt = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") };
    }

    public void SaveLoginPreferences(LoginPreferences prefs)
    {
        using var conn = GetConnection();
        conn.Execute(@"
            INSERT INTO app_login_settings
                (Id, LastUserName, SavePassword, AutoLogin, ProtectedPassword, ProtectedRememberToken, UpdatedAt)
            VALUES
                (@Id, @LastUserName, @SavePassword, @AutoLogin, @ProtectedPassword, @ProtectedRememberToken, @UpdatedAt)
            ON CONFLICT(Id) DO UPDATE SET
                LastUserName = excluded.LastUserName,
                SavePassword = excluded.SavePassword,
                AutoLogin = excluded.AutoLogin,
                ProtectedPassword = excluded.ProtectedPassword,
                ProtectedRememberToken = excluded.ProtectedRememberToken,
                UpdatedAt = excluded.UpdatedAt;", prefs);
    }

    public AppUserSettings GetUserSettings(long userId)
    {
        using var conn = GetConnection();
        return conn.QueryFirstOrDefault<AppUserSettings>(
            "SELECT * FROM app_user_settings WHERE UserId = @userId LIMIT 1",
            new { userId })
            ?? new AppUserSettings { UserId = userId, UpdatedAt = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") };
    }

    public void SaveUserSettings(AppUserSettings settings)
    {
        using var conn = GetConnection();
        conn.Execute(@"
            INSERT INTO app_user_settings
                (UserId, ProtectedGoogleCredentialPath, GoogleSpreadsheetId, GoogleDefaultSheetName, ProtectedCafe24Json, ProtectedCoupangJson, UpdatedAt)
            VALUES
                (@UserId, @ProtectedGoogleCredentialPath, @GoogleSpreadsheetId, @GoogleDefaultSheetName, @ProtectedCafe24Json, @ProtectedCoupangJson, @UpdatedAt)
            ON CONFLICT(UserId) DO UPDATE SET
                ProtectedGoogleCredentialPath = excluded.ProtectedGoogleCredentialPath,
                GoogleSpreadsheetId = excluded.GoogleSpreadsheetId,
                GoogleDefaultSheetName = excluded.GoogleDefaultSheetName,
                ProtectedCafe24Json = excluded.ProtectedCafe24Json,
                ProtectedCoupangJson = excluded.ProtectedCoupangJson,
                UpdatedAt = excluded.UpdatedAt;", settings);
    }
}
