#!/usr/bin/env node
// Отправить файлы дня (ГГГГ-ММ-ДД.md) в DevLog — для автоимпорта со своего ПК.
//
// Запуск (Node 18+, без зависимостей):
//   set DEVLOG_IMPORT_TOKEN=dli_…            (PowerShell: $env:DEVLOG_IMPORT_TOKEN = "dli_…")
//   node tools/push-day-files.mjs --dir "C:\путь\к\записям" [--project <id проекта>] [--days 45]
//                                 [--url https://…] [--overwrite] [--dry-run] [--hook]
//
// --hook — режим хука Claude Code (PostToolUse на Write|Edit): JSON о записанном файле
// приходит на вход, отправляется только этот файл и только если он лежит в --dir и в его
// имени есть дата. Остальные правки хук молча пропускает. Пример настройки — docs/auto-import.md.
//
// Токен выпускается в вебе: «Файлы дня» → «Токен для скриптов». Он умеет только импорт —
// читать и удалять записи им нельзя. Передаётся ТОЛЬКО переменной окружения, не аргументом:
// аргументы видны в списке процессов и оседают в истории команд.
//
// Что делает: ищет в папке (и подпапках, например «2026-10») файлы .md с датой в имени,
// берёт последние --days дней и отправляет пачками по 100. Сервер сам разберётся, что
// новое, что дописано, а что не изменилось, — поэтому скрипт можно запускать хоть каждый
// час: повторная отправка того же файла ничего не дублирует.
//
// Код возврата: 0 — всё отправлено (файлы с ошибкой разбора тоже считаются отправленными,
// они перечислены в выводе); 1 — сервер или сеть не ответили, 2 — неверные аргументы.

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, basename, resolve, relative, isAbsolute } from 'node:path';

const DEFAULT_URL = 'https://vihreaschedule.duckdns.org:34444';
const BATCH = 100;
const DATE_IN_NAME = /(\d{4})-(\d{2})-(\d{2})/;

function parseArgs(argv) {
  const args = { days: 45, overwrite: false, dryRun: false, hook: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => { if (i + 1 >= argv.length) fail(`После ${a} нужно значение`); return argv[++i]; };
    if (a === '--dir') args.dir = next();
    else if (a === '--project') args.project = next();
    else if (a === '--days') args.days = Number(next());
    else if (a === '--url') args.url = next();
    else if (a === '--overwrite') args.overwrite = true;
    else if (a === '--dry-run') args.dryRun = true;
    else if (a === '--hook') args.hook = true;
    else if (a === '--help' || a === '-h') { printHelp(); process.exit(0); }
    else fail(`Непонятный аргумент: ${a}`);
  }
  if (!args.dir) fail('Укажите папку с файлами: --dir "C:\\путь"');
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

async function send(url, token, files, projectId, overwrite) {
  const res = await fetch(url + '/api/import/day-files', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: JSON.stringify({ files, projectId: projectId || null, overwrite }),
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

const args = parseArgs(process.argv.slice(2));
const url = (args.url || process.env.DEVLOG_URL || DEFAULT_URL).replace(/\/+$/, '');
const since = new Date(Date.now() - args.days * 86400_000).toISOString().slice(0, 10);

let paths;
if (args.hook) {
  const one = await hookFile(args.dir);
  if (!one) process.exit(0);
  paths = [one];
} else {
  paths = findDayFiles(args.dir).filter(p => dateOf(p) >= since).sort();
}
if (!paths.length) {
  console.log(`Файлов дня за последние ${args.days} дн. в ${args.dir} нет — отправлять нечего.`);
  process.exit(0);
}
console.log(`Файлов дня с ${since}: ${paths.length}` + (args.dryRun ? ' (пробный прогон — ничего не отправляю)' : ` → ${url}`));
if (args.dryRun) {
  paths.forEach(p => console.log('  ' + basename(p)));
  process.exit(0);
}

const token = process.env.DEVLOG_IMPORT_TOKEN;
if (!token) fail('Нет токена: задайте переменную окружения DEVLOG_IMPORT_TOKEN (выпускается в вебе DevLog)');

const totals = { created: 0, updated: 0, unchanged: 0, failed: 0 };
try {
  for (let i = 0; i < paths.length; i += BATCH) {
    const files = paths.slice(i, i + BATCH).map(p => ({ name: basename(p), text: readFileSync(p, 'utf8') }));
    const r = await send(url, token, files, args.project, args.overwrite);
    for (const k of Object.keys(totals)) totals[k] += r[k] || 0;
    for (const f of r.files) {
      if (f.status !== 'unchanged') console.log(`  ${f.name}: ${f.status}${f.message ? ' — ' + f.message : ''}`);
    }
  }
} catch (e) {
  console.error('Не удалось отправить: ' + e.message);
  process.exit(1);
}
console.log(`Готово: новых ${totals.created}, обновлено ${totals.updated}, без изменений ${totals.unchanged}` +
  (totals.failed ? `, с ошибкой ${totals.failed}` : ''));
