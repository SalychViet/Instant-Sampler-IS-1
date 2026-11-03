# English install guides for ISL-1: python3 translate_install.py -> install_isl_en.html, install_try_en.html.
# Run after python3 build_install.py and IS1_EDITION=try python3 build_install.py. Every Russian phrase must be in EN.
import html
import re

EN = {
    "Установка {N}": "Installing {N}",
    "Как поставить приложение на телефон Android": "How to put the app on your Android phone",
    "{MB} МБ · версия 0.4.2 · Android 10 и новее": "{MB} MB · version 0.4.2 · Android 10 or newer",
    "Перенести файл": "Move the file", "на телефон": "to your phone", "Разрешить": "Allow", "установку": "the install",
    "Установить": "Install", "и открыть": "and open",
    "Займёт около 5 минут · версия инструкции от 3 ноября 2026": "Takes about 5 minutes · guide version of 3 November 2026",
    "Перед началом": "Before you start",
    "{N} не из Google Play, поэтому его ставят из файла": "{N} is not on Google Play, so you install it from an",
    "— это обычный «установщик» приложения для Android. Android попросит пару подтверждений: это нормально для любых приложений не из магазина.": "file, a normal app installer for Android. Android will ask for a couple of confirmations: that is usual for any app from outside the store.",
    "Что нужно": "What you need", "Подробно": "Details", "Телефон": "Phone",
    "или новее (Xiaomi, Samsung, Pixel и другие)": "or newer (Xiaomi, Samsung, Pixel and others)",
    "Место": "Space", "около": "about", "150 МБ": "150 MB", "свободной памяти (приложение + звуки)": "of free storage (app and sounds)",
    "Файл": "File", "— он лежит рядом с этой инструкцией": "— it comes with this guide", "Время": "Time", "около 5 минут": "about 5 minutes",
    "Как перенести файл на телефон — выберите удобный способ": "How to move the file to your phone: pick any way",
    "Отправьте файл себе в": "Send the file to your", "«Избранное»": "“Saved Messages”", "с компьютера и откройте его на телефоне.": "from a computer and open it on your phone.",
    "проще всего": "easiest", "🔌 Кабель USB": "🔌 USB cable", "Подключите телефон к компьютеру и скопируйте файл в папку": "Connect the phone to a computer and copy the file to the folder",
    "☁ Google Диск": "☁ Google Drive", "Загрузите файл на Диск с компьютера и скачайте его на телефоне.": "Upload the file to Drive from a computer and download it on your phone.",
    "Важно.": "Important.", "Если приложение {N} уже стоит на телефоне —": "If {N} is already on your phone,", "не удаляйте его": "do not uninstall it",
    "перед установкой. Новая версия встанет поверх, и все ваши пады, паттерны и песни сохранятся (подробнее — раздел 4).": "before installing. The new version goes over the old one and keeps your pads, patterns and songs (see section 4).",
    "Перенесите файл на телефон": "Move the file to your phone", "Способ А: через Telegram «Избранное»": "Way A: Telegram “Saved Messages”",
    "На компьютере откройте Telegram →": "On a computer open Telegram →", "Избранное": "Saved Messages", "→ скрепка 📎 → выберите файл": "→ paperclip 📎 → choose the file", "→ Отправить.": "→ Send.",
    "1. Отправьте файл себе": "1. Send the file to yourself", "{MB} МБ · APK": "{MB} MB · APK", "2. На телефоне откройте «Избранное» и коснитесь файла": "2. On your phone open “Saved Messages” and tap the file",
    "Открыть с помощью": "Open with", "Установщик пакетов": "Package installer", "Только сейчас": "Just once", "Всегда": "Always", "3. Выберите «Установщик пакетов»": "3. Choose “Package installer”",
    "Способ Б: через кабель USB": "Way B: USB cable", "Использовать USB для": "Use USB for", "◉ Передача файлов": "◉ File transfer", "○ Передача фото": "○ Photo transfer", "○ Только зарядка": "○ Charging only",
    "1. На телефоне выберите «Передача файлов»": "1. On the phone choose “File transfer”", "Mac: программа": "Mac: the app", "Windows: телефон появится в «Этот компьютер».": "Windows: the phone appears in “This PC”.",
    "Скопируйте": "Copy", "в папку": "to the folder", "2. Скопируйте файл в Download": "2. Copy the file to Download", "Файлы › Download": "Files › Download", "{MB} МБ": "{MB} MB",
    "3. Откройте «Файлы» → Download → файл": "3. Open “Files” → Download → the file", "Совет.": "Tip.", "С Google Диска всё так же: откройте файл в приложении «Диск» → ⋮ →": "Google Drive works the same: open the file in the Drive app → ⋮ →",
    "Скачать": "Download", ", затем коснитесь скачанного файла в уведомлении или в папке Download.": ", then tap the downloaded file in the notification or in the Download folder.",
    "Разрешите установку из этого источника": "Allow installing from this source",
    "При первой установке Android спросит, можно ли приложению, через которое вы открыли файл (Telegram, «Файлы» или «Диск»), устанавливать программы. Это разрешение нужно дать один раз.": "On the first install Android asks whether the app you opened the file with (Telegram, Files or Drive) may install apps. You only need to allow this once.",
    "Установка заблокирована": "Install blocked", "В целях безопасности на телефоне запрещена установка неизвестных приложений из этого источника.": "For your security, your phone is not allowed to install unknown apps from this source.",
    "Отмена": "Cancel", "Настройки": "Settings", "1. Нажмите «Настройки»": "1. Tap “Settings”", "← Установка неизвестных приложений": "← Install unknown apps", "Разрешить установку из этого источника": "Allow from this source",
    "Установка приложений из неизвестных источников может навредить телефону и вашим данным.": "Installing apps from unknown sources can harm your phone and your data.", "2. Включите переключатель": "2. Turn the switch on",
    "Установить приложение?": "Install this app?", "3. Вернитесь назад — появится установка": "3. Go back: the install dialog appears",
    "то же разрешение можно включить вручную:": "you can also turn this on by hand:", "Настройки → Приложения → Разрешения → Установка неизвестных приложений": "Settings → Apps → Permissions → Install unknown apps",
    "(на некоторых версиях —": "(on some versions", "Защита конфиденциальности → Особые разрешения": "Privacy protection → Special permissions", ") → выберите Telegram или «Файлы» → включите.": ") → choose Telegram or Files → turn it on.",
    "Проверьте.": "Check.", "Надписи могут немного отличаться в зависимости от модели и версии Android, но смысл везде одинаковый: «разрешить установку из этого источника».": "Wording differs a little between phones and Android versions, but the meaning is always “allow installing from this source”.",
    "Установите приложение": "Install the app", "1. Нажмите «Установить»": "1. Tap “Install”", "Приложение заблокировано": "App blocked",
    "Google Play Защита не знает этого разработчика. Приложения от неизвестных разработчиков могут быть небезопасны.": "Google Play Protect does not recognise this developer. Apps from unknown developers can be unsafe.",
    "Подробнее": "More details", "2. Если появилась Play Защита — «Подробнее»": "2. If Play Protect appears, tap “More details”", "Всё равно установить": "Install anyway", "3. → «Всё равно установить»": "3. → “Install anyway”",
    "Проверка безопасности": "Security check", "Угроз не обнаружено": "No threats found", "Xiaomi: проверка — потом «Установить»": "Xiaomi: a check, then “Install”", "Приложение установлено": "App installed", "Готово": "Done", "Открыть": "Open",
    "4. Готово — нажмите «Открыть»": "4. Done: tap “Open”", "Иконка {N} появится на рабочем столе": "The {N} icon appears on your home screen",
    "Почему Play Защита предупреждает? Приложение подписано не через Google Play, а вашим собственным ключом разработчика. Это ожидаемо для своего приложения: просто подтвердите установку.": "Why does Play Protect warn you? The app is signed by its developer rather than through Google Play. That is expected for an app from outside the store: just confirm the install.",
    "Первый запуск и обновления": "First launch and updates", "Разрешить приложению {N} записывать аудио?": "Allow {N} to record audio?", "Запретить": "Don't allow", "При использовании": "While using the app",
    "Микрофон — нужен для записи семплов": "Microphone: needed to record samples", "Разрешить приложению {N} отправлять уведомления?": "Allow {N} to send notifications?", "Уведомления — для записи звука других приложений": "Notifications: needed to record other apps",
    "Поверните телефон": "Turn the phone", "горизонтально": "sideways", "— {N} работает в альбомной ориентации.": ": {N} works in landscape.", "Держите телефон горизонтально": "Hold the phone sideways",
    "Как обновить {N} на новую версию": "How to update {N}", "Получите новый файл APK (так же, как в шаге 1).": "Get the new APK file (as in step 1).", "Откройте его и нажмите": "Open it and tap", "«Обновить»": "“Update”",
    ". Старую версию удалять": ". You do", "не нужно": "not need to uninstall the old version", "Пады, паттерны, песня, эффекты и настройки останутся на месте.": "Pads, patterns, the song, effects and settings stay in place.",
    "Не удаляйте приложение перед обновлением: при удалении Android стирает все данные {N} (пады, паттерны, песни). Если всё же нужно удалить — сначала сохраните треки через": "Do not uninstall before updating: uninstalling erases all {N} data (pads, patterns, songs). If you must uninstall, first save your tracks with",
    "Сохранённые через EXPORT треки лежат в папке": "Tracks saved with EXPORT are in the folder", "Музыка/IS-1": "Music/IS-1", "и при удалении приложения не пропадают.": "and stay there if you uninstall the app.",
    "Если что-то пошло не так": "If something goes wrong", "Что видите": "What you see", "Что делать": "What to do",
    "«Приложение не установлено» / «Конфликт с существующим пакетом»": "“App not installed” / “Package conflicts with an existing package”", "Новая версия подписана другим ключом, чем установленная. Сохраните треки через EXPORT, удалите старую {N} и установите заново.": "The new version is signed with a different key. Save your tracks with EXPORT, uninstall the old {N} and install again.",
    "«Синтаксическая ошибка» / «Ошибка при синтаксическом анализе пакета»": "“Parse error” / “There was a problem parsing the package”", "Файл скачался не полностью. Скачайте его ещё раз (размер должен быть около {MB} МБ).": "The file did not download fully. Download it again (it should be about {MB} MB).",
    "Файл открывается как документ или архив": "The file opens as a document or archive", "Откройте его через приложение «Файлы» (Download) и выберите «Установщик пакетов».": "Open it from the Files app (Download) and choose “Package installer”.",
    "Кнопка «Установить» неактивна": "The “Install” button is greyed out", "Закройте плавающие окна и пузыри чатов поверх экрана (например, подсказки Telegram) и попробуйте снова.": "Close floating windows and chat bubbles on the screen (Telegram hints, for example) and try again.",
    "Нет кнопки «Всё равно установить»": "No “Install anyway” button", "Play Защита → ⋮ → Настройки → временно выключите «Проверять приложения», установите {N} и включите проверку обратно.": "Play Protect → ⋮ → Settings → briefly turn off “Scan apps”, install {N}, then turn scanning back on.",
    "Нет звука": "No sound", "Проверьте громкость мультимедиа (кнопки громкости во время игры) и фейдеры SEQ и LOOPER в приложении.": "Check the media volume (volume buttons while playing) and the SEQ and LOOPER faders in the app.",
    "Запись с микрофона не идёт": "Microphone recording does not work", "Настройки → Приложения → {N} → Разрешения → Микрофон → «Разрешить при использовании».": "Settings → Apps → {N} → Permissions → Microphone → “Allow while using the app”.",
    "Не пишется звук других приложений": "Other apps are not recorded", "При записи SRC:INT Android каждый раз спрашивает разрешение на запись экрана — нажмите «Начать». Некоторые приложения (DRM) запись запрещают.": "For SRC INT, Android asks permission to capture the screen: tap “Start”. Some apps (DRM) forbid recording.",
    "Коротко": "In short", "Файл → «Избранное» в Telegram": "File → “Saved Messages” in Telegram", "Коснуться файла → «Настройки» → разрешить": "Tap the file → “Settings” → allow", "«Установить» → «Открыть»": "“Install” → “Open”",
}

for src, name, mb, out in (("install_isl.html", "ISL-1", "44,7", "install_isl_en.html"), ("install_try.html", "ISL-1 TRY", "44,7", "install_try_en.html")):
    doc = open(src, encoding="utf-8").read()
    head, body = doc.split("</head>", 1)
    missing = []

    def tr(m):
        raw = html.unescape(m.group(1))
        s = raw.strip()
        if not re.search("[а-яА-ЯёЁ]", s):
            return m.group(0)
        key = s.replace(name, "{N}").replace(mb, "{MB}")
        if key not in EN:
            missing.append(key)
            return m.group(0)
        new = EN[key].replace("{N}", name).replace("{MB}", mb.replace(",", "."))
        return ">" + html.escape(raw.replace(s, new), quote=False) + "<"

    body = re.sub(r">([^<]+)<", tr, body).replace('class="ru install isl"', 'class="en install isl"')
    assert not missing, missing
    open(out, "w", encoding="utf-8").write(head.replace('lang="ru"', 'lang="en"').replace("Установка", "Installing") + "</head>" + body)
    print(out)
