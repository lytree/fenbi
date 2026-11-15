#:package ClosedXML@0.104.2
using ClosedXML.Excel;
var p = @"F:\Code\Android\fenbi\超格老于言语-进阶易错600题（导入模板）.xlsx";
using var wb = new XLWorkbook(p);
foreach (var ws in wb.Worksheets)
{
    var rng = ws.RangeUsed();
    Console.WriteLine($"{ws.Name} | rows={rng?.RowCount()} cols={rng?.ColumnCount()}");
}
