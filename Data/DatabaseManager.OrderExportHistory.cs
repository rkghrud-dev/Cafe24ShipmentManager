using Dapper;
using Microsoft.Data.Sqlite;
using Cafe24ShipmentManager.Models;

namespace Cafe24ShipmentManager.Data;

public partial class DatabaseManager
{
    private static void InitializeShipmentRequestExportTables(SqliteConnection conn)
    {
        conn.Execute(@"
            CREATE TABLE IF NOT EXISTS shipment_request_export_batches (
                Id INTEGER PRIMARY KEY AUTOINCREMENT,
                AppUserId INTEGER DEFAULT 0,
                UserName TEXT DEFAULT '',
                SavedFrom TEXT DEFAULT '',
                MarketName TEXT DEFAULT '',
                ExportDate TEXT DEFAULT '',
                RowCount INTEGER DEFAULT 0,
                MissingProductCodeCount INTEGER DEFAULT 0,
                FilePath TEXT DEFAULT '',
                CreatedAt TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS shipment_request_export_rows (
                Id INTEGER PRIMARY KEY AUTOINCREMENT,
                BatchId INTEGER NOT NULL,
                RowNumber INTEGER DEFAULT 0,
                ProductCode TEXT DEFAULT '',
                MarketName TEXT DEFAULT '',
                ExportDate TEXT DEFAULT '',
                Quantity INTEGER DEFAULT 0,
                RecipientName TEXT DEFAULT '',
                RecipientPhone TEXT DEFAULT '',
                PostalCode TEXT DEFAULT '',
                FullAddress TEXT DEFAULT '',
                ShippingMessage TEXT DEFAULT '',
                DetailAddress TEXT DEFAULT '',
                FOREIGN KEY (BatchId) REFERENCES shipment_request_export_batches(Id)
            );

            CREATE INDEX IF NOT EXISTS idx_sreb_user_created ON shipment_request_export_batches(AppUserId, CreatedAt);
            CREATE INDEX IF NOT EXISTS idx_srer_batch ON shipment_request_export_rows(BatchId, RowNumber);

            CREATE TABLE IF NOT EXISTS shipment_request_product_code_mappings (
                Id INTEGER PRIMARY KEY AUTOINCREMENT,
                AppUserId INTEGER DEFAULT 0,
                ProductKey TEXT NOT NULL,
                SupplierProductName TEXT DEFAULT '',
                ProductOption TEXT DEFAULT '',
                ProductCode TEXT DEFAULT '',
                CreatedAt TEXT NOT NULL,
                UpdatedAt TEXT NOT NULL,
                UNIQUE(AppUserId, ProductKey)
            );

            CREATE INDEX IF NOT EXISTS idx_srpcm_user_key ON shipment_request_product_code_mappings(AppUserId, ProductKey);
        ");
    }

    public long InsertShipmentRequestExportBatch(ShipmentRequestExportBatch batch, IReadOnlyCollection<ShipmentRequestExportRow> rows)
    {
        using var conn = GetConnection();
        using var tx = conn.BeginTransaction();

        var batchId = conn.ExecuteScalar<long>(@"
            INSERT INTO shipment_request_export_batches
                (AppUserId, UserName, SavedFrom, MarketName, ExportDate, RowCount, MissingProductCodeCount, FilePath, CreatedAt)
            VALUES
                (@AppUserId, @UserName, @SavedFrom, @MarketName, @ExportDate, @RowCount, @MissingProductCodeCount, @FilePath, @CreatedAt);
            SELECT last_insert_rowid();",
            batch,
            transaction: tx);

        foreach (var row in rows)
        {
            row.BatchId = batchId;
            conn.Execute(@"
                INSERT INTO shipment_request_export_rows
                    (BatchId, RowNumber, ProductCode, MarketName, ExportDate, Quantity, RecipientName, RecipientPhone, PostalCode, FullAddress, ShippingMessage, DetailAddress)
                VALUES
                    (@BatchId, @RowNumber, @ProductCode, @MarketName, @ExportDate, @Quantity, @RecipientName, @RecipientPhone, @PostalCode, @FullAddress, @ShippingMessage, @DetailAddress);",
                row,
                transaction: tx);
        }

        tx.Commit();
        return batchId;
    }

    public List<ShipmentRequestExportBatch> GetRecentShipmentRequestExportBatches(long? appUserId = null, int limit = 200)
    {
        using var conn = GetConnection();
        return conn.Query<ShipmentRequestExportBatch>(@"
            SELECT *
            FROM shipment_request_export_batches
            WHERE (@AppUserId IS NULL OR AppUserId = @AppUserId)
            ORDER BY Id DESC
            LIMIT @Limit;",
            new { AppUserId = appUserId, Limit = limit }).ToList();
    }

    public List<ShipmentRequestExportRow> GetShipmentRequestExportRows(long batchId)
    {
        using var conn = GetConnection();
        return conn.Query<ShipmentRequestExportRow>(@"
            SELECT *
            FROM shipment_request_export_rows
            WHERE BatchId = @BatchId
            ORDER BY RowNumber, Id;",
            new { BatchId = batchId }).ToList();
    }

    public List<ShipmentRequestProductCodeMapping> GetShipmentRequestProductCodeMappings(long appUserId)
    {
        using var conn = GetConnection();
        return conn.Query<ShipmentRequestProductCodeMapping>(@"
            SELECT *
            FROM shipment_request_product_code_mappings
            WHERE AppUserId = @AppUserId
            ORDER BY SupplierProductName, ProductOption;",
            new { AppUserId = appUserId }).ToList();
    }

    public void UpsertShipmentRequestProductCodeMapping(ShipmentRequestProductCodeMapping mapping)
    {
        using var conn = GetConnection();
        var now = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss");
        if (string.IsNullOrWhiteSpace(mapping.CreatedAt))
            mapping.CreatedAt = now;
        mapping.UpdatedAt = now;

        conn.Execute(@"
            INSERT INTO shipment_request_product_code_mappings
                (AppUserId, ProductKey, SupplierProductName, ProductOption, ProductCode, CreatedAt, UpdatedAt)
            VALUES
                (@AppUserId, @ProductKey, @SupplierProductName, @ProductOption, @ProductCode, @CreatedAt, @UpdatedAt)
            ON CONFLICT(AppUserId, ProductKey) DO UPDATE SET
                SupplierProductName = excluded.SupplierProductName,
                ProductOption = excluded.ProductOption,
                ProductCode = excluded.ProductCode,
                UpdatedAt = excluded.UpdatedAt;",
            mapping);
    }
}
