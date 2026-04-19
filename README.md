# AltCO2 Logger (Android MVP)

Минимальный Android-логгер для ночного сбора CO2/температуры по BLE с вашей прошивки `AltCO2`.

## Что делает

- Ищет BLE устройство с именем `AltCO2`
- Подключается к ESS (0x181A)
- Подписывается на notify:
  - CO2: `0x2B8C`
  - Temperature: `0x2A6E`
- Пишет данные в локальную базу Room
- Показывает live-значения, график CO2 и последние точки

## Сборка

1. Открыть папку `android_logger` в Android Studio.
2. Дождаться Sync Gradle.
3. `Build > Build APK(s)`.
4. Установить `app-debug.apk` на Android.

## Для ночной записи

- Дать Bluetooth/Notification permissions.
- Нажать `No Sleep` и разрешить исключение из энергосбережения.
- Нажать `Start` перед сном.

## Важно

Это MVP. Следующий шаг можно сделать экспорт в CSV/Share и выбор конкретного MAC-адреса устройства.
