// IS-1 Telegram bot — Cloudflare Worker (free plan).
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC).
//
// What it does:
//   * IS-1 FREE: free download (APK + manuals) for everyone.
//   * IS-1 PRO: sold for Telegram Stars and (optionally) USDT via @CryptoBot (Crypto Pay).
//     After payment the bot sends the PRO files; buyers can get them again with /mypro.
//   * Files live in Telegram itself: the admin sends them to the bot once, the bot stores their file_id.
//
// Environment (Cloudflare → Worker → Settings → Variables and Secrets):
//   BOT_TOKEN         secret  token from @BotFather
//   WEBHOOK_SECRET    secret  any random string of A–Z a–z 0–9 _ - (16+ chars); also protects /setup
//   ADMIN_ID          text    your numeric Telegram id (send /myid to the bot)
//   PRICE_STARS       text    PRO price in Stars, e.g. 750
//   PRICE_USDT        text    PRO price in USDT, e.g. 9.99   (optional)
//   CRYPTO_PAY_TOKEN  secret  token of your app in @CryptoBot → Crypto Pay (optional; enables USDT)
// KV binding: DB  (Cloudflare → Storage → KV → create namespace → bind to the Worker as DB)

const SOURCE_URL = "https://github.com/SalychViet/Instant-Sampler-IS-1";
const VERSION = "0.4.0";

// Which file goes where. Admin sends documents with exactly these names (as in the release folders).
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
const KIT = {
  free: ["free_apk", "free_install", "free_manual_ru", "free_manual_en"],
  pro: ["pro_apk", "pro_install", "pro_manual_ru", "pro_manual_en"],
};

// ------------------------------------------------------------------ texts (RU / EN)

const T = {
  ru: {
    welcome: (stars, usdt) =>
      `🎛 <b>IS-1</b> — семплер, секвенсор и KAOSS-эффекты для Android (10+).\n\n` +
      `<b>IS-1 FREE</b> — бесплатно: 16 падов, запись семплов, секвенсор, 25 эффектов.\n` +
      `<b>IS-1 PRO</b> — 64 пада, все 50 эффектов, Ableton Link, экспорт WAV/MP3 320, проекты и стартовый проект с сэмплами.\n\n` +
      `Цена PRO: <b>${stars} ⭐</b>` + (usdt ? ` или <b>${usdt} USDT</b>` : "") + `.\n\n` +
      `IS-1 — свободная программа (GNU GPL v3), исходный код: ${SOURCE_URL}`,
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
      `• Файлы приходят сюда сразу после оплаты; повторно получить их можно командой /mypro.\n` +
      `• Перед покупкой попробуйте бесплатную IS-1 FREE — она работает на том же движке.\n` +
      `• Если PRO не запускается на вашем устройстве, напишите в /paysupport в течение 14 дней — вернём оплату.\n` +
      `• Приложение распространяется по лицензии GNU GPL v3; исходный код: ${SOURCE_URL}\n` +
      `• Продавец: Salavat (SALYCH MUSIC).`,
    choosePay: "Нажимая кнопку оплаты, вы соглашаетесь с условиями выше. Как удобнее оплатить?",
    payStars: (n) => `⭐ Оплатить ${n} Stars`,
    payUsdt: (n) => `💵 Оплатить ${n} USDT`,
    invoiceTitle: "IS-1 PRO",
    invoiceDesc: `IS-1 PRO ${VERSION}: 64 пада, 50 эффектов KAOSS, Ableton Link, WAV/MP3 320, проекты. APK для Android 10+ и руководства.`,
    usdtInvoice: (url) => `Счёт в USDT создан. Оплатите в @CryptoBot по кнопке ниже, затем нажмите «Проверить оплату».`,
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
  },
  en: {
    welcome: (stars, usdt) =>
      `🎛 <b>IS-1</b> — a sampler, sequencer and KAOSS effects for Android (10+).\n\n` +
      `<b>IS-1 FREE</b> — free: 16 pads, sample recording, sequencer, 25 effects.\n` +
      `<b>IS-1 PRO</b> — 64 pads, all 50 effects, Ableton Link, WAV/MP3 320 export, projects and a starter project with samples.\n\n` +
      `PRO price: <b>${stars} ⭐</b>` + (usdt ? ` or <b>${usdt} USDT</b>` : "") + `.\n\n` +
      `IS-1 is free software (GNU GPL v3), source code: ${SOURCE_URL}`,
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
      `• The app is licensed under the GNU GPL v3; source code: ${SOURCE_URL}\n` +
      `• Seller: Salavat (SALYCH MUSIC).`,
    choosePay: "By tapping a payment button you agree to the terms above. How would you like to pay?",
    payStars: (n) => `⭐ Pay ${n} Stars`,
    payUsdt: (n) => `💵 Pay ${n} USDT`,
    invoiceTitle: "IS-1 PRO",
    invoiceDesc: `IS-1 PRO ${VERSION}: 64 pads, 50 KAOSS effects, Ableton Link, WAV/MP3 320, projects. APK for Android 10+ and manuals.`,
    usdtInvoice: (url) => `USDT invoice created. Pay in @CryptoBot with the button below, then tap “Check payment”.`,
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

const send = (env, chat_id, text, extra = {}) =>
  tg(env, "sendMessage", { chat_id, text, parse_mode: "HTML", disable_web_page_preview: true, ...extra });

function kb(rows) {
  return { reply_markup: { inline_keyboard: rows } };
}

function mainMenu(t) {
  return kb([
    [{ text: t.btnFree, callback_data: "free" }],
    [{ text: t.btnBuy, callback_data: "buy" }],
    [{ text: t.btnMyPro, callback_data: "mypro" }, { text: t.btnAbout, callback_data: "about" }],
    [{ text: t.btnSupport, callback_data: "support" }],
  ]);
}

// ------------------------------------------------------------------ storage helpers

const isAdmin = (env, id) => String(id) === String(env.ADMIN_ID || "").trim();
const priceStars = (env) => Math.max(1, parseInt(env.PRICE_STARS || "750", 10) || 750);
function priceUsdt(env) {
  const v = parseFloat(env.PRICE_USDT || "");
  return env.CRYPTO_PAY_TOKEN && v > 0 ? v.toFixed(2) : null;
}

async function getFileIds(env, kind) {
  const ids = await Promise.all(KIT[kind].map((k) => env.DB.get("file:" + k)));
  return ids;
}

async function sendKit(env, chatId, kind, t) {
  const ids = await getFileIds(env, kind);
  const docs = ids.filter(Boolean);
  if (!ids[0]) {
    // no APK registered yet
    await send(env, chatId, t.notReady);
    if (env.ADMIN_ID) await send(env, env.ADMIN_ID, `⚠️ Нет файла ${KIT[kind][0]} — пришлите его боту.`);
    return false;
  }
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
async function completePurchase(env, user, chatId, method, ref, amount, resendIfDone = true) {
  const t = T[lang(user)];
  const refKey = "paid:" + method + ":" + ref;
  if (await env.DB.get(refKey)) {
    // already processed: a webhook retry stays silent, a user's repeated tap gets the files again
    if (resendIfDone) await sendKit(env, chatId, "pro", t);
    return;
  }
  const rec = { user_id: user.id, username: user.username || "", method, ref: String(ref), amount: String(amount), date: new Date().toISOString() };
  await env.DB.put(refKey, JSON.stringify(rec));
  await env.DB.put("buyer:" + user.id, JSON.stringify(rec));
  await send(env, chatId, t.thanks);
  await sendKit(env, chatId, "pro", t);
  if (env.ADMIN_ID) {
    const who = user.username ? "@" + user.username : user.first_name || "";
    await send(env, env.ADMIN_ID,
      `💰 Продажа IS-1 PRO: ${amount} ${method === "stars" ? "⭐" : "USDT"} от ${escapeHtml(who)} (<code>${user.id}</code>)\nref: <code>${escapeHtml(String(ref))}</code>`);
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
  const expected = await hmacHex(secret, raw);
  if (!safeEqual(expected, request.headers.get("crypto-pay-api-signature") || "")) {
    return new Response("bad signature", { status: 401 });
  }
  const upd = JSON.parse(raw);
  if (upd.update_type === "invoice_paid" && upd.payload) await deliverIfInvoicePaid(env, upd.payload, null, false);
  return new Response("ok");
}

/** inv: Crypto Pay Invoice object. expectUserId: when called from the user's "check" button. */
async function deliverIfInvoicePaid(env, inv, expectUserId, resendIfDone = true) {
  // Invoices can only be created with our Crypto Pay token, i.e. by this bot, at the price of that moment.
  if (!inv || inv.status !== "paid" || inv.asset !== "USDT") return false;
  const p = String(inv.payload || "").split(":"); // pro:<userId>:<chatId>:<lang>
  if (p[0] !== "pro" || !/^\d+$/.test(p[1] || "")) return false;
  const userId = Number(p[1]);
  if (expectUserId && userId !== expectUserId) return false;
  const user = { id: userId, language_code: p[3] === "ru" ? "ru" : "en" };
  await completePurchase(env, user, Number(p[2] || userId), "usdt", inv.invoice_id, inv.amount, resendIfDone);
  return true;
}

// ------------------------------------------------------------------ Telegram update handling

async function handleUpdate(upd, env) {
  if (upd.pre_checkout_query) return onPreCheckout(upd.pre_checkout_query, env);
  if (upd.callback_query) return onCallback(upd.callback_query, env);
  const m = upd.message;
  if (!m || !m.from) return;
  if (m.successful_payment) return onPaid(m, env);
  if (m.document && isAdmin(env, m.from.id)) return onAdminDocument(m, env);
  if (typeof m.text === "string") return onText(m, env);
}

async function onText(m, env) {
  const t = T[lang(m.from)];
  const chat = m.chat.id;
  const [cmdRaw, ...rest] = m.text.trim().split(/\s+/);
  const cmd = cmdRaw.toLowerCase().replace(/@.*$/, "");
  const arg = m.text.trim().slice(cmdRaw.length).trim();

  switch (cmd) {
    case "/start":
      if (arg === "free") return sendFree(env, chat, t);
      if (arg === "buy") return showTerms(env, chat, t);
      return send(env, chat, t.welcome(priceStars(env), priceUsdt(env)), mainMenu(t));
    case "/free":
      return sendFree(env, chat, t);
    case "/buy":
      return showTerms(env, chat, t);
    case "/terms":
      return send(env, chat, t.terms);
    case "/mypro":
      return myPro(env, m.from, chat, t);
    case "/about":
      return send(env, chat, t.about);
    case "/myid":
      return send(env, chat, `Your id: <code>${m.from.id}</code>`);
    case "/support":
    case "/paysupport":
      if (!arg) return send(env, chat, cmd === "/paysupport" ? t.paysupport : t.support);
      return forwardToAdmin(env, m, cmd, arg, t);
  }
  if (isAdmin(env, m.from.id)) return onAdminText(m, env, cmd, rest, arg);
  return send(env, chat, t.welcome(priceStars(env), priceUsdt(env)), mainMenu(t));
}

async function sendFree(env, chat, t) {
  if ((await getFileIds(env, "free"))[0]) await send(env, chat, t.sendingFree);
  await sendKit(env, chat, "free", t);
}

async function showTerms(env, chat, t) {
  const rows = [[{ text: t.payStars(priceStars(env)), callback_data: "pay_stars" }]];
  const usdt = priceUsdt(env);
  if (usdt) rows.push([{ text: t.payUsdt(usdt), callback_data: "pay_usdt" }]);
  await send(env, chat, t.terms + "\n\n" + t.choosePay, kb(rows));
}

async function myPro(env, user, chat, t) {
  if (!(await getPurchase(env, user.id))) return send(env, chat, t.noPurchase);
  await send(env, chat, t.alreadyBought);
  await sendKit(env, chat, "pro", t);
}

async function forwardToAdmin(env, m, cmd, text, t) {
  if (!env.ADMIN_ID) return;
  const who = m.from.username ? "@" + m.from.username : m.from.first_name || "";
  const bought = await getPurchase(env, m.from.id);
  await send(env, env.ADMIN_ID,
    `${cmd === "/paysupport" ? "🧾 PAYSUPPORT" : "💬 SUPPORT"} от ${escapeHtml(who)} (<code>${m.from.id}</code>)` +
    (bought ? `\nPRO куплен: ${bought.method} ${escapeHtml(bought.amount)}, ref <code>${escapeHtml(bought.ref)}</code>` : "") +
    `\n\n${escapeHtml(text)}\n\nОтветить: <code>/reply ${m.from.id} текст</code>`);
  await send(env, m.chat.id, t.sentToAuthor);
}

async function onCallback(q, env) {
  const t = T[lang(q.from)];
  const chat = q.message ? q.message.chat.id : q.from.id;
  await tg(env, "answerCallbackQuery", { callback_query_id: q.id });
  const d = q.data || "";
  if (d === "free") return sendFree(env, chat, t);
  if (d === "buy") return showTerms(env, chat, t);
  if (d === "mypro") return myPro(env, q.from, chat, t);
  if (d === "about") return send(env, chat, t.about);
  if (d === "support") return send(env, chat, t.support);
  if (d === "pay_stars") {
    const n = priceStars(env);
    return tg(env, "sendInvoice", {
      chat_id: chat,
      title: t.invoiceTitle,
      description: t.invoiceDesc,
      payload: `pro:${n}`,
      provider_token: "",
      currency: "XTR",
      prices: [{ label: "IS-1 PRO", amount: n }],
    });
  }
  if (d === "pay_usdt") {
    const amount = priceUsdt(env);
    if (!amount) return send(env, chat, t.usdtOff);
    const r = await cryptoPay(env, "createInvoice", {
      asset: "USDT",
      amount,
      description: t.invoiceDesc.slice(0, 1000),
      payload: `pro:${q.from.id}:${chat}:${lang(q.from)}`,
      expires_in: 3600,
    });
    if (!r.ok) return send(env, chat, t.usdtOff);
    const inv = r.result;
    const payUrl = inv.bot_invoice_url || inv.pay_url || inv.mini_app_invoice_url;
    return send(env, chat, t.usdtInvoice(payUrl), kb([
      [{ text: t.btnOpenInvoice, url: payUrl }],
      [{ text: t.btnCheck, callback_data: "chk:" + inv.invoice_id }],
    ]));
  }
  if (d.startsWith("chk:")) {
    const id = d.slice(4);
    const r = await cryptoPay(env, "getInvoices", { invoice_ids: id });
    const inv = r.ok && r.result && r.result.items && r.result.items[0];
    if (!(await deliverIfInvoicePaid(env, inv, q.from.id))) return send(env, chat, t.notPaidYet);
  }
}

async function onPreCheckout(q, env) {
  const t = T[lang(q.from)];
  const ok = q.currency === "XTR" && q.invoice_payload === `pro:${q.total_amount}` &&
             q.total_amount === priceStars(env) && (await getFileIds(env, "pro"))[0];
  return tg(env, "answerPreCheckoutQuery", ok
    ? { pre_checkout_query_id: q.id, ok: true }
    : { pre_checkout_query_id: q.id, ok: false, error_message: (await getFileIds(env, "pro"))[0] ? t.priceChanged : t.notReady });
}

async function onPaid(m, env) {
  const p = m.successful_payment;
  if (p.currency !== "XTR" || !String(p.invoice_payload).startsWith("pro:")) return;
  await completePurchase(env, m.from, m.chat.id, "stars", p.telegram_payment_charge_id, p.total_amount);
}

// ------------------------------------------------------------------ admin

async function onAdminDocument(m, env) {
  const name = m.document.file_name || "";
  const key = FILES[name];
  if (!key) {
    return send(env, m.chat.id,
      `Не знаю файл <code>${escapeHtml(name)}</code>. Ожидаю одно из:\n` + Object.keys(FILES).map((f) => "• " + f).join("\n"));
  }
  await env.DB.put("file:" + key, m.document.file_id);
  const missing = Object.entries(FILES).filter(([, k]) => k !== key);
  const have = await Promise.all(missing.map(([, k]) => env.DB.get("file:" + k)));
  const left = missing.filter((_, i) => !have[i]).map(([f]) => f);
  return send(env, m.chat.id, `✅ ${escapeHtml(name)} сохранён.` + (left.length ? `\nОсталось: ${left.length}` : "\nВсе файлы на месте 🎉"));
}

async function onAdminText(m, env, cmd, rest, arg) {
  const chat = m.chat.id;
  if (cmd === "/admin") {
    return send(env, chat,
      `<b>Команды автора</b>\n` +
      `• пришлите файл из папки релиза — бот запомнит его\n` +
      `/files — какие файлы загружены\n` +
      `/sales — последние продажи\n` +
      `/reply id текст — ответить покупателю\n` +
      `/refund id — вернуть Stars покупателю\n` +
      `/grant id — выдать PRO вручную (например, после оплаты другим способом)`);
  }
  if (cmd === "/files") {
    const rows = await Promise.all(Object.entries(FILES).map(async ([f, k]) => ((await env.DB.get("file:" + k)) ? "✅ " : "❌ ") + f));
    return send(env, chat, rows.join("\n"));
  }
  if (cmd === "/sales") {
    const list = await env.DB.list({ prefix: "buyer:", limit: 1000 });
    const recs = (await Promise.all(list.keys.slice(-20).map((k) => env.DB.get(k.name)))).filter(Boolean).map((v) => JSON.parse(v));
    return send(env, chat,
      `Покупателей: ${list.keys.length}${list.list_complete ? "" : "+"}\n` +
      recs.map((r) => `${r.date.slice(0, 10)} · ${r.method} ${escapeHtml(r.amount)} · ${r.username ? "@" + escapeHtml(r.username) : ""} <code>${r.user_id}</code>`).join("\n") +
      (recs.length ? "" : "пока нет"));
  }
  if (cmd === "/reply" && rest.length >= 2) {
    const to = rest[0];
    const text = arg.slice(to.length).trim();
    const r = await send(env, to, `💬 Ответ автора IS-1:\n\n${escapeHtml(text)}`);
    return send(env, chat, r.ok ? "Отправлено ✅" : "Не получилось отправить ❌");
  }
  if (cmd === "/refund" && rest[0]) {
    const rec = await getPurchase(env, rest[0]);
    if (!rec || rec.method !== "stars") return send(env, chat, "Покупка за Stars не найдена (USDT возвращайте вручную в @CryptoBot).");
    const r = await tg(env, "refundStarPayment", { user_id: Number(rest[0]), telegram_payment_charge_id: rec.ref });
    if (r.ok) await env.DB.delete("buyer:" + rest[0]);
    return send(env, chat, r.ok ? "Stars возвращены ✅, доступ к /mypro снят." : "Ошибка возврата: " + escapeHtml(r.description || ""));
  }
  if (cmd === "/grant" && rest[0]) {
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
    ru: [["start", "Меню"], ["free", "Скачать IS-1 FREE"], ["buy", "Купить IS-1 PRO"], ["mypro", "Получить купленный PRO ещё раз"],
         ["terms", "Условия покупки"], ["paysupport", "Помощь с оплатой"], ["support", "Написать автору"], ["about", "О программе и исходный код"]],
    en: [["start", "Menu"], ["free", "Download IS-1 FREE"], ["buy", "Buy IS-1 PRO"], ["mypro", "Get your PRO again"],
         ["terms", "Purchase terms"], ["paysupport", "Payment help"], ["support", "Contact the author"], ["about", "About and source code"]],
  };
  for (const [lc, list] of Object.entries(cmds)) {
    const commands = list.map(([command, description]) => ({ command, description }));
    out["commands_" + lc] = await tg(env, "setMyCommands", lc === "en" ? { commands } : { commands, language_code: lc });
  }
  out.desc_ru = await tg(env, "setMyDescription", { language_code: "ru",
    description: "IS-1 — семплер, секвенсор и KAOSS-эффекты для Android. Скачайте бесплатную IS-1 FREE или купите IS-1 PRO за Stars." });
  out.desc_en = await tg(env, "setMyDescription", {
    description: "IS-1 — a sampler, sequencer and KAOSS effects for Android. Download IS-1 FREE for free or buy IS-1 PRO with Stars." });
  out.short_ru = await tg(env, "setMyShortDescription", { language_code: "ru", short_description: "Семплер IS-1 для Android: FREE бесплатно, PRO за ⭐" });
  out.short_en = await tg(env, "setMyShortDescription", { short_description: "IS-1 sampler for Android: FREE for free, PRO for ⭐" });
  const ok = Object.values(out).every((r) => r && r.ok);
  return new Response(
    (ok ? "✅ Bot is set up. Open it in Telegram and send /start.\n\n" : "⚠️ Something failed:\n\n") + JSON.stringify(out, null, 2),
    { headers: { "content-type": "text/plain; charset=utf-8" } });
}

// ------------------------------------------------------------------ entry

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (request.method === "POST" && url.pathname === "/telegram") {
      if (!env.WEBHOOK_SECRET || request.headers.get("x-telegram-bot-api-secret-token") !== env.WEBHOOK_SECRET) {
        return new Response("forbidden", { status: 403 });
      }
      const upd = await request.json();
      // pre_checkout must be answered within 10 s, so handle inline; everything else too (fast enough).
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
