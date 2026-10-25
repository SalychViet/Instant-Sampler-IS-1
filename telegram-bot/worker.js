// IS-1 Telegram bot — Cloudflare Worker (free plan).
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC).
//
// What it does:
//   * IS-1 FREE: free download (APK + manuals) for everyone.
//   * IS-1 PRO: sold for Telegram Stars (price in USD, converted automatically) and optionally USDT via @CryptoBot.
//     Holiday sale until a date, promo codes. After payment the bot sends the PRO files; /mypro sends them again.
//   * Files and branding images live in Telegram itself: the admin sends them to the bot once.
//
// Secrets (Cloudflare → Worker → Settings → Variables and Secrets):
//   BOT_TOKEN         token from @BotFather
//   WEBHOOK_SECRET    random string A–Z a–z 0–9 _ - (16+ chars); protects the webhook, /setup and /claim
//   CRYPTO_PAY_TOKEN  (optional) token of your app in @CryptoBot → Crypto Pay; enables USDT
// Variables (wrangler.toml [vars], editable in the dashboard):
//   PRICE_USD        regular PRO price, e.g. 9.99
//   SALE_PRICE_USD   sale price, e.g. 8.99 (empty = no sale)
//   SALE_UNTIL       end of the sale, ISO time, e.g. 2027-01-01T00:00:00+03:00
//   PROMO_CODES      code=price pairs, e.g. salychmusic=7.99,friend=5
//   STARS_PER_USD    conversion for Stars, e.g. 50 (≈ what buyers pay per Star)
//   ADMIN_ID         (optional) your Telegram id; or claim admin once with /claim <WEBHOOK_SECRET>
// KV binding: DB

const SOURCE_URL = "https://github.com/SalychViet/Instant-Sampler-IS-1";
const VERSION = "0.4.0";

// Admin sends documents with exactly these names (as in the release folders on the Desktop).
const FILES = {
  "IS-1_FREE_0.4.0.apk": "free_apk",
  "IS-1_FREE_Install_RU.pdf": "free_install",
  "IS-1_FREE_Manual.pdf": "free_manual_ru",
  "IS-1_FREE_Manual_EN.pdf": "free_manual_en",
  "IS-1_PRO_0.4.0.apk": "pro_apk",
  "IS-1_PRO_Install_RU.pdf": "pro_install",
  "IS-1_PRO_Manual.pdf": "pro_manual_ru",
  "IS-1_PRO_Manual_EN.pdf": "pro_manual_en",
};
// Branding images (kept out of the public repo): sent to the bot once as files.
const IMAGES = { "IS-1_bot_banner.jpg": "banner", "IS-1_bot_avatar.jpg": "avatar" };
const KIT = {
  free: ["free_apk", "free_install", "free_manual_ru", "free_manual_en"],
  pro: ["pro_apk", "pro_install", "pro_manual_ru", "pro_manual_en"],
};

// ------------------------------------------------------------------ pricing

function cents(usd) { return Math.round(parseFloat(usd) * 100); }
const fmt = (c) => (c / 100).toFixed(2);

function promoTable(env) {
  const t = {};
  for (const part of String(env.PROMO_CODES || "").split(",")) {
    const [k, v] = part.split("=").map((s) => (s || "").trim());
    if (k && parseFloat(v) > 0) t[k.toLowerCase()] = cents(v);
  }
  return t;
}

/** Current PRO price. code: promo code (any case) or empty. */
function price(env, code, now = Date.now()) {
  const regular = cents(env.PRICE_USD || "9.99");
  const saleC = env.SALE_PRICE_USD ? cents(env.SALE_PRICE_USD) : 0;
  const saleUntil = env.SALE_UNTIL ? Date.parse(env.SALE_UNTIL) : 0;
  const saleOn = saleC > 0 && saleC < regular && saleUntil > now;
  let c = saleOn ? saleC : regular;
  const promos = promoTable(env);
  const key = String(code || "").toLowerCase();
  const promoOn = !!(key && promos[key] && promos[key] < c);
  if (promoOn) c = promos[key];
  const rate = parseFloat(env.STARS_PER_USD || "50") || 50;
  return {
    cents: c,
    usd: fmt(c),
    regularUsd: fmt(regular),
    stars: Math.max(1, Math.round((c * rate) / 100)),
    regularStars: Math.max(1, Math.round((regular * rate) / 100)),
    saleOn,
    saleUntil,
    promo: promoOn ? key : "",
  };
}
const usdtOn = (env) => !!env.CRYPTO_PAY_TOKEN;
function isPromo(env, code) { return !!promoTable(env)[String(code || "").toLowerCase()]; }

// ------------------------------------------------------------------ texts (RU / EN)

function saleDate(p, lc) {
  // the day before SALE_UNTIL, in Moscow time (the sale lasts "until 31 December inclusive")
  const d = new Date(p.saleUntil - 1 + 3 * 3600 * 1000);
  const months = lc === "ru"
    ? ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"]
    : ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
  return lc === "ru" ? `${d.getUTCDate()} ${months[d.getUTCMonth()]}` : `${months[d.getUTCMonth()]} ${d.getUTCDate()}`;
}

function priceLine(p, lc) {
  const ru = lc === "ru";
  let s;
  if (p.promo) {
    s = ru ? `💎 Цена по промокоду <b>${p.promo}</b>: <s>$${p.regularUsd}</s> <b>$${p.usd}</b> · <b>${p.stars} ⭐</b>`
           : `💎 Price with code <b>${p.promo}</b>: <s>$${p.regularUsd}</s> <b>$${p.usd}</b> · <b>${p.stars} ⭐</b>`;
  } else if (p.saleOn) {
    s = ru ? `🔥 Скидка до ${saleDate(p, lc)}: <s>$${p.regularUsd}</s> <b>$${p.usd}</b> · <b>${p.stars} ⭐</b>`
           : `🔥 Sale until ${saleDate(p, lc)}: <s>$${p.regularUsd}</s> <b>$${p.usd}</b> · <b>${p.stars} ⭐</b>`;
  } else {
    s = ru ? `Цена PRO: <b>$${p.usd}</b> · <b>${p.stars} ⭐</b>` : `PRO price: <b>$${p.usd}</b> · <b>${p.stars} ⭐</b>`;
  }
  return s;
}

const T = {
  ru: {
    welcome: (p) =>
      `🎛 <b>IS-1</b> — семплер, секвенсор и KAOSS-эффекты для Android 10+.\n\n` +
      `<b>FREE</b> — бесплатно: 16 падов, запись семплов, секвенсор, 25 эффектов.\n` +
      `<b>PRO</b> — 64 пада, 50 эффектов, Ableton Link, экспорт WAV/MP3 320, проекты и стартовый проект.\n\n` +
      priceLine(p, "ru") + `\nЕсть промокод? Отправьте <code>/promo КОД</code>`,
    btnFree: "⬇️ Скачать IS-1 FREE",
    btnBuy: "⭐ Купить IS-1 PRO",
    btnMyPro: "📦 Мой IS-1 PRO",
    btnAbout: "ℹ️ О программе",
    btnSupport: "💬 Поддержка",
    sendingFree: "Отправляю IS-1 FREE 👇 Начните с инструкции по установке.",
    notReady: "Файлы ещё загружаются, попробуйте чуть позже 🙏",
    terms:
      `<b>Условия покупки IS-1 PRO</b>\n\n` +
      `• Вы покупаете файл приложения IS-1 PRO ${VERSION} для Android 10+ и руководства (RU/EN).\n` +
      `• Файлы приходят сюда сразу после оплаты; повторно — командой /mypro.\n` +
      `• Перед покупкой попробуйте бесплатную IS-1 FREE — она работает на том же движке.\n` +
      `• Если PRO не запускается на вашем устройстве, напишите в /paysupport в течение 14 дней — вернём оплату.\n` +
      `• Лицензия GNU GPL v3; исходный код: ${SOURCE_URL}\n` +
      `• Продавец: Salavat (SALYCH MUSIC).`,
    choosePay: "Нажимая кнопку оплаты, вы соглашаетесь с условиями выше.",
    payStars: (p) => `⭐ Оплатить ${p.stars} Stars`,
    payUsdt: (p) => `💵 Оплатить ${p.usd} USDT`,
    invoiceDesc: `IS-1 PRO ${VERSION}: 64 пада, 50 эффектов KAOSS, Ableton Link, WAV/MP3 320, проекты. APK для Android 10+ и руководства.`,
    usdtInvoice: "Счёт в USDT создан. Оплатите в @CryptoBot по кнопке ниже, затем нажмите «Проверить оплату».",
    btnOpenInvoice: "💵 Оплатить в CryptoBot",
    btnCheck: "✅ Проверить оплату",
    notPaidYet: "Оплата ещё не пришла. Если вы уже оплатили — подождите минуту и нажмите ещё раз.",
    usdtOff: "Оплата в USDT сейчас недоступна, воспользуйтесь Stars ⭐",
    thanks: "Спасибо за покупку! 🎉 Вот ваш IS-1 PRO 👇 Начните с инструкции по установке.",
    alreadyBought: "У вас уже есть IS-1 PRO — отправляю файлы ещё раз 👇",
    noPurchase: "Покупок IS-1 PRO на этом аккаунте не найдено. Купить: /buy",
    about:
      `<b>IS-1 ${VERSION}</b> · © 2026 Salavat (SALYCH MUSIC)\n` +
      `Свободная программа: GNU GPL v3 или новее. Исходный код:\n${SOURCE_URL}\n\n` +
      `Ableton Link — товарный знак Ableton AG; IS-1 не связан с Ableton.`,
    support: "Напишите вопрос одним сообщением так:\n<code>/support ваш текст</code>\nи я передам его автору.",
    paysupport: "Проблема с оплатой или файлами? Напишите одним сообщением:\n<code>/paysupport ваш текст</code>\nУкажите, чем оплачивали. Автор ответит здесь.",
    sentToAuthor: "Сообщение отправлено автору ✅ Ответ придёт сюда.",
    priceChanged: "Цена изменилась, откройте покупку заново: /buy",
    promoHow: "Отправьте промокод так: <code>/promo КОД</code>",
    promoOk: (p) => `🎟 Промокод <b>${p.promo}</b> применён!\n` + priceLine(p, "ru"),
    promoBad: "Такого промокода нет 🤷 Проверьте написание.",
    promoNoGain: "Промокод действует, но сейчас цена и так ниже 🙂",
  },
  en: {
    welcome: (p) =>
      `🎛 <b>IS-1</b> — a sampler, sequencer and KAOSS effects for Android 10+.\n\n` +
      `<b>FREE</b> — free: 16 pads, sample recording, sequencer, 25 effects.\n` +
      `<b>PRO</b> — 64 pads, 50 effects, Ableton Link, WAV/MP3 320 export, projects and a starter project.\n\n` +
      priceLine(p, "en") + `\nHave a promo code? Send <code>/promo CODE</code>`,
    btnFree: "⬇️ Download IS-1 FREE",
    btnBuy: "⭐ Buy IS-1 PRO",
    btnMyPro: "📦 My IS-1 PRO",
    btnAbout: "ℹ️ About",
    btnSupport: "💬 Support",
    sendingFree: "Here is IS-1 FREE 👇 The install guide is in Russian; the manual is in English and Russian.",
    notReady: "Files are still being uploaded, please try again a bit later 🙏",
    terms:
      `<b>IS-1 PRO purchase terms</b>\n\n` +
      `• You are buying the IS-1 PRO ${VERSION} app file for Android 10+ and the manuals (EN/RU).\n` +
      `• The files arrive here right after payment; get them again any time with /mypro.\n` +
      `• Please try the free IS-1 FREE first — it runs on the same engine.\n` +
      `• If PRO does not run on your device, contact /paysupport within 14 days for a refund.\n` +
      `• Licensed under the GNU GPL v3; source code: ${SOURCE_URL}\n` +
      `• Seller: Salavat (SALYCH MUSIC).`,
    choosePay: "By tapping a payment button you agree to the terms above.",
    payStars: (p) => `⭐ Pay ${p.stars} Stars`,
    payUsdt: (p) => `💵 Pay ${p.usd} USDT`,
    invoiceDesc: `IS-1 PRO ${VERSION}: 64 pads, 50 KAOSS effects, Ableton Link, WAV/MP3 320, projects. APK for Android 10+ and manuals.`,
    usdtInvoice: "USDT invoice created. Pay in @CryptoBot with the button below, then tap “Check payment”.",
    btnOpenInvoice: "💵 Pay in CryptoBot",
    btnCheck: "✅ Check payment",
    notPaidYet: "Payment has not arrived yet. If you have paid, wait a minute and tap again.",
    usdtOff: "USDT payment is not available right now, please use Stars ⭐",
    thanks: "Thank you for your purchase! 🎉 Here is your IS-1 PRO 👇",
    alreadyBought: "You already own IS-1 PRO — sending the files again 👇",
    noPurchase: "No IS-1 PRO purchase found for this account. Buy: /buy",
    about:
      `<b>IS-1 ${VERSION}</b> · © 2026 Salavat (SALYCH MUSIC)\n` +
      `Free software: GNU GPL v3 or later. Source code:\n${SOURCE_URL}\n\n` +
      `Ableton Link is a trademark of Ableton AG; IS-1 is not affiliated with Ableton.`,
    support: "Send your question in one message like this:\n<code>/support your text</code>\nand I will pass it to the author.",
    paysupport: "Problem with a payment or the files? Send one message:\n<code>/paysupport your text</code>\nSay how you paid. The author will reply here.",
    sentToAuthor: "Message sent to the author ✅ The reply will come here.",
    priceChanged: "The price has changed, please start the purchase again: /buy",
    promoHow: "Send your promo code like this: <code>/promo CODE</code>",
    promoOk: (p) => `🎟 Promo code <b>${p.promo}</b> applied!\n` + priceLine(p, "en"),
    promoBad: "No such promo code 🤷 Please check the spelling.",
    promoNoGain: "The code is valid, but the current price is already lower 🙂",
  },
};

function lang(user) {
  const c = (user && user.language_code) || "";
  return /^(ru|uk|be|kk)/.test(c) ? "ru" : "en";
}

// ------------------------------------------------------------------ Telegram API

async function tg(env, method, body) {
  const r = await fetch(`https://api.telegram.org/bot${env.BOT_TOKEN}/${method}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  const j = await r.json().catch(() => ({ ok: false }));
  if (!j.ok) console.log(`tg ${method} failed:`, JSON.stringify(j).slice(0, 300));
  return j;
}

/** multipart call (for uploading the bot avatar) */
async function tgForm(env, method, form) {
  const r = await fetch(`https://api.telegram.org/bot${env.BOT_TOKEN}/${method}`, { method: "POST", body: form });
  const j = await r.json().catch(() => ({ ok: false }));
  if (!j.ok) console.log(`tg ${method} failed:`, JSON.stringify(j).slice(0, 300));
  return j;
}

const send = (env, chat_id, text, extra = {}) =>
  tg(env, "sendMessage", { chat_id, text, parse_mode: "HTML", disable_web_page_preview: true, ...extra });

const kb = (rows) => ({ reply_markup: { inline_keyboard: rows } });
const codeSuffix = (code) => (code ? ":" + code : "");

function mainMenu(t, code) {
  return kb([
    [{ text: t.btnFree, callback_data: "free" }],
    [{ text: t.btnBuy, callback_data: "buy" + codeSuffix(code) }],
    [{ text: t.btnMyPro, callback_data: "mypro" }, { text: t.btnAbout, callback_data: "about" }],
    [{ text: t.btnSupport, callback_data: "support" }],
  ]);
}

// ------------------------------------------------------------------ storage helpers

async function isAdmin(env, id) {
  const fixed = String(env.ADMIN_ID || "").trim();
  if (fixed) return String(id) === fixed;
  return String(id) === String((await env.DB.get("admin")) || "");
}
async function adminChat(env) {
  return String(env.ADMIN_ID || "").trim() || (await env.DB.get("admin"));
}

async function getFileIds(env, kind) {
  return Promise.all(KIT[kind].map((k) => env.DB.get("file:" + k)));
}

async function sendKit(env, chatId, kind, t) {
  const ids = await getFileIds(env, kind);
  if (!ids[0]) {
    await send(env, chatId, t.notReady);
    const a = await adminChat(env);
    if (a) await send(env, a, `⚠️ Нет файла ${KIT[kind][0]} — пришлите его боту.`);
    return false;
  }
  const docs = ids.filter(Boolean);
  const media = docs.map((id, i) => ({
    type: "document",
    media: id,
    ...(i === docs.length - 1 ? { caption: `IS-1 ${kind.toUpperCase()} ${VERSION} · ${SOURCE_URL}` } : {}),
  }));
  if (media.length === 1) await tg(env, "sendDocument", { chat_id: chatId, document: media[0].media, caption: media[0].caption });
  else await tg(env, "sendMediaGroup", { chat_id: chatId, media });
  return true;
}

async function getPurchase(env, userId) {
  const v = await env.DB.get("buyer:" + userId);
  return v ? JSON.parse(v) : null;
}

/** Record a purchase once and deliver. `ref` is unique per payment (charge id / invoice id). */
async function completePurchase(env, user, chatId, method, ref, amount, promo = "", resendIfDone = true) {
  const t = T[lang(user)];
  const refKey = "paid:" + method + ":" + ref;
  if (await env.DB.get(refKey)) {
    if (resendIfDone) await sendKit(env, chatId, "pro", t);
    return;
  }
  const rec = { user_id: user.id, username: user.username || "", method, ref: String(ref), amount: String(amount), promo, date: new Date().toISOString() };
  await env.DB.put(refKey, JSON.stringify(rec));
  await env.DB.put("buyer:" + user.id, JSON.stringify(rec));
  await send(env, chatId, t.thanks);
  await sendKit(env, chatId, "pro", t);
  const a = await adminChat(env);
  if (a) {
    const who = user.username ? "@" + user.username : user.first_name || "";
    await send(env, a,
      `💰 Продажа IS-1 PRO: ${escapeHtml(amount)} ${method === "stars" ? "⭐" : method === "usdt" ? "USDT" : ""}` +
      (promo ? ` · промокод ${escapeHtml(promo)}` : "") +
      ` от ${escapeHtml(who)} (<code>${user.id}</code>)\nref: <code>${escapeHtml(String(ref))}</code>`);
  }
}

function escapeHtml(s) {
  return String(s).replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c]));
}

// ------------------------------------------------------------------ Crypto Pay (USDT)

async function cryptoPay(env, method, params) {
  const r = await fetch(`https://pay.crypt.bot/api/${method}`, {
    method: "POST",
    headers: { "content-type": "application/json", "Crypto-Pay-API-Token": env.CRYPTO_PAY_TOKEN },
    body: JSON.stringify(params),
  });
  const j = await r.json().catch(() => ({ ok: false }));
  if (!j.ok) console.log(`cryptopay ${method} failed:`, JSON.stringify(j).slice(0, 300));
  return j;
}

async function hmacHex(keyBytes, data) {
  const key = await crypto.subtle.importKey("raw", keyBytes, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(data));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function safeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let x = 0;
  for (let i = 0; i < a.length; i++) x |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return x === 0;
}

async function handleCryptoWebhook(request, env) {
  if (!env.CRYPTO_PAY_TOKEN) return new Response("off", { status: 404 });
  const raw = await request.text();
  const secret = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(env.CRYPTO_PAY_TOKEN));
  if (!safeEqual(await hmacHex(secret, raw), request.headers.get("crypto-pay-api-signature") || "")) {
    return new Response("bad signature", { status: 401 });
  }
  const upd = JSON.parse(raw);
  if (upd.update_type === "invoice_paid" && upd.payload) await deliverIfInvoicePaid(env, upd.payload, null, false);
  return new Response("ok");
}

async function deliverIfInvoicePaid(env, inv, expectUserId, resendIfDone = true) {
  // Invoices can only be created with our Crypto Pay token, i.e. by this bot, at the price of that moment.
  if (!inv || inv.status !== "paid" || inv.asset !== "USDT") return false;
  const p = String(inv.payload || "").split(":"); // pro:<userId>:<chatId>:<lang>:<promo>
  if (p[0] !== "pro" || !/^\d+$/.test(p[1] || "")) return false;
  const userId = Number(p[1]);
  if (expectUserId && userId !== expectUserId) return false;
  const user = { id: userId, language_code: p[3] === "ru" ? "ru" : "en" };
  await completePurchase(env, user, Number(p[2] || userId), "usdt", inv.invoice_id, inv.amount, p[4] || "", resendIfDone);
  return true;
}

// ------------------------------------------------------------------ Telegram update handling

async function handleUpdate(upd, env) {
  if (upd.pre_checkout_query) return onPreCheckout(upd.pre_checkout_query, env);
  if (upd.callback_query) return onCallback(upd.callback_query, env);
  const m = upd.message;
  if (!m || !m.from) return;
  if (m.successful_payment) return onPaid(m, env);
  if (m.document && (await isAdmin(env, m.from.id))) return onAdminDocument(m, env);
  if (typeof m.text === "string") return onText(m, env);
}

async function welcome(env, chat, t, code) {
  const p = price(env, code);
  const banner = await env.DB.get("img:banner");
  if (banner) return tg(env, "sendPhoto", { chat_id: chat, photo: banner, caption: t.welcome(p), parse_mode: "HTML", ...mainMenu(t, p.promo) });
  return send(env, chat, t.welcome(p), mainMenu(t, p.promo));
}

async function onText(m, env) {
  const t = T[lang(m.from)];
  const chat = m.chat.id;
  const text = m.text.trim();
  const [cmdRaw, ...rest] = text.split(/\s+/);
  const cmd = cmdRaw.toLowerCase().replace(/@.*$/, "");
  const arg = text.slice(cmdRaw.length).trim();

  switch (cmd) {
    case "/start":
      if (arg === "free") return sendFree(env, chat, t);
      if (arg === "buy") return showTerms(env, chat, t, "");
      if (arg && isPromo(env, arg)) return applyPromo(env, chat, t, arg);
      return welcome(env, chat, t, "");
    case "/free":
      return sendFree(env, chat, t);
    case "/buy":
      return showTerms(env, chat, t, arg && isPromo(env, arg) ? arg : "");
    case "/promo":
      if (!arg) return send(env, chat, t.promoHow);
      return applyPromo(env, chat, t, arg);
    case "/terms":
      return send(env, chat, t.terms);
    case "/mypro":
      return myPro(env, m.from, chat, t);
    case "/about":
      return send(env, chat, t.about);
    case "/myid":
      return send(env, chat, `Your id: <code>${m.from.id}</code>`);
    case "/claim":
      return claimAdmin(env, m, arg);
    case "/support":
    case "/paysupport":
      if (!arg) return send(env, chat, cmd === "/paysupport" ? t.paysupport : t.support);
      return forwardToAdmin(env, m, cmd, arg, t);
  }
  if (await isAdmin(env, m.from.id)) return onAdminText(m, env, cmd, rest, arg);
  return welcome(env, chat, t, "");
}

async function applyPromo(env, chat, t, code) {
  if (!isPromo(env, code)) return send(env, chat, t.promoBad);
  const p = price(env, code);
  if (!p.promo) return send(env, chat, t.promoNoGain);
  return showTerms(env, chat, t, p.promo, t.promoOk(p) + "\n\n");
}

async function sendFree(env, chat, t) {
  if ((await getFileIds(env, "free"))[0]) await send(env, chat, t.sendingFree);
  await sendKit(env, chat, "free", t);
}

async function showTerms(env, chat, t, code, prefix = "") {
  const p = price(env, code);
  const rows = [[{ text: t.payStars(p), callback_data: "pay_stars" + codeSuffix(p.promo) }]];
  if (usdtOn(env)) rows.push([{ text: t.payUsdt(p), callback_data: "pay_usdt" + codeSuffix(p.promo) }]);
  await send(env, chat, prefix + t.terms + "\n\n" + priceLine(p, t === T.ru ? "ru" : "en") + "\n" + t.choosePay, kb(rows));
}

async function myPro(env, user, chat, t) {
  if (!(await getPurchase(env, user.id))) return send(env, chat, t.noPurchase);
  await send(env, chat, t.alreadyBought);
  await sendKit(env, chat, "pro", t);
}

async function forwardToAdmin(env, m, cmd, text, t) {
  const a = await adminChat(env);
  if (!a) return;
  const who = m.from.username ? "@" + m.from.username : m.from.first_name || "";
  const bought = await getPurchase(env, m.from.id);
  await send(env, a,
    `${cmd === "/paysupport" ? "🧾 PAYSUPPORT" : "💬 SUPPORT"} от ${escapeHtml(who)} (<code>${m.from.id}</code>)` +
    (bought ? `\nPRO куплен: ${bought.method} ${escapeHtml(bought.amount)}, ref <code>${escapeHtml(bought.ref)}</code>` : "") +
    `\n\n${escapeHtml(text)}\n\nОтветить: <code>/reply ${m.from.id} текст</code>`);
  await send(env, m.chat.id, t.sentToAuthor);
}

async function onCallback(q, env) {
  const t = T[lang(q.from)];
  const chat = q.message ? q.message.chat.id : q.from.id;
  await tg(env, "answerCallbackQuery", { callback_query_id: q.id });
  const [d, code = ""] = String(q.data || "").split(":");
  if (d === "free") return sendFree(env, chat, t);
  if (d === "buy") return showTerms(env, chat, t, code);
  if (d === "mypro") return myPro(env, q.from, chat, t);
  if (d === "about") return send(env, chat, t.about);
  if (d === "support") return send(env, chat, t.support);
  if (d === "pay_stars") {
    const p = price(env, code);
    return tg(env, "sendInvoice", {
      chat_id: chat,
      title: "IS-1 PRO",
      description: t.invoiceDesc,
      payload: `pro:${p.stars}:${p.promo}`,
      provider_token: "",
      currency: "XTR",
      prices: [{ label: "IS-1 PRO", amount: p.stars }],
    });
  }
  if (d === "pay_usdt") {
    if (!usdtOn(env)) return send(env, chat, t.usdtOff);
    const p = price(env, code);
    const r = await cryptoPay(env, "createInvoice", {
      asset: "USDT",
      amount: p.usd,
      description: t.invoiceDesc.slice(0, 1000),
      payload: `pro:${q.from.id}:${chat}:${lang(q.from)}:${p.promo}`,
      expires_in: 3600,
    });
    if (!r.ok) return send(env, chat, t.usdtOff);
    const inv = r.result;
    const payUrl = inv.bot_invoice_url || inv.pay_url || inv.mini_app_invoice_url;
    return send(env, chat, t.usdtInvoice, kb([
      [{ text: t.btnOpenInvoice, url: payUrl }],
      [{ text: t.btnCheck, callback_data: "chk:" + inv.invoice_id }],
    ]));
  }
  if (d === "chk") {
    const r = await cryptoPay(env, "getInvoices", { invoice_ids: code });
    const inv = r.ok && r.result && r.result.items && r.result.items[0];
    if (!(await deliverIfInvoicePaid(env, inv, q.from.id))) return send(env, chat, t.notPaidYet);
  }
}

async function onPreCheckout(q, env) {
  const t = T[lang(q.from)];
  const [tag, , code = ""] = String(q.invoice_payload).split(":");
  const p = price(env, code);
  const haveFiles = !!(await getFileIds(env, "pro"))[0];
  const ok = q.currency === "XTR" && tag === "pro" && q.total_amount === p.stars && haveFiles;
  return tg(env, "answerPreCheckoutQuery", ok
    ? { pre_checkout_query_id: q.id, ok: true }
    : { pre_checkout_query_id: q.id, ok: false, error_message: haveFiles ? t.priceChanged : t.notReady });
}

async function onPaid(m, env) {
  const p = m.successful_payment;
  if (p.currency !== "XTR" || !String(p.invoice_payload).startsWith("pro:")) return;
  const promo = String(p.invoice_payload).split(":")[2] || "";
  await completePurchase(env, m.from, m.chat.id, "stars", p.telegram_payment_charge_id, p.total_amount, promo);
}

// ------------------------------------------------------------------ admin

async function claimAdmin(env, m, secret) {
  if (String(env.ADMIN_ID || "").trim() || (await env.DB.get("admin"))) return send(env, m.chat.id, "Администратор уже назначен.");
  if (!env.WEBHOOK_SECRET || secret !== env.WEBHOOK_SECRET) return send(env, m.chat.id, "Неверный ключ.");
  await env.DB.put("admin", String(m.from.id));
  return send(env, m.chat.id,
    "✅ Вы администратор бота.\n\nТеперь отправьте боту <b>как файлы</b> 8 документов из папок «IS-1 FREE 0.4.0» и «IS-1 PRO 0.4.0» " +
    "и 2 картинки из папки «IS-1 Telegram Bot/branding»: IS-1_bot_banner.jpg и IS-1_bot_avatar.jpg.\nСписок команд: /admin");
}

/** Download a file the bot received and return its bytes. */
async function downloadFile(env, fileId) {
  const f = await tg(env, "getFile", { file_id: fileId });
  if (!f.ok) return null;
  const r = await fetch(`https://api.telegram.org/file/bot${env.BOT_TOKEN}/${f.result.file_path}`);
  return r.ok ? new Uint8Array(await r.arrayBuffer()) : null;
}

async function onAdminDocument(m, env) {
  const name = m.document.file_name || "";
  if (IMAGES[name]) return onAdminImage(m, env, IMAGES[name]);
  const key = FILES[name];
  if (!key) {
    return send(env, m.chat.id,
      `Не знаю файл <code>${escapeHtml(name)}</code>. Ожидаю одно из:\n` + [...Object.keys(FILES), ...Object.keys(IMAGES)].map((f) => "• " + f).join("\n"));
  }
  await env.DB.put("file:" + key, m.document.file_id);
  return send(env, m.chat.id, `✅ ${escapeHtml(name)} сохранён.` + (await progress(env)));
}

async function progress(env) {
  const need = [...Object.values(FILES).map((k) => "file:" + k), ...Object.values(IMAGES).map((k) => "img:" + k)];
  const have = await Promise.all(need.map((k) => env.DB.get(k)));
  const left = have.filter((v) => !v).length;
  return left ? `\nОсталось: ${left}` : "\nВсё на месте 🎉 Бот готов к продажам.";
}

async function onAdminImage(m, env, kind) {
  const bytes = await downloadFile(env, m.document.file_id);
  if (!bytes) return send(env, m.chat.id, "Не удалось скачать картинку, пришлите ещё раз.");
  const blob = new Blob([bytes], { type: "image/jpeg" });
  if (kind === "banner") {
    // re-upload as a photo once to get a photo file_id for welcome messages
    const form = new FormData();
    form.append("chat_id", String(m.chat.id));
    form.append("caption", "Баннер для приветствия ✅");
    form.append("photo", blob, "banner.jpg");
    const r = await tgForm(env, "sendPhoto", form);
    if (!r.ok) return send(env, m.chat.id, "Не удалось сохранить баннер ❌");
    const sizes = r.result.photo || [];
    await env.DB.put("img:banner", sizes[sizes.length - 1].file_id);
  } else {
    const form = new FormData();
    form.append("photo", JSON.stringify({ type: "static", photo: "attach://avatar" }));
    form.append("avatar", blob, "avatar.jpg");
    const r = await tgForm(env, "setMyProfilePhoto", form);
    if (!r.ok) return send(env, m.chat.id, "Не удалось поставить аватар ❌ Поставьте его в @BotFather → /setuserpic.");
    await env.DB.put("img:avatar", "set");
  }
  return send(env, m.chat.id, `✅ ${kind === "banner" ? "Баннер" : "Аватар бота"} сохранён.` + (await progress(env)));
}

async function onAdminText(m, env, cmd, rest, arg) {
  const chat = m.chat.id;
  if (cmd === "/admin") {
    const p = price(env, ""), pr = promoTable(env), me = (await env.DB.get("me")) || "ваш_бот";
    return send(env, chat,
      `<b>Команды автора</b>\n` +
      `• пришлите файл из папки релиза — бот запомнит его\n` +
      `/files — какие файлы загружены\n` +
      `/sales — последние продажи\n` +
      `/reply id текст — ответить покупателю\n` +
      `/refund id — вернуть Stars покупателю\n` +
      `/grant id — выдать PRO вручную\n\n` +
      `<b>Цены сейчас</b>\n${priceLine(p, "ru")}\n` +
      Object.keys(pr).map((c) => `🎟 ${escapeHtml(c)}: $${fmt(pr[c])} · ${price(env, c).stars} ⭐ · ссылка t.me/${escapeHtml(me)}?start=${escapeHtml(c)}`).join("\n"));
  }
  if (cmd === "/files") {
    const rows = await Promise.all([
      ...Object.entries(FILES).map(async ([f, k]) => ((await env.DB.get("file:" + k)) ? "✅ " : "❌ ") + f),
      ...Object.entries(IMAGES).map(async ([f, k]) => ((await env.DB.get("img:" + k)) ? "✅ " : "❌ ") + f),
    ]);
    return send(env, chat, rows.join("\n"));
  }
  if (cmd === "/sales") {
    const list = await env.DB.list({ prefix: "buyer:", limit: 1000 });
    const recs = (await Promise.all(list.keys.slice(-20).map((k) => env.DB.get(k.name)))).filter(Boolean).map((v) => JSON.parse(v));
    return send(env, chat,
      `Покупателей: ${list.keys.length}${list.list_complete ? "" : "+"}\n` +
      recs.map((r) => `${r.date.slice(0, 10)} · ${r.method} ${escapeHtml(r.amount)}${r.promo ? " · " + escapeHtml(r.promo) : ""} · ${r.username ? "@" + escapeHtml(r.username) : ""} <code>${r.user_id}</code>`).join("\n") +
      (recs.length ? "" : "пока нет"));
  }
  if (cmd === "/reply" && rest.length >= 2) {
    const to = rest[0];
    const r = await send(env, to, `💬 Ответ автора IS-1:\n\n${escapeHtml(arg.slice(to.length).trim())}`);
    return send(env, chat, r.ok ? "Отправлено ✅" : "Не получилось отправить ❌");
  }
  if (cmd === "/refund" && rest[0]) {
    const rec = await getPurchase(env, rest[0]);
    if (!rec || rec.method !== "stars") return send(env, chat, "Покупка за Stars не найдена (USDT возвращайте вручную в @CryptoBot).");
    const r = await tg(env, "refundStarPayment", { user_id: Number(rest[0]), telegram_payment_charge_id: rec.ref });
    if (r.ok) await env.DB.delete("buyer:" + rest[0]);
    return send(env, chat, r.ok ? "Stars возвращены ✅, доступ к /mypro снят." : "Ошибка возврата: " + escapeHtml(r.description || ""));
  }
  if (cmd === "/grant" && /^\d+$/.test(rest[0] || "")) {
    const uid = Number(rest[0]);
    await completePurchase(env, { id: uid, language_code: "ru" }, uid, "manual", "grant-" + uid + "-" + Date.now(), 0);
    return send(env, chat, "Выдано ✅");
  }
  return send(env, chat, "Команды автора: /admin");
}

// ------------------------------------------------------------------ setup (run once from the browser)

async function setup(request, env) {
  const url = new URL(request.url);
  if (!env.WEBHOOK_SECRET || url.searchParams.get("key") !== env.WEBHOOK_SECRET) return new Response("forbidden", { status: 403 });
  const out = {};
  out.webhook = await tg(env, "setWebhook", {
    url: `${url.origin}/telegram`,
    secret_token: env.WEBHOOK_SECRET,
    allowed_updates: ["message", "callback_query", "pre_checkout_query"],
    drop_pending_updates: true,
  });
  const cmds = {
    ru: [["start", "Меню"], ["free", "Скачать IS-1 FREE"], ["buy", "Купить IS-1 PRO"], ["promo", "Ввести промокод"],
         ["mypro", "Получить купленный PRO ещё раз"], ["terms", "Условия покупки"], ["paysupport", "Помощь с оплатой"],
         ["support", "Написать автору"], ["about", "О программе и исходный код"]],
    en: [["start", "Menu"], ["free", "Download IS-1 FREE"], ["buy", "Buy IS-1 PRO"], ["promo", "Enter a promo code"],
         ["mypro", "Get your PRO again"], ["terms", "Purchase terms"], ["paysupport", "Payment help"],
         ["support", "Contact the author"], ["about", "About and source code"]],
  };
  for (const [lc, list] of Object.entries(cmds)) {
    const commands = list.map(([command, description]) => ({ command, description }));
    out["commands_" + lc] = await tg(env, "setMyCommands", lc === "en" ? { commands } : { commands, language_code: lc });
  }
  out.desc_ru = await tg(env, "setMyDescription", { language_code: "ru",
    description: "🎛 IS-1 — семплер, секвенсор и KAOSS-эффекты для Android от SALYCH MUSIC. Скачайте бесплатную IS-1 FREE или купите IS-1 PRO с Ableton Link, 64 падами и 50 эффектами." });
  out.desc_en = await tg(env, "setMyDescription", {
    description: "🎛 IS-1 — a sampler, sequencer and KAOSS effects for Android by SALYCH MUSIC. Download IS-1 FREE for free or buy IS-1 PRO with Ableton Link, 64 pads and 50 effects." });
  out.short_ru = await tg(env, "setMyShortDescription", { language_code: "ru", short_description: "Семплер IS-1 для Android от SALYCH MUSIC: FREE бесплатно, PRO за ⭐" });
  out.short_en = await tg(env, "setMyShortDescription", { short_description: "IS-1 sampler for Android by SALYCH MUSIC: FREE for free, PRO for ⭐" });
  const me = await tg(env, "getMe", {});
  const ok = Object.values(out).every((r) => r && r.ok);
  const user = me.ok ? me.result.username : "";
  if (user) await env.DB.put("me", user);
  return new Response(
    (ok ? `✅ Bot is set up: https://t.me/${user}\n\nNext, in Telegram send to the bot:\n/claim <your WEBHOOK_SECRET>\n\n` : "⚠️ Something failed:\n\n") +
    JSON.stringify(out, null, 2),
    { headers: { "content-type": "text/plain; charset=utf-8" } });
}

// ------------------------------------------------------------------ entry

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "POST" && url.pathname === "/telegram") {
      if (!env.WEBHOOK_SECRET || request.headers.get("x-telegram-bot-api-secret-token") !== env.WEBHOOK_SECRET) {
        return new Response("forbidden", { status: 403 });
      }
      const upd = await request.json();
      try {
        await handleUpdate(upd, env);
      } catch (e) {
        console.log("update error:", e && e.stack ? e.stack : String(e));
      }
      return new Response("ok");
    }
    if (request.method === "POST" && url.pathname === "/cryptopay") return handleCryptoWebhook(request, env);
    if (url.pathname === "/setup") return setup(request, env);
    return new Response("IS-1 bot is running.");
  },
};
