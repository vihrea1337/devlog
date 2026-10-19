#!/usr/bin/env node
// Отправить в DevLog файлы дня (ГГГГ-ММ-ДД.md) и коммиты из локальной копии репозитория —
// для автоимпорта со своего ПК. Заодно показать уведомление о готовом отчёте.
//
// Запуск (Node 18+, без зависимостей):
//   set DEVLOG_IMPORT_TOKEN=dli_…            (PowerShell: $env:DEVLOG_IMPORT_TOKEN = "dli_…")
//   node tools/push-day-files.mjs [--dir "C:\путь\к\записям"] [--git "C:\путь\к\репозиторию"]
//                                 [--project <id> | --project-name <имя>] [--days 45]
//                                 [--repo владелец/имя] [--author <email или имя>]
//                                 [--notify] [--url https://…] [--overwrite] [--dry-run] [--hook]
//
// --dir  — папка с файлами дня (и подпапки, например «2026-10»).
// --git  — локальная копия репозитория: коммиты автора за --days дней берутся `git log`
//          (только чтение, в репозитории ничего не меняется) и уходят в DevLog так же, как
//          импорт GitHub «один репозиторий». Токен GitHub для этого НЕ нужен — даже для
//          чужого приватного репозитория. --repo по умолчанию — из адреса origin,
//          --author — `git config user.email` этой копии.
// --notify — спросить DevLog о новых отчётах, собранных автоматикой (за день, неделю,
//          месяц), и показать уведомление Windows; уже показанные запоминаются в
//          %LOCALAPPDATA%\DevLog\notified.json.
// --hook — режим хука Claude Code (PostToolUse на Write|Edit): JSON о записанном файле
//          приходит на вход, отправляется только этот файл и только если он лежит в --dir и
//          в его имени есть дата. Остальные правки хук молча пропускает. См. docs/auto-import.md.
// --log <файл> — дописывать вывод в файл (для запуска Планировщиком, где окна нет):
//          видно, что ушло и почему не вышло. Файл не растёт бесконечно — хранится ~1000 строк.
//
// Токен выпускается в вебе: «Файлы дня» → «Токен для скриптов». Он умеет только импорт —
// читать и удалять записи им нельзя. Передаётся ТОЛЬКО переменной окружения, не аргументом:
// аргументы видны в списке процессов и оседают в истории команд.
//
// Сервер сам разберётся, что новое, что дописано, а что не изменилось, — поэтому скрипт
// можно запускать хоть каждый час: повторная отправка ничего не дублирует.
//
// Код возврата: 0 — всё отправлено (файлы с ошибкой разбора тоже считаются отправленными,
// они перечислены в выводе); 1 — сервер, сеть или git не ответили, 2 — неверные аргументы.

import { readdirSync, readFileSync, statSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, basename, resolve, relative, isAbsolute } from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';

const DEFAULT_URL = 'https://vihreaschedule.duckdns.org:34444';
const BATCH = 100;
const DATE_IN_NAME = /(\d{4})-(\d{2})-(\d{2})/;

function parseArgs(argv) {
  const args = { days: 45, overwrite: false, dryRun: false, hook: false, notify: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => { if (i + 1 >= argv.length) fail(`После ${a} нужно значение`); return argv[++i]; };
    if (a === '--dir') args.dir = next();
    else if (a === '--git') args.git = next();
    else if (a === '--repo') args.repo = next();
    else if (a === '--author') args.author = next();
    else if (a === '--project') args.project = next();
    else if (a === '--project-name') args.projectName = next();
    else if (a === '--days') args.days = Number(next());
    else if (a === '--url') args.url = next();
    else if (a === '--notify') args.notify = true;
    else if (a === '--log') args.log = next();
    else if (a === '--overwrite') args.overwrite = true;
    else if (a === '--dry-run') args.dryRun = true;
    else if (a === '--hook') args.hook = true;
    else if (a === '--help' || a === '-h') { printHelp(); process.exit(0); }
    else fail(`Непонятный аргумент: ${a}`);
  }
  if (!args.dir && !args.git && !args.notify) fail('Укажите, что отправлять: --dir "C:\\папка с файлами дня" и/или --git "C:\\репозиторий"');
  if (args.hook && !args.dir) fail('Для --hook нужна --dir');
  if (!Number.isInteger(args.days) || args.days < 1) fail('--days — целое число дней, от 1');
  return args;
}

function printHelp() {
  console.log(readFileSync(new URL(import.meta.url), 'utf8').split('\n').filter(l => l.startsWith('//')).map(l => l.slice(3)).join('\n'));
}

function fail(message) {
  console.error('Ошибка: ' + message);
  process.exit(2);
}

/** Все .md с датой в имени — в папке и подпапках (папки месяцев). */
function findDayFiles(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    const st = statSync(path);
    if (st.isDirectory()) out.push(...findDayFiles(path));
    else if (/\.md$/i.test(name) && DATE_IN_NAME.test(name)) out.push(path);
  }
  return out;
}

function dateOf(path) {
  const m = basename(path).match(DATE_IN_NAME);
  return m ? `${m[1]}-${m[2]}-${m[3]}` : null;
}

async function api(url, token, path, body) {
  const res = await fetch(url + path, {
    method: body ? 'POST' : 'GET',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(`${res.status}: ${data.error || res.statusText}`);
  return data;
}

/** Файл из JSON хука, если это файл дня внутри --dir; иначе null (правка не наша — пропускаем). */
async function hookFile(dir) {
  let input = '';
  for await (const chunk of process.stdin) input += chunk;
  let file;
  try { file = JSON.parse(input)?.tool_input?.file_path; } catch { return null; }
  if (!file) return null;
  const rel = relative(resolve(dir), resolve(file));
  const inside = rel && !rel.startsWith('..') && !isAbsolute(rel);
  return inside && /\.md$/i.test(file) && DATE_IN_NAME.test(basename(file)) ? resolve(file) : null;
}

// --- git ---

/** git без оболочки (аргументы не разбираются shell'ом) и только читающие команды. */
function git(repoDir, ...gitArgs) {
  return execFileSync('git', ['-C', repoDir, ...gitArgs], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
}

/** «владелец/имя» из адреса origin: https://github.com/o/r(.git) или git@github.com:o/r.git. */
function repoFromRemote(remoteUrl) {
  const m = remoteUrl.trim().match(/github\.com[/:]([^/]+)\/([^/]+?)(?:\.git)?\/?$/i);
  return m ? `${m[1]}/${m[2]}` : null;
}

// Разделители полей и коммитов — управляющие символы, которых не бывает в сообщениях.
const FIELD = '\x1f';
const RECORD = '\x1e';

/**
 * Коммиты автора с [since] — от старых к новым, без merge. Дата — по часам автора
 * (`--date=short` у %ad берёт его собственный пояс), как у файлов дня.
 */
function parseGitLog(out) {
  return out.split(RECORD).map(r => r.replace(/^\s+/, '')).filter(Boolean).map(r => {
    const [sha, date, ...rest] = r.split(FIELD);
    return { sha: sha.trim(), date: date.trim(), message: rest.join(FIELD).trim() };
  }).filter(c => c.sha && c.message);
}

function readCommits(repoDir, author, since) {
  const out = git(repoDir, 'log', '--no-merges', '--reverse', `--author=${author}`, `--since=${since}T00:00:00`,
    '--date=short', `--format=%H${FIELD}%ad${FIELD}%B${RECORD}`);
  return parseGitLog(out).filter(c => c.date >= since);
}

// --- Уведомления Windows ---

function stateFile() {
  const base = process.env.LOCALAPPDATA || join(process.env.HOME || '.', '.local', 'share');
  return join(base, 'DevLog', 'notified.json');
}

function loadShown() {
  try { return new Set(JSON.parse(readFileSync(stateFile(), 'utf8'))); } catch { return new Set(); }
}

function saveShown(ids) {
  const f = stateFile();
  mkdirSync(join(f, '..'), { recursive: true });
  writeFileSync(f, JSON.stringify([...ids].slice(-200)));
}

const KIND = { day: 'за день', week: 'за неделю', month: 'за месяц' };

/** XML-экранирование для текста уведомления. */
const xml = s => String(s).replace(/[<>&"']/g, c => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&apos;' }[c]));

/**
 * Всплывающее уведомление Windows встроенными средствами (WinRT из Windows PowerShell),
 * без модулей. Нажатие открывает отчёт в браузере. Не Windows или не вышло — false.
 */
function toast(title, text, link) {
  if (process.platform !== 'win32') return false;
  const toastXml = `<toast activationType="protocol" launch="${xml(link)}"><visual><binding template="ToastGeneric">` +
    `<text>${xml(title)}</text><text>${xml(text)}</text></binding></visual></toast>`;
  const ps = `
[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null
$x = New-Object Windows.Data.Xml.Dom.XmlDocument
$x.LoadXml(@'
${toastXml}
'@)
$app = '{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe'
[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($app).Show([Windows.UI.Notifications.ToastNotification]::new($x))
`;
  // -EncodedCommand (UTF-16LE base64): кириллица и кавычки доезжают без проблем с экранированием.
  const r = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(ps, 'utf16le').toString('base64')], { stdio: 'ignore' });
  return r.status === 0;
}

// --- Журнал ---

/** Дублировать console.log/error в файл [path]; при выходе обрезать журнал до последних строк. */
function startLog(path) {
  const lines = [];
  const stamp = new Date().toLocaleString('sv-SE'); // «2026-10-19 22:17:23» по местному времени
  for (const name of ['log', 'error']) {
    const orig = console[name].bind(console);
    console[name] = (...a) => { lines.push(`${stamp} ${a.join(' ')}`); orig(...a); };
  }
  process.on('exit', code => {
    lines.push(`${stamp} код выхода ${code}`);
    let old = [];
    try { old = readFileSync(path, 'utf8').split('\n').filter(Boolean); } catch {}
    try {
      mkdirSync(join(path, '..'), { recursive: true });
      writeFileSync(path, [...old, ...lines].slice(-1000).join('\n') + '\n');
    } catch {}
  });
}

// --- main ---

const args = parseArgs(process.argv.slice(2));
if (args.log) startLog(args.log);
const url = (args.url || process.env.DEVLOG_URL || DEFAULT_URL).replace(/\/+$/, '');
const since = new Date(Date.now() - args.days * 86400_000).toISOString().slice(0, 10);
const project = { projectId: args.project || null, projectName: args.project ? null : (args.projectName || null) };

// 1. Что отправлять.
let paths = [];
if (args.hook) {
  const one = await hookFile(args.dir);
  if (!one) process.exit(0);
  paths = [one];
} else if (args.dir) {
  paths = findDayFiles(args.dir).filter(p => dateOf(p) >= since).sort();
  console.log(`Файлов дня с ${since}: ${paths.length}`);
}

let commitsBody = null;
if (args.git && !args.hook) {
  try {
    const repo = args.repo || repoFromRemote(git(args.git, 'remote', 'get-url', 'origin'));
    if (!repo) fail('Не понял, какой это репозиторий GitHub, — укажите --repo владелец/имя');
    const author = args.author || git(args.git, 'config', 'user.email').trim();
    if (!author) fail('Не задан автор коммитов — укажите --author <email>');
    const commits = readCommits(args.git, author, since);
    console.log(`Коммитов ${author} в ${repo} с ${since}: ${commits.length}`);
    commitsBody = { repo, commits, ...project };
  } catch (e) {
    console.error('git не ответил: ' + (e.stderr?.toString().trim() || e.message));
    process.exit(1);
  }
}

if (args.dryRun) {
  console.log('Пробный прогон — ничего не отправляю.');
  paths.forEach(p => console.log('  ' + basename(p)));
  commitsBody?.commits.forEach(c => console.log(`  ${c.date} ${c.sha.slice(0, 7)} ${c.message.split('\n').find(l => l.trim()) || ''}`));
  process.exit(0);
}

const token = process.env.DEVLOG_IMPORT_TOKEN;
if (!token) fail('Нет токена: задайте переменную окружения DEVLOG_IMPORT_TOKEN (выпускается в вебе DevLog)');

let code = 0;
try {
  // 2. Файлы дня — пачками.
  if (paths.length) {
    const totals = { created: 0, updated: 0, unchanged: 0, failed: 0 };
    for (let i = 0; i < paths.length; i += BATCH) {
      const files = paths.slice(i, i + BATCH).map(p => ({ name: basename(p), text: readFileSync(p, 'utf8') }));
      const r = await api(url, token, '/api/import/day-files', { files, overwrite: args.overwrite, ...project });
      for (const k of Object.keys(totals)) totals[k] += r[k] || 0;
      for (const f of r.files) {
        if (f.status !== 'unchanged') console.log(`  ${f.name}: ${f.status}${f.message ? ' — ' + f.message : ''}`);
      }
    }
    console.log(`Файлы дня: новых ${totals.created}, обновлено ${totals.updated}, без изменений ${totals.unchanged}` +
      (totals.failed ? `, с ошибкой ${totals.failed}` : ''));
  }
  // 3. Коммиты.
  if (commitsBody?.commits.length) {
    const r = await api(url, token, '/api/import/commits', commitsBody);
    console.log(`Коммиты: дней ${r.days}, новых записей ${r.created}, обновлено ${r.updated}, без изменений ${r.skipped}`);
  }
} catch (e) {
  console.error('Не удалось отправить: ' + e.message);
  code = 1;
}

// 4. Уведомления о готовых отчётах.
if (args.notify) {
  try {
    const shown = loadShown();
    const fresh = (await api(url, token, '/api/import/notices')).filter(n => !shown.has(n.reportId));
    for (const n of fresh.reverse()) {
      const head = `Отчёт ${KIND[n.kind] || ''} готов`.replace('  ', ' ');
      const ok = toast(head, `${n.title} — прочитайте и поправьте перед отправкой`, url + n.url);
      console.log(`${head}: ${n.title} — ${url}${n.url}${ok ? '' : ' (уведомление показать не удалось)'}`);
      shown.add(n.reportId);
    }
    if (fresh.length) saveShown(shown);
  } catch (e) {
    console.error('Не удалось спросить об отчётах: ' + e.message);
    code = 1;
  }
}
process.exit(code);
