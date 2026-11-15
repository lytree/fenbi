#!/usr/bin/env dotnet
#:property PublishAot=false

// 把「政治考点卡」与「政治理论思维导图」两份资产合并成一份带标签的统一库。
// =================================================================
// 为什么要合并：两份内容同属政治理论，却各有一套「分组」（卡片 29 个分类、
// 导图 10 个板块 36 个主题），分组名多半是「第一、二节」「重要文件1」这种
// 编号切片，既不能当导航也解释不了内容。合并后不再有分组层级，只留：
//
//   · items[]  —— 一条一屏的背诵条目（card = 填空卡 / line = 导图原文行）
//   · tags[]   —— 扁平标签词表（内容 18 个 + 来源 3 个），替代原来的分组
//   · context  —— 导图条目记着它出自哪个主题、树里什么位置，只做面包屑，不参与筛选
//
// 顺手做的规整（每条都可回查报告）：
//   R1 去重：同类型下正文一致的条目合并，标签取并集
//   R2 剥离连线题伪节点：导图里「1.是……」这种其实是连线题答案，被 OCR 连成了子节点
//   R3 断句：无子节点、超 70 字且含「；」的长句按分号拆开（一条一屏读得完）
//   R4 打标签：原分组名做主标签，正文关键词补充（最多补 2 个）
//
// 用法：
//     dotnet run tools/build_library.cs
//     dotnet run tools/build_library.cs -- --out app/src/main/assets/recite_library.json
//
// 产物：
//     app/src/main/assets/recite_library.json   统一库
//     docs/政治库-合并与标签报告.md              条目变化、标签分布、待人工确认的碎片根

using System.Runtime.CompilerServices;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

var repo = RepoRoot();
string Opt(string name, string fallback)
{
    var i = Array.IndexOf(args, name);
    return i >= 0 && i + 1 < args.Length ? args[i + 1] : fallback;
}

var politicsPath = Path.GetFullPath(Opt("--politics", Path.Combine(repo, "app/src/main/assets/recite_politics.json")));
var mindmapPath = Path.GetFullPath(Opt("--mindmap", Path.Combine(repo, "app/src/main/assets/recite_mindmap.json")));
var outPath = Path.GetFullPath(Opt("--out", Path.Combine(repo, "app/src/main/assets/recite_library.json")));
var reportPath = Path.GetFullPath(Opt("--report", Path.Combine(repo, "docs/政治库-合并与标签报告.md")));

// ---------------------------------------------------------------- 标签词表

// 内容标签：合并后唯一的浏览维度。数量刻意压到 18 个——再多就等于没标签。
var tagDefs = new (string Id, string Name)[]
{
    ("xinsixiang", "新思想"),
    ("dangjian", "党的建设"),
    ("jingji", "经济"),
    ("jinrong", "金融"),
    ("gaige", "改革开放"),
    ("fazhi", "法治"),
    ("minzhu", "民主政治"),
    ("wenhua", "文化"),
    ("jiaoyu", "教育科技"),
    ("minsheng", "民生"),
    ("sannong", "三农"),
    ("shengtai", "生态文明"),
    ("anquan", "安全国防"),
    ("waijiao", "外交"),
    ("dangshi", "党史"),
    ("guihua", "规划与会议"),
    ("jichu", "基础理论"),
    ("minzu", "民族宗教"),
};

// 来源标签：只用来回答「这条出自哪本书」，与内容标签分开展示。
var sourceTags = new (string Id, string Name)[]
{
    ("chaoge", "超格手册"),
    ("jingbian", "精编考点"),
    ("daotu", "思维导图"),
};

// 原分组 → 内容标签。分组本身不再出现在 UI 里，只在这一步换算。
var catTags = new Dictionary<string, string[]>
{
    ["cg01"] = ["xinsixiang"],
    ["cg02"] = ["xinsixiang"],
    ["cg03"] = ["xinsixiang"],
    ["cg04"] = ["xinsixiang"],
    ["cg05"] = ["xinsixiang"],
    ["cg06"] = ["xinsixiang"],
    ["cg07"] = ["xinsixiang"],
    ["cg08"] = ["xinsixiang"],
    ["cg09"] = ["xinsixiang"],
    // 「谋划十五五」「系列讲话」「重要文件」这几个分类是来源切片，不是主题：
    // 同一批卡片里既有养老保险也有外交宗旨，硬给一个标签只会把内容搅浑。
    // 这几类一律交给正文关键词定主标签。
    ["cg10"] = [],
    ["cg11"] = [],
    ["cg12"] = [],
    ["cg13"] = [],
    ["cg14"] = [],
    ["cg15"] = [],
    ["cg16"] = [],   // 重要文件：同上
    ["cg17"] = [],
    ["cg18"] = [],
    ["cg19"] = [],
    ["cg20"] = [],
    ["sz4"] = ["guihua"],
    ["lh"] = ["guihua", "jingji"],
    ["jd"] = ["dangshi"],
    ["xsx"] = ["xinsixiang"],
    ["dj"] = ["dangjian"],
    ["my"] = ["jichu"],
    ["mzt"] = ["jichu"],
    ["sg"] = ["jichu", "dangshi"],
    ["sx"] = ["jichu", "fazhi"],
};

var sectionTags = new Dictionary<string, string[]>
{
    ["s01"] = ["dangjian"],
    ["s02"] = ["xinsixiang"],
    ["s03"] = ["jingji"],
    ["s04"] = ["minzhu"],
    ["s05"] = ["jiaoyu"],
    ["s06"] = ["minsheng"],
    ["s07"] = ["anquan"],
    ["s08"] = ["shengtai"],
    ["s09"] = ["waijiao"],
    ["s10"] = ["dangshi"],
};

// 主题级映射：板块标签太粗（「经济高质量发展与金融」一个板块里既有金融、又有改革、
// 还有实体经济），落到主题这一层才能一次给准。板块标签只在主题没登记时兜底。
var topicTags = new Dictionary<string, string[]>
{
    ["mm02"] = ["dangjian"], ["mm03"] = ["dangjian"], ["mm04"] = ["dangjian"],
    ["mm05"] = ["dangjian"], ["mm06"] = ["dangjian"], ["mm07"] = ["dangjian"],
    ["mm08"] = ["dangjian"],
    ["mm09"] = ["xinsixiang"], ["mm10"] = ["xinsixiang"], ["mm11"] = ["xinsixiang"],
    ["mm12"] = ["gaige"], ["mm13"] = ["jingji"], ["mm14"] = ["jinrong"],
    ["mm15"] = ["jinrong"], ["mm16"] = ["jingji"], ["mm17"] = ["jingji"],
    ["mm18"] = ["minzhu"], ["mm19"] = ["fazhi"],
    ["mm20"] = ["jiaoyu"], ["mm21"] = ["jiaoyu"], ["mm22"] = ["jiaoyu"],
    ["mm23"] = ["jiaoyu"], ["mm24"] = ["wenhua"], ["mm25"] = ["minzu"],
    ["mm26"] = ["minsheng"], ["mm27"] = ["minsheng"], ["mm28"] = ["sannong"],
    ["mm29"] = ["sannong"], ["mm30"] = ["minsheng"],
    ["mm31"] = ["anquan"], ["mm32"] = ["anquan"], ["mm33"] = ["anquan"],
    ["mm34"] = ["shengtai"], ["mm35"] = ["waijiao"],
    ["mm36"] = ["dangshi"], ["mm37"] = ["dangshi"],
};

// 关键词兜底：分组名给不准时（系列讲话 / 重要文件 / 跨界板块）靠正文判断。
// 每个标签只放强特征词，宁缺毋滥；命中 2 个词以上才敢当补充标签。
var keywords = new Dictionary<string, string[]>
{
    ["xinsixiang"] = ["新思想", "两个结合", "两个确立", "十个明确", "十四个坚持", "十三个方面", "六个必须坚持", "新时代中国特色社会主义思想", "中国式现代化"],
    ["dangjian"] = ["从严治党", "党的建设", "自我革命", "党风廉政", "反腐败", "反腐", "党的纪律", "党性", "党组织", "党管干部", "政治建设", "思想建设", "组织建设", "作风建设", "纪律建设", "制度建设", "八项规定", "巡视", "党内法规", "党的领导", "党中央", "一把手", "理想信念", "党员干部", "党内", "治党"],
    ["jingji"] = ["高质量发展", "经济", "供给侧", "新发展格局", "扩大内需", "消费", "实体经济", "民营经济", "市场", "财政", "税收", "产业", "企业", "制造业", "服务业", "工业化", "产业链", "供应链", "新质生产力"],
    ["jinrong"] = ["金融", "银行", "保险", "证券", "资本市场", "货币", "外汇", "人民币"],
    ["gaige"] = ["改革开放", "对外开放", "自贸", "一带一路", "制度型开放", "体制机制改革", "全面深化改革", "外资", "外贸", "营商环境"],
    ["fazhi"] = ["法治", "依法", "宪法", "法律", "立法", "司法", "执法", "普法"],
    ["minzhu"] = ["全过程人民民主", "民主", "人民代表大会", "政协", "协商民主", "统一战线", "基层群众自治"],
    ["wenhua"] = ["文化", "文明", "文艺", "意识形态", "宣传思想", "核心价值观", "新闻舆论", "文化遗产", "网络内容"],
    ["jiaoyu"] = ["教育强国", "教育科技", "教育事业", "义务教育", "高等教育", "立德树人", "思政课", "科教", "科技", "创新", "人才", "人工智能", "基础研究", "科研", "科学家", "科技自立自强", "数字化"],
    ["minsheng"] = ["民生", "收入分配", "社会保障", "社保", "医疗", "健康", "养老", "人口", "住房", "就业", "共同富裕"],
    ["sannong"] = ["三农", "乡村", "农业", "农村", "农民", "粮食", "脱贫", "振兴"],
    ["shengtai"] = ["生态", "绿色", "碳达峰", "碳中和", "环境", "污染", "节能", "生物多样性", "美丽中国"],
    ["anquan"] = ["国家安全", "平安中国", "国防", "军队", "强军", "军事", "风险防范", "应急", "公共安全"],
    ["waijiao"] = ["外交", "全球治理", "金砖", "上合", "中美", "中俄", "周边外交", "人类命运共同体", "联合国", "东盟", "中欧", "对外", "单边", "多边", "大国", "发展中国家", "全球"],
    ["dangshi"] = ["党史", "建党", "百年奋斗", "革命", "长征", "延安", "历史决议", "伟大成就"],
    ["guihua"] = ["十五五", "五中全会", "四中全会", "二十届", "五年规划", "两会", "政府工作报告", "中央经济工作会议"],
    ["jichu"] = ["马克思主义", "马原", "毛中特", "史纲", "思修", "哲学", "政治经济学", "唯物", "辩证法", "科学社会主义", "毛泽东思想", "剩余价值"],
    ["minzu"] = ["民族", "宗教", "港澳", "台湾", "祖国统一", "一国两制", "新疆", "西藏", "侨务"],
};

var tagNameOf = tagDefs.Concat(sourceTags).ToDictionary(t => t.Id, t => t.Name);
var sourceKind = sourceTags.Select(t => t.Id).ToHashSet();

// ---------------------------------------------------------------- 读数据

var politics = JsonNode.Parse(File.ReadAllText(politicsPath))!;
var mindmap = JsonNode.Parse(File.ReadAllText(mindmapPath))!;

var catGroup = new Dictionary<string, string>();
foreach (var c in politics["categories"]!.AsArray())
{
    catGroup[c!["id"]!.GetValue<string>()] = c["group"]?.GetValue<string>() ?? "";
}

var items = new List<Item>();
var byText = new Dictionary<string, Item>(StringComparer.Ordinal);
var stats = new Stats();

// ---------------------------------------------------------------- 卡片

foreach (var c in politics["cards"]!.AsArray())
{
    var cat = c!["cat"]!.GetValue<string>();
    var text = Clean(c["text"]!.GetValue<string>());
    var group = catGroup.GetValueOrDefault(cat, "");
    var baseTags = catTags.GetValueOrDefault(cat, []);
    var tags = Tag(text, baseTags);
    tags.Add(SourceTagOf(group));

    var item = new Item
    {
        Id = c["id"]!.GetValue<string>(),
        Kind = "card",
        Text = text,
        Answers = c["answers"]?.AsArray().Select(a => a!.GetValue<string>()).ToList() ?? [],
        Note = c["note"]?.GetValue<string>() ?? "",
        Source = c["source"]?.GetValue<string>() ?? group,
        Tags = tags,
        Group = cat,
    };
    Add(item);
}

// ---------------------------------------------------------------- 导图

foreach (var sec in mindmap["sections"]!.AsArray())
{
    var secId = sec!["id"]!.GetValue<string>();
    var secName = sec["name"]!.GetValue<string>();
    var secFallback = sectionTags.GetValueOrDefault(secId, []);

    foreach (var topic in sec["topics"]!.AsArray())
    {
        var topicId = topic!["id"]!.GetValue<string>();
        var topicName = topic["name"]!.GetValue<string>();
        var label = topic["label"]?.GetValue<string>() ?? "";
        var baseTags = topicTags.TryGetValue(topicId, out var tt) ? tt : secFallback;

        // 连线题的「提法 ↔ 内涵」成对存着；树里出现的同名节点是 OCR 把题面连错了
        var rights = new HashSet<string>(StringComparer.Ordinal);
        var lefts = new HashSet<string>(StringComparer.Ordinal);
        if (topic["pairs"] is JsonArray pairs)
        {
            foreach (var p in pairs)
            {
                rights.Add(Key(p!["right"]!.GetValue<string>()));
                lefts.Add(Key(p["left"]!.GetValue<string>()));
            }
        }

        var ctx = new Ctx(topicId, topicName, label, secName);
        foreach (var item in Flatten(topic["nodes"]!.AsArray(), "", 0, ctx, rights, lefts, baseTags))
            Add(item);
    }
}

// ---------------------------------------------------------------- 输出

FillMissingTags();
ComputeCloze();

var tagged = items.Where(i => i.Tags.Any(t => !sourceKind.Contains(t))).Count();
var counts = new Dictionary<string, int>();
foreach (var i in items)
    foreach (var t in i.Tags.Distinct())
        counts[t] = counts.GetValueOrDefault(t) + 1;

Directory.CreateDirectory(Path.GetDirectoryName(outPath)!);
using (var fs = File.Create(outPath))
using (var w = new Utf8JsonWriter(fs, new JsonWriterOptions
{
    Indented = true,
    NewLine = "\n",
    Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
}))
{
    w.WriteStartObject();
    w.WriteNumber("version", 2);
    w.WriteString("title", "政治理论");
    w.WriteString("updated", DateTime.Now.ToString("yyyy-MM-dd"));
    w.WriteString("intro",
        $"{items.Count} 条内容合并成一份库，不再分板块和章节——用标签筛，一条一屏背。" +
        $"来源：超格手册 + 精编考点 + 思维导图。");

    w.WriteStartArray("tags");
    foreach (var t in tagDefs.Concat(sourceTags))
    {
        w.WriteStartObject();
        w.WriteString("id", t.Id);
        w.WriteString("name", t.Name);
        w.WriteString("kind", sourceKind.Contains(t.Id) ? "source" : "topic");
        w.WriteNumber("count", counts.GetValueOrDefault(t.Id));
        w.WriteEndObject();
    }
    w.WriteEndArray();

    w.WriteStartArray("items");
    foreach (var i in items)
    {
        w.WriteStartObject();
        w.WriteString("id", i.Id);
        w.WriteString("kind", i.Kind);
        w.WriteString("text", i.Text);
        if (i.Answers.Count > 0)
        {
            w.WriteStartArray("answers");
            foreach (var a in i.Answers) w.WriteStringValue(a);
            w.WriteEndArray();
        }
        if (i.Note.Length > 0) w.WriteString("note", i.Note);
        w.WriteString("source", i.Source);
        if (i.Cloze.Count > 0)
        {
            w.WriteStartArray("cloze");
            foreach (var (start, end) in i.Cloze)
            {
                w.WriteStartObject();
                w.WriteNumber("start", start);
                w.WriteNumber("end", end);
                w.WriteEndObject();
            }
            w.WriteEndArray();
        }
        w.WriteStartArray("tags");
        foreach (var t in i.Tags.Distinct()) w.WriteStringValue(t);
        w.WriteEndArray();
        if (i.Context is { } ctx)
        {
            w.WriteStartObject("context");
            w.WriteString("topic", ctx.Topic);
            w.WriteString("name", ctx.Name);
            w.WriteString("path", i.Path);
            w.WriteNumber("depth", i.Depth);
            w.WriteEndObject();
        }
        w.WriteEndObject();
    }
    w.WriteEndArray();
    w.WriteEndObject();
}

WriteReport();
Console.WriteLine($"items {items.Count}（卡片 {items.Count(i => i.Kind == "card")} / 导图 {items.Count(i => i.Kind == "line")}）");
Console.WriteLine($"有内容标签 {tagged}（{tagged * 100.0 / items.Count:F1}%），无标签 {items.Count - tagged}");
Console.WriteLine($"去重合并 {stats.Deduped} 条，剥离连线题伪节点 {stats.Stripped} 条，断句 {stats.SplitFrom} → {stats.SplitTo} 条");
foreach (var t in tagDefs.OrderByDescending(t => counts.GetValueOrDefault(t.Id)))
    Console.WriteLine($"  {t.Name,-6} {counts.GetValueOrDefault(t.Id),5}");
Console.WriteLine($"写入 {outPath}");
Console.WriteLine($"报告 {reportPath}");

// ---------------------------------------------------------------- 核心逻辑

void Add(Item item)
{
    // 去重只在同类型内做：卡片和导图正文撞了也是两种背诵方式，不合并
    var k = item.Kind + "|" + Key(item.Text);
    if (byText.TryGetValue(k, out var exist))
    {
        stats.Deduped++;
        stats.DupLog.Add((exist.Id, item.Id, item.Text));
        foreach (var t in item.Tags)
            if (!exist.Tags.Contains(t)) exist.Tags.Add(t);
        return;
    }
    byText[k] = item;
    items.Add(item);
}

IEnumerable<Item> Flatten(JsonArray nodes, string parentPath, int depth, Ctx ctx,
    HashSet<string> rights, HashSet<string> lefts, string[] baseTags)
{
    for (var i = 0; i < nodes.Count; i++)
    {
        var node = nodes[i]!;
        var raw = Clean(node["text"]!.GetValue<string>());
        var path = parentPath.Length == 0 ? i.ToString() : $"{parentPath}.{i}";
        var children = node["children"] as JsonArray;

        // R2：连线题答案被 OCR 连成了子节点——“1.是党的领导的最高原则”对上 pairs.right
        if (IsPairAnswer(raw, rights))
        {
            stats.Stripped++;
            stats.StripLog.Add((ctx.Topic, raw));
            continue;
        }

        var kids = new List<Item>();
        if (children is { Count: > 0 })
            kids.AddRange(Flatten(children, path, depth + 1, ctx, rights, lefts, baseTags));

        // 子节点全被剥离、自己又是连线题的「提法」——整个节点只是题面，删掉
        if (children is { Count: > 0 } && kids.Count == 0 && lefts.Contains(Key(raw)))
        {
            stats.Stripped++;
            stats.StripLog.Add((ctx.Topic, raw));
            continue;
        }

        foreach (var piece in SplitLong(raw, path, kids.Count > 0))
        {
            yield return new Item
            {
                Id = $"m-{ctx.Topic}-{piece.Path}",
                Kind = "line",
                Text = piece.Text,
                Source = $"{ctx.Section} / {ctx.Name}",
                Tags = [.. Tag(piece.Text, baseTags), "daotu"],
                Context = ctx,
                Path = piece.Path,
                Depth = depth,
                Group = ctx.Topic,
            };
        }

        foreach (var k in kids) yield return k;
    }
}

List<string> Tag(string text, string[] baseTags)
{
    var tags = new List<string>();
    foreach (var t in baseTags)
        if (!tags.Contains(t)) tags.Add(t);

    // 计分 = 命中了几个**不同**的特征词。一条反腐卡里只出现了「思想教育」，
    // 就算「教育」这个词被反复提到，也只算命中一个词，不该因此被判成教育科技。
    var hits = keywords
        .Select(kv => (
            Tag: kv.Key,
            Score: kv.Value.Count(w => text.Contains(w, StringComparison.Ordinal))
        ))
        .Where(h => h.Score > 0)
        .OrderByDescending(h => h.Score)
        .ToList();

    // 分组名没给出主标签（系列讲话 / 重要文件）→ 至少命中 2 个不同特征词才敢定主标签；
    // 只沾一个词的多半是「经济发展」「思想教育」这种跨领域常用词，交给邻接继承更稳。
    if (tags.Count == 0 && hits.Count > 0 && hits[0].Score >= 2) tags.Add(hits[0].Tag);

    // 补充标签：至少命中 2 个特征词才加，最多补 2 个，避免一条挂五六个标签
    var added = 0;
    foreach (var h in hits)
    {
        if (added >= 2) break;
        if (tags.Contains(h.Tag)) continue;
        if (h.Score < 2) continue;
        tags.Add(h.Tag);
        added++;
    }
    return tags;
}

string SourceTagOf(string group) => group switch
{
    "精编考点" => "jingbian",
    _ when group.StartsWith("超格", StringComparison.Ordinal) => "chaoge",
    _ => "chaoge",
};

// 挖空把特征词一起挖走了（「思想建设的首要任务是____」正文里已经没有可判的实词）。
// 但卡片在库里是同分类连续的，同一份文件/同一节课相邻条目的主题必然一致：
// 先看左右各 2 条，凑够 2 票就采用；不够再退回整组投票（组内同质率 ≥55% 才敢用）。
//
// 这里刻意**不加**「候选标签必须在组内占多数」的约束：「系列讲话」「重要文件」这些分类
// 本来就是跨领域的，组里没有主导标签，一加约束就会把「外交宗旨：____」这种
// 主题明明很清楚的卡片全推进「未归类」。
void FillMissingTags()
{
    bool HasTopic(Item i) => i.Tags.Any(t => !sourceKind.Contains(t));
    string Primary(Item i) => i.Tags.First(t => !sourceKind.Contains(t));

    for (var i = 0; i < items.Count; i++)
    {
        var it = items[i];
        if (it.Kind != "card" || HasTopic(it)) continue;

        var votes = new List<string>();
        for (var d = 1; d <= 2; d++)
        {
            if (i - d >= 0 && items[i - d].Group == it.Group && HasTopic(items[i - d]))
                votes.Add(Primary(items[i - d]));
            if (i + d < items.Count && items[i + d].Group == it.Group && HasTopic(items[i + d]))
                votes.Add(Primary(items[i + d]));
        }
        var near = votes.GroupBy(x => x).OrderByDescending(g => g.Count()).FirstOrDefault();
        if (near is { } n && n.Count() >= 2)
        {
            it.Tags.Insert(0, n.Key);
            stats.NearFilled++;
            continue;
        }

        var whole = items.Where(x => x.Group == it.Group && HasTopic(x))
            .GroupBy(Primary).OrderByDescending(g => g.Count()).FirstOrDefault();
        var total = items.Count(x => x.Group == it.Group);
        if (whole is { } w && total > 0 && w.Count() * 1.0 / total >= 0.55)
        {
            it.Tags.Insert(0, w.Key);
            stats.GroupFilled++;
        }
    }
}

// R3：一条一屏装不下的长句按分号拆开（只拆叶子，别把子节点挂错爹）
IEnumerable<(string Text, string Path)> SplitLong(string text, string path, bool hasChildren)
{
    if (hasChildren || text.Length <= 70 || !text.Contains('；'))
    {
        yield return (text, path);
        yield break;
    }
    var parts = text.Split('；', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
    if (parts.Length < 2)
    {
        yield return (text, path);
        yield break;
    }
    stats.SplitFrom++;
    stats.SplitTo += parts.Length;
    for (var i = 0; i < parts.Length; i++)
    {
        var body = i == parts.Length - 1 ? parts[i] : parts[i] + "；";
        yield return (body, $"{path}#{i}");
    }
}

// 序号码 + 正文 对得上 pairs.right，说明这条是连线题的答案
// 挖空预计算：给导图行算「遮答」用的区间，写进 item.Cloze，App 端只读不算。
// 原则只有一条 —— **宁可少挖，绝不切词**：遮罩切在词中间（「马克思主_义政_治家」）比不遮还糟。
// 卡片自带 `____` + answers，不走这里。
void ComputeCloze()
{
    var reQuote = new Regex("“([^”]{2,20})”");
    var reNum = new Regex(@"\d{4}年|\d+(?:\.\d+)?%|第[一二三四五六七八九十百]+次|二十届[一二三四五六七八九十]+中全会");
    var reClause = new Regex(@"[^，。；：、,;]+");
    var reBreak = new Regex("[，。；：,;]");

    foreach (var it in items)
    {
        if (it.Kind != "line") continue;

        var text = it.Text;
        var spans = new List<(int Start, int End)>();

        // 1 引号里的提法（「一把手」「两张皮」「灯下黑」）—— 最该背的术语
        foreach (Match m in reQuote.Matches(text))
            spans.Add((m.Groups[1].Index, m.Groups[1].Index + m.Groups[1].Length));

        // 2 数字：年份 / 百分比 / 届次
        foreach (Match m in reNum.Matches(text))
            spans.Add((m.Index, m.Index + m.Length));

        // 3 同一分句内的顿号并列（≥3 项、每项 2~10 字）：挖第 2 项往后。
        //   限定在同一分句里，避免「学习、领悟、运用……根本途径」这种跨句被整段吞掉。
        foreach (Match m in reClause.Matches(text))
        {
            var parts = m.Value.Split('、');
            if (parts.Length < 3) continue;
            if (parts.Any(p => p.Trim().Length is < 2 or > 10)) continue;
            spans.Add((m.Index + parts[0].Length + 1, m.Index + m.Length));
        }

        spans.Sort((a, b) => a.Start.CompareTo(b.Start));
        var keep = new List<(int Start, int End)>();
        foreach (var s in spans)
        {
            if (s.End - s.Start < 2) continue;
            if (keep.Count > 0 && s.Start < keep[^1].End) continue;
            keep.Add(s);
        }

        // 4 兜底：遮最后一个完整分句。分句边界天然不切词，前半句留着当提示。
        //   要求前后都 ≥5 字；单句短语（「全面从严治党关键在从严治吏」）不遮 —— 遮了没法猜。
        if (keep.Count == 0)
        {
            var breaks = reBreak.Matches(text);
            if (breaks.Count > 0)
            {
                var start = breaks[^1].Index + breaks[^1].Length;
                if (start >= 5 && text.Length - start >= 5) keep.Add((start, text.Length));
            }
        }

        if (keep.Count > 3) keep.RemoveRange(3, keep.Count - 3);
        it.Cloze = keep;
    }
}

static bool IsPairAnswer(string text, HashSet<string> rights)
{
    if (rights.Count == 0) return false;
    var body = Regex.Replace(text, @"^\s*\d+\s*[.、．]\s*", "");
    return rights.Contains(Key(body));
}

// 比较用的键：去掉所有空白，避免换行/全角空格导致同一条算两条
static string Key(string s) => Regex.Replace(s, @"[\s\u3000]+", "");

static string Clean(string s) => Regex.Replace(s.Trim(), @"[\u3000 ]*\n[\u3000 ]*", "\n");

void WriteReport()
{
    var sb = new StringBuilder();
    sb.AppendLine("# 政治库：合并与标签报告");
    sb.AppendLine();
    sb.AppendLine($"> 由 `tools/build_library.cs` 生成，{DateTime.Now:yyyy-MM-dd HH:mm}。改脚本后重跑即可，不要手改资产。");
    sb.AppendLine();
    sb.AppendLine("## 一、总量");
    sb.AppendLine();
    sb.AppendLine("| 项 | 数量 |");
    sb.AppendLine("|---|---|");
    sb.AppendLine($"| 卡片原条目 | 1204 |");
    sb.AppendLine($"| 导图原节点 | 1105 |");
    sb.AppendLine($"| 去重合并 | {stats.Deduped} |");
    sb.AppendLine($"| 剥离连线题伪节点 | {stats.Stripped} |");
    sb.AppendLine($"| 长句断句 | 输入 {stats.SplitFrom} 条 → 输出 {stats.SplitTo} 条 |");
    var clozeLines = items.Count(i => i.Kind == "line");
    var clozeHit = items.Count(i => i.Kind == "line" && i.Cloze.Count > 0);
    sb.AppendLine($"| 挖空预计算 | 导图行 {clozeLines} 条，有挖空 {clozeHit} 条（{clozeHit * 100.0 / Math.Max(1, clozeLines):F1}%），其余是单句短语不遮 |");
    sb.AppendLine($"| 邻接补标签 | {stats.NearFilled} |");
    sb.AppendLine($"| 整组投票补标签 | {stats.GroupFilled} |");
    sb.AppendLine($"| **合计条目** | **{items.Count}**（卡片 {items.Count(i => i.Kind == "card")} / 导图 {items.Count(i => i.Kind == "line")}） |");
    sb.AppendLine($"| 有内容标签 | {tagged}（{tagged * 100.0 / items.Count:F1}%） |");
    sb.AppendLine($"| 无任何内容标签 | {items.Count - tagged} |");
    sb.AppendLine();

    sb.AppendLine("## 二、标签分布");
    sb.AppendLine();
    sb.AppendLine("| 标签 | id | 条数 |");
    sb.AppendLine("|---|---|---|");
    foreach (var t in tagDefs.OrderByDescending(t => counts.GetValueOrDefault(t.Id)))
        sb.AppendLine($"| {t.Name} | `{t.Id}` | {counts.GetValueOrDefault(t.Id)} |");
    foreach (var t in sourceTags)
        sb.AppendLine($"| {t.Name}（来源） | `{t.Id}` | {counts.GetValueOrDefault(t.Id)} |");
    sb.AppendLine();

    sb.AppendLine("## 三、去重明细");
    sb.AppendLine();
    if (stats.DupLog.Count == 0) sb.AppendLine("无。");
    else
    {
        sb.AppendLine("| 保留 | 合并掉 | 正文 |");
        sb.AppendLine("|---|---|---|");
        foreach (var (keep, drop, text) in stats.DupLog.Take(40))
            sb.AppendLine($"| `{keep}` | `{drop}` | {Clip(text, 40)} |");
        if (stats.DupLog.Count > 40) sb.AppendLine($"| … | 共 {stats.DupLog.Count} 组 | |");
    }
    sb.AppendLine();

    sb.AppendLine("## 四、剥离的连线题伪节点");
    sb.AppendLine();
    sb.AppendLine("判据：节点正文去掉序号前缀后，与同主题 `pairs[].right` 完全一致——它是连线题的答案，不是子节点。");
    sb.AppendLine();
    foreach (var g in stats.StripLog.GroupBy(x => x.Topic).OrderByDescending(g => g.Count()))
    {
        sb.AppendLine($"* **{g.Key}**（{g.Count()} 条）");
        foreach (var (_, text) in g.Take(4)) sb.AppendLine($"  * {Clip(text, 60)}");
    }
    if (stats.StripLog.Count == 0) sb.AppendLine("无。");
    sb.AppendLine();

    sb.AppendLine("## 五、无标签条目（需人工）");
    sb.AppendLine();
    var untagged = items.Where(i => !i.Tags.Any(t => !sourceKind.Contains(t))).ToList();
    if (untagged.Count == 0) sb.AppendLine("无。");
    else
    {
        sb.AppendLine($"共 {untagged.Count} 条，占 {(untagged.Count * 100.0 / items.Count):F1}%。这些条目正文里没有命中任何标签特征词，先原样保留，不加猜测性标签。");
        sb.AppendLine();
        foreach (var i in untagged.Take(25)) sb.AppendLine($"* `{i.Id}` {Clip(i.Text, 70)}");
        if (untagged.Count > 25) sb.AppendLine($"* … 其余 {untagged.Count - 25} 条见资产");
    }
    sb.AppendLine();

    sb.AppendLine("## 六、疑似碎片根（需人工）");
    sb.AppendLine();
    sb.AppendLine("导图里的一级节点如果是单字，基本是 OCR 把书页上的词逐字切开（如「教 / 育 / 强国」）。脚本**不自动合并**——拼错一个词比留个碎片更糟。");
    sb.AppendLine();
    var frag = items
        .Where(i => i.Kind == "line" && i.Context != null && i.Depth == 0 && i.Text.Length <= 2)
        .ToList();
    sb.AppendLine($"共 {frag.Count} 条。");
    if (frag.Count > 0)
    {
        foreach (var g in frag.GroupBy(i => i.Context!.Name))
            sb.AppendLine($"* {g.Key}：{string.Join(" / ", g.Select(i => i.Text))}");
    }
    sb.AppendLine();

    sb.AppendLine("## 七、口径说明");
    sb.AppendLine();
    sb.AppendLine("* **分组没了**：原 `categories`（29 个）与 `sections`（10 个）不再是一层结构，只在构建时换算成标签。");
    sb.AppendLine("* **主标签**：条目 `tags[0]`。分组名能给出主标签就用分组名，给不出（系列讲话 / 重要文件）用正文关键词最强的一个。");
    sb.AppendLine("* **补充标签**：正文命中 ≥2 个特征词才加，最多 2 个。");
    sb.AppendLine("* **来源标签**：`chaoge` / `jingbian` / `daotu`，`kind=source`，与内容标签分开展示。");
    sb.AppendLine("* **context 不是分组**：只用于面包屑和定位（主题名 + 树路径 + 深度），不参与筛选。");
    sb.AppendLine("* **卡片 id 保持原样**，导图 id 为 `m-<主题>-<路径>`；进度与收藏按 id 存，重新构建只要树不变得以保留。");

    Directory.CreateDirectory(Path.GetDirectoryName(reportPath)!);
    File.WriteAllText(reportPath, sb.ToString(), new UTF8Encoding(false));
}

static string Clip(string s, int n)
{
    s = s.Replace("\n", " ");
    return s.Length <= n ? s : s[..n] + "…";
}

static string RepoRoot([CallerFilePath] string caller = "")
    => Path.GetFullPath(Path.Combine(Path.GetDirectoryName(caller)!, ".."));

// ---------------------------------------------------------------- 类型

sealed class Item
{
    public string Id = "";
    public string Kind = "card";
    public string Text = "";
    public List<string> Answers = [];
    public string Note = "";
    public string Source = "";
    public List<string> Tags = [];
    public Ctx? Context;
    public string Path = "";
    public int Depth;

    /// <summary>构建期的邻居判定用：卡片 = 原分类 id，导图 = 主题 id。产物里不写。</summary>
    public string Group = "";

    /// <summary>挖空区间（字符下标，UTF-16，和 App 端 String 下标一致）；卡片为空。</summary>
    public List<(int Start, int End)> Cloze = [];
}

sealed record Ctx(string Topic, string Name, string Label, string Section);

sealed class Stats
{
    public int Deduped;
    public int Stripped;
    public int SplitFrom;
    public int SplitTo;
    public int NearFilled;
    public int GroupFilled;
    public List<(string Keep, string Drop, string Text)> DupLog = [];
    public List<(string Topic, string Text)> StripLog = [];
}
