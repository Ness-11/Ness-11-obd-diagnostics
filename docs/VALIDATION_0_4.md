# Weryfikacja 0.4 — 2026-10-08

## Wyniki

- 55 testów: 46 core JVM + 9 Android Robolectric (SDK 34), zero failures/errors/skipped.
- `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`: BUILD SUCCESSFUL. Po dodaniu końcowego testu integracyjnego powtórzono testy Android i lint: BUILD SUCCESSFUL.
- Android lint: zero błędów i fatal, 15 ostrzeżeń (między innymi dostępne nowsze wersje zależności; nie są błędami kompilacji).
- APK: pakiet `pl.obd.readonly`, versionCode 4 / versionName 0.4.0, minSdk 26, targetSdk 35.
- Podpis sprawdzony `apksigner verify`; ten sam certyfikat co 0.3, SHA-256: `bf79daac1e9fd1e1ea275ca11b5597e525e37c3652a59133f4113800574eb5a8`.
- SHA-256 APK: `7abe3732a1aa251b607db67736b325641dce64a9ad931bb53bcff13acee224e0` (10 787 149 bajtów).

## Nowe testy

Offsety, jednostki i maski 67/69/77/78/79/83/85/8B, realne zero kontra sensor nieobecny, pełna wymagana długość. Wieloramkowy simulator wysyła SF/FF/CF; brak końcowej ramki odrzuca całą odpowiedź. Skalowanie 4F: własne zakresy, zera, niepotwierdzony wymagany odczyt. Utrzymano listę read-only i odrzucenie Mode 04 / usług UDS / arbitralnego ATSH.

Skan DTC rozdziela poprawne puste wyniki, ucięty payload, NO DATA i brak odpowiedzi drugiego ECU. Historia i pakiet ZIP zachowują UTC/statusy/kody po odtworzeniu RecordingStore; odrzucono identyfikatory plików zawierające traversal.

Testy Android obejmują zapis w tle, stop usługi/rozłączenie, VIN w trakcie, domyślny skan DTC na początku zapisu, konfigurację 4F w połączeniu, nowe masked sensory, ręczny skan przy wyłączonym automatycznym oraz adnotację statusu regeneracji DPF. CSV nadal zawiera wyłącznie wybrane pomiary.

## Replay i symulacja

Odtworzono wcześniejszy fizyczny log: 2388 dozwolonych komend, 2291 payloadów OBD, 10 NO DATA, zero błędów parsera/dekodera. To regresja wcześniej zapisanych transakcji, nie próba nowych PID-ów ani nowego skalowania 4F na aucie.

Symulacja schedulera przez 180 s: 22 unikalne żądania odpowiadające ostatniemu CSV, RTT ECU 162 ms / ATRV 49 ms, przerwa 80 ms, cel szybki 500 ms. Po rozgrzaniu mediana RPM wyniosła 3275 ms, płynu 7873 ms, dystansu od kasowania DTC 63468 ms. RPM był szybszy od około 5,7 s w poprzednim CSV. **To model timingowy, nie pomiar szybkości nowego APK w samochodzie.** Więcej nowych PID-ów, retries i skany DTC zmieniają wynik. Test potwierdza brak zagłodzenia liczników i backoff/reset po błędach.

## Jeszcze do sprawdzenia fizycznie

Nowe parametry i zakresy 4F z ECU, zachowanie klona przy dłuższych odpowiedziach, skany DTC oraz stabilność w tle na telefonie. Szczegóły HARDWARE_TEST_PLAN.md. Nie deklarujemy pełnej diagnostyki VAG ani gwarantowanej częstotliwości odczytu. Katalog ma 81 sygnałów z 52 PID-ów i ATRV, nie oznacza to 81 dostępnych czujników w każdym aucie.
