# Weryfikacja wersji 0.3 — 2026-10-05

## Build

Gradle 8.11.1, Temurin JDK 17, Android SDK 35 / Build Tools 35.0.0.
Polecenie `gradle :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` zakończone BUILD SUCCESSFUL. Log: BUILD_ATTEMPT.log.

## Testy

- pl.obd.core.AdvancedTelemetryTest: 7 testów, 0 niepowodzeń, 0 błędów.
- pl.obd.core.ParserTest: 12 testów, 0 niepowodzeń, 0 błędów.
- pl.obd.core.ElmSessionTest: 6 testów, 0 niepowodzeń, 0 błędów.
- pl.obd.core.ReadOnlyTest: 3 testów, 0 niepowodzeń, 0 błędów.
- pl.obd.core.DiagnosticAnalysisTest: 9 testów, 0 niepowodzeń, 0 błędów.
- pl.obd.readonly.RecordingStoreTest: 5 testów, 0 niepowodzeń, 0 błędów.
- pl.obd.readonly.DiagnosticEngineTest: 2 testów, 0 niepowodzeń, 0 błędów.

Łącznie **44 testy**, Android Robolectric SDK 34. Android lint: **0 błędów**, 14 ostrzeżeń (aktualizacje zależności, backup/ikony, sugestie KTX).

Nowe testy obejmują podtrzymanie/histerezę, rozdzielenie ECU, brak/starość danych, restart oceny, brak zgadywanych alertów mieszanki, adnotację zapłonu, ograniczenie historii, trwałość raportów, odtwarzanie starszych CSV, porównania oraz RPM z Live jako kontekst alertu bez dopisywania niezaznaczonego RPM do CSV. Testy rejestracji w tle nadal przechodzą.

## Rzeczywiste dane

- Dostarczony surowy log: 2388 dozwolonych komend, 2388 promptów, 0 nakładających się poleceń; replay nowego core: 2291 payloadów OBD, 10 NO DATA, 0 błędów parsera/dekodera.
- Cztery CSV: 1467 wierszy; 1461 OK i 6 NO_DATA. Porównanie surowych odpowiedzi: wszystkie 1467 wierszy zgodne.
- Analizator na czterech CSV: 0 alertów domyślnych. Wyłączenie zapłonu potwierdzone przez użytkownika; w analizie najdłuższej sesji dodano tę adnotację przed pierwszym NO_DATA.
- PID 34: przykład 1D C3 82 C6 → 0,232513 oraz 2,773438 mA. Zachowano wzór; doprecyzowano nazwę EQR, brak reguł mieszanki.

## APK

- OBD-Diagnostics-0.3.apk, pakiet pl.obd.readonly.
- versionCode 3 / versionName 0.3.0; minSdk 26, targetSdk 35.
- Podpis debug zgodny z v0.1/v0.2, apksigner zweryfikowany.
- SHA-256 `426d4d10bcd1b188c2aad88735aab3bcc9a08548e9e66be4bf65847a855a2b59`.

Nowe funkcje nie zostały przetestowane fizycznie na telefonie z adapterem/ECU. Replay nie potwierdza zachowania firmware podczas nowych PID-ów ani sterowania energią Androida. Plan sprzętowy: HARDWARE_TEST_PLAN.md. Nie implementowano transportu/profili VAG ani diagnostyki innych modułów auta.
