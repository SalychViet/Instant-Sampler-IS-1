// ISL-1 Telegram bot — Cloudflare Worker (free plan).
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC).
//
// What it does:
//   * ISL-1 TRY: free 10-day version (APK + manuals) for everyone.
//   * ISL-1: sold for Telegram Stars (price in USD, converted automatically) and optionally USDT via @CryptoBot.
//     Holiday sale until a date, promo codes. After payment the bot sends the files; /mypro sends them again.
//   * "Хочу бесплатно!": a video review in Instagram for a free ISL-1. The person leaves their Instagram, the admin
//     approves or rejects the request, an approved person gets a one-time code (ISL-XXXX-XXXX) for /promo.
//   * Contact lists for the admin: downloaded TRY, got ISL-1 for a review, bought.
//   * Files and branding images live in Telegram itself: the admin sends them to the bot once.
//
// Secrets (Cloudflare → Worker → Settings → Variables and Secrets):
//   BOT_TOKEN         token from @BotFather
//   WEBHOOK_SECRET    random string A–Z a–z 0–9 _ - (16+ chars); protects the webhook, /setup and /claim
//   CRYPTO_PAY_TOKEN  (optional) token of your app in @CryptoBot → Crypto Pay; enables USDT
// Variables (wrangler.toml [vars], editable in the dashboard):
//   PRICE_USD        regular ISL-1 price, e.g. 9.99
//   SALE_PRICE_USD   sale price, e.g. 8.99 (empty = no sale)
//   SALE_UNTIL       end of the sale, ISO time, e.g. 2027-01-01T00:00:00+03:00
//   PROMO_CODES      code=price pairs, e.g. salychmusic=7.99,friend=5
//   STARS_PER_USD    conversion for Stars, e.g. 50 (≈ what buyers pay per Star)
//   ADMIN_ID         (optional) your Telegram id; or claim admin once with /claim <WEBHOOK_SECRET>
// KV binding: DB

const SOURCE_URL = "https://github.com/SalychViet/Instant-Sampler-IS-1";
const VERSION = "0.4.1";

const TRIAL_DAYS = 10;
const INSTAGRAM = "@salychmusic";

// Admin sends documents with exactly these names (the folder "ISL-1 Bot" on the Desktop).
const FILES = {
  "ISL-1_TRY_0.4.1.apk": "try_apk",
  "ISL-1_TRY_Install_RU.pdf": "try_install",
  "ISL-1_TRY_Install_EN.pdf": "try_install_en",
  "ISL-1_0.4.1.apk": "isl_apk",
  "ISL-1_Install_RU.pdf": "isl_install",
  "ISL-1_Install_EN.pdf": "isl_install_en",
  "ISL-1_Manual_RU.pdf": "isl_manual_ru",
  "ISL-1_Manual_EN.pdf": "isl_manual_en",
  "ISL-1_Looper_RU.pdf": "isl_looper_ru",
  "ISL-1_Looper_EN.pdf": "isl_looper_en",
};
// Branding images (kept out of the public repo): sent to the bot once as files.
const IMAGES = { "ISL-1_bot_banner.jpg": "isl_banner", "ISL-1_bot_avatar.jpg": "isl_avatar" };
const MANUALS = ["isl_manual_ru", "isl_manual_en", "isl_looper_ru", "isl_looper_en"];
const KIT = {
  try: ["try_apk", "try_install", "try_install_en", ...MANUALS],
  full: ["isl_apk", "isl_install", "isl_install_en", ...MANUALS],
};
const KIT_NAME = { try: "ISL-1 TRY", full: "ISL-1" };

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

/** Current ISL-1 price. code: promo code (any case) or empty. */
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
    s = ru ? `Цена ISL-1: <b>$${p.usd}</b> · <b>${p.stars} ⭐</b>` : `ISL-1 price: <b>$${p.usd}</b> · <b>${p.stars} ⭐</b>`;
  }
  return s;
}

const T = {
  ru: {
    welcome: (p) =>
      `🎛 <b>ISL-1</b> — семплер, секвенсор, лупер и KAOSS-эффекты для Android 10+.\n\n` +
      `🔁 Лупы прямо на падах, запись с микрофона, из телефона и из самого приложения, 64 пада, 50 эффектов, Ableton Link, экспорт WAV/MP3 320.\n\n` +
      `🚀 <b>ISL-1 TRY</b> — все функции бесплатно на ${TRIAL_DAYS} дней.\n` +
      `🎁 Снимите видеоотзыв в Instagram — и получите полную версию бесплатно.\n\n` +
      priceLine(p, "ru") + `\nЕсть промокод? Отправьте <code>/promo КОД</code>`,
    btnTry: `🚀 Скачать ISL-1 TRY (${TRIAL_DAYS} дней)`,
    btnBuy: "⭐ Купить ISL-1",
    btnWant: "🎁 Хочу бесплатно!",
    btnMyPro: "📦 Мой ISL-1",
    btnAbout: "ℹ️ О программе",
    btnSupport: "💬 Поддержка",
    sendingTry: "Отправляю ISL-1 TRY 👇 Инструкции и руководства — на русском и английском. Начните с инструкции по установке.",
    tryOffer:
      `⏳ <b>ISL-1 TRY работает ${TRIAL_DAYS} дней</b> с первого запуска, все функции открыты. Потом приложение перестанет работать.\n\n` +
      `🎁 <b>Полная версия бесплатно!</b> Если за эти ${TRIAL_DAYS} дней вы запишете видеоотзыв со своими впечатлениями в Instagram ` +
      `и отметите аккаунт <b>${INSTAGRAM}</b>, вы сможете получить полную версию бесплатно.\n` +
      `В кадре должно быть видно приложение, либо это может быть запись с экрана.\n\n` +
      `Записали? Нажмите кнопку ниже 👇`,
    wantAsk:
      `🎁 <b>Полная версия за видеоотзыв</b>\n\n` +
      `1. Запишите видеоотзыв со своими впечатлениями в Instagram: в кадре приложение или запись с экрана.\n` +
      `2. Отметьте аккаунт <b>${INSTAGRAM}</b>.\n` +
      `3. Отправьте сюда свой Instagram-аккаунт одним сообщением, например <code>@your_name</code>.\n\n` +
      `Автор проверит отзыв, и вам придёт одноразовый промокод.`,
    igBad: "Не похоже на Instagram-аккаунт 🤔 Отправьте его так: <code>@your_name</code>",
    wantSent: (ig) => `✅ Заявка принята, Instagram: <b>@${ig}</b>. Автор проверит отзыв, ответ придёт сюда.`,
    wantPending: "Ваша заявка уже у автора на проверке ⏳ Ответ придёт сюда.",
    wantHave: "У вас уже есть ISL-1 — отправить файлы ещё раз: /mypro",
    wantApproved: (code) => `У вас уже есть промокод: <code>${code}</code>\nОтправьте <code>/promo ${code}</code>`,
    reviewApproved: (code) =>
      `🎉 <b>Отзыв принят, спасибо!</b>\n\nВаш одноразовый промокод: <code>${code}</code>\n` +
      `Нажмите кнопку ниже или отправьте <code>/promo ${code}</code>`,
    reviewRejected: "😔 Автор не смог подтвердить отзыв. Проверьте, что видео опубликовано, в кадре видно приложение и отмечен " + INSTAGRAM + ", и подайте заявку ещё раз.",
    btnRedeem: "🎁 Получить ISL-1",
    reviewThanks: "🎉 Промокод принят! Вот ваш ISL-1 👇 Начните с инструкции по установке. Новую версию ставьте поверх: файлы всегда здесь — /mypro",
    codeUsed: "Этот промокод уже использован.",
    notReady: "Файлы ещё загружаются, попробуйте чуть позже 🙏",
    terms:
      `<b>Условия покупки ISL-1</b>\n\n` +
      `• Вы покупаете файл приложения ISL-1 ${VERSION} для Android 10+ и руководства (RU/EN).\n` +
      `• Файлы приходят сюда сразу после оплаты; повторно — командой /mypro.\n` +
      `• Перед покупкой попробуйте бесплатную ISL-1 TRY: все функции на ${TRIAL_DAYS} дней.\n` +
      `• Если ISL-1 не запускается на вашем устройстве, напишите в /paysupport в течение 14 дней — вернём оплату.\n` +
      `• Лицензия GNU GPL v3; исходный код: ${SOURCE_URL}\n` +
      `• Бот хранит ваш Telegram id, имя и username, чтобы выдавать покупку и вести статистику; эти данные никому не передаются.\n` +
      `• Продавец: Salavat (SALYCH MUSIC).`,
    choosePay: "Нажимая кнопку оплаты, вы соглашаетесь с условиями выше.",
    payStars: (p) => `⭐ Оплатить ${p.stars} Stars`,
    payUsdt: (p) => `💵 Оплатить ${p.usd} USDT`,
    invoiceDesc: `ISL-1 ${VERSION}: лупер на падах, 64 пада, 50 эффектов KAOSS, Ableton Link, WAV/MP3 320, проекты. APK для Android 10+ и руководства.`,
    usdtInvoice: "Счёт в USDT создан. Оплатите в @CryptoBot по кнопке ниже, затем нажмите «Проверить оплату».",
    btnOpenInvoice: "💵 Оплатить в CryptoBot",
    btnCheck: "✅ Проверить оплату",
    notPaidYet: "Оплата ещё не пришла. Если вы уже оплатили — подождите минуту и нажмите ещё раз.",
    usdtOff: "Оплата в USDT сейчас недоступна, воспользуйтесь Stars ⭐",
    thanks: "Спасибо за покупку! 🎉 Вот ваш ISL-1 👇 Начните с инструкции по установке.",
    alreadyBought: "У вас уже есть ISL-1 — отправляю файлы ещё раз 👇",
    noPurchase: "Покупок ISL-1 на этом аккаунте не найдено. Купить: /buy",
    about:
      `<b>ISL-1 ${VERSION}</b> · © 2026 Salavat (SALYCH MUSIC)\n` +
      `Свободная программа: GNU GPL v3 или новее. Исходный код:\n${SOURCE_URL}\n\n` +
      `Ableton Link — товарный знак Ableton AG; ISL-1 не связан с Ableton.`,
    support: "Напишите вопрос одним сообщением так:\n<code>/support ваш текст</code>\nи я передам его автору.",
    paysupport: "Проблема с оплатой или файлами? Напишите одним сообщением:\n<code>/paysupport ваш текст</code>\nУкажите, чем оплачивали. Автор ответит здесь.",
    sentToAuthor: "Сообщение отправлено автору ✅ Ответ придёт сюда.",
    priceChanged: "Цена изменилась, откройте покупку заново: /buy",
    promoHow: "Отправьте промокод так: <code>/promo КОД</code>",
    promoOk: (p) => `🎟 Промокод <b>${p.promo}</b> применён!\n` + priceLine(p, "ru"),
    promoBad: "Такого промокода нет 🤷 Проверьте написание.",
    promoNoGain: "Промокод действует, но сейчас цена и так ниже 🙂",
    btnFaq: "❓ Частые вопросы",
    btnMenu: "🏠 Меню",
    btnAskAuthor: "📨 Не помогло — спросить автора",
    btnAsk: "💬 Моего вопроса тут нет",
    faqTitle: "❓ <b>Частые вопросы</b>\nВыберите тему — или просто напишите вопрос своими словами, я постараюсь ответить сразу.",
    faqHelped: "Если ответ не помог — нажмите кнопку ниже, и я передам ваш вопрос автору.",
    faqNoMatch: "Готового ответа на это у меня нет 🤔\nПередать ваше сообщение автору? Он ответит здесь же.",
    askHow: "Напишите вопрос одним сообщением своими словами — я поищу ответ, а если не найду, предложу отправить его автору.",
    askExpired: "Не нашёл ваш вопрос — напишите его ещё раз одним сообщением.",
  },
  en: {
    welcome: (p) =>
      `🎛 <b>ISL-1</b> — a sampler, sequencer, looper and KAOSS effects for Android 10+.\n\n` +
      `🔁 Loops right on the pads, recording from the mic, the phone and the app itself, 64 pads, 50 effects, Ableton Link, WAV/MP3 320 export.\n\n` +
      `🚀 <b>ISL-1 TRY</b> — all features free for ${TRIAL_DAYS} days.\n` +
      `🎁 Post a video review on Instagram and get the full version for free.\n\n` +
      priceLine(p, "en") + `\nHave a promo code? Send <code>/promo CODE</code>`,
    btnTry: `🚀 Download ISL-1 TRY (${TRIAL_DAYS} days)`,
    btnBuy: "⭐ Buy ISL-1",
    btnWant: "🎁 I want it for free!",
    btnMyPro: "📦 My ISL-1",
    btnAbout: "ℹ️ About",
    btnSupport: "💬 Support",
    sendingTry: "Here is ISL-1 TRY 👇 The install guide and manuals are included in English and Russian.",
    tryOffer:
      `⏳ <b>ISL-1 TRY works for ${TRIAL_DAYS} days</b> from the first launch with all features on. After that the app stops working.\n\n` +
      `🎁 <b>Get the full version for free!</b> If within these ${TRIAL_DAYS} days you post a video review with your impressions on Instagram ` +
      `and tag <b>${INSTAGRAM}</b>, you can get the full version for free.\n` +
      `The app must be visible in the video, or it can be a screen recording.\n\n` +
      `Done? Tap the button below 👇`,
    wantAsk:
      `🎁 <b>Full version for a video review</b>\n\n` +
      `1. Post a video review with your impressions on Instagram: the app in frame, or a screen recording.\n` +
      `2. Tag <b>${INSTAGRAM}</b>.\n` +
      `3. Send your Instagram account here in one message, e.g. <code>@your_name</code>.\n\n` +
      `The author will check the review and you will get a one-time promo code.`,
    igBad: "That doesn't look like an Instagram account 🤔 Send it like this: <code>@your_name</code>",
    wantSent: (ig) => `✅ Request received, Instagram: <b>@${ig}</b>. The author will check the review; the answer will come here.`,
    wantPending: "Your request is already with the author ⏳ The answer will come here.",
    wantHave: "You already have ISL-1 — get the files again: /mypro",
    wantApproved: (code) => `You already have a promo code: <code>${code}</code>\nSend <code>/promo ${code}</code>`,
    reviewApproved: (code) =>
      `🎉 <b>Review accepted, thank you!</b>\n\nYour one-time promo code: <code>${code}</code>\n` +
      `Tap the button below or send <code>/promo ${code}</code>`,
    reviewRejected: "😔 The author could not confirm your review. Check that the video is published, the app is visible and " + INSTAGRAM + " is tagged, then apply again.",
    btnRedeem: "🎁 Get ISL-1",
    reviewThanks: "🎉 Code accepted! Here is your ISL-1 👇 Files are always here: /mypro",
    codeUsed: "This promo code has already been used.",
    notReady: "Files are still being uploaded, please try again a bit later 🙏",
    terms:
      `<b>ISL-1 purchase terms</b>\n\n` +
      `• You are buying the ISL-1 ${VERSION} app file for Android 10+ and the manuals (EN/RU).\n` +
      `• The files arrive here right after payment; get them again any time with /mypro.\n` +
      `• Please try the free ISL-1 TRY first: all features for ${TRIAL_DAYS} days.\n` +
      `• If ISL-1 does not run on your device, contact /paysupport within 14 days for a refund.\n` +
      `• Licensed under the GNU GPL v3; source code: ${SOURCE_URL}\n` +
      `• The bot stores your Telegram id, name and username to deliver your purchase and keep statistics; they are not shared with anyone.\n` +
      `• Seller: Salavat (SALYCH MUSIC).`,
    choosePay: "By tapping a payment button you agree to the terms above.",
    payStars: (p) => `⭐ Pay ${p.stars} Stars`,
    payUsdt: (p) => `💵 Pay ${p.usd} USDT`,
    invoiceDesc: `ISL-1 ${VERSION}: looper on pads, 64 pads, 50 KAOSS effects, Ableton Link, WAV/MP3 320, projects. APK for Android 10+ and manuals.`,
    usdtInvoice: "USDT invoice created. Pay in @CryptoBot with the button below, then tap “Check payment”.",
    btnOpenInvoice: "💵 Pay in CryptoBot",
    btnCheck: "✅ Check payment",
    notPaidYet: "Payment has not arrived yet. If you have paid, wait a minute and tap again.",
    usdtOff: "USDT payment is not available right now, please use Stars ⭐",
    thanks: "Thank you for your purchase! 🎉 Here is your ISL-1 👇 Start with the install guide.",
    alreadyBought: "You already own ISL-1 — sending the files again 👇",
    noPurchase: "No ISL-1 purchase found for this account. Buy: /buy",
    about:
      `<b>ISL-1 ${VERSION}</b> · © 2026 Salavat (SALYCH MUSIC)\n` +
      `Free software: GNU GPL v3 or later. Source code:\n${SOURCE_URL}\n\n` +
      `Ableton Link is a trademark of Ableton AG; ISL-1 is not affiliated with Ableton.`,
    support: "Send your question in one message like this:\n<code>/support your text</code>\nand I will pass it to the author.",
    paysupport: "Problem with a payment or the files? Send one message:\n<code>/paysupport your text</code>\nSay how you paid. The author will reply here.",
    sentToAuthor: "Message sent to the author ✅ The reply will come here.",
    priceChanged: "The price has changed, please start the purchase again: /buy",
    promoHow: "Send your promo code like this: <code>/promo CODE</code>",
    promoOk: (p) => `🎟 Promo code <b>${p.promo}</b> applied!\n` + priceLine(p, "en"),
    promoBad: "No such promo code 🤷 Please check the spelling.",
    promoNoGain: "The code is valid, but the current price is already lower 🙂",
    btnFaq: "❓ FAQ",
    btnMenu: "🏠 Menu",
    btnAskAuthor: "📨 Didn't help — ask the author",
    btnAsk: "💬 My question is not here",
    faqTitle: "❓ <b>Frequently asked questions</b>\nPick a topic — or just type your question, I will try to answer right away.",
    faqHelped: "If this did not help, tap the button below and I will pass your question to the author.",
    faqNoMatch: "I don't have a ready answer for this 🤔\nShall I send your message to the author? The reply will come here.",
    askHow: "Type your question in one message — I will look for an answer, and if there is none, offer to send it to the author.",
    askExpired: "I could not find your question — please send it again in one message.",
  },
};

function lang(user) {
  const c = (user && user.language_code) || "";
  return /^(ru|uk|be|kk)/.test(c) ? "ru" : "en";
}

const langMenu = () => kb([[{ text: "🇷🇺 Русский", callback_data: "lang:ru" }, { text: "🇬🇧 English", callback_data: "lang:en" }]]);
const LANG_ASK = "🌐 Выберите язык / Choose your language";

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
    [{ text: "🌐 Язык / Language", callback_data: "langask" }],
    [{ text: t.btnTry, callback_data: "try" }],
    [{ text: t.btnBuy, callback_data: "buy" + codeSuffix(code) }],
    [{ text: t.btnWant, callback_data: "want" }],
    [{ text: t.btnMyPro, callback_data: "mypro" }, { text: t.btnAbout, callback_data: "about" }],
    [{ text: t.btnFaq, callback_data: "faq" }, { text: t.btnSupport, callback_data: "support" }],
  ]);
}

// ------------------------------------------------------------------ FAQ (automatic answers)
// Free-text questions are matched against these topics by keywords; the best match is answered at once.
// No match → the bot offers to forward the question to the author. Edit answers here; keywords are regexes.
const FAQ = [
  {
    id: "install",
    kw: /установ|устанавл|поставит|install|\bapk\b|неизвестн\w* источник|unknown source/,
    q: { ru: "Как установить приложение?", en: "How do I install the app?" },
    a: {
      ru: "📲 <b>Как установить</b>\n1. Скачайте APK в этом чате: ISL-1 TRY — кнопка «🚀 Скачать ISL-1 TRY», ISL-1 — приходит после оплаты или по /mypro.\n" +
          "2. Откройте файл на телефоне.\n3. Android попросит разрешить установку из неизвестных источников для Telegram или файлового менеджера — разрешите.\n" +
          "4. Нажмите «Установить».\n\nЕсли Google Play Protect предупредит о неизвестном приложении: «Подробнее» → «Всё равно установить».\n" +
          "Пошагово с картинками — в PDF «Install», он приходит вместе с APK.",
      en: "📲 <b>How to install</b>\n1. Get the APK in this chat: ISL-1 TRY — the “🚀 Download ISL-1 TRY” button, ISL-1 — sent after payment or via /mypro.\n" +
          "2. Open the file on your phone.\n3. Android will ask to allow installing from unknown sources for Telegram or your file manager — allow it.\n" +
          "4. Tap “Install”.\n\nIf Google Play Protect warns about an unknown app: “More details” → “Install anyway”.\n" +
          "A step-by-step guide with pictures is in the “Install” PDF that comes with the APK.",
    },
  },
  {
    id: "devices",
    kw: /iphone|айфон|\bios\b|ipad|айпад|apple|эппл|планшет|tablet|требован|requirement|совмест|compatib|подойд|пойд[её]т|поддерж\w* (мой|телефон|устройств)|android ?\d|андроид ?\d|верси\w* андроид|which (phone|device)/,
    q: { ru: "На каких устройствах работает?", en: "Which devices are supported?" },
    a: {
      ru: "📱 ISL-1 работает на <b>Android 10 и новее</b> — телефоны и планшеты. Для iPhone и iPad версии нет.\n\n" +
          "Проще всего проверить своё устройство так: поставьте бесплатную ISL-1 TRY: это та же программа на 10 дней.",
      en: "📱 ISL-1 runs on <b>Android 10 or newer</b> — phones and tablets. There is no iPhone/iPad version.\n\n" +
          "The easiest way to check your device: install the free ISL-1 TRY: the same app for 10 days.",
    },
  },
  {
    id: "try",
    kw: /разниц|отлича|отличи|\btry\b|пробн|триал|trial|10 дн|бесплатн|\bfree\b|отзыв|review|инстаграм|instagram|истек|закончил\w* срок|перестал\w* работ/,
    q: { ru: "Что такое TRY и как получить бесплатно?", en: "What is TRY and how do I get it for free?" },
    a: {
      ru: `🚀 <b>ISL-1 TRY</b> — та же программа со всеми функциями, но работает ${TRIAL_DAYS} дней с первого запуска, потом перестаёт работать.\n\n` +
          `🎁 <b>Бесплатно за отзыв:</b> за эти ${TRIAL_DAYS} дней запишите видеоотзыв со своими впечатлениями в Instagram (в кадре приложение или запись с экрана), отметьте ${INSTAGRAM} и нажмите «🎁 Хочу бесплатно!» в меню. После проверки придёт одноразовый промокод.\n\n` +
          `ISL-1 ставится отдельным приложением рядом с TRY. Чтобы перенести работу, сохраните проект: PRJCT → SAVE, потом LOAD.`,
      en: `🚀 <b>ISL-1 TRY</b> is the same app with all features, but it works for ${TRIAL_DAYS} days from the first launch and then stops.\n\n` +
          `🎁 <b>Free for a review:</b> within these ${TRIAL_DAYS} days post a video review with your impressions on Instagram (the app in frame or a screen recording), tag ${INSTAGRAM} and tap “🎁 I want it for free!” in the menu. After a check you get a one-time promo code.\n\n` +
          `ISL-1 installs as a separate app next to TRY. To move your work, save the project: PRJCT → SAVE, then LOAD.`,
    },
  },
  {
    id: "notreceived",
    kw: /не приш|не получ|после оплат|не приход|не пришл|не отправ|где (мои )?файл|потерял|удалил|оплатил|заплатил|didn.?t (get|receive|come)|not (received|arrived)|where .*(file|apk)|lost|paid/,
    q: { ru: "Оплатил, но файлы не пришли", en: "I paid but did not get the files" },
    a: {
      ru: "📦 Файлы ISL-1 приходят в этот чат сразу после оплаты. Получить их ещё раз можно в любой момент: /mypro или кнопка «📦 Мой ISL-1».\n\n" +
          "Платили в USDT — откройте сообщение со счётом и нажмите «✅ Проверить оплату».\n" +
          "Не помогло — напишите <code>/paysupport</code> и опишите, чем и когда платили.",
      en: "📦 ISL-1 files arrive in this chat right after payment. Get them again any time: /mypro or the “📦 My ISL-1” button.\n\n" +
          "Paid in USDT — open the invoice message and tap “✅ Check payment”.\n" +
          "Still nothing — send <code>/paysupport</code> and say how and when you paid.",
    },
  },
  {
    id: "pay",
    kw: /оплат|заплат|купит|купить|покупк|звезд|звёзд|stars?\b|usdt|крипт|crypto|карт[аоуы]|card|цена|стоит|стоимост|сколько|price|cost|how much|pay|buy/,
    q: { ru: "Как оплатить и сколько стоит?", en: "How do I pay and how much is it?" },
    a: {
      ru: (p) => "💳 <b>Как купить ISL-1</b>\nКнопка «⭐ Купить ISL-1» → выберите способ:\n" +
          "• <b>Telegram Stars</b> ⭐ — звёзды покупаются прямо в Telegram;\n• <b>USDT</b> через @CryptoBot.\n\n" +
          priceLine(p, "ru") + "\nЕсть промокод? <code>/promo КОД</code>",
      en: (p) => "💳 <b>How to buy ISL-1</b>\nTap “⭐ Buy ISL-1” and choose:\n" +
          "• <b>Telegram Stars</b> ⭐ — buy Stars right in Telegram;\n• <b>USDT</b> via @CryptoBot.\n\n" +
          priceLine(p, "en") + "\nHave a promo code? <code>/promo CODE</code>",
    },
  },
  {
    id: "update",
    kw: /обнов|нов\w* верси|update|upgrade|new version|переустан|reinstall|друг\w* (телефон|устройств)|нов\w* (телефон|устройств)|сменил телефон|another (phone|device)|new (phone|device)|перенес|перенос|transfer/,
    q: { ru: "Обновления и новый телефон", en: "Updates and a new phone" },
    a: {
      ru: "🔄 Обновления для владельцев ISL-1 бесплатны: /mypro всегда присылает последнюю версию.\n" +
          "Новую версию ставьте поверх старой — пады, паттерны и настройки сохранятся.\n\n" +
          "Новый телефон: войдите в тот же Telegram-аккаунт → /mypro → установите APK. Проекты переносите файлами .sal (PRJCT → SAVE, потом LOAD на новом телефоне).",
      en: "🔄 Updates are free for ISL-1 owners: /mypro always sends the latest version.\n" +
          "Install the new version over the old one — pads, patterns and settings are kept.\n\n" +
          "New phone: use the same Telegram account → /mypro → install the APK. Move projects as .sal files (PRJCT → SAVE, then LOAD on the new phone).",
    },
  },
  {
    id: "refund",
    kw: /возврат|верн\w* (деньг|оплат|звезд|звёзд)|refund|money back/,
    q: { ru: "Можно ли вернуть оплату?", en: "Can I get a refund?" },
    a: {
      ru: "↩️ Если ISL-1 не запускается на вашем устройстве, напишите в течение 14 дней после покупки: <code>/paysupport ваш текст</code> — оплату вернём.\n" +
          "Перед покупкой советуем проверить бесплатную ISL-1 TRY: это та же программа.",
      en: "↩️ If ISL-1 does not run on your device, write within 14 days of purchase: <code>/paysupport your text</code> — we will refund you.\n" +
          "Please try the free ISL-1 TRY first: it is the same app.",
    },
  },
  {
    id: "link",
    kw: /ableton|абл?етон|\blink\b|линк|синхрон|sync|\blive\b/,
    q: { ru: "Как работает Ableton Link?", en: "How does Ableton Link work?" },
    a: {
      ru: "🔗 Ableton Link: <b>SETUP → Ableton Link</b> → включить.\n" +
          "Телефон и компьютер (или другие Link-приложения) должны быть в одной Wi-Fi сети; в Ableton Live включите LINK слева вверху.\n" +
          "Темп, такт и старт/стоп станут общими. Если звук спешит или отстаёт — подстройте сдвиг задержки там же, в SETUP.",
      en: "🔗 Ableton Link: <b>SETUP → Ableton Link</b> → turn it on.\n" +
          "The phone and the computer (or other Link apps) must be on the same Wi-Fi; in Ableton Live turn on LINK at the top left.\n" +
          "Tempo, bar and start/stop become shared. If the sound is early or late, adjust the latency offset in SETUP.",
    },
  },
  {
    id: "promo",
    kw: /промо|promo|скидк|discount|купон|coupon/,
    q: { ru: "Как ввести промокод?", en: "How do I use a promo code?" },
    a: {
      ru: (p) => "🎟 Отправьте промокод так: <code>/promo КОД</code> — цена пересчитается сразу.\n" + priceLine(p, "ru"),
      en: (p) => "🎟 Send your code like this: <code>/promo CODE</code> — the price updates at once.\n" + priceLine(p, "en"),
    },
  },
  {
    id: "formats",
    kw: /формат|format|\bwav\b|\bmp3\b|flac|aiff|\bogg\b|импорт|import|семпл|сэмпл|sample|запис\w* (звук|с микрофон|микрофон)|микрофон|microphone|экспорт|export/,
    q: { ru: "Какие форматы звука поддерживаются?", en: "Which audio formats are supported?" },
    a: {
      ru: "🎵 <b>Импорт</b>: WAV, AIFF, MP3, FLAC, OGG, AAC/M4A — кнопка IMPORT загружает файл прямо на выбранный пад.\n" +
          "<b>Запись</b>: с микрофона и внутренний звук телефона (кнопка SRC, потом SAMPLE и удерживайте пад), а также звук самого приложения (SRC → SELF).\n" +
          "<b>Экспорт</b>: WAV и MP3 320 кбит/с до 10 минут.",
      en: "🎵 <b>Import</b>: WAV, AIFF, MP3, FLAC, OGG, AAC/M4A — the IMPORT button loads a file straight onto the selected pad.\n" +
          "<b>Recording</b>: microphone and the phone's internal audio (the SRC button, then SAMPLE and hold a pad), plus the app's own sound (SRC → SELF).\n" +
          "<b>Export</b>: WAV and MP3 320 kbps up to 10 minutes.",
    },
  },
  {
    id: "safe",
    kw: /вирус|безопас|virus|malware|\bsafe\b|play protect|google play|play market|play store|плей ?маркет|гугл ?плей|магазин/,
    q: { ru: "Почему нет в Google Play? Это безопасно?", en: "Why not on Google Play? Is it safe?" },
    a: {
      ru: "🛡 ISL-1 распространяется файлом APK через этот бот, в Google Play его нет.\n" +
          "Код приложения полностью открыт (GNU GPL v3), его может проверить любой: " + SOURCE_URL + "\n" +
          "Предупреждение Play Protect о «неизвестном приложении» — стандартное для любых APK не из магазина: «Подробнее» → «Всё равно установить».",
      en: "🛡 ISL-1 is distributed as an APK through this bot; it is not on Google Play.\n" +
          "The source code is fully open (GNU GPL v3) and anyone can check it: " + SOURCE_URL + "\n" +
          "Play Protect's “unknown app” warning is standard for any APK from outside the store: “More details” → “Install anyway”.",
    },
  },
  {
    id: "trouble",
    kw: /вылета|краш|крэш|crash|закрыва|не запуска|не открыва|не работает|завис|тормоз|freez|нет звука|не слышно|без звука|тих|no sound|задержк|опаздыва|latency|lag/,
    q: { ru: "Вылетает, нет звука или задержка", en: "Crashes, no sound or latency" },
    a: {
      ru: "🛠 Что проверить:\n1. Перезапустите приложение: смахните его из недавних и откройте снова.\n2. Нужен Android 10 или новее.\n" +
          "3. Нет звука — громкость телефона, фейдер MAIN справа, и что на паде есть семпл.\n" +
          "4. Задержка — с проводными наушниками или динамиком она меньше; Bluetooth всегда добавляет задержку.\n\n" +
          "Не помогло — нажмите кнопку ниже и укажите модель телефона и версию Android.",
      en: "🛠 Things to check:\n1. Restart the app: swipe it away from recent apps and open it again.\n2. Android 10 or newer is required.\n" +
          "3. No sound — phone volume, the MAIN fader on the right, and that the pad has a sample.\n" +
          "4. Latency — it is lower with wired headphones or the speaker; Bluetooth always adds delay.\n\n" +
          "Still stuck — tap the button below and include your phone model and Android version.",
    },
  },
  {
    id: "manual",
    kw: /инструкц|руководств|мануал|manual|guide|как пользоват|как работает|как сдела|как записат|how to (use|make)|tutorial|обучени|язык|language|english|английск|русск/,
    q: { ru: "Где руководство? Какой язык?", en: "Where is the manual? Which language?" },
    a: {
      ru: "📖 Руководство пользователя и инструкция по установке приходят вместе с APK на русском и английском. Надписи на панели приложения — на английском.",
      en: "📖 The user manual and the install guide come with the APK in English and Russian. The app panel labels are in English.",
    },
  },
];
const FAQ_GREETING = /^(привет\w*|здравствуй\w*|добр\w* (день|утро|вечер)|хай|hi|hello|hey|салам\w*|start|старт|меню|menu)[!. ]*$/;

// JS \w and \b know only Latin letters: let \w also match Cyrillic, and treat ё as е.
const faqRe = (re) => new RegExp(re.source.replace(/\\w/g, "[\\wа-яё]").replace(/ё/g, "е"), "g");

function faqMatch(text) {
  const s = text.toLowerCase().replace(/ё/g, "е");
  let best = null, bestScore = 0;
  for (const f of FAQ) {
    const re = faqRe(f.kw);
    const score = (s.match(re) || []).length;
    if (score > bestScore) { best = f; bestScore = score; }
  }
  return best;
}

function faqAnswer(env, f, lc, code = "") {
  const a = f.a[lc];
  return typeof a === "function" ? a(price(env, code)) : a;
}

function faqMenu(t, lc) {
  return kb([...FAQ.map((f) => [{ text: f.q[lc], callback_data: "faq:" + f.id }]), [{ text: t.btnAsk, callback_data: "askhow" }]]);
}

/** Free text from a buyer: answer from the FAQ, or offer to forward the question to the author. */
async function onFreeText(env, m, t, text) {
  const lc = lang(m.from);
  const chat = m.chat.id;
  if (text.length < 2 || faqRe(FAQ_GREETING).test(text.toLowerCase().replace(/ё/g, "е"))) return welcome(env, chat, t, "");
  await env.DB.put("q:" + m.from.id, text.slice(0, 3500), { expirationTtl: 86400 });
  const f = faqMatch(text);
  if (f) {
    return send(env, chat, faqAnswer(env, f, lc) + "\n\n" + t.faqHelped, kb([
      [{ text: t.btnAskAuthor, callback_data: "ask" }],
      [{ text: t.btnFaq, callback_data: "faq" }, { text: t.btnMenu, callback_data: "menu" }],
    ]));
  }
  return send(env, chat, t.faqNoMatch, kb([
    [{ text: t.btnAskAuthor, callback_data: "ask" }],
    [{ text: t.btnFaq, callback_data: "faq" }, { text: t.btnMenu, callback_data: "menu" }],
  ]));
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
  if (!ids[0] || !ids[1]) {
    await send(env, chatId, t.notReady);
    const a = await adminChat(env);
    if (a) await send(env, a, `⚠️ Нет файла для ${KIT_NAME[kind]} — пришлите его боту, список: /files`);
    return false;
  }
  const docs = ids.filter(Boolean);
  const media = docs.map((id, i) => ({
    type: "document",
    media: id,
    ...(i === docs.length - 1 ? { caption: `${KIT_NAME[kind]} ${VERSION} · ${SOURCE_URL}` } : {}),
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
    if (resendIfDone && (await sendKit(env, chatId, "full", t))) await trackProDownload(env, user);
    return;
  }
  const rec = { ...contactOf(user), method, ref: String(ref), amount: String(amount), promo, date: new Date().toISOString() };
  await env.DB.put(refKey, JSON.stringify(rec));
  await env.DB.put("buyer:" + user.id, JSON.stringify(rec));
  await trackSale(env, method, amount);
  await send(env, chatId, t.thanks);
  if (await sendKit(env, chatId, "full", t)) await trackProDownload(env, user);
  const a = await adminChat(env);
  if (a) {
    const who = user.username ? "@" + user.username : user.first_name || "";
    await send(env, a,
      `💰 Продажа ISL-1: ${escapeHtml(amount)} ${method === "stars" ? "⭐" : method === "usdt" ? "USDT" : ""}` +
      (promo ? ` · промокод ${escapeHtml(promo)}` : "") +
      ` от ${escapeHtml(who)} (<code>${user.id}</code>)\nref: <code>${escapeHtml(String(ref))}</code>`);
  }
}

function escapeHtml(s) {
  return String(s).replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c]));
}

// ------------------------------------------------------------------ statistics and contacts (admin only)
// One KV record "stats" with totals and per-month numbers (Moscow time). Contacts: "try:<id>" downloaded TRY,
// "reviewer:<id>" got ISL-1 for a review, "buyer:<id>" bought.
// The admin's own downloads are not counted.
const STAR_PAYOUT_USD = 0.013;   // ≈ what one Star brings on withdrawal via Fragment (override with env STAR_PAYOUT_USD)

const monthKey = (d = new Date(Date.now())) => new Date(d.getTime() + 3 * 3600e3).toISOString().slice(0, 7);
const emptyBlock = () => ({ tryDl: 0, tryUsers: 0, reviewN: 0, proDl: 0, starsN: 0, starsSum: 0, usdtN: 0, usdtSum: 0, manualN: 0, refundN: 0, refundStars: 0 });

async function getStats(env) {
  const raw = await env.DB.get("stats");
  if (raw) return JSON.parse(raw);
  // First run: rebuild sales from the payments already recorded.
  const s = { total: emptyBlock(), months: {} };
  for (const name of await listKeys(env, "paid:")) {
    const rec = JSON.parse((await env.DB.get(name)) || "null");
    if (rec) addSale(s, rec.method, Number(rec.amount) || 0, rec.date ? new Date(rec.date) : undefined);
  }
  return s;
}

function bump(s, fn, date) {
  fn(s.total);
  const m = monthKey(date);
  s.months[m] = s.months[m] || emptyBlock();
  fn(s.months[m]);
}

function addSale(s, method, amount, date) {
  bump(s, (b) => {
    if (method === "stars") { b.starsN++; b.starsSum += amount; }
    else if (method === "usdt") { b.usdtN++; b.usdtSum = Math.round((b.usdtSum + amount) * 100) / 100; }
    else b.manualN++;
  }, date);
}

async function saveStats(env, s) {
  // keep the last 24 months
  const keys = Object.keys(s.months).sort();
  for (const k of keys.slice(0, Math.max(0, keys.length - 24))) delete s.months[k];
  await env.DB.put("stats", JSON.stringify(s));
}

async function listKeys(env, prefix) {
  const out = [];
  let cursor;
  do {
    const r = await env.DB.list({ prefix, cursor });
    out.push(...r.keys.map((k) => k.name));
    cursor = r.list_complete ? undefined : r.cursor;
  } while (cursor);
  return out;
}

const contactOf = (user) => ({
  user_id: user.id, username: user.username || "", first_name: user.first_name || "", last_name: user.last_name || "",
  language: user.language_code || "",
});

/** A TRY kit was delivered: count it and remember the contact. */
async function trackTry(env, user) {
  if (!user || (await isAdmin(env, user.id))) return;
  const key = "try:" + user.id;
  const old = JSON.parse((await env.DB.get(key)) || "null");
  const now = new Date(Date.now()).toISOString();
  await env.DB.put(key, JSON.stringify({ ...contactOf(user), first_date: old ? old.first_date : now, last_date: now, downloads: (old ? old.downloads : 0) + 1 }));
  const s = await getStats(env);
  bump(s, (b) => { b.tryDl = (b.tryDl || 0) + 1; if (!old) b.tryUsers = (b.tryUsers || 0) + 1; });
  await saveStats(env, s);
}

async function trackReview(env) {
  const s = await getStats(env);
  bump(s, (b) => { b.reviewN = (b.reviewN || 0) + 1; });
  await saveStats(env, s);
}

/** A full ISL-1 kit was delivered (purchase, review or /mypro). */
async function trackProDownload(env, user) {
  if (!user || (await isAdmin(env, user.id))) return;
  const s = await getStats(env);
  bump(s, (b) => { b.proDl++; });
  await saveStats(env, s);
}

async function trackSale(env, method, amount) {
  const s = await getStats(env);
  addSale(s, method, Number(amount) || 0);
  await saveStats(env, s);
}

async function trackRefund(env, stars) {
  const s = await getStats(env);
  bump(s, (b) => { b.refundN++; b.refundStars += Number(stars) || 0; });
  await saveStats(env, s);
}

const MONTHS_RU = ["январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"];

const money = (x) => (Math.round(x * 100) / 100).toFixed(2);

function statsBlock(env, b) {
  const rate = Number(env.STAR_PAYOUT_USD) || STAR_PAYOUT_USD;
  const netStars = b.starsSum - b.refundStars;
  const starsUsd = netStars * rate;
  return `🚀 TRY — скачиваний: <b>${b.tryDl || 0}</b> · людей: ${b.tryUsers || 0}\n` +
    `🎁 За отзыв: <b>${b.reviewN || 0}</b>\n` +
    `💎 ISL-1 — продаж: <b>${b.starsN + b.usdtN}</b>` + (b.manualN ? ` · выдано вручную: ${b.manualN}` : "") + ` · выдач файлов: ${b.proDl}\n` +
    `⭐ Stars: <b>${netStars} ⭐</b> · продаж: ${b.starsN}` + (b.refundN ? ` · возвратов: ${b.refundN} (−${b.refundStars} ⭐)` : "") + ` · ≈ $${money(starsUsd)} к выводу\n` +
    `💵 USDT: <b>${money(b.usdtSum)}</b> · продаж: ${b.usdtN}\n` +
    `💰 Итого ≈ <b>$${money(starsUsd + b.usdtSum)}</b>`;
}

async function showStats(env, chat) {
  const s = await getStats(env);
  const m = monthKey();
  const cur = s.months[m] || emptyBlock();
  const [, mm] = m.split("-");
  const tryContacts = (await listKeys(env, "try:")).length;
  const reviewers = (await listKeys(env, "reviewer:")).length;
  const buyers = (await listKeys(env, "buyer:")).length;
  return send(env, chat,
    `📊 <b>Статистика ISL-1</b>\n\n<b>За всё время</b>\n${statsBlock(env, s.total)}\n\n` +
    `<b>За ${MONTHS_RU[Number(mm) - 1]}</b>\n${statsBlock(env, cur)}\n\n` +
    `👥 Контакты: скачали TRY — ${tryContacts}, за отзыв — ${reviewers}, купили — ${buyers}. Списки: /list, таблицы: /contacts\n` +
    `<i>Stars в долларах — примерно, по курсу вывода ~$${Number(env.STAR_PAYOUT_USD) || STAR_PAYOUT_USD} за ⭐; Stars можно вывести через 21 день после оплаты.</i>`);
}

function csv(rows, cols) {
  const cell = (v) => {
    const s = String(v == null ? "" : v);
    return /[";\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
  };
  return "﻿" + [cols.join(";"), ...rows.map((r) => cols.map((c) => cell(r[c])).join(";"))].join("\r\n") + "\r\n";
}

async function sendCsv(env, chat, name, text, caption) {
  const form = new FormData();
  form.append("chat_id", String(chat));
  form.append("caption", caption);
  form.append("document", new Blob([text], { type: "text/csv" }), name);
  return tgForm(env, "sendDocument", form);
}

const LISTS = {
  try: { prefix: "try:", title: "🚀 Скачали TRY", file: "ISL-1_TRY", date: "last_date",
         cols: ["user_id", "username", "first_name", "last_name", "language", "first_date", "last_date", "downloads"] },
  review: { prefix: "reviewer:", title: "🎁 Скачали за отзыв", file: "ISL-1_review", date: "date",
            cols: ["user_id", "username", "first_name", "last_name", "language", "date", "instagram", "code"] },
  buy: { prefix: "buyer:", title: "💎 Купили", file: "ISL-1_buyers", date: "date",
         cols: ["user_id", "username", "first_name", "last_name", "language", "date", "method", "amount", "promo", "ref"] },
};

async function listRows(env, l) {
  const rows = [];
  for (const k of await listKeys(env, l.prefix)) { const v = await env.DB.get(k); if (v) rows.push(JSON.parse(v)); }
  return rows.sort((a, b) => String(b[l.date]).localeCompare(String(a[l.date])));
}

/** /contacts [try|review|buy] — the contact lists as CSV files (open in Excel / Numbers / Google Sheets). */
async function sendContacts(env, chat, which) {
  const day = new Date(Date.now()).toISOString().slice(0, 10);
  const names = LISTS[which] ? [which] : ["try", "review", "buy"];
  for (const n of names) {
    const l = LISTS[n];
    const rows = await listRows(env, l);
    await sendCsv(env, chat, `${l.file}_${day}.csv`, csv(rows, l.cols), `${l.title}: ${rows.length}`);
  }
}

/** /list [try|review|buy] — the latest people of a category right in the chat. */
async function showList(env, chat, which) {
  const l = LISTS[which];
  if (!l) return send(env, chat, "Какой список показать?", listsMenu());
  const rows = await listRows(env, l);
  const line = (r) => {
    const who = r.username ? "@" + escapeHtml(r.username) : escapeHtml([r.first_name, r.last_name].filter(Boolean).join(" ") || "без имени");
    const ig = r.instagram ? ` · IG @${escapeHtml(r.instagram)}` : "";
    return `${who} · <code>${r.user_id}</code> · ${String(r[l.date] || "").slice(0, 10)}${ig}`;
  };
  return send(env, chat,
    `<b>${l.title}: ${rows.length}</b>\n` + (rows.slice(0, 50).map(line).join("\n") || "пока никого") +
    (rows.length > 50 ? `\n… показаны последние 50, полный список: /contacts ${which}` : ""));
}

function listsMenu() {
  return kb([
    [{ text: LISTS.try.title, callback_data: "adm:try" }],
    [{ text: LISTS.review.title, callback_data: "adm:review" }],
    [{ text: LISTS.buy.title, callback_data: "adm:buy" }],
    [{ text: "📬 Заявки на отзыв", callback_data: "adm:req" }],
  ]);
}

// ------------------------------------------------------------------ free ISL-1 for a video review
// "ig:<id>"  the bot waits for this person's Instagram (1 hour)
// "req:<id>" request: status pending / approved / rejected
// "code:<CODE>" one-time promo code; "reviewer:<id>" a person who redeemed one

const parseInstagram = (text) => {
  const s = String(text).trim().replace(/^https?:\/\/(www\.)?instagram\.com\//i, "").replace(/^@/, "").split(/[/?#]/)[0];
  return /^[A-Za-z0-9._]{1,30}$/.test(s) ? s.toLowerCase() : "";
};
const normCode = (s) => String(s || "").trim().toUpperCase();
const isReviewCode = (s) => /^ISL-[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(normCode(s));

function newCode() {
  const abc = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";   // no 0/O, 1/I
  const b = crypto.getRandomValues(new Uint8Array(8));
  const p = [...b].map((x) => abc[x % abc.length]).join("");
  return `ISL-${p.slice(0, 4)}-${p.slice(4)}`;
}

const hasFull = async (env, id) => !!((await getPurchase(env, id)) || (await env.DB.get("reviewer:" + id)));

async function getRequest(env, id) {
  return JSON.parse((await env.DB.get("req:" + id)) || "null");
}

async function wantFree(env, user, chat, t) {
  if (await hasFull(env, user.id)) return send(env, chat, t.wantHave);
  const r = await getRequest(env, user.id);
  if (r && r.status === "pending") return send(env, chat, t.wantPending);
  if (r && r.status === "approved") return send(env, chat, t.wantApproved(r.code), kb([[{ text: t.btnRedeem, callback_data: "redeem:" + r.code }]]));
  await env.DB.put("ig:" + user.id, "1", { expirationTtl: 3600 });
  return send(env, chat, t.wantAsk);
}

function requestCard(r) {
  const who = r.username ? "@" + escapeHtml(r.username) : escapeHtml(r.first_name || "без имени");
  return {
    text: `🎁 <b>Заявка на ISL-1 за отзыв</b>\nTelegram: ${who} (<code>${r.user_id}</code>)\nInstagram: <a href="https://instagram.com/${r.instagram}">@${r.instagram}</a>\nДата: ${r.date.slice(0, 10)}`,
    keyboard: kb([[{ text: "✅ Одобрить", callback_data: "rv_ok:" + r.user_id }, { text: "❌ Отклонить", callback_data: "rv_no:" + r.user_id }]]),
  };
}

async function onInstagram(env, m, t, text) {
  const ig = parseInstagram(text);
  if (!ig) return send(env, m.chat.id, t.igBad);
  await env.DB.delete("ig:" + m.from.id);
  const r = { ...contactOf(m.from), chat: m.chat.id, instagram: ig, status: "pending", date: new Date(Date.now()).toISOString() };
  await env.DB.put("req:" + m.from.id, JSON.stringify(r));
  await send(env, m.chat.id, t.wantSent(ig));
  const a = await adminChat(env);
  if (a) {
    const c = requestCard(r);
    await send(env, a, c.text, c.keyboard);
  }
}

/** /requests — all requests waiting for a decision. */
async function showRequests(env, chat) {
  const rows = [];
  for (const k of await listKeys(env, "req:")) {
    const r = JSON.parse((await env.DB.get(k)) || "null");
    if (r && r.status === "pending") rows.push(r);
  }
  if (!rows.length) return send(env, chat, "Активных заявок нет ✅");
  await send(env, chat, `📬 Активных заявок: <b>${rows.length}</b>`);
  for (const r of rows.slice(0, 10)) {
    const c = requestCard(r);
    await send(env, chat, c.text, c.keyboard);
  }
}

async function decideRequest(env, q, approve, uid) {
  const chat = q.message ? q.message.chat.id : q.from.id;
  const r = await getRequest(env, uid);
  if (!r || r.status !== "pending") return send(env, chat, "Заявка уже обработана.");
  const t = T[lang({ language_code: r.language })];
  if (q.message) await tg(env, "editMessageReplyMarkup", { chat_id: chat, message_id: q.message.message_id, reply_markup: { inline_keyboard: [] } });
  if (!approve) {
    r.status = "rejected";
    await env.DB.put("req:" + uid, JSON.stringify(r));
    await send(env, r.chat, t.reviewRejected);
    return send(env, chat, `❌ Заявка @${r.instagram} отклонена.`);
  }
  r.status = "approved";
  r.code = newCode();
  await env.DB.put("code:" + r.code, JSON.stringify({ for_user: r.user_id, instagram: r.instagram, used_by: 0, date: new Date(Date.now()).toISOString() }));
  await env.DB.put("req:" + uid, JSON.stringify(r));
  await send(env, r.chat, t.reviewApproved(r.code), kb([[{ text: t.btnRedeem, callback_data: "redeem:" + r.code }]]));
  return send(env, chat, `✅ Заявка @${r.instagram} одобрена, промокод <code>${r.code}</code> отправлен.`);
}

async function redeemCode(env, user, chat, t, raw) {
  const code = normCode(raw);
  const rec = JSON.parse((await env.DB.get("code:" + code)) || "null");
  if (!rec) return send(env, chat, t.promoBad);
  if (rec.used_by) {
    if (rec.used_by !== user.id) return send(env, chat, t.codeUsed);
    return myPro(env, user, chat, t);
  }
  rec.used_by = user.id;
  rec.used_date = new Date(Date.now()).toISOString();
  await env.DB.put("code:" + code, JSON.stringify(rec));
  await env.DB.put("reviewer:" + user.id, JSON.stringify({ ...contactOf(user), date: rec.used_date, instagram: rec.instagram, code }));
  await trackReview(env);
  await send(env, chat, t.reviewThanks);
  if (await sendKit(env, chat, "full", t)) await trackProDownload(env, user);
  const a = await adminChat(env);
  if (a) await send(env, a, `🎁 Промокод <code>${code}</code> использован: ${user.username ? "@" + escapeHtml(user.username) : escapeHtml(user.first_name || "")} (<code>${user.id}</code>), Instagram @${rec.instagram}`);
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
  if ((await env.DB.get("cmds_rev")) !== COMMANDS_REV) await setCommands(env);
  // The language chosen in the bot replaces the one from the Telegram app in every handler.
  const from = (upd.pre_checkout_query || upd.callback_query || upd.message || {}).from;
  if (from) {
    const saved = await env.DB.get("lang:" + from.id);
    if (saved) from.language_code = saved;
    else from.langUnset = true;
  }
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
  const banner = await env.DB.get("img:isl_banner");
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
      if (arg === "try" || arg === "free") return sendTry(env, chat, t, m.from);
      if (arg === "buy") return showTerms(env, chat, t, "");
      if (arg === "want") return wantFree(env, m.from, chat, t);
      if (isReviewCode(arg)) return redeemCode(env, m.from, chat, t, arg);
      if (arg && isPromo(env, arg)) return applyPromo(env, chat, t, arg);
      if (m.from.langUnset) return send(env, chat, LANG_ASK, langMenu());
      return welcome(env, chat, t, "");
    case "/language":
    case "/lang":
      return send(env, chat, LANG_ASK, langMenu());
    case "/try":
    case "/free":
      return sendTry(env, chat, t, m.from);
    case "/want":
      return wantFree(env, m.from, chat, t);
    case "/buy":
      return showTerms(env, chat, t, arg && isPromo(env, arg) ? arg : "");
    case "/promo":
      if (!arg) return send(env, chat, t.promoHow);
      if (isReviewCode(arg)) return redeemCode(env, m.from, chat, t, arg);
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
    case "/faq":
    case "/help":
      return send(env, chat, t.faqTitle, faqMenu(t, lang(m.from)));
    case "/support":
    case "/paysupport":
      if (!arg) return send(env, chat, cmd === "/paysupport" ? t.paysupport : t.support);
      return forwardToAdmin(env, m.from, chat, cmd, arg, t);
  }
  if (!cmd.startsWith("/") && (await env.DB.get("ig:" + m.from.id))) return onInstagram(env, m, t, text);
  if (await isAdmin(env, m.from.id)) return onAdminText(m, env, cmd, rest, arg);
  if (cmd.startsWith("/")) return welcome(env, chat, t, "");
  return onFreeText(env, m, t, text);
}

async function applyPromo(env, chat, t, code) {
  if (!isPromo(env, code)) return send(env, chat, t.promoBad);
  const p = price(env, code);
  if (!p.promo) return send(env, chat, t.promoNoGain);
  return showTerms(env, chat, t, p.promo, t.promoOk(p) + "\n\n");
}

async function sendTry(env, chat, t, user) {
  const ids = await getFileIds(env, "try");
  if (ids[0] && ids[1]) await send(env, chat, t.sendingTry);
  if (await sendKit(env, chat, "try", t)) {
    await trackTry(env, user);
    await send(env, chat, t.tryOffer, kb([[{ text: t.btnWant, callback_data: "want" }]]));
  }
}

async function showTerms(env, chat, t, code, prefix = "") {
  const p = price(env, code);
  const rows = [[{ text: t.payStars(p), callback_data: "pay_stars" + codeSuffix(p.promo) }]];
  if (usdtOn(env)) rows.push([{ text: t.payUsdt(p), callback_data: "pay_usdt" + codeSuffix(p.promo) }]);
  await send(env, chat, prefix + t.terms + "\n\n" + priceLine(p, t === T.ru ? "ru" : "en") + "\n" + t.choosePay, kb(rows));
}

async function myPro(env, user, chat, t) {
  if (!(await hasFull(env, user.id))) return send(env, chat, t.noPurchase);
  await send(env, chat, t.alreadyBought);
  if (await sendKit(env, chat, "full", t)) await trackProDownload(env, user);
}

async function forwardToAdmin(env, from, chat, cmd, text, t) {
  const a = await adminChat(env);
  if (!a) return;
  const who = from.username ? "@" + from.username : from.first_name || "";
  const bought = await getPurchase(env, from.id);
  await send(env, a,
    `${cmd === "/paysupport" ? "🧾 PAYSUPPORT" : cmd === "ask" ? "❓ ВОПРОС (автоответ не помог)" : "💬 SUPPORT"} от ${escapeHtml(who)} (<code>${from.id}</code>)` +
    (bought ? `\nISL-1 куплен: ${bought.method} ${escapeHtml(bought.amount)}, ref <code>${escapeHtml(bought.ref)}</code>` : "") +
    `\n\n${escapeHtml(text)}\n\nОтветить: <code>/reply ${from.id} текст</code>`);
  await send(env, chat, t.sentToAuthor);
}

async function onCallback(q, env) {
  const t = T[lang(q.from)];
  const chat = q.message ? q.message.chat.id : q.from.id;
  await tg(env, "answerCallbackQuery", { callback_query_id: q.id });
  const [d, code = ""] = String(q.data || "").split(":");
  if (d === "langask") return send(env, chat, LANG_ASK, langMenu());
  if (d === "lang" && (code === "ru" || code === "en")) {
    await env.DB.put("lang:" + q.from.id, code);
    return welcome(env, chat, T[code], "");
  }
  if (d === "try" || d === "free") return sendTry(env, chat, t, q.from);
  if (d === "want") return wantFree(env, q.from, chat, t);
  if (d === "redeem") return redeemCode(env, q.from, chat, t, code);
  if ((d === "rv_ok" || d === "rv_no" || d === "adm") && !(await isAdmin(env, q.from.id))) return;
  if (d === "rv_ok" || d === "rv_no") return decideRequest(env, q, d === "rv_ok", code);
  if (d === "adm") return code === "req" ? showRequests(env, chat) : showList(env, chat, code);
  if (d === "buy") return showTerms(env, chat, t, code);
  if (d === "mypro") return myPro(env, q.from, chat, t);
  if (d === "about") return send(env, chat, t.about);
  if (d === "support") return send(env, chat, t.support);
  if (d === "menu") return welcome(env, chat, t, "");
  if (d === "faq" && !code) return send(env, chat, t.faqTitle, faqMenu(t, lang(q.from)));
  if (d === "faq") {
    const f = FAQ.find((x) => x.id === code);
    if (!f) return send(env, chat, t.faqTitle, faqMenu(t, lang(q.from)));
    return send(env, chat, faqAnswer(env, f, lang(q.from)), kb([[{ text: t.btnFaq, callback_data: "faq" }, { text: t.btnMenu, callback_data: "menu" }]]));
  }
  if (d === "askhow") return send(env, chat, t.askHow);
  if (d === "ask") {
    const question = await env.DB.get("q:" + q.from.id);
    if (!question) return send(env, chat, t.askExpired);
    await env.DB.delete("q:" + q.from.id);
    return forwardToAdmin(env, q.from, chat, "ask", question, t);
  }
  if (d === "pay_stars") {
    const p = price(env, code);
    return tg(env, "sendInvoice", {
      chat_id: chat,
      title: "ISL-1",
      description: t.invoiceDesc,
      payload: `pro:${p.stars}:${p.promo}`,
      provider_token: "",
      currency: "XTR",
      prices: [{ label: "ISL-1", amount: p.stars }],
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
  const haveFiles = !!(await getFileIds(env, "full"))[0];
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
    "✅ Вы администратор бота.\n\nТеперь отправьте боту <b>как файлы</b> 12 файлов из папки «ISL-1 Bot» на рабочем столе.\nСписок команд: /admin");
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
  if (kind === "isl_banner") {
    // re-upload as a photo once to get a photo file_id for welcome messages
    const form = new FormData();
    form.append("chat_id", String(m.chat.id));
    form.append("caption", "Баннер для приветствия ✅");
    form.append("photo", blob, "banner.jpg");
    const r = await tgForm(env, "sendPhoto", form);
    if (!r.ok) return send(env, m.chat.id, "Не удалось сохранить баннер ❌");
    const sizes = r.result.photo || [];
    await env.DB.put("img:isl_banner", sizes[sizes.length - 1].file_id);
  } else {
    const form = new FormData();
    form.append("photo", JSON.stringify({ type: "static", photo: "attach://avatar" }));
    form.append("avatar", blob, "avatar.jpg");
    const r = await tgForm(env, "setMyProfilePhoto", form);
    if (!r.ok) return send(env, m.chat.id, "Не удалось поставить аватар ❌ Поставьте его в @BotFather → /setuserpic.");
    await env.DB.put("img:isl_avatar", "set");
  }
  return send(env, m.chat.id, `✅ ${kind === "isl_banner" ? "Баннер" : "Аватар бота"} сохранён.` + (await progress(env)));
}

async function onAdminText(m, env, cmd, rest, arg) {
  const chat = m.chat.id;
  if (cmd === "/admin") {
    const p = price(env, ""), pr = promoTable(env), me = (await env.DB.get("me")) || "ваш_бот";
    return send(env, chat,
      `<b>Команды автора</b>\n` +
      `• пришлите файл из папки релиза — бот запомнит его\n` +
      `/requests — активные заявки на ISL-1 за отзыв\n` +
      `/list — люди по категориям: скачали TRY, за отзыв, купили\n` +
      `/stats — скачивания и полученные деньги\n` +
      `/contacts — те же списки таблицами CSV; /contacts try, review или buy — один\n` +
      `/files — какие файлы загружены\n` +
      `/sales — последние продажи\n` +
      `/reply id текст — ответить покупателю\n` +
      `/refund id — вернуть Stars покупателю\n` +
      `/grant id — выдать ISL-1 вручную\n\n` +
      `<b>Цены сейчас</b>\n${priceLine(p, "ru")}\n` +
      Object.keys(pr).map((c) => `🎟 ${escapeHtml(c)}: $${fmt(pr[c])} · ${price(env, c).stars} ⭐ · ссылка t.me/${escapeHtml(me)}?start=${escapeHtml(c)}`).join("\n"),
      listsMenu());
  }
  if (cmd === "/stats") return showStats(env, chat);
  if (cmd === "/requests") return showRequests(env, chat);
  if (cmd === "/list") return showList(env, chat, (rest[0] || "").toLowerCase());
  if (cmd === "/contacts") return sendContacts(env, chat, (rest[0] || "").toLowerCase());
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
    const r = await send(env, to, `💬 Ответ автора ISL-1:\n\n${escapeHtml(arg.slice(to.length).trim())}`);
    return send(env, chat, r.ok ? "Отправлено ✅" : "Не получилось отправить ❌");
  }
  if (cmd === "/refund" && rest[0]) {
    const rec = await getPurchase(env, rest[0]);
    if (!rec || rec.method !== "stars") return send(env, chat, "Покупка за Stars не найдена (USDT возвращайте вручную в @CryptoBot).");
    const r = await tg(env, "refundStarPayment", { user_id: Number(rest[0]), telegram_payment_charge_id: rec.ref });
    if (r.ok) {
      await env.DB.delete("buyer:" + rest[0]);
      await trackRefund(env, rec.amount);
    }
    return send(env, chat, r.ok ? "Stars возвращены ✅, доступ к /mypro снят." : "Ошибка возврата: " + escapeHtml(r.description || ""));
  }
  if (cmd === "/grant" && /^\d+$/.test(rest[0] || "")) {
    const uid = Number(rest[0]);
    await completePurchase(env, { id: uid, language_code: "ru" }, uid, "manual", "grant-" + uid + "-" + Date.now(), 0);
    return send(env, chat, "Выдано ✅");
  }
  return send(env, chat, "Команды автора: /admin");
}

// Bot menu commands. Bump COMMANDS_REV when the list changes — the bot updates Telegram by itself on the next message.
const COMMANDS_REV = "4";
async function setCommands(env) {
  const out = {};
  const cmds = {
    ru: [["start", "Меню"], ["try", `Скачать ISL-1 TRY на ${TRIAL_DAYS} дней`], ["buy", "Купить ISL-1"], ["want", "Хочу бесплатно: ISL-1 за видеоотзыв"],
         ["faq", "Частые вопросы"], ["promo", "Ввести промокод"], ["mypro", "Получить мой ISL-1 ещё раз"], ["terms", "Условия покупки"],
         ["paysupport", "Помощь с оплатой"], ["support", "Написать автору"], ["about", "О программе и исходный код"], ["language", "Язык / Language"]],
    en: [["start", "Menu"], ["try", `Download ISL-1 TRY for ${TRIAL_DAYS} days`], ["buy", "Buy ISL-1"], ["want", "Free ISL-1 for a video review"],
         ["faq", "Frequently asked questions"], ["promo", "Enter a promo code"], ["mypro", "Get your ISL-1 again"], ["terms", "Purchase terms"],
         ["paysupport", "Payment help"], ["support", "Contact the author"], ["about", "About and source code"], ["language", "Язык / Language"]],
  };
  for (const [lc, list] of Object.entries(cmds)) {
    const commands = list.map(([command, description]) => ({ command, description }));
    out["commands_" + lc] = await tg(env, "setMyCommands", lc === "en" ? { commands } : { commands, language_code: lc });
  }
  // The bot's profile follows the same revision.
  await tg(env, "setMyName", { name: "ISL-1 Sampler" });
  await tg(env, "setMyDescription", { language_code: "ru",
    description: `🎛 ISL-1 — семплер, секвенсор, лупер и KAOSS-эффекты для Android от SALYCH MUSIC. Скачайте ISL-1 TRY на ${TRIAL_DAYS} дней, купите полную версию или получите её бесплатно за видеоотзыв.` });
  await tg(env, "setMyDescription", {
    description: `🎛 ISL-1 — a sampler, sequencer, looper and KAOSS effects for Android by SALYCH MUSIC. Download ISL-1 TRY for ${TRIAL_DAYS} days, buy the full version or get it free for a video review.` });
  await tg(env, "setMyShortDescription", { language_code: "ru", short_description: `Лупер-семплер ISL-1 для Android от SALYCH MUSIC: ${TRIAL_DAYS} дней бесплатно, полная версия за ⭐` });
  await tg(env, "setMyShortDescription", { short_description: `ISL-1 looper sampler for Android by SALYCH MUSIC: ${TRIAL_DAYS} days free, full version for ⭐` });
  if (out.commands_ru.ok && out.commands_en.ok) await env.DB.put("cmds_rev", COMMANDS_REV);
  return out;
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
  Object.assign(out, await setCommands(env));
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
  async fetch(request, rawEnv) {
    // Secrets pasted into the dashboard often carry a trailing newline or spaces — ignore them.
    const env = Object.assign({}, rawEnv, {
      BOT_TOKEN: String(rawEnv.BOT_TOKEN || "").trim(),
      WEBHOOK_SECRET: String(rawEnv.WEBHOOK_SECRET || "").trim(),
      CRYPTO_PAY_TOKEN: String(rawEnv.CRYPTO_PAY_TOKEN || "").trim(),
    });
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
    return new Response(`ISL-1 bot is running. rev ${COMMANDS_REV}`);
  },
};
