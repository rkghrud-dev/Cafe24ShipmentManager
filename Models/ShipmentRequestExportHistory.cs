namespace Cafe24ShipmentManager.Models;

public class ShipmentRequestExportBatch
{
    public long Id { get; set; }
    public long AppUserId { get; set; }
    public string UserName { get; set; } = "";
    public string SavedFrom { get; set; } = "";
    public string MarketName { get; set; } = "";
    public string ExportDate { get; set; } = "";
    public int RowCount { get; set; }
    public int MissingProductCodeCount { get; set; }
    public string FilePath { get; set; } = "";
    public string CreatedAt { get; set; } = "";
}

public class ShipmentRequestExportRow
{
    public long Id { get; set; }
    public long BatchId { get; set; }
    public int RowNumber { get; set; }
    public string ProductCode { get; set; } = "";
    public string MarketName { get; set; } = "";
    public string ExportDate { get; set; } = "";
    public int Quantity { get; set; }
    public string RecipientName { get; set; } = "";
    public string RecipientPhone { get; set; } = "";
    public string PostalCode { get; set; } = "";
    public string FullAddress { get; set; } = "";
    public string ShippingMessage { get; set; } = "";
    public string DetailAddress { get; set; } = "";
}

public class ShipmentRequestProductCodeMapping
{
    public long Id { get; set; }
    public long AppUserId { get; set; }
    public string ProductKey { get; set; } = "";
    public string SupplierProductName { get; set; } = "";
    public string ProductOption { get; set; } = "";
    public string ProductCode { get; set; } = "";
    public string CreatedAt { get; set; } = "";
    public string UpdatedAt { get; set; } = "";
}
