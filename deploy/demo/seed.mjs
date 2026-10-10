// Наполнение демо-аккаунта DevLog данными из demo-data.json — через обычный публичный API,
// как это делал бы человек в приложении (никакого прямого доступа к базе).
//
// Запуск (Node 18+, без зависимостей):
//   DEVLOG_URL=https://vihreaschedule.duckdns.org:34444 \
//   DEMO_EMAIL=showcase@devlog.app DEMO_PASSWORD=... node deploy/demo/seed.mjs
//
// Пароль — только из переменной окружения: репозиторий публичный, в файлы его не пишем.
//
// Скрипт можно запускать повторно — дублей не будет:
//  - аккаунт: если email уже занят, просто входим;
//  - проекты: ищем по имени, создаём только недостающие;
//  - записи: у каждой постоянный id (UUID из хеша даты, проекта и текста), а сервер
//    идемпотентен по id — повторная отправка той же записи ничего не создаёт.
//
// После заливки записи разбирает ИИ в фоне (на бесплатном Groq — 3–4 записи в минуту).
// Отчёты собирайте, когда разбор закончится: флаг --reports ждёт окончания разбора,
// собирает отчёты и включает им публичные ссылки.

import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";

const BASE = (process.env.DEVLOG_URL ?? "https://vihreaschedule.duckdns.org:34444").replace(/\/$/, "");
const EMAIL = process.env.DEMO_EMAIL ?? "showcase@devlog.app";
const PASSWORD = process.env.DEMO_PASSWORD;
const NAME = process.env.DEMO_NAME ?? "Демо-разработчик";
const GITHUB_LOGIN = process.env.DEMO_GITHUB ?? "vihrea1337";
const WITH_REPORTS = process.argv.includes("--reports");

if (!PASSWORD) {
    console.error("Нужна переменная окружения DEMO_PASSWORD");
    process.exit(1);
}

const data = JSON.parse(readFileSync(new URL("./demo-data.json", import.meta.url), "utf8"));
let token = null;

/** Запрос к API: JSON туда и обратно, ошибка — с телом ответа (сервер пишет причину в {"error"}). */
async function api(method, path, body) {
    const res = await fetch(BASE + path, {
        method,
        headers: {
            "Content-Type": "application/json",
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await res.text();
    if (!res.ok) {
        const err = new Error(`${method} ${path} → ${res.status}: ${text.slice(0, 200)}`);
        err.status = res.status;
        throw err;
    }
    return text ? JSON.parse(text) : null;
}

/** Постоянный UUID из строки: одна и та же запись при любом запуске получает один и тот же id. */
function stableUuid(key) {
    const h = createHash("sha1").update("devlog-demo:" + key).digest("hex");
    // Форма UUID v5 (версия 5, вариант RFC 4122) — сервер принимает любой корректный UUID.
    return `${h.slice(0, 8)}-${h.slice(8, 12)}-5${h.slice(13, 16)}-${((parseInt(h[16], 16) & 3) | 8).toString(16)}${h.slice(17, 20)}-${h.slice(20, 32)}`;
}

async function login() {
    try {
        const r = await api("POST", "/api/auth/register", { email: EMAIL, password: PASSWORD, displayName: NAME });
        console.log(`Создан аккаунт ${EMAIL}`);
        return r.token;
    } catch (e) {
        if (e.status !== 409) throw e;
        const r = await api("POST", "/api/auth/login", { email: EMAIL, password: PASSWORD });
        console.log(`Аккаунт ${EMAIL} уже есть — вошли`);
        return r.token;
    }
}

async function ensureProjects() {
    const existing = await api("GET", "/api/projects");
    const byName = new Map(existing.map((p) => [p.name, p]));
    for (const p of data.projects) {
        let project = byName.get(p.name);
        if (!project) {
            project = await api("POST", "/api/projects", { name: p.name, color: p.color, aiEnabled: p.aiEnabled });
            console.log(`Проект «${p.name}» создан`);
        }
        if (p.githubExportRepo && project.githubExportRepo !== p.githubExportRepo) {
            project = await api("PUT", `/api/projects/${project.id}`, { githubExportRepo: p.githubExportRepo });
        }
        byName.set(p.name, project);
    }
    return byName;
}

async function seedEntries(projects) {
    let created = 0;
    for (const e of data.entries) {
        const id = stableUuid(`${e.date}|${e.project}|${e.text}`);
        const saved = await api("POST", "/api/entries", {
            id,
            occurredOn: e.date,
            rawText: e.text,
            projectId: projects.get(e.project)?.id ?? null,
            timeSpentMin: e.min ?? null,
        });
        // Запись уже была (повторный запуск), а время в demo-data.json поправили — обновляем.
        // PUT без rawText не отправляет запись на повторный разбор ИИ.
        if ((saved.timeSpentMin ?? null) !== (e.min ?? null)) {
            await api("PUT", `/api/entries/${id}`, { timeSpentMin: e.min ?? null });
        }
        created++;
    }
    console.log(`Записей отправлено: ${created} (повторные — без дублей)`);
}

/** Ждём, пока ИИ разберёт все записи (статусы queued/processing уйдут). */
async function waitForAi() {
    for (;;) {
        const entries = await api("GET", "/api/entries");
        const pending = entries.filter((e) => e.status === "queued" || e.status === "processing").length;
        const failed = entries.filter((e) => e.status === "failed").length;
        console.log(`ИИ: в очереди ${pending}, с ошибкой ${failed}, всего ${entries.length}`);
        if (pending === 0) return;
        await new Promise((r) => setTimeout(r, 30_000));
    }
}

async function makeReports(projects) {
    const plan = [
        { title: "Solmu: Android-клиент и звонки", periodStart: "2026-09-06", periodEnd: "2026-10-10", project: "Solmu" },
        { title: "DevLog: от идеи до продакшена", periodStart: "2026-07-20", periodEnd: "2026-10-10", project: "DevLog" },
        { title: "Отчёт за сентябрь 2026", periodStart: "2026-09-01", periodEnd: "2026-09-30" },
    ];
    const existing = await api("GET", "/api/reports");
    for (const r of plan) {
        if (existing.some((x) => x.title === r.title)) {
            console.log(`Отчёт «${r.title}» уже есть — пропускаю`);
            continue;
        }
        const report = await api("POST", "/api/reports", {
            title: r.title,
            periodStart: r.periodStart,
            periodEnd: r.periodEnd,
            projectId: r.project ? projects.get(r.project)?.id : null,
        });
        const share = await api("POST", `/api/reports/${report.id}/share`);
        console.log(`Отчёт «${r.title}»: ${BASE}${share.url}`);
    }
}

token = await login();
await api("PUT", "/api/me", { githubLogin: GITHUB_LOGIN });
const projects = await ensureProjects();
await seedEntries(projects);
if (WITH_REPORTS) {
    await waitForAi();
    await makeReports(projects);
} else {
    console.log("Отчёты: запустите ещё раз с флагом --reports, когда ИИ разберёт записи.");
}
