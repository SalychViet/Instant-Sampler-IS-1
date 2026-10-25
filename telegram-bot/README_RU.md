# Telegram-бот IS-1: запуск на бесплатном Cloudflare

Бот раздаёт **IS-1 FREE** бесплатно и продаёт **IS-1 PRO** за Telegram Stars ⭐ (и, по желанию, за USDT через @CryptoBot).
После оплаты он сразу присылает APK и руководства; купивший может получить файлы снова командой `/mypro`.

Сервер — **Cloudflare Workers, бесплатный тариф** (регистрация по почте, без карты, 100 000 запросов в день, не засыпает).
Сами файлы хранятся в Telegram: вы один раз отправляете их боту, и он пересылает их покупателям.

Всё делается в браузере, программировать не нужно. ~15 минут.

---

## 1. Создайте бота в Telegram

1. Откройте **@BotFather** → `/newbot`.
2. Имя: `IS-1 Sampler` (любое). Username: например `IS1_sampler_bot` (должен заканчиваться на `bot`).
3. BotFather пришлёт **токен** вида `123456789:AA...`. Это пароль от бота — никому его не показывайте.
4. (По желанию) `/setuserpic` — картинка бота, например `Logos/app_icon.png` из папки PRO.

## 2. Создайте Worker в Cloudflare

1. Зарегистрируйтесь на **dash.cloudflare.com** (только почта).
2. Слева **Workers & Pages** → **Create** → **Create Worker** (шаблон «Hello World»).
   Имя: `is1-bot` → **Deploy**.
3. Нажмите **Edit code**, удалите всё в редакторе, вставьте целиком содержимое файла `worker.js` из этой папки → **Deploy**.
4. Запомните адрес Worker'а: `https://is1-bot.ВАШ-ПОДДОМЕН.workers.dev` (виден на странице Worker'а).

## 3. Хранилище (KV)

1. Слева **Storage & Databases** → **KV** → **Create** → имя `is1-db`.
2. Откройте Worker `is1-bot` → **Settings** → **Bindings** → **Add** → **KV namespace**:
   Variable name: `DB`, namespace: `is1-db` → **Save/Deploy**.

## 4. Переменные

Worker `is1-bot` → **Settings** → **Variables and Secrets** → **Add**:

| Имя | Тип | Значение |
|---|---|---|
| `BOT_TOKEN` | **Secret** | токен от BotFather |
| `WEBHOOK_SECRET` | **Secret** | строка из файла `webhook_secret.txt` в этой папке |
| `PRICE_STARS` | Text | цена PRO в звёздах, например `750` |
| `ADMIN_ID` | Text | пока пусто — заполните в шаге 6 |

→ **Deploy**.

## 5. Подключите бота к серверу (один раз)

Откройте в браузере (подставьте свой адрес и строку из `webhook_secret.txt`):

```
https://is1-bot.ВАШ-ПОДДОМЕН.workers.dev/setup?key=СТРОКА_ИЗ_webhook_secret.txt
```

Должно появиться **«✅ Bot is set up»**. Бот получил меню команд и описание на русском и английском.

## 6. Станьте администратором и загрузите файлы

1. Напишите своему боту `/myid` — он ответит вашим числовым id.
2. Cloudflare → `is1-bot` → **Variables and Secrets** → добавьте `ADMIN_ID` = этот id → **Deploy**.
3. Отправьте боту **как файлы** 8 документов из папок на рабочем столе:
   - `IS-1 FREE 0.4.0`: `IS-1_FREE_0.4.0.apk`, `IS-1_FREE_Install_RU.pdf`, `IS-1_FREE_Manual.pdf`, `IS-1_FREE_Manual_EN.pdf`
   - `IS-1 PRO 0.4.0`: `IS-1_PRO_0.4.0.apk`, `IS-1_PRO_Install_RU.pdf`, `IS-1_PRO_Manual.pdf`, `IS-1_PRO_Manual_EN.pdf`

   Имена файлов менять нельзя — по ним бот понимает, что есть что. На каждый файл бот ответит «✅ сохранён».
4. `/files` — проверка: должно быть 8 ✅.

Готово. Проверьте с другого аккаунта: `/start` → «Скачать IS-1 FREE».

---

## Оплата в USDT (по желанию)

1. В **@CryptoBot** → **Crypto Pay** → **Create App** → скопируйте **API token**.
2. Cloudflare → переменные: `CRYPTO_PAY_TOKEN` (**Secret**) = этот токен, `PRICE_USDT` (Text) = например `9.99` → **Deploy**.
3. В @CryptoBot → Crypto Pay → ваше приложение → **Webhooks** → включите и укажите
   `https://is1-bot.ВАШ-ПОДДОМЕН.workers.dev/cryptopay`.
   (Без вебхука тоже работает: покупатель жмёт «Проверить оплату».)

Без `CRYPTO_PAY_TOKEN` бот предлагает только Stars.

## Как вывести заработанные Stars

Telegram → ваш бот → профиль бота → **Баланс / Monetization** (или @BotFather → бот → **Balance**) → вывод через **Fragment** в **TON** на ваш кошелёк (Tonkeeper или @wallet).
Для вывода нужен **облачный пароль Telegram** (двухэтапная аутентификация). У Telegram есть минимальная сумма и период удержания — их видно на экране вывода.

## Команды

Для всех: `/start`, `/free`, `/buy`, `/mypro`, `/terms`, `/paysupport`, `/support`, `/about`, `/myid`.

Для вас (ADMIN_ID): `/admin`, `/files`, `/sales`,
`/reply id текст` — ответить покупателю, `/refund id` — вернуть Stars,
`/grant id` — выдать PRO вручную (например, если заплатили другим способом).

Покупатели пишут вам через `/paysupport текст` — сообщение приходит вам в бот вместе с данными покупки.

## Обновление версии

Новая версия: отправьте боту новые файлы с теми же именами (бот заменит старые).
Если меняется номер версии в имени (`0.5.0`), поменяйте имена в начале `worker.js` (блок `FILES`) и вставьте код заново.

## Безопасность

- `BOT_TOKEN`, `WEBHOOK_SECRET`, `CRYPTO_PAY_TOKEN` храните только в Cloudflare как **Secret**.
- Бот принимает запросы только от Telegram (по `WEBHOOK_SECRET`) и от Crypto Pay (по подписи).
- PRO выдаётся только после подтверждённой оплаты; повторные уведомления о той же оплате не дублируют продажу.

## Проверка кода

`node test/run.mjs` — 20 автотестов (имитация Telegram и Crypto Pay).
