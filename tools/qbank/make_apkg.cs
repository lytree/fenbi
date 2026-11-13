#!/usr/bin/env dotnet
// 把 700 题图推题库打包成一键导入的 Anki 牌组（.apkg）。
//
// 牌组结构：图推700题 :: 第01套 ... 第35套
// 笔记类型：图推700题（字段：题图 / 答案 / 来源）
// 图片以 media 内嵌，导入 .apkg 无需手工拷贝文件。
//
// 用法：
//   dotnet run tools/qbank/make_apkg.cs
//
// .apkg 结构（手写，等价于 genanki.Package.write_to_file）：
//   collection.anki2 —— sqlite，含 col/notes/cards/revlog/graves 表，col 里写 models/decks/dconf JSON；
//   media            —— {"0": "s01_q01.png", ...} 数字文件名 -> 原文件名；
//   0, 1, 2 ...      —— 按数字文件名存放的图片字节。
// MODEL_ID / DECK_ID_BASE / 字段名 / 卡片模板 / CSS 全部与原脚本保持一致。

#:package Microsoft.Data.Sqlite@9.0.0
#:property PublishAot=false

using System.Globalization;
using System.IO.Compression;
using System.Runtime.CompilerServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Data.Sqlite;

Console.OutputEncoding = System.Text.Encoding.UTF8;

var ROOT = Path.GetFullPath(Path.Combine(ThisDir(), "..", ".."));
var OUT_DIR = Path.Combine(ROOT, "qbank_out");
var IMG_DIR = Path.Combine(OUT_DIR, "images");
var OUT_APKG = Path.Combine(OUT_DIR, "图推700题.apkg");

const long MODEL_ID = 1743902500117;
const long DECK_ID_BASE = 1743902500200;

var questionsPath = Path.Combine(OUT_DIR, "questions.json");
if (!File.Exists(questionsPath))
{
    Console.Error.WriteLine($"找不到 {questionsPath}");
    return 2;
}

var records = new List<Record>();
using (var jd = JsonDocument.Parse(File.ReadAllText(questionsPath, Encoding.UTF8)))
{
    foreach (var e in jd.RootElement.EnumerateArray())
        records.Add(new Record(
            e.GetProperty("image").GetString()!,
            e.GetProperty("answer").GetString()!,
            e.GetProperty("set").GetInt32(),
            e.GetProperty("qnum").GetInt32()));
}
if (records.Count != 700)
{
    Console.Error.WriteLine($"题目数应为 700，实际 {records.Count}");
    return 2;
}

var media = new List<string>();
foreach (var r in records)
{
    var path = Path.Combine(IMG_DIR, r.Image);
    if (!File.Exists(path))
    {
        Console.Error.WriteLine($"找不到图片：{path}");
        return 2;
    }
    media.Add(path);
}
// pkg.media_files = sorted(set(media))；enumerate 后按数字文件名写入。
var mediaFiles = media.Distinct(StringComparer.Ordinal).OrderBy(x => x, StringComparer.Ordinal).ToList();

long modTs = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
long idGen = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

// ---------- 组装 col 里的 models / decks JSON ----------
var decks = new JsonObject { ["1"] = DefaultDeckJson() };
for (int s = 1; s <= 35; s++)
    decks[(DECK_ID_BASE + s).ToString(CultureInfo.InvariantCulture)] =
        DeckJson(DECK_ID_BASE + s, "图推700题::第" + s.ToString("D2", CultureInfo.InvariantCulture) + "套");
// root 父牌组最后写入，且不带任何 note。
decks[DECK_ID_BASE.ToString(CultureInfo.InvariantCulture)] = DeckJson(DECK_ID_BASE, "图推700题");

// 模型 did 等于「最后一个带 note 的子牌组」的 id（第35套）；root 无 note，不改模型。
var models = new JsonObject
{
    [MODEL_ID.ToString(CultureInfo.InvariantCulture)] = BuildModel(MODEL_ID, DECK_ID_BASE + 35, modTs)
};
var modelsJson = ToJson(models);
var decksJson = ToJson(decks);

// ---------- 写 collection.anki2 ----------
var dbPath = Path.Combine(Path.GetTempPath(), "apkg_" + Guid.NewGuid().ToString("N") + ".anki2");
byte[] dbBytes;
try
{
    var csb = new SqliteConnectionStringBuilder { DataSource = dbPath };
    using (var conn = new SqliteConnection(csb.ToString()))
    {
        conn.Open();
        InitSchema(conn);
        InsertCol(conn, modelsJson, decksJson);

        foreach (var r in records)
        {
            long noteId = idGen++;
            var guid = GuidFor("tutui700", r.Set, r.Qnum);
            var f0 = $"<img src=\"{r.Image}\">";
            var f1 = r.Answer;
            var f2 = $"第{r.Set}套 第{r.Qnum}题";
            var flds = string.Join("\u001F", new[] { f0, f1, f2 });
            var tags = " 图推700题 第" + r.Set.ToString("D2", CultureInfo.InvariantCulture) + "套 ";
            InsertNote(conn, noteId, guid, MODEL_ID, modTs, tags, flds, f0);
            InsertCard(conn, idGen++, noteId, DECK_ID_BASE + r.Set, modTs);
        }
    }
    dbBytes = File.ReadAllBytes(dbPath);
}
finally
{
    if (File.Exists(dbPath)) File.Delete(dbPath);
}

// ---------- 打包 .apkg ----------
Directory.CreateDirectory(OUT_DIR);
if (File.Exists(OUT_APKG)) File.Delete(OUT_APKG);
using (var fs = new FileStream(OUT_APKG, FileMode.Create, FileAccess.Write))
using (var zip = new ZipArchive(fs, ZipArchiveMode.Create))
{
    var e = zip.CreateEntry("collection.anki2");
    using (var es = e.Open()) es.Write(dbBytes, 0, dbBytes.Length);

    var mediaJson = new JsonObject();
    for (int i = 0; i < mediaFiles.Count; i++)
        mediaJson[i.ToString(CultureInfo.InvariantCulture)] = Path.GetFileName(mediaFiles[i]);
    var me = zip.CreateEntry("media");
    using (var ms = me.Open())
    {
        var b = Encoding.UTF8.GetBytes(ToJson(mediaJson));
        ms.Write(b, 0, b.Length);
    }

    for (int i = 0; i < mediaFiles.Count; i++)
    {
        var fe = zip.CreateEntry(i.ToString(CultureInfo.InvariantCulture));
        using (var fes = fe.Open())
        {
            var b = File.ReadAllBytes(mediaFiles[i]);
            fes.Write(b, 0, b.Length);
        }
    }
}

Console.WriteLine($"wrote {OUT_APKG}");
Console.WriteLine($"notes: {records.Count}  media: {mediaFiles.Count}");
Console.WriteLine($"size: {(new FileInfo(OUT_APKG).Length / 1024.0 / 1024.0).ToString("F1", CultureInfo.InvariantCulture)} MB");
return 0;

// ---------------- helpers ----------------

static string ThisDir([CallerFilePath] string p = "") => Path.GetDirectoryName(p)!;

static string ToJson(JsonNode node) => node.ToJsonString(new JsonSerializerOptions
{
    WriteIndented = false,
    Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping
});

// genanki.guid_for 的等价实现（按 genanki 源码 util.py）：
//   hash_str = '__'.join(str(v) for v in values)
//   SHA256(hash_str.encode('utf-8')) 取前 8 字节 -> 大端整数
//   -> 用 genanki 自定义 91 字符表 BASE91_TABLE 做 base91（低位在前、最后反转）
// 注意：genanki 源码用的是 SHA256（且不做 10 位截断），并非 MD5；任务括号里的
// "MD5/Base91" 与源码不符，这里以 genanki 源码为准，保证与 Python 端 GUID 一致。
static string GuidFor(params object[] values)
{
    const string table = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!#$%&()*+,-./:;<=>?@[]^_`{|}~";
    var hashStr = string.Join("__", values.Select(v => Convert.ToString(v, CultureInfo.InvariantCulture)));
    var digest = SHA256.HashData(Encoding.UTF8.GetBytes(hashStr));
    ulong hashInt = 0;
    for (int i = 0; i < 8; i++) { hashInt <<= 8; hashInt += digest[i]; }
    var rev = new List<char>();
    while (hashInt > 0)
    {
        rev.Add(table[(int)(hashInt % (ulong)table.Length)]);
        hashInt /= (ulong)table.Length;
    }
    rev.Reverse();
    return new string(rev.ToArray());
}

static JsonObject DefaultDeckJson() => new()
{
    ["collapsed"] = false,
    ["conf"] = 1,
    ["desc"] = "",
    ["dyn"] = 0,
    ["extendNew"] = 10,
    ["extendRev"] = 50,
    ["id"] = 1,
    ["lrnToday"] = new JsonArray { 0, 0 },
    ["mod"] = 1425279151,
    ["name"] = "Default",
    ["newToday"] = new JsonArray { 0, 0 },
    ["revToday"] = new JsonArray { 0, 0 },
    ["timeToday"] = new JsonArray { 0, 0 },
    ["usn"] = 0,
};

static JsonObject DeckJson(long id, string name) => new()
{
    ["collapsed"] = false,
    ["conf"] = 1,
    ["desc"] = "",
    ["dyn"] = 0,
    ["extendNew"] = 0,
    ["extendRev"] = 50,
    ["id"] = JsonValue.Create(id),
    ["lrnToday"] = new JsonArray { 163, 2 },
    ["mod"] = 1425278051,
    ["name"] = name,
    ["newToday"] = new JsonArray { 163, 2 },
    ["revToday"] = new JsonArray { 163, 0 },
    ["timeToday"] = new JsonArray { 163, 23598 },
    ["usn"] = -1,
};

// Model.to_json(timestamp, deck_id)：字段/模板补 ord 及默认值，did 为牌组 id，mod 为时间戳。
static JsonObject BuildModel(long modelId, long did, long modTs)
{
    var qfmt = @"<div class=""q"">{{题图}}</div>";
    var afmt = @"{{FrontSide}}
<hr id=answer>
<div class=""ans"">{{答案}}</div>
<div class=""src"">{{来源}}</div>";
    var css = @"
.card {
  font-family: -apple-system, ""Segoe UI"", ""PingFang SC"", ""Microsoft YaHei"", sans-serif;
  font-size: 18px;
  text-align: center;
  color: #1b1b1b;
  background: #ffffff;
  padding: 14px 10px;
  line-height: 1.6;
}
/* Question art is black-on-white line drawing; never invert it, the puzzle
   semantics (filled vs empty faces, colours) would be destroyed. */
.q img { max-width: 100%; height: auto; background: #ffffff; border-radius: 2px; }
hr#answer { border: 0; border-top: 1px solid #dcdcdc; margin: 14px auto; width: 60%; }
.ans { font-size: 42px; font-weight: 700; color: #c0392b; letter-spacing: 6px; line-height: 1.2; }
.src { font-size: 12px; color: #9a9a9a; margin-top: 8px; }
.nightMode.card, .night_mode.card { background: #1f1f1f; color: #e6e6e6; }
.nightMode hr#answer, .night_mode hr#answer { border-top-color: #4a4a4a; }
.nightMode .ans, .night_mode .ans { color: #ff7b6b; }
.nightMode .src, .night_mode .src { color: #808080; }
";
    var latexPre = "\\documentclass[12pt]{article}\n\\special{papersize=3in,5in}\n\\usepackage[utf8]{inputenc}\n\\usepackage{amssymb,amsmath}\n\\pagestyle{empty}\n\\setlength{\\parindent}{0in}\n\\begin{document}\n";
    const string latexPost = "\\end{document}";

    var tmpl = new JsonObject
    {
        ["name"] = "看图选答案",
        ["qfmt"] = qfmt,
        ["afmt"] = afmt,
        ["ord"] = 0,
        ["bafmt"] = "",
        ["bqfmt"] = "",
        ["bfont"] = "",
        ["bsize"] = 0,
        ["did"] = null,
    };
    JsonObject Field(string name, int ord) => new()
    {
        ["name"] = name,
        ["ord"] = ord,
        ["font"] = "Liberation Sans",
        ["media"] = new JsonArray(),
        ["rtl"] = false,
        ["size"] = 20,
        ["sticky"] = false,
    };
    // req = [[0, "all", [0]]]：唯一模板 qfmt 只引用 {{题图}}。
    return new JsonObject
    {
        ["css"] = css,
        ["did"] = JsonValue.Create(did),
        ["flds"] = new JsonArray { Field("题图", 0), Field("答案", 1), Field("来源", 2) },
        ["id"] = modelId.ToString(CultureInfo.InvariantCulture),
        ["latexPost"] = latexPost,
        ["latexPre"] = latexPre,
        ["latexsvg"] = false,
        ["mod"] = JsonValue.Create(modTs),
        ["name"] = "图推700题",
        ["req"] = new JsonArray { new JsonArray { 0, "all", new JsonArray { 0 } } },
        ["sortf"] = 0,
        ["tags"] = new JsonArray(),
        ["tmpls"] = new JsonArray { tmpl },
        ["type"] = 0,
        ["usn"] = -1,
        ["vers"] = new JsonArray(),
    };
}

static void Exec(SqliteConnection conn, string sql)
{
    using var cmd = conn.CreateCommand();
    cmd.CommandText = sql;
    cmd.ExecuteNonQuery();
}

// APKG_SCHEMA：col/notes/cards/revlog/graves 表 + 索引。
static void InitSchema(SqliteConnection conn)
{
    foreach (var sql in new[]
    {
        "CREATE TABLE col (id integer primary key, crt integer not null, mod integer not null, scm integer not null, ver integer not null, dty integer not null, usn integer not null, ls integer not null, conf text not null, models text not null, decks text not null, dconf text not null, tags text not null);",
        "CREATE TABLE notes (id integer primary key, guid text not null, mid integer not null, mod integer not null, usn integer not null, tags text not null, flds text not null, sfld integer not null, csum integer not null, flags integer not null, data text not null);",
        "CREATE TABLE cards (id integer primary key, nid integer not null, did integer not null, ord integer not null, mod integer not null, usn integer not null, type integer not null, queue integer not null, due integer not null, ivl integer not null, factor integer not null, reps integer not null, lapses integer not null, left integer not null, odue integer not null, odid integer not null, flags integer not null, data text not null);",
        "CREATE TABLE revlog (id integer primary key, cid integer not null, usn integer not null, ease integer not null, ivl integer not null, lastIvl integer not null, factor integer not null, time integer not null, type integer not null);",
        "CREATE TABLE graves (usn integer not null, oid integer not null, type integer not null);",
        "CREATE INDEX ix_notes_usn on notes (usn);",
        "CREATE INDEX ix_cards_usn on cards (usn);",
        "CREATE INDEX ix_revlog_usn on revlog (usn);",
        "CREATE INDEX ix_cards_nid on cards (nid);",
        "CREATE INDEX ix_cards_sched on cards (did, queue, due);",
        "CREATE INDEX ix_revlog_cid on revlog (cid);",
        "CREATE INDEX ix_notes_csum on notes (csum);",
    }) Exec(conn, sql);
}

// APKG_COL 的单行 col；conf/dconf/tags 为固定值，models/decks 由调用方给出。
static void InsertCol(SqliteConnection conn, string models, string decks)
{
    const string conf = """{"activeDecks":[1],"addToCur":true,"collapseTime":1200,"curDeck":1,"curModel":"1425279151691","dueCounts":true,"estTimes":true,"newBury":true,"newSpread":0,"nextPos":1,"sortBackwards":false,"sortType":"noteFld","timeLim":0}""";
    const string dconf = """{"1":{"autoplay":true,"id":1,"lapse":{"delays":[10],"leechAction":0,"leechFails":8,"minInt":1,"mult":0},"maxTaken":60,"mod":0,"name":"Default","new":{"bury":true,"delays":[1,10],"initialFactor":2500,"ints":[1,4,7],"order":1,"perDay":20,"separate":true},"replayq":true,"rev":{"bury":true,"ease4":1.3,"fuzz":0.05,"ivlFct":1,"maxIvl":36500,"minSpace":1,"perDay":100},"timer":0,"usn":0}}""";
    using var cmd = conn.CreateCommand();
    cmd.CommandText = "INSERT INTO col VALUES(null,1411124400,1425279151694,1425279151690,11,0,0,0,$conf,$models,$decks,$dconf,'{}');";
    cmd.Parameters.AddWithValue("$conf", conf);
    cmd.Parameters.AddWithValue("$models", models);
    cmd.Parameters.AddWithValue("$decks", decks);
    cmd.Parameters.AddWithValue("$dconf", dconf);
    cmd.ExecuteNonQuery();
}

static void InsertNote(SqliteConnection conn, long id, string guid, long mid, long mod,
                       string tags, string flds, string sfld)
{
    using var cmd = conn.CreateCommand();
    cmd.CommandText = "INSERT INTO notes VALUES($id,$guid,$mid,$mod,-1,$tags,$flds,$sfld,0,0,'');";
    cmd.Parameters.AddWithValue("$id", id);
    cmd.Parameters.AddWithValue("$guid", guid);
    cmd.Parameters.AddWithValue("$mid", mid);
    cmd.Parameters.AddWithValue("$mod", mod);
    cmd.Parameters.AddWithValue("$tags", tags);
    cmd.Parameters.AddWithValue("$flds", flds);
    cmd.Parameters.AddWithValue("$sfld", sfld);
    cmd.ExecuteNonQuery();
}

static void InsertCard(SqliteConnection conn, long id, long nid, long did, long mod)
{
    using var cmd = conn.CreateCommand();
    cmd.CommandText = "INSERT INTO cards VALUES($id,$nid,$did,0,$mod,-1,0,0,0,0,0,0,0,0,0,0,0,'');";
    cmd.Parameters.AddWithValue("$id", id);
    cmd.Parameters.AddWithValue("$nid", nid);
    cmd.Parameters.AddWithValue("$did", did);
    cmd.Parameters.AddWithValue("$mod", mod);
    cmd.ExecuteNonQuery();
}

record Record(string Image, string Answer, int Set, int Qnum);
