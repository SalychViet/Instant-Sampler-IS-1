// Local tests for worker.js: fake Telegram / Crypto Pay APIs and KV, then drive the bot with updates.
import worker from "../worker.js";
import assert from "node:assert/strict";
import { createHash, createHmac } from "node:crypto";

const ADMIN = 111, USER = 222, OTHER = 333;
const SECRET = "test_secret_1234567890";
const CP_TOKEN = "cp-token-xyz";

function makeKV() {
  const m = new Map();
  return {
    m,
    writes: 0,
    async get(k) { return m.has(k) ? m.get(k) : null; },
    async put(k, v) { this.writes++; m.set(k, String(v)); },
    async delete(k) { m.delete(k); },
    async list({ prefix = "" } = {}) { return { keys: [...m.keys()].filter((k) => k.startsWith(prefix)).map((name) => ({ name })), list_complete: true }; },
  };
}

let calls = [];
let invoices = {};
let nextInvoice = 1000;
globalThis.fetch = async (url, init) => {
  if (url.startsWith("https://api.telegram.org/file/")) return new Response(new Uint8Array([0xff, 0xd8, 0xff]));
  const isForm = init && init.body instanceof FormData;
  const body = isForm ? Object.fromEntries([...init.body.keys()].map((k) => [k, init.body.get(k)])) : init && init.body ? JSON.parse(init.body) : {};
  if (url.startsWith("https://api.telegram.org/")) {
    const method = url.split("/").pop();
    calls.push({ method, body });
    if (method === "getFile") return new Response(JSON.stringify({ ok: true, result: { file_path: "documents/x.jpg" } }));
    if (method === "getMe") return new Response(JSON.stringify({ ok: true, result: { username: "IS1_sampler_bot" } }));
    if (method === "sendPhoto" && isForm) return new Response(JSON.stringify({ ok: true, result: { photo: [{ file_id: "SMALL" }, { file_id: "BANNER_PHOTO" }] } }));
    return new Response(JSON.stringify({ ok: true, result: { message_id: 1 } }));
  }
  if (url.startsWith("https://pay.crypt.bot/api/")) {
    const method = url.split("/").pop();
    assert.equal(init.headers["Crypto-Pay-API-Token"], CP_TOKEN);
    calls.push({ method: "cp." + method, body });
    if (method === "createInvoice") {
      const inv = { invoice_id: nextInvoice++, status: "active", asset: body.asset, amount: body.amount, payload: body.payload, bot_invoice_url: "https://t.me/CryptoBot?start=x" };
      invoices[inv.invoice_id] = inv;
      return new Response(JSON.stringify({ ok: true, result: inv }));
    }
    if (method === "getInvoices") {
      return new Response(JSON.stringify({ ok: true, result: { items: [invoices[body.invoice_ids]].filter(Boolean) } }));
    }
  }
  throw new Error("unexpected fetch " + url);
};

const FUTURE = new Date(Date.now() + 30 * 864e5).toISOString();
const env = { BOT_TOKEN: "123:abc", WEBHOOK_SECRET: SECRET + "\n", PRICE_USD: "9.99", SALE_PRICE_USD: "8.99", SALE_UNTIL: FUTURE,
              PROMO_CODES: "salychmusic=7.99", STARS_PER_USD: "50", CRYPTO_PAY_TOKEN: CP_TOKEN, DB: makeKV() };

const FRESH = 950;   // the only user without a saved language
async function post(update, secret = SECRET) {
  calls = [];
  const from = (update.callback_query || update.message || update.pre_checkout_query || {}).from;
  if (from && from.id !== FRESH && from.language_code) env.DB.m.set("lang:" + from.id, /^(ru|uk|be|kk)/.test(from.language_code) ? "ru" : "en");
  const res = await worker.fetch(new Request("https://bot.example.workers.dev/telegram", {
    method: "POST", headers: { "content-type": "application/json", "x-telegram-bot-api-secret-token": secret }, body: JSON.stringify(update),
  }), env);
  return res;
}
const user = (id, lc = "ru") => ({ id, first_name: "U" + id, username: "user" + id, language_code: lc });
const msg = (from, text) => ({ message: { message_id: 1, from, chat: { id: from.id, type: "private" }, text } });
const cb = (from, data) => ({ callback_query: { id: "cb1", from, data, message: { chat: { id: from.id } } } });
const sent = (method) => calls.filter((c) => c.method === method);
const texts = () => sent("sendMessage").map((c) => c.body.text).join("\n---\n");

let passed = 0;
async function test(name, fn) { await fn(); passed++; console.log("✓", name); }

await test("rejects webhook without secret", async () => {
  const r = await post(msg(user(USER), "/start"), "wrong");
  assert.equal(r.status, 403);
  assert.equal(calls.length, 0);
});

await test("/start shows RU menu with price", async () => {
  await post(msg(user(USER), "/start"));
  const m = sent("sendMessage")[0];
  assert.match(m.body.text, /ISL-1 TRY/);
  assert.match(m.body.text, /<s>\$9\.99<\/s> <b>\$8\.99<\/b> · <b>450 ⭐<\/b>/);
  assert.match(m.body.text, /Скидка до/);
  assert.equal(m.body.reply_markup.inline_keyboard[0][0].callback_data, "try");
});

await test("/start in English for non-RU users", async () => {
  await post(msg(user(OTHER, "en"), "/start"));
  assert.match(texts(), /a sampler, sequencer/);
});

await test("free download before files are uploaded → 'not ready' + admin alert", async () => {
  await post(cb(user(USER), "try"));
  assert.match(texts(), /ещё загружаются/);
  assert.equal(sent("sendMediaGroup").length, 0);
});

await test("non-admin documents are ignored", async () => {
  await post({ message: { from: user(USER), chat: { id: USER }, document: { file_name: "ISL-1_0.4.1.apk", file_id: "EVIL" } } });
  assert.equal(await env.DB.get("file:isl_apk"), null);
});

await test("admin claim: wrong key refused, right key works once", async () => {
  await post(msg(user(ADMIN), "/claim nope"));
  assert.match(texts(), /Неверный ключ/);
  await post(msg(user(ADMIN), "/claim " + SECRET));
  assert.match(texts(), /Вы администратор/);
  await post(msg(user(OTHER), "/claim " + SECRET));
  assert.match(texts(), /уже назначен/);
  assert.equal(await env.DB.get("admin"), String(ADMIN));
});

await test("admin uploads all release files", async () => {
  const names = ["ISL-1_TRY_0.4.1.apk", "ISL-1_TRY_Install_RU.pdf", "ISL-1_TRY_Install_EN.pdf", "ISL-1_0.4.1.apk", "ISL-1_Install_RU.pdf", "ISL-1_Install_EN.pdf",
                 "ISL-1_Manual_RU.pdf", "ISL-1_Manual_EN.pdf", "ISL-1_Looper_RU.pdf", "ISL-1_Looper_EN.pdf"];
  for (const n of names) await post({ message: { from: user(ADMIN), chat: { id: ADMIN }, document: { file_name: n, file_id: "ID_" + n } } });
  assert.match(texts(), /Осталось: 2/);
  await post({ message: { from: user(ADMIN), chat: { id: ADMIN }, document: { file_name: "random.zip", file_id: "X" } } });
  assert.match(texts(), /Не знаю файл/);
  await post(msg(user(ADMIN), "/files"));
  assert.equal((texts().match(/✅/g) || []).length, 10);
});

await test("admin uploads banner and avatar → photo file_id stored, avatar set", async () => {
  await post({ message: { from: user(ADMIN), chat: { id: ADMIN }, document: { file_name: "ISL-1_bot_banner.jpg", file_id: "DOC_BANNER" } } });
  assert.equal(await env.DB.get("img:isl_banner"), "BANNER_PHOTO");
  await post({ message: { from: user(ADMIN), chat: { id: ADMIN }, document: { file_name: "ISL-1_bot_avatar.jpg", file_id: "DOC_AVATAR" } } });
  const av = sent("setMyProfilePhoto")[0].body;
  assert.equal(JSON.parse(av.photo).photo, "attach://avatar");
  assert.ok(av.avatar instanceof Blob);
  assert.match(texts(), /Всё на месте/);
});

await test("welcome uses the banner photo once uploaded", async () => {
  await post(msg(user(USER), "/start"));
  const ph = sent("sendPhoto")[0].body;
  assert.equal(ph.photo, "BANNER_PHOTO");
  assert.match(ph.caption, /8\.99/);
  assert.ok(ph.caption.length <= 1024);
});

await test("TRY download sends the TRY kit, the 10-day offer, no full APK", async () => {
  await post(cb(user(USER), "try"));
  const g = sent("sendMediaGroup")[0].body.media.map((x) => x.media);
  assert.deepEqual(g, ["ID_ISL-1_TRY_0.4.1.apk", "ID_ISL-1_TRY_Install_RU.pdf", "ID_ISL-1_TRY_Install_EN.pdf", "ID_ISL-1_Manual_RU.pdf", "ID_ISL-1_Manual_EN.pdf", "ID_ISL-1_Looper_RU.pdf", "ID_ISL-1_Looper_EN.pdf"]);
  assert.ok(!JSON.stringify(calls).includes("ID_ISL-1_0.4.1"));
});

await test("/mypro without purchase → no PRO files", async () => {
  await post(msg(user(USER), "/mypro"));
  assert.match(texts(), /не найдено/);
  assert.ok(!JSON.stringify(calls).includes("ID_ISL-1_0.4.1"));
});

await test("buy shows terms and both payment buttons", async () => {
  await post(cb(user(USER), "buy"));
  const m = sent("sendMessage")[0];
  assert.match(m.body.text, /Условия покупки/);
  assert.match(m.body.text, /paysupport/);
  const btns = m.body.reply_markup.inline_keyboard.flat().map((b) => b.callback_data);
  assert.deepEqual(btns, ["pay_stars", "pay_usdt"]);
});

await test("Stars invoice uses XTR, empty provider, right price", async () => {
  await post(cb(user(USER), "pay_stars"));
  const inv = sent("sendInvoice")[0].body;
  assert.equal(inv.currency, "XTR");
  assert.equal(inv.provider_token, "");
  assert.deepEqual(inv.prices, [{ label: "ISL-1", amount: 450 }]);
  assert.equal(inv.payload, "pro:450:");
});

await test("pre-checkout: right amount ok, wrong amount rejected", async () => {
  await post({ pre_checkout_query: { id: "pc1", from: user(USER), currency: "XTR", total_amount: 450, invoice_payload: "pro:450:" } });
  assert.equal(sent("answerPreCheckoutQuery")[0].body.ok, true);
  await post({ pre_checkout_query: { id: "pc2", from: user(USER), currency: "XTR", total_amount: 1, invoice_payload: "pro:1:" } });
  assert.equal(sent("answerPreCheckoutQuery")[0].body.ok, false);
  await post({ pre_checkout_query: { id: "pc3", from: user(USER), currency: "XTR", total_amount: 400, invoice_payload: "pro:400:fakecode" } });
  assert.equal(sent("answerPreCheckoutQuery")[0].body.ok, false, "unknown promo can't lower the price");
});

await test("successful Stars payment delivers PRO and notifies admin once", async () => {
  const pay = { message: { from: user(USER), chat: { id: USER }, successful_payment: { currency: "XTR", total_amount: 450, invoice_payload: "pro:450:", telegram_payment_charge_id: "CHG1" } } };
  await post(pay);
  assert.match(texts(), /Спасибо за покупку/);
  assert.equal(sent("sendMediaGroup")[0].body.media[0].media, "ID_ISL-1_0.4.1.apk");
  assert.ok(sent("sendMessage").some((c) => c.body.chat_id === String(ADMIN) && /Продажа/.test(c.body.text)));
  assert.equal(JSON.parse(await env.DB.get("buyer:" + USER)).ref, "CHG1");
  await post(pay); // duplicate update
  assert.ok(!sent("sendMessage").some((c) => c.body.chat_id === String(ADMIN)), "no second admin notification");
});

await test("/mypro after purchase resends PRO", async () => {
  await post(msg(user(USER), "/mypro"));
  assert.equal(sent("sendMediaGroup")[0].body.media[0].media, "ID_ISL-1_0.4.1.apk");
});

await test("USDT: invoice, unpaid check, foreign user can't claim, paid check delivers", async () => {
  await post(cb(user(OTHER, "en"), "pay_usdt"));
  const ci = calls.find((c) => c.method === "cp.createInvoice").body;
  assert.equal(ci.asset, "USDT");
  assert.equal(ci.amount, "8.99");
  assert.equal(ci.payload, `pro:${OTHER}:${OTHER}:en:`);
  const invId = Object.keys(invoices).pop();
  await post(cb(user(OTHER, "en"), "chk:" + invId));
  assert.match(texts(), /not arrived yet/);
  invoices[invId].status = "paid";
  await post(cb(user(USER), "chk:" + invId)); // someone else's button
  assert.equal(sent("sendMediaGroup").length, 0);
  await post(cb(user(OTHER, "en"), "chk:" + invId));
  assert.match(texts(), /Thank you/);
  assert.equal(sent("sendMediaGroup")[0].body.chat_id, OTHER);
});

async function cryptoHook(bodyObj, sign = true) {
  calls = [];
  const raw = JSON.stringify(bodyObj);
  const key = createHash("sha256").update(CP_TOKEN).digest();
  const sig = sign ? createHmac("sha256", key).update(raw).digest("hex") : "deadbeef";
  return worker.fetch(new Request("https://bot.example.workers.dev/cryptopay", { method: "POST", headers: { "crypto-pay-api-signature": sig }, body: raw }), env);
}

await test("Crypto Pay webhook: bad signature rejected, good one delivers, retry silent", async () => {
  const NEW = 444;
  const inv = { invoice_id: 77, status: "paid", asset: "USDT", amount: "8.99", payload: `pro:${NEW}:${NEW}:ru:` };
  const upd = { update_id: 1, update_type: "invoice_paid", request_date: new Date().toISOString(), payload: inv };
  assert.equal((await cryptoHook(upd, false)).status, 401);
  assert.equal(calls.length, 0);
  assert.equal((await cryptoHook(upd)).status, 200);
  assert.ok(sent("sendMediaGroup").some((c) => c.body.chat_id === NEW));
  await cryptoHook(upd);
  assert.equal(calls.length, 0, "retry does nothing");
});

await test("support forwarding and admin reply", async () => {
  await post(msg(user(USER), "/paysupport не пришли файлы"));
  assert.ok(sent("sendMessage").some((c) => c.body.chat_id === String(ADMIN) && /PAYSUPPORT/.test(c.body.text) && /CHG1/.test(c.body.text)));
  await post(msg(user(ADMIN), `/reply ${USER} Отправил ещё раз`));
  assert.ok(sent("sendMessage").some((c) => String(c.body.chat_id) === String(USER) && /Отправил ещё раз/.test(c.body.text)));
});

await test("non-admin can't use admin commands", async () => {
  await post(msg(user(USER), `/refund ${USER}`));
  assert.equal(sent("refundStarPayment").length, 0);
  await post(msg(user(USER), `/grant ${OTHER}`));
  assert.ok(!sent("sendMessage").some((c) => /Выдано/.test(c.body.text)));
});

await test("admin refund calls refundStarPayment and revokes /mypro", async () => {
  await post(msg(user(ADMIN), `/refund ${USER}`));
  assert.deepEqual(sent("refundStarPayment")[0].body, { user_id: USER, telegram_payment_charge_id: "CHG1" });
  assert.equal(await env.DB.get("buyer:" + USER), null);
});

await test("/setup is protected and sets webhook with secret", async () => {
  calls = [];
  let r = await worker.fetch(new Request("https://bot.example.workers.dev/setup?key=nope"), env);
  assert.equal(r.status, 403);
  r = await worker.fetch(new Request(`https://bot.example.workers.dev/setup?key=${SECRET}`), env);
  const wh = sent("setWebhook")[0].body;
  assert.equal(wh.url, "https://bot.example.workers.dev/telegram");
  assert.equal(wh.secret_token, SECRET);
  assert.match(await r.text(), /Bot is set up/);
});

await test("without Crypto Pay token only Stars is offered", async () => {
  const saved = env.CRYPTO_PAY_TOKEN;
  delete env.CRYPTO_PAY_TOKEN;
  await post(cb(user(USER), "buy"));
  assert.deepEqual(sent("sendMessage")[0].body.reply_markup.inline_keyboard.flat().map((b) => b.callback_data), ["pay_stars"]);
  assert.doesNotMatch(texts(), /USDT/);
  env.CRYPTO_PAY_TOKEN = saved;
});

await test("promo code: /promo and deep link give $7.99 / 400 ⭐, any case", async () => {
  await post(msg(user(USER), "/promo SalychMusic"));
  assert.match(texts(), /Промокод <b>salychmusic<\/b> применён/);
  assert.match(texts(), /\$7\.99/);
  const btns = sent("sendMessage")[0].body.reply_markup.inline_keyboard.flat().map((b) => b.callback_data);
  assert.deepEqual(btns, ["pay_stars:salychmusic", "pay_usdt:salychmusic"]);
  await post(cb(user(USER), "pay_stars:salychmusic"));
  const inv = sent("sendInvoice")[0].body;
  assert.equal(inv.prices[0].amount, 400);
  assert.equal(inv.payload, "pro:400:salychmusic");
  await post({ pre_checkout_query: { id: "pc9", from: user(USER), currency: "XTR", total_amount: 400, invoice_payload: "pro:400:salychmusic" } });
  assert.equal(sent("answerPreCheckoutQuery")[0].body.ok, true);
  await post(msg(user(OTHER, "en"), "/start salychmusic"));
  assert.match(texts(), /Promo code <b>salychmusic<\/b> applied/);
  await post(cb(user(OTHER, "en"), "pay_usdt:salychmusic"));
  assert.equal(calls.find((c) => c.method === "cp.createInvoice").body.amount, "7.99");
  await post(msg(user(USER), "/promo wrongcode"));
  assert.match(texts(), /нет/);
});

await test("after the sale ends: $9.99 / 500 ⭐, promo still $7.99", async () => {
  const saved = env.SALE_UNTIL;
  env.SALE_UNTIL = new Date(Date.now() - 1000).toISOString();
  await post(cb(user(USER), "buy"));
  assert.match(texts(), /Цена ISL-1: <b>\$9\.99<\/b> · <b>500 ⭐<\/b>/);
  await post(cb(user(USER), "pay_stars"));
  assert.equal(sent("sendInvoice")[0].body.prices[0].amount, 500);
  await post({ pre_checkout_query: { id: "pcA", from: user(USER), currency: "XTR", total_amount: 450, invoice_payload: "pro:450:" } });
  assert.equal(sent("answerPreCheckoutQuery")[0].body.ok, false, "old sale invoice rejected after the sale");
  await post(msg(user(USER), "/promo salychmusic"));
  assert.match(texts(), /\$7\.99/);
  env.SALE_UNTIL = saved;
});

await test("sale date shown as 31 December for 2027-01-01 00:00 MSK", async () => {
  const saved = env.SALE_UNTIL;
  env.SALE_UNTIL = "2027-01-01T00:00:00+03:00";
  const realNow = Date.now;
  Date.now = () => Date.parse("2026-11-15T12:00:00Z");
  await post(msg(user(USER), "/start"));
  assert.match(sent("sendPhoto")[0].body.caption, /Скидка до 31 декабря/);
  Date.now = () => Date.parse("2026-12-31T21:00:00Z"); // = 2027-01-01 00:00 MSK
  await post(msg(user(USER), "/start"));
  assert.match(sent("sendPhoto")[0].body.caption, /\$9\.99<\/b> · <b>500 ⭐/);
  Date.now = realNow;
  env.SALE_UNTIL = saved;
});

await test("FAQ: free-text questions get automatic answers (RU/EN)", async () => {
  const cases = [["Как установить приложение?", /Как установить/], ["Работает на айфоне?", /Android 10/],
                 ["чем про отличается от фри", /10 дней/], ["Сколько стоит?", /\$8\.99/], ["можно вернуть деньги?", /14 дней/],
                 ["я оплатил но файлы не пришли", /\/mypro/], ["как подключить к Ableton", /SETUP → Ableton Link/],
                 ["приложение вылетает", /Перезапустите/]];
  for (const [q, re] of cases) {
    await post(msg(user(USER), q));
    assert.match(texts(), re, q);
    assert.equal(sent("sendMessage")[0].body.reply_markup.inline_keyboard[0][0].callback_data, "ask");
  }
  await post(msg(user(OTHER, "en"), "does it work on iPhone"));
  assert.match(texts(), /no iPhone/);
});

await test("FAQ: greeting shows the menu, no question stored", async () => {
  await env.DB.delete("q:" + USER);
  await post(msg(user(USER), "Привет!"));
  assert.ok(sent("sendPhoto").length === 1);
  assert.equal(await env.DB.get("q:" + USER), null);
});

await test("FAQ: unknown question → offer, then forwarded to the author only on tap", async () => {
  await post(msg(user(USER), "Будет ли версия для Windows?"));
  assert.match(texts(), /Готового ответа/);
  assert.ok(!calls.some((c) => c.method === "sendMessage" && c.body.chat_id === String(ADMIN)));
  await post(cb(user(USER), "ask"));
  const toAdmin = sent("sendMessage").find((c) => String(c.body.chat_id) === String(ADMIN));
  assert.match(toAdmin.body.text, /ВОПРОС/);
  assert.match(toAdmin.body.text, /Windows/);
  assert.match(texts(), /отправлено автору/);
  await post(cb(user(USER), "ask"));
  assert.match(texts(), /ещё раз/);
});

await test("FAQ: menu button, topic buttons and /faq", async () => {
  await post(msg(user(USER), "/faq"));
  const rows = sent("sendMessage")[0].body.reply_markup.inline_keyboard;
  assert.ok(rows.length >= 10);
  await post(cb(user(USER), "faq:promo"));
  assert.match(texts(), /\/promo КОД/);
  await post(cb(user(USER), "faq:pay"));
  assert.match(texts(), /450 ⭐/);
});

await test("bot commands are refreshed once after an update (faq added)", async () => {
  await env.DB.delete("cmds_rev");
  await post(msg(user(USER), "/start"));
  const c = sent("setMyCommands");
  assert.equal(c.length, 2);
  assert.ok(c[0].body.commands.some((x) => x.command === "faq"));
  await post(msg(user(USER), "/start"));
  assert.equal(sent("setMyCommands").length, 0);
});

await test("admin free text is not treated as a buyer question", async () => {
  await post(msg(user(ADMIN), "как установить"));
  assert.match(texts(), /Команды автора/);
});

const stats = async () => JSON.parse((await env.DB.get("stats")) || "null");

await test("stats: FREE downloads counted, contacts saved, admin not counted", async () => {
  const before = (await stats()).total;
  await post(cb(user(555), "try"));
  await post(msg(user(555), "/try"));
  await post(cb(user(ADMIN), "try"));
  const after = (await stats()).total;
  assert.equal(after.tryDl - before.tryDl, 2);
  assert.equal(after.tryUsers - before.tryUsers, 1);
  const c = JSON.parse(await env.DB.get("try:555"));
  assert.equal(c.username, "user555");
  assert.equal(c.downloads, 2);
  assert.equal(await env.DB.get("try:" + ADMIN), null);
});

await test("stats: Stars sale, PRO downloads, refund; /stats is admin-only", async () => {
  const before = (await stats()).total;
  await post({ message: { from: user(666), chat: { id: 666 }, successful_payment: { currency: "XTR", total_amount: 450, invoice_payload: "pro:450:", telegram_payment_charge_id: "CHG666" } } });
  await post(msg(user(666), "/mypro"));
  let after = (await stats()).total;
  assert.equal(after.starsN - before.starsN, 1);
  assert.equal(after.starsSum - before.starsSum, 450);
  assert.equal(after.proDl - before.proDl, 2);
  assert.equal(JSON.parse(await env.DB.get("buyer:666")).first_name, "U666");
  await post(msg(user(666), "/stats"));
  assert.ok(!/Статистика/.test(texts()));
  await post(msg(user(ADMIN), "/stats"));
  assert.match(texts(), /Статистика ISL-1/);
  assert.match(texts(), /За всё время/);
  assert.match(texts(), /≈ \$\d+\.\d\d к выводу/);
  await post(msg(user(ADMIN), "/refund 666"));
  after = (await stats()).total;
  assert.equal(after.refundStars - before.refundStars, 450);
});

await test("language: first /start asks, the choice sticks, the menu can change it", async () => {
  const u = { ...user(FRESH), language_code: "ru" };
  await post(msg(u, "/start"));
  assert.match(texts(), /Choose your language/);
  await post(cb(u, "lang:en"));
  assert.match(JSON.stringify(calls), /Download ISL-1 TRY/);
  await post(msg({ ...user(FRESH), language_code: "ru" }, "/about"));
  assert.match(texts(), /Free software/);
  await post(msg(u, "/start"));
  assert.ok(sent("sendPhoto").length + sent("sendMessage").length >= 1);
  assert.doesNotMatch(texts(), /Choose your language/);
  await post(msg(u, "/language"));
  assert.equal(sent("sendMessage")[0].body.reply_markup.inline_keyboard[0].length, 2);
  await post(cb(u, "lang:ru"));
  assert.match(JSON.stringify(calls), /Скачать ISL-1 TRY/);
  await post(cb(u, "lang:xx"));
  assert.equal(await env.DB.get("lang:" + FRESH), "ru");
});

await test("/contacts sends three category tables as CSV files", async () => {
  await post(msg(user(ADMIN), "/contacts"));
  const docs = sent("sendDocument");
  assert.equal(docs.length, 3);
  const tr = await docs[0].body.document.text(), buy = await docs[2].body.document.text();
  assert.deepEqual([...new Uint8Array(await docs[0].body.document.arrayBuffer()).slice(0, 3)], [0xef, 0xbb, 0xbf], "UTF-8 BOM for Excel");
  assert.ok(tr.replace(/^\uFEFF/, "").startsWith("user_id;username"));
  assert.match(tr, /555;user555;U555/);
  assert.match(docs[0].body.caption, /TRY/);
  assert.match(buy, /user_id;username/);
  await post(msg(user(ADMIN), "/contacts buy"));
  assert.equal(sent("sendDocument").length, 1);
});

await test("old links and buttons for FREE lead to TRY", async () => {
  await post(cb(user(800), "free"));
  assert.equal(sent("sendMediaGroup")[0].body.media[0].media, "ID_ISL-1_TRY_0.4.1.apk");
  await post(msg(user(801), "/start free"));
  assert.equal(sent("sendMediaGroup")[0].body.media[0].media, "ID_ISL-1_TRY_0.4.1.apk");
});

await test("TRY offer: 10 days, Instagram rules and the button", async () => {
  await post(cb(user(802), "try"));
  const offer = sent("sendMessage").at(-1).body;
  assert.match(offer.text, /10 дней/);
  assert.match(offer.text, /@salychmusic/);
  assert.match(offer.text, /запись с экрана/);
  assert.equal(offer.reply_markup.inline_keyboard[0][0].callback_data, "want");
  assert.equal(offer.reply_markup.inline_keyboard[0][0].text, "🎁 Хочу бесплатно!");
});

const lastTo = (id) => sent("sendMessage").filter((c) => String(c.body.chat_id) === String(id)).at(-1);

await test("review: Instagram is required, the admin gets an active request", async () => {
  await post(cb(user(900), "want"));
  assert.match(lastTo(900).body.text, /Instagram/);
  await post(msg(user(900), "не аккаунт, а текст"));
  assert.match(lastTo(900).body.text, /Не похоже/);
  assert.equal(await env.DB.get("req:900"), null);
  await post(msg(user(900), "https://www.instagram.com/Some.User_1/?hl=ru"));
  const r = JSON.parse(await env.DB.get("req:900"));
  assert.equal(r.instagram, "some.user_1");
  assert.equal(r.status, "pending");
  const card = lastTo(ADMIN).body;
  assert.match(card.text, /@some\.user_1/);
  assert.match(card.text, /instagram\.com\/some\.user_1/);
  assert.deepEqual(card.reply_markup.inline_keyboard[0].map((b) => b.callback_data), ["rv_ok:900", "rv_no:900"]);
  await post(cb(user(900), "want"));
  assert.match(lastTo(900).body.text, /уже у автора/);
  await post(msg(user(ADMIN), "/requests"));
  assert.ok(sent("sendMessage").some((c) => /@some\.user_1/.test(c.body.text)));
});

await test("review: only the admin can decide", async () => {
  await post(cb(user(900), "rv_ok:900"));
  assert.equal(JSON.parse(await env.DB.get("req:900")).status, "pending");
  await post(cb(user(900), "adm:buy"));
  assert.ok(!sent("sendMessage").length);
});

await test("review: rejected person is told and can apply again", async () => {
  await post(cb(user(ADMIN), "rv_no:900"));
  assert.equal(JSON.parse(await env.DB.get("req:900")).status, "rejected");
  assert.match(lastTo(900).body.text, /не смог подтвердить/);
  await post(cb(user(ADMIN), "rv_no:900"));
  assert.match(lastTo(ADMIN).body.text, /уже обработана/);
  await post(cb(user(900), "want"));
  await post(msg(user(900), "@some.user_1"));
  assert.equal(JSON.parse(await env.DB.get("req:900")).status, "pending");
});

await test("review: approval sends a one-time code; it gives the full kit once", async () => {
  await post(cb(user(ADMIN), "rv_ok:900"));
  const m = lastTo(900).body;
  const code = /ISL-[A-Z2-9]{4}-[A-Z2-9]{4}/.exec(m.text)[0];
  await post(msg(user(900), "/mypro"));
  assert.ok(!JSON.stringify(calls).includes("ID_ISL-1_0.4.1"), "no files before redeeming");
  await post(msg(user(900), "/promo " + code.toLowerCase()));
  assert.equal(sent("sendMediaGroup")[0].body.media[0].media, "ID_ISL-1_0.4.1.apk");
  assert.ok(sent("sendMessage").some((c) => String(c.body.chat_id) === String(ADMIN) && c.body.text.includes(code)));
  await post(msg(user(901), "/promo " + code));
  assert.match(lastTo(901).body.text, /уже использован/);
  assert.ok(!JSON.stringify(calls).includes("ID_ISL-1_0.4.1"));
  await post(cb(user(900), "redeem:" + code));
  assert.equal(sent("sendMediaGroup")[0].body.media[0].media, "ID_ISL-1_0.4.1.apk");
  await post(msg(user(900), "/mypro"));
  assert.equal(sent("sendMediaGroup")[0].body.media[0].media, "ID_ISL-1_0.4.1.apk");
  await post(cb(user(900), "want"));
  assert.match(lastTo(900).body.text, /уже есть ISL-1/);
  assert.equal(JSON.parse(await env.DB.get("reviewer:900")).instagram, "some.user_1");
  assert.equal(await env.DB.get("buyer:900"), null, "not counted as a sale");
});

await test("unknown code is refused, ordinary promo code still works", async () => {
  await post(msg(user(902), "/promo ISL-AAAA-BBBB"));
  assert.match(lastTo(902).body.text, /Такого промокода нет/);
  await post(msg(user(902), "/promo salychmusic"));
  assert.match(lastTo(902).body.text, /\$7\.99/);
});

await test("/list shows people by category, admin only", async () => {
  await post(msg(user(ADMIN), "/list review"));
  assert.match(lastTo(ADMIN).body.text, /@user900/);
  assert.match(lastTo(ADMIN).body.text, /IG @some\.user_1/);
  await post(msg(user(ADMIN), "/list"));
  assert.ok(lastTo(ADMIN).body.reply_markup.inline_keyboard.length >= 3);
  await post(cb(user(ADMIN), "adm:try"));
  assert.match(lastTo(ADMIN).body.text, /Скачали TRY/);
  await post(msg(user(902), "/list review"));
  assert.ok(!sent("sendMessage").some((c) => /@user900/.test(c.body.text)));
});

await test("stats show TRY and review counters", async () => {
  await post(msg(user(ADMIN), "/stats"));
  assert.match(texts(), /🎁 За отзыв: <b>1<\/b>/);
});

await test("the bot profile is ISL-1 and the sale conditions are unchanged", async () => {
  await env.DB.delete("cmds_rev");
  await post(msg(user(902), "/start"));
  assert.ok(sent("setMyName").some((c) => c.body.name === "ISL-1 Sampler"));
  assert.ok(sent("setMyCommands").some((c) => c.body.commands.some((x) => x.command === "want")));
  assert.match(texts() + JSON.stringify(calls), /\$8\.99/);
});

await test("stats rebuild from recorded payments when the stats record is missing", async () => {
  await env.DB.delete("stats");
  await post({ message: { from: user(888), chat: { id: 888 }, successful_payment: { currency: "XTR", total_amount: 450, invoice_payload: "pro:450:", telegram_payment_charge_id: "CHG888" } } });
  const t = (await stats()).total;
  assert.ok(t.starsN >= 4, "earlier Stars payments recovered from paid: records");
  assert.ok(t.usdtN >= 2);
});

console.log(`\n${passed} tests passed · KV writes used: ${env.DB.writes}`);
