package io.github.vihrea1337.devlog

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Публичная страница отчёта `/r/{token}` — то, что работодатель открывает по ссылке.
 *
 * Раньше это был голый Markdown → HTML на белом листе. Теперь — документ:
 *  - шапка: заголовок, период, автор, проект;
 *  - плитки «Цифры периода» сверху (дни, часы, записи, проекты) и график активности по дням —
 *    числа считает код по записям периода, не модель;
 *  - разделы отчёта со своим оформлением: «Кратко» — врезка, хронология — линия времени;
 *  - превью ссылки в мессенджерах (Open Graph), печать в PDF, тёмная тема.
 *
 * Таблица «Цифры периода» из Markdown на странице не повторяется (её заменяют плитки),
 * но остаётся в самом тексте отчёта — для скачанного .md и экспорта в GitHub.
 */
object PublicReportPage {

    /** Что известно о периоде помимо текста отчёта: автор и записи, по которым он собран. */
    data class Context(
        val authorName: String?,
        val projectName: String?,
        val entries: List<EntryDto>,
        val projectCount: Int,
    )

    fun render(report: ReportDto, ctx: Context, origin: String, autoPrint: Boolean): String {
        val esc = Markdown::escapeHtml
        val from = LocalDate.parse(report.periodStart)
        val to = LocalDate.parse(report.periodEnd)
        val sections = splitSections(report.contentMd)
        val summary = sections.firstOrNull { it.title.equals("Кратко", ignoreCase = true) }?.body
            ?.let(::plainText)?.take(280)
            ?: "Отчёт о работе за ${ReportGenerator.humanDate(from)} — ${ReportGenerator.humanDate(to)}"

        val days = ctx.entries.map { it.occurredOn }.distinct().size
        val minutes = ctx.entries.mapNotNull { it.timeSpentMin }.sum()
        val tiles = buildList {
            add(days.toString() to plural(days, "рабочий день", "рабочих дня", "рабочих дней"))
            if (minutes > 0) add(hoursLabel(minutes) to "учтено времени")
            add(ctx.entries.size.toString() to plural(ctx.entries.size, "запись", "записи", "записей"))
            if (ctx.projectCount > 0) add(ctx.projectCount.toString() to plural(ctx.projectCount, "проект", "проекта", "проектов"))
        }

        val body = sections.joinToString("\n") { sec ->
            // Базовую таблицу заменяют плитки. Но у «Отчёта работодателю» в ней ещё и цифры,
            // сложенные из записей дня (тесты, коммиты, сборки), — их плитки не покажут, оставляем.
            if (sec.title.equals("Цифры периода", ignoreCase = true) && "Сложено кодом" !in sec.body) return@joinToString ""
            val cls = when (sec.title.lowercase()) {
                "кратко" -> "sec-lead"
                "хронология" -> "sec-timeline"
                "что дальше" -> "sec-next"
                else -> "sec"
            }
            val heading = if (sec.title.isBlank()) "" else "<h2>${esc(sec.title)}</h2>"
            var html = Markdown.toHtml(sec.body)
            // В хронологии дата стоит отдельной строкой над текстом — тире между ними лишнее.
            if (cls == "sec-timeline") html = html.replace("</strong> — ", "</strong>")
            """<section class="$cls">$heading$html</section>"""
        }

        val meta = listOfNotNull(
            "${ReportGenerator.humanDate(from)} — ${ReportGenerator.humanDate(to)}",
            ctx.authorName?.let { "автор: ${esc(it)}" },
            ctx.projectName?.let { "проект «${esc(it)}»" },
        ).joinToString(" · ")

        return """
<!doctype html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<!-- Отчёт открыт по секретной ссылке, но попасть в поисковую выдачу он не должен. -->
<meta name="robots" content="noindex, nofollow">
<title>${esc(report.title)} — DevLog</title>
<meta name="description" content="${esc(summary)}">
<!-- Превью ссылки в Telegram, Slack и т.п.: заголовок, описание, картинка. -->
<meta property="og:type" content="article">
<meta property="og:site_name" content="DevLog">
<meta property="og:title" content="${esc(report.title)}">
<meta property="og:description" content="${esc(summary)}">
<meta property="og:image" content="$origin/og-devlog.png">
<meta name="twitter:card" content="summary_large_image">
<meta name="theme-color" content="#3b6ef5">
<style>
  :root { --bg:#f4f6fb; --paper:#fff; --ink:#171c28; --ink2:#3a4252; --muted:#6b7386; --line:#e2e6ef; --accent:#3b6ef5; --accent-bg:#ebf0fe; --chip:#f0f2f8; --hm0:#e7eaf2; }
  @media (prefers-color-scheme: dark) { :root { --bg:#0f1218; --paper:#171b23; --ink:#e8ebf2; --ink2:#c4cad6; --muted:#98a1b3; --line:#282e3a; --accent:#7c9dff; --accent-bg:#1e2945; --chip:#1e232d; --hm0:#222834; color-scheme:dark; } }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:var(--ink); font:16px/1.65 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif; }
  .cover { color:#fff; background: radial-gradient(120% 90% at 100% 0%, rgba(255,255,255,.16) 0%, transparent 55%), linear-gradient(135deg,#1e3a8a 0%,#3b6ef5 58%,#6f97ff 100%); position:relative; overflow:hidden; }
  .cover::before { content:""; position:absolute; right:-50px; bottom:-50px; width:320px; height:200px; transform:rotate(-8deg); background-image:radial-gradient(rgba(255,255,255,.17) 3px,transparent 3.5px); background-size:16px 16px; }
  .cover-in { max-width:860px; margin:0 auto; padding:28px 20px 34px; position:relative; display:grid; gap:14px; }
  .brand { display:flex; align-items:center; gap:10px; font-weight:700; font-size:15px; opacity:.95; }
  .brand a { color:inherit; text-decoration:none; display:flex; align-items:center; gap:10px; }
  .logo { width:30px; height:30px; border-radius:8px; background:rgba(255,255,255,.95); color:#1e3a8a; display:grid; place-items:center; font-weight:800; font-size:13px; }
  .brand .kind { font-weight:500; opacity:.8; }
  h1 { font-size:clamp(26px,4.4vw,38px); line-height:1.15; margin:6px 0 0; letter-spacing:-.02em; text-wrap:balance; }
  .meta { color:rgba(255,255,255,.88); font-size:15px; }
  .tools { display:flex; gap:8px; flex-wrap:wrap; margin-top:4px; }
  .tools button { font:inherit; font-size:14px; font-weight:600; border:1px solid rgba(255,255,255,.35); background:rgba(255,255,255,.12); color:#fff; padding:7px 14px; border-radius:9px; cursor:pointer; }
  .tools button:hover { background:rgba(255,255,255,.22); }
  main { max-width:860px; margin:-18px auto 0; padding:0 20px 40px; position:relative; display:grid; gap:18px; }
  .card { background:var(--paper); border:1px solid var(--line); border-radius:16px; padding:22px 24px; box-shadow:0 1px 2px rgba(20,30,60,.05),0 8px 24px rgba(20,30,60,.06); }
  .tiles { display:grid; grid-template-columns:repeat(auto-fit,minmax(150px,1fr)); gap:10px; }
  .tile { background:var(--chip); border-radius:12px; padding:12px 14px; }
  .tile b { display:block; font-size:28px; line-height:1.15; font-variant-numeric:tabular-nums; letter-spacing:-.01em; }
  .tile span { font-size:13px; color:var(--muted); }
  .chart-title { font-size:12px; letter-spacing:.06em; text-transform:uppercase; color:var(--muted); font-weight:700; margin:18px 0 8px; display:flex; justify-content:space-between; }
  .bars { display:flex; align-items:flex-end; gap:2px; height:72px; }
  .bars i { flex:1; min-width:2px; border-radius:3px 3px 1px 1px; background:var(--accent); opacity:.9; }
  .bars i.z { background:var(--hm0); opacity:1; }
  .axis { display:flex; justify-content:space-between; font-size:12px; color:var(--muted); margin-top:4px; }
  article.card { padding:8px 28px 24px; }
  article section { padding-top:18px; }
  article section + section { border-top:1px solid var(--line); margin-top:18px; }
  h2 { font-size:13px; letter-spacing:.07em; text-transform:uppercase; color:var(--accent); margin:0 0 10px; }
  article p { margin:0 0 8px; color:var(--ink2); }
  article ul, article ol { margin:0; padding-left:20px; display:grid; gap:8px; color:var(--ink2); }
  article li strong, article p strong { color:var(--ink); }
  .sec-lead p { font-size:18px; line-height:1.6; color:var(--ink); }
  .sec-next { background:var(--accent-bg); border-radius:12px; padding:16px 18px !important; border-top:0 !important; }
  .sec-timeline ul { list-style:none; padding:0; gap:0; position:relative; }
  .sec-timeline li { position:relative; padding:0 0 14px 22px; }
  .sec-timeline li::before { content:""; position:absolute; left:4px; top:9px; width:8px; height:8px; border-radius:50%; background:var(--accent); }
  .sec-timeline li::after { content:""; position:absolute; left:7.5px; top:20px; bottom:0; width:1px; background:var(--line); }
  .sec-timeline li:last-child::after { display:none; }
  .sec-timeline li strong { display:block; color:var(--ink); font-size:14px; }
  code { font-family:ui-monospace,"Cascadia Mono",Consolas,monospace; font-size:.86em; background:var(--chip); padding:1px 6px; border-radius:5px; }
  table { border-collapse:collapse; width:100%; margin:8px 0; font-size:14px; display:block; overflow-x:auto; }
  th, td { border:1px solid var(--line); padding:6px 10px; text-align:left; }
  footer { max-width:860px; margin:0 auto; padding:0 20px 36px; color:var(--muted); font-size:13px; display:flex; justify-content:space-between; gap:12px; flex-wrap:wrap; }
  footer a { color:var(--muted); }
  .toast { position:fixed; left:50%; bottom:24px; transform:translateX(-50%); background:var(--ink); color:var(--bg); padding:9px 16px; border-radius:10px; font-size:14px; display:none; }
  @media (max-width:600px) { .chart-title span + span { display:none; } article.card { padding:4px 18px 18px; } .card { padding:18px; } .tile b { font-size:24px; } .sec-lead p { font-size:16.5px; } }
  /* Печать в PDF: без фона и теней, обложка — тонкой шапкой, кнопки скрыты. */
  @media print {
    :root { --bg:#fff; --paper:#fff; --ink:#111; --ink2:#222; --muted:#555; --line:#ddd; --chip:#f3f4f7; --accent:#3b6ef5; --accent-bg:#ebf0fe; --hm0:#e7eaf2; color-scheme:light; }
    body { font-size:12.5pt; }
    .cover { background:#fff !important; color:#111; border-bottom:2px solid #3b6ef5; }
    .cover::before, .tools, .toast { display:none !important; }
    .meta { color:#444; } .logo { background:#3b6ef5; color:#fff; }
    main { margin-top:12px; } .card { box-shadow:none; border:0; padding:0; }
    article section { break-inside:avoid-page; }
    .bars i { print-color-adjust:exact; -webkit-print-color-adjust:exact; }
  }
</style></head><body>
<header class="cover"><div class="cover-in">
  <div class="brand"><a href="/install.html"><span class="logo">DL</span>DevLog</a><span class="kind">· отчёт о работе</span></div>
  <h1>${esc(report.title)}</h1>
  <div class="meta">$meta</div>
  <div class="tools"><button id="pdf">Скачать PDF</button><button id="copy">Скопировать ссылку</button></div>
</div></header>
<main>
  <div class="card">
    <div class="tiles">${tiles.joinToString("") { (v, l) -> "<div class=\"tile\"><b>${esc(v)}</b><span>${esc(l)}</span></div>" }}</div>
    ${activityChart(ctx.entries, from, to)}
  </div>
  <article class="card">$body</article>
</main>
<footer><span>Собрано в DevLog · ${ReportGenerator.humanDate(LocalDate.parse(report.createdAt.take(10)))}. Текст по темам пишет ИИ, цифры и хронологию считает код.</span><a href="/install.html">Что такое DevLog</a></footer>
<div class="toast" id="toast">Ссылка скопирована</div>
<script>
  document.getElementById('pdf').onclick = function () { window.print(); };
  document.getElementById('copy').onclick = function () {
    var t = document.getElementById('toast');
    (navigator.clipboard ? navigator.clipboard.writeText(location.href) : Promise.reject()).then(function () {
      t.textContent = 'Ссылка скопирована';
    }, function () { t.textContent = location.href; }).finally(function () {
      t.style.display = 'block'; setTimeout(function () { t.style.display = 'none'; }, 2500);
    });
  };
  ${if (autoPrint) "window.addEventListener('load', function () { setTimeout(function () { window.print(); }, 300); });" else ""}
</script>
</body></html>
""".trimIndent()
    }

    /** Раздел Markdown: заголовок второго уровня и текст под ним (до следующего «## »). */
    data class Section(val title: String, val body: String)

    fun splitSections(md: String): List<Section> {
        val out = mutableListOf<Section>()
        var title = ""
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotBlank() || title.isNotEmpty()) out += Section(title, buf.toString().trim())
            buf.clear()
        }
        for (line in md.lines()) {
            when {
                line.startsWith("# ") -> Unit // заголовок отчёта — уже в шапке страницы
                line.startsWith("_Период:") -> Unit // период — тоже в шапке
                line.startsWith("## ") -> { flush(); title = line.removePrefix("## ").trim() }
                else -> buf.append(line).append('\n')
            }
        }
        flush()
        return out.filter { it.body.isNotBlank() }
    }

    /**
     * График активности: столбик на день (до 62 дней) или на неделю — высота по часам,
     * а если время не указывали — по числу записей.
     */
    private fun activityChart(entries: List<EntryDto>, from: LocalDate, to: LocalDate): String {
        if (entries.isEmpty()) return ""
        val totalDays = ChronoUnit.DAYS.between(from, to).toInt() + 1
        val byDay = entries.groupBy { LocalDate.parse(it.occurredOn) }
        fun value(es: List<EntryDto>?) = es.orEmpty().let { list -> list.mapNotNull { it.timeSpentMin }.sum().takeIf { it > 0 } ?: (list.size * 60) }
        val weekly = totalDays > 62
        val buckets = if (!weekly) {
            (0 until totalDays).map { value(byDay[from.plusDays(it.toLong())]) }
        } else {
            (0 until (totalDays + 6) / 7).map { w ->
                (0 until 7).sumOf { d -> value(byDay[from.plusDays((w * 7 + d).toLong())]) }
            }
        }
        val max = (buckets.maxOrNull() ?: 0).coerceAtLeast(1)
        val bars = buckets.joinToString("") { v ->
            if (v == 0) "<i class=\"z\" style=\"height:3px\"></i>" else "<i style=\"height:${(6 + 66.0 * v / max).toInt()}px\"></i>"
        }
        return """
    <div class="chart-title"><span>Активность ${if (weekly) "по неделям" else "по дням"}</span><span>высота — учтённые часы</span></div>
    <div class="bars">$bars</div>
    <div class="axis"><span>${ReportGenerator.humanDate(from)}</span><span>${ReportGenerator.humanDate(to)}</span></div>"""
    }

    /** Markdown → короткий текст для описания ссылки: без разметки и переносов. */
    private fun plainText(md: String): String =
        md.replace(Regex("[*_`#>]"), "").replace(Regex("\\s+"), " ").trim()

    private fun hoursLabel(minutes: Int): String {
        val h = minutes / 60.0
        return if (h >= 10) "${h.toInt()} ч" else String.format("%.1f ч", h).replace('.', ',')
    }

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val t = n % 100
        val o = n % 10
        return when {
            t in 11..14 -> many
            o == 1 -> one
            o in 2..4 -> few
            else -> many
        }
    }
}
