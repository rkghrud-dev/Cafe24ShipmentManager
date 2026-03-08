namespace Cafe24ShipmentManager.Models;

public class StockOrderHeader
{
    public long Id { get; set; }
    public string BaseCodeA { get; set; } = "";
    public string SiteUrl { get; set; } = "";
    public int TotalQty { get; set; }
    public decimal TotalAmountYuan { get; set; }
    public int ItemCount { get; set; }
    public string OrderedAt { get; set; } = "";
}

public class StockOrderLine
{
    public long Id { get; set; }
    public long HeaderId { get; set; }
    public string ProductCode { get; set; } = "";
    public string ImportDetail { get; set; } = "";
    public string OptionText { get; set; } = "";
    public int OrderQty { get; set; }
    public decimal UnitYuan { get; set; }
    public decimal AmountYuan { get; set; }
}

public class TopOrderedProduct
{
    public string ProductCode { get; set; } = "";
    public long TotalQty { get; set; }
    public int OrderCount { get; set; }
}

public class MonthlyOrderTrend
{
    public string Month { get; set; } = "";
    public long TotalQty { get; set; }
    public decimal TotalAmountYuan { get; set; }
}

public class OptionMonthlyRecord
{
    public string ProductCode { get; set; } = "";
    public string OptionText { get; set; } = "";
    public string Month { get; set; } = "";
    public long TotalQty { get; set; }
}
