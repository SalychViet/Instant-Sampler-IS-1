# Telegram-бот IS-1

Бот раздаёт **IS-1 FREE** бесплатно и продаёт **IS-1 PRO** за Telegram Stars ⭐ (и, по желанию, за USDT через @CryptoBot).
Работает на бесплатном **Cloudflare Workers** (регистрация по почте, без карты, не засыпает).

**Цены** (меняются в Cloudflare → Worker → Settings → Variables, без правки кода):

| | USD | Stars |
|---|---|---|
| Обычная цена (`PRICE_USD`) | $9.99 | 500 ⭐ |
| Скидка до 31 декабря (`SALE_PRICE_USD`, `SALE_UNTIL`) | $8.99 | 450 ⭐ |
| Промокод `salychmusic` (`PROMO_CODES`) | $7.99 | 400 ⭐ |

Stars считаются автоматически: `STARS_PER_USD` = 50 (≈ столько звёзд покупатель получает за $1).
1 января 2027 в 00:00 по Москве скидка заканчивается сама. Промокод действует всегда.
Ссылка с промокодом: `https://t.me/ВАШ_БОТ?start=salychmusic`.

---

## Запуск (≈10 минут)

### 1. Бот в Telegram
@BotFather → `/newbot` → имя `IS-1 Sampler` → username, например `IS1_sampler_bot` → скопируйте **токен**.

### 2. Деплой в Cloudflare одной кнопкой
Откройте:
**https://deploy.workers.cloudflare.com/?url=https://github.com/SalychViet/Instant-Sampler-IS-1/tree/main/telegram-bot**

1. Войдите или зарегистрируйтесь в Cloudflare (почта) и подключите GitHub, когда попросит.
2. Cloudflare сам создаст хранилище `DB` и спросит два секрета:
   - `BOT_TOKEN` — токен из BotFather;
   - `WEBHOOK_SECRET` — строка из файла `webhook_secret.txt` (лежит рядом с этой инструкцией у вас на Mac).
3. **Deploy**. Запомните адрес: `https://is1-bot.ВАШ-ПОДДОМЕН.workers.dev`.

### 3. Подключите бота
Откройте в браузере:
```
https://is1-bot.ВАШ-ПОДДОМЕН.workers.dev/setup?key=СТРОКА_ИЗ_webhook_secret.txt
```
Появится «✅ Bot is set up» и ссылка на бота.

### 4. Станьте администратором и загрузите файлы
1. Напишите боту: `/claim СТРОКА_ИЗ_webhook_secret.txt` → «Вы администратор».
2. Отправьте боту **как файлы** (скрепка → Файл):
   - из «IS-1 FREE 0.4.1»: `IS-1_FREE_0.4.1.apk`, `IS-1_FREE_Install_RU.pdf`, `IS-1_FREE_Manual.pdf`, `IS-1_FREE_Manual_EN.pdf`
   - из «IS-1 PRO 0.4.1»: `IS-1_PRO_0.4.1.apk`, `IS-1_PRO_Install_RU.pdf`, `IS-1_PRO_Manual.pdf`, `IS-1_PRO_Manual_EN.pdf`
   - из «IS-1 Telegram Bot/branding»: `IS-1_bot_banner.jpg` (баннер приветствия), `IS-1_bot_avatar.jpg` (аватар бота)
3. На последнем файле бот напишет «Всё на месте 🎉». Проверка: `/files`.

Готово — проверьте с другого аккаунта: `/start`.

---

## Оплата в USDT (по желанию)
1. @CryptoBot → **Crypto Pay** → **Create App** → API token.
2. Cloudflare → `is1-bot` → Settings → Variables and Secrets → добавьте секрет `CRYPTO_PAY_TOKEN` → Deploy.
3. В приложении Crypto Pay → **Webhooks** → URL `https://is1-bot.ВАШ-ПОДДОМЕН.workers.dev/cryptopay`.

## Вывод Stars
Профиль бота → баланс / @BotFather → бот → Balance → вывод через **Fragment** в **TON** (Tonkeeper или @wallet).
Нужен облачный пароль Telegram (двухэтапная аутентификация).

## Команды
Покупатели: `/start`, `/free`, `/buy`, `/faq`, `/promo КОД`, `/mypro`, `/terms`, `/paysupport`, `/support`, `/about`.

## Автоответы (частые вопросы)
Если покупатель просто пишет вопрос своими словами, бот ищет ответ среди частых вопросов (установка, устройства, FREE и PRO,
оплата, «файлы не пришли», обновления, возврат, Ableton Link, промокод, форматы, безопасность, проблемы, руководство) и отвечает сразу.
Под ответом — кнопка «Не помогло — спросить автору»: только по ней вопрос приходит вам с пометкой «❓ ВОПРОС». Если готового
ответа нет, бот сразу предлагает отправить вопрос вам. Тексты и ключевые слова — в `worker.js`, массив `FAQ`.
Автор: `/admin` (цены и ссылки на промокоды), `/files`, `/sales`, `/reply id текст`, `/refund id`, `/grant id`.

## Безопасность
- Токены — только в Cloudflare как **Secret**. `webhook_secret.txt` и `branding/` в репозиторий не попадают.
- Бот принимает запросы только от Telegram (по `WEBHOOK_SECRET`) и Crypto Pay (по подписи).
- PRO выдаётся только после подтверждённой оплаты; цену и промокод проверяет сервер, а не кнопка.

## Тесты
`node test/run.mjs` — 32 автотеста (имитация Telegram и Crypto Pay).
