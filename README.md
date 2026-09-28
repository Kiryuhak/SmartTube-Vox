<div align="center">
  <img src="docs/images/smarttube-vox-hero.png" alt="SmartTube VOX — видео, голос, перевод" width="780">
</div>

<h1 align="center">SmartTube VOX</h1>

<p align="center">
  <strong>Независимая неофициальная версия SmartTube для Android TV и Google TV с расширенным закадровым переводом Яндекса.</strong>
</p>

<p align="center">
  <a href="https://github.com/Kiryuhak/SmartTube-Vox/releases/latest"><strong>Скачать</strong></a> ·
  <a href="https://github.com/Kiryuhak/SmartTube-Vox/releases/latest">Последний релиз</a> ·
  <a href="CHANGELOG.md">История изменений</a> ·
  <a href="https://github.com/Kiryuhak/SmartTube-Vox/issues">Сообщить об ошибке</a> ·
  <a href="docs/BUILDING.md">Инструкция по сборке</a>
</p>

<p align="center">
  <a href="https://github.com/Kiryuhak/SmartTube-Vox/releases/latest"><img src="https://img.shields.io/github/v/release/Kiryuhak/SmartTube-Vox?label=%D0%92%D0%B5%D1%80%D1%81%D0%B8%D1%8F" alt="Версия"></a>
  <img src="https://img.shields.io/badge/%D0%9F%D0%BB%D0%B0%D1%82%D1%84%D0%BE%D1%80%D0%BC%D0%B0-Android%20TV%20%7C%20Google%20TV-blue" alt="Платформа Android TV и Google TV">
  <a href="https://github.com/Kiryuhak/SmartTube-Vox/actions/workflows/CI.yml"><img src="https://img.shields.io/github/actions/workflow/status/Kiryuhak/SmartTube-Vox/CI.yml?branch=main&amp;label=%D0%9F%D1%80%D0%BE%D0%B2%D0%B5%D1%80%D0%BA%D0%B8" alt="Проверки"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/Kiryuhak/SmartTube-Vox?label=%D0%9B%D0%B8%D1%86%D0%B5%D0%BD%D0%B7%D0%B8%D1%8F" alt="Лицензия MIT"></a>
  <a href="https://github.com/Kiryuhak/SmartTube-Vox/releases"><img src="https://img.shields.io/badge/%D0%9E%D0%B1%D0%BD%D0%BE%D0%B2%D0%BB%D0%B5%D0%BD%D0%B8%D1%8F-%D0%B2%20%D0%BF%D1%80%D0%B8%D0%BB%D0%BE%D0%B6%D0%B5%D0%BD%D0%B8%D0%B8-success" alt="Обновления в приложении"></a>
</p>

## 📺 Что такое SmartTube VOX

SmartTube VOX сохраняет телевизионный интерфейс [SmartTube](https://github.com/yuliskov/SmartTube) и добавляет закадровый перевод Яндекса. У приложения отдельный пакет и собственный канал обновлений: его можно установить рядом с оригинальным SmartTube. Проект развивается независимо и не является официальной версией SmartTube, Яндекса или Google.

## ✨ Основные возможности

- Обычная озвучка и **«Живой голос»** Яндекса.
- Подготовка перевода для видео, у которых ещё нет готовой озвучки, с ожиданием результата в плеере.
- Синхронизация перевода с паузой, перемоткой и изменением скорости воспроизведения.
- Настройка соотношения оригинального звука и перевода; восстановление обычного звука после отключения озвучки.
- Корректное завершение прежней озвучки при смене ролика и обработка временных сетевых ошибок.
- Отдельные обновления SmartTube VOX через GitHub Releases.

## 🎙 Закадровый перевод Яндекса

Плеер запрашивает озвучку для текущего видео. Если дорожка готова, она воспроизводится вместе с роликом. Если готового перевода пока нет, приложение может начать его подготовку и дождаться результата. Доступность перевода зависит от внешнего сервиса Яндекса; обычный режим может работать без Яндекс ID.

## 🗣 «Живой голос»

Для «Живого голоса» войдите в SmartTube и Яндекс ID: **Настройки → Плеер → Закадровый перевод (Яндекс) → Войти в Яндекс**. Обычный перевод остаётся доступен и без этого режима.

## 📦 Установка

Скачайте APK на [странице последнего релиза](https://github.com/Kiryuhak/SmartTube-Vox/releases/latest). Если не знаете архитектуру устройства, выберите [универсальный APK](https://github.com/Kiryuhak/SmartTube-Vox/releases/latest/download/smarttube_vox.apk).

| Файл | Для какого устройства |
|---|---|
| `smarttube_vox.apk` | Универсальная сборка; подходит, если архитектура неизвестна. |
| `smarttube_vox-arm64-v8a.apk` | Современное 64-битное устройство. |
| `smarttube_vox-armeabi-v7a.apk` | 32-битный или многие старые телевизоры и приставки. |

Текущая версия: **`32.56-vot.3`** · пакет: **`io.github.kiryuhak.smarttubevot.stable`** · код версии: **`2446003`**. Сборку с тем же пакетом и ключом подписи можно обновить установкой APK поверх неё.

## 🔄 Обновления

Обновления публикуются в [GitHub Releases](https://github.com/Kiryuhak/SmartTube-Vox/releases). Основной файл для обновления — `smarttube_vox.apk`; сведения о версии приложение получает через `smarttube_vox.json`. Если автоматическое обновление не сработало, установите актуальный APK вручную.

## 🧩 На чём основан проект

Основа приложения — [SmartTube](https://github.com/yuliskov/SmartTube) от yuliskov и участников проекта. Опыт [vsvoice/SmartTube](https://github.com/vsvoice/SmartTube) и открытые работы сообщества VOT помогли развить интеграцию перевода. Подробные сведения об использованных и адаптированных компонентах сохранены в [CREDITS.md](CREDITS.md) и [NOTICE](NOTICE).

## ❤️ Благодарности

| Проект | Авторы | Вклад в развитие VOX |
|---|---|---|
| [SmartTube](https://github.com/yuliskov/SmartTube) | yuliskov и участники | Основа телевизионного плеера. |
| [vsvoice/SmartTube](https://github.com/vsvoice/SmartTube) | vsvoice | Ранний опыт интеграции перевода. |
| [voice-over-translation](https://github.com/ilyhalight/voice-over-translation) | ilyhalight; sodapng указан в [NOTICE](NOTICE) | Поведение перевода и восстановление звука. |
| [vot-cli](https://github.com/FOSWLY/vot-cli) и [vot.js](https://github.com/FOSWLY/vot.js) | FOSWLY | Формат запросов и обработка исходного аудио. |
| [dual-vot-patches](https://github.com/sashade8-ship-it/dual-vot-patches) | sashade8-ship-it | Архитектура и синхронизация перевода. |
| [revanced-patches](https://github.com/anddea/revanced-patches) | anddea, Jav1x | Ориентир для реализации API Яндекс VOT. |
| [morphe-patches-yavot](https://github.com/123jjck/morphe-patches-yavot) и [morphe-patches](https://github.com/MorpheApp/morphe-patches) | 123jjck, MorpheApp | Интеграция VOT и основа патчей. |

Это благодарности и атрибуция, а не указание на официальную поддержку SmartTube VOX перечисленными авторами.

## 📄 Лицензия и сторонние компоненты

Лицензия репозитория — [MIT](LICENSE). У сторонних компонентов могут быть собственные условия и обязательные уведомления: они приведены в [NOTICE](NOTICE) и [CREDITS.md](CREDITS.md). Ознакомьтесь с ними перед распространением производной сборки.

## 🐛 Ошибки и предложения

Сообщить об ошибке или предложить улучшение можно через [GitHub Issues](https://github.com/Kiryuhak/SmartTube-Vox/issues). Инструкции для разработчиков — в [docs/BUILDING.md](docs/BUILDING.md).
