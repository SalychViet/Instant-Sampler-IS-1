# Telegram-бот ISL-1

Бот раздаёт **ISL-1 TRY** (все функции на 10 дней), продаёт **ISL-1** за Telegram Stars ⭐ (и, по желанию, за USDT через @CryptoBot) и выдаёт ISL-1 бесплатно за видеоотзыв в Instagram.
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
2. Отправьте боту **как файлы** (скрепка → Файл) все файлы из папки «ISL-1 Bot» на рабочем столе:
   `ISL-1_TRY_0.4.2.apk`, `ISL-1_0.4.2.apk`, `ISL-1_TRY_Install_RU.pdf`, `ISL-1_Install_RU.pdf`, `ISL-1_Manual_RU.pdf`, `ISL-1_Manual_EN.pdf`,
   `ISL-1_Looper_RU.pdf`, `ISL-1_Looper_EN.pdf`, `ISL-1_bot_banner.jpg` (баннер приветствия), `ISL-1_bot_avatar.jpg` (аватар бота)
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

## ISL-1 бесплатно за видеоотзыв
1. Человек нажимает «🎁 Хочу бесплатно!» и присылает свой Instagram.
2. Автору приходит заявка с Telegram и Instagram и кнопками «✅ Одобрить» и «❌ Отклонить». Все ждущие заявки: `/requests`.
3. При одобрении человеку приходит одноразовый промокод вида `ISL-XXXX-XXXX`. Он вводит его через `/promo` или нажимает кнопку и получает файлы, потом они доступны по `/mypro`.
4. Отклонённый человек может подать заявку ещё раз.

## Списки людей
`/list` — по категориям прямо в чате: 🚀 скачали TRY, 🎁 скачали за отзыв, 💎 купили. `/contacts` — те же списки таблицами CSV.
Один человек может быть в нескольких списках. Покупатели прошлой версии остаются в списке «Купили» и получают ISL-1 по `/mypro`.

## Команды
Покупатели: `/start`, `/try`, `/buy`, `/want`, `/promo КОД`, `/mypro`, `/faq`, `/terms`, `/paysupport`, `/support`, `/about`.
Автор: `/admin` (цены, ссылки на промокоды, кнопки списков), `/requests`, `/list`, `/stats`, `/contacts`, `/files`, `/sales`, `/reply id текст`, `/refund id`, `/grant id`.

## Безопасность
- Токены — только в Cloudflare как **Secret**. `webhook_secret.txt` и `branding/` в репозиторий не попадают.
- Бот принимает запросы только от Telegram (по `WEBHOOK_SECRET`) и Crypto Pay (по подписи).
- ISL-1 выдаётся только после подтверждённой оплаты или по одобренному автором промокоду; цену и промокод проверяет сервер, а не кнопка.

## Тесты
`node test/run.mjs` — 46 автотестов (имитация Telegram и Crypto Pay).
