#:package ClosedXML@0.104.2
using ClosedXML.Excel;
foreach (var p in new[]{ @"F:\Code\Android\fenbi\疯狂刷2500题-言语理解与表达600题（题目+答案解析）.xlsx" })
{
    using var wb = new XLWorkbook(p);
    Console.WriteLine("FILE " + Path.GetFileName(p) + " sheets=" + wb.Worksheets.Count);
    foreach (var ws in wb.Worksheets)
        Console.WriteLine($"  {ws.Name} | rows={ws.RangeUsed()?.RowCount()} cols={ws.RangeUsed()?.ColumnCount()}");
}
Console.WriteLine("--- 超格 header ---");
using (var wb = new XLWorkbook(@"F:\Code\Android\fenbi\超格老于言语-进阶易错600题（导入模板）.xlsx"))
{
    var ws = wb.Worksheet(2);
    for (int c = 1; c <= 15; c++) Console.Write($"[{ws.Cell(1,c).GetString()}]");
    Console.WriteLine();
    for (int c = 1; c <= 15; c++) Console.Write($"[{Trunc(ws.Cell(2,c).GetString(),18)}]");
    Console.WriteLine();
}
static string Trunc(string s, int n) => s.Length <= n ? s : s[..n] + "…";
