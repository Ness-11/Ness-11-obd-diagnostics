# OBD Diagnostics 0.3

[Pobierz aplikację na Androida — APK 0.3](https://github.com/Ness-11/Ness-11-obd-diagnostics/raw/refs/heads/main/releases/OBD-Diagnostics-0.3.apk)

## Instalacja

Pobierz APK na telefon, otwórz pobrany plik i zainstaluj jako aktualizację. Wymagany Android 8.0 lub nowszy. Jeżeli Android poprosi, zezwól wybranej przeglądarce na instalację aplikacji z tego źródła.

Pakiet: `pl.obd.readonly`; wersja 0.3.0, versionCode 3. APK ma ten sam podpis debug co poprzednie wersje 0.1/0.2.

## Funkcje

- Bluetooth Classic SPP i adapter ELM327, jedno polecenie naraz do promptu `>`.
- VIN, DTC bez kasowania, bitmapy PID, Live Data i wykresy.
- Wybór parametrów Live/CSV, katalog 46 sygnałów filtrowany przez bitmapę ECU.
- Zapis CSV w tle, zestawienia min/średnia/max, jakość danych, porównanie sesji i raporty.
- Konfigurowalne alerty temperatury/napięcia/korekt, podtrzymanie i histereza.
- Oznaczenie wyłączenia zapłonu, status MIL i monitorów co 30 s, pełny surowy log ELM.

Parametry → Wszystkie dostępne → Live → Zapisz CSV. Wyniki: Analiza i Zapisy.

## Zakres i weryfikacja

Aplikacja jest **READ-ONLY**: bez kasowania DTC, kodowania, adaptacji, SecurityAccess, testów wykonawczych i zapisów ECU. Progi użytkownika są wskazówkami, nie fabrycznymi limitami ani diagnozą uszkodzenia. Pełna diagnostyka producenta VAG i innych modułów nie jest jeszcze zaimplementowana.

Kompilacja, Android lint i 44 testy przeszły. Odtworzono rzeczywiste logi; nowe funkcje wymagają sprawdzenia na fizycznym telefonie i adapterze. APK nie zawiera przesłanych przez użytkownika logów auta.

SHA-256 APK: `426d4d10bcd1b188c2aad88735aab3bcc9a08548e9e66be4bf65847a855a2b59`.
