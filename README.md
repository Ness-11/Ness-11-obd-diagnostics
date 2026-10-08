# OBD Diagnostics 0.4 — Android, READ ONLY

Kotlin, Jetpack Compose, coroutines, Bluetooth Classic SPP, ELM327. Odczyt standardowego OBD SAE J1979, wykresy i rejestrator wybranych parametrów. Nadal bez kasowania DTC, kodowania, adaptacji, SecurityAccess, testów wykonawczych, arbitralnych CAN i UDS.

Nowe funkcje, definicje i odnośniki do dokumentacji: [docs/UPDATE_0_4.md](docs/UPDATE_0_4.md).

## Instalacja aktualizacji

[Pobierz OBD-Diagnostics-0.4.apk](https://raw.githubusercontent.com/Ness-11/Ness-11-obd-diagnostics/main/releases/OBD-Diagnostics-0.4.apk). Otwórz go na telefonie i zainstaluj jako aktualizację. Wymagany Android 8.0+ (API 26). Pakiet `pl.obd.readonly`, versionCode 4, ten sam certyfikat co wcześniejsze wersje — istniejące dane aplikacji zostają zachowane. Nie trzeba instalować Android Studio na telefonie.

[Pobierz kompletny projekt Android Studio ZIP](https://raw.githubusercontent.com/Ness-11/Ness-11-obd-diagnostics/main/releases/OBD-Diagnostics-0.4-projekt.zip). Kod jest też dostępny bezpośrednio w tym repozytorium. Plik APK z kompilacji CI/własnego komputera może mieć inny certyfikat; opublikowane aktualizacje APK zachowują dotychczasowy certyfikat.

## Obsługa

1. **Adapter:** sparuj urządzenie w ustawieniach Androida, odśwież listę, połącz. Dostępna jest też wyraźnie oznaczona demonstracja z fikcyjnymi pomiarami.
2. **Parametry:** zaznacz osobno **Live** (wykresy) i **Zapis** (CSV). Ustawienia są zapamiętywane. Nieobsługiwane przez ECU pozycje są oznaczone i nie są odpytywane. Presety: Wszystkie dostępne, Podstawowe, Dolot, Paliwo. W trakcie zapisu jego wybór jest zamrożony; wybór wykresów można nadal zmieniać.
3. **Live:** Start Live uruchamia odczyty. Wykresy pokazują 30/60/180 sekund, osobne jednostki i autoskalowanie. Dotknij wykresu lub przesuwaj kursor poziomo. Pod wykresem są min/max/średnia z widocznego okna. Brak odpowiedzi tworzy przerwę, nie zero. Po zatrzymaniu wykres pozostaje na ostatnim oknie.
4. **Zapisz CSV:** uruchamia zapis także po wygaszeniu ekranu lub przejściu do innej aplikacji. Powiadomienie ma przycisk Stop. Telefon może poprosić o uprawnienia Bluetooth i powiadomień. Stop zapisu zamyka CSV; jeśli aplikacja jest widoczna, Live może dalej pracować. Zatrzymaj wszystko kończy odczyty i zapis.
5. **Zapisy:** eksport „Sesja ZIP” łączy CSV, raport, metadane i historię DTC; pełny raw TSV eksportuj osobno w Terminalu.  lista lokalnych sesji, liczba parametrów i wierszy, eksport CSV także po rozłączeniu. Eksport aktywnej sesji jest migawką do chwili eksportu. Zapis rozróżnia dane DEMO.
6. **OBD:** VIN, DTC zapisane/oczekujące/trwałe oraz monitory gotowości/MIL. Skan ręczny wstrzymuje polling na czas swojej operacji i potem odczyty wracają; CSV pozostaje aktywny. NO DATA nie oznacza braku usterek. Gotowość monitorów nie oznacza oceny sprawności auta.
7. **Terminal:** dozwolone polecenia odczytu i eksport pełnego logu TX/RX do TSV. Surowy log jest niezależny od wybranego CSV i nie jest skracany do ekranu terminala.

8. **Analiza:** zestawienie bieżącego połączenia (min/średnia/max, OK/braki, średni odstęp i RTT), historia alertów, konfigurowalne progi. Wybierz „Wszystkie dostępne”, następnie Start Live / Zapisz CSV, aby monitorować wszystkie dostępne pomiary z katalogu. Bitmapy bez dekodera są jawnie wymienione w Parametrach.
9. **Zapisy → Zestawienie i alerty / Eksportuj raport:** raport Markdown powstaje przy zakończeniu zapisu; aktywny raport jest migawką. Starsze CSV mogą być analizowane statystycznie bez wymyślania historycznych alertów. Porównanie obejmuje ostatnie maksymalnie trzy zakończone sesje tego samego typu (OBD/DEMO), oddziela ECU i pokazuje brak wspólnego parametru jako —.

Zakładki można przesuwać poziomo, aby dotrzeć do Live/Adapter/Terminal.

## Parametry i częstotliwość

Katalog zawiera **81 sygnałów (52 PID-y + ATRV)**, z czujnikami EGR, EGT, NOx, AdBlue i statusem DPF, w tym dotychczasowe RPM, prędkość, płyn, obciążenie, przepustnicę, napięcie ECU/adaptera, oraz MAP, MAF, temperaturę dolotu/otoczenia/oleju, korekty paliwowe banków 1/2, ciśnienie paliwa/szyny, EQR i prąd szerokopasmowej sondy B1S1, wyprzedzenie zapłonu, poziom paliwa, czasy i dystanse OBD, położenia pedału i zadaną przepustnicę.

Dostępność zależy od bitmap danego ECU. PID 34 daje dwa sygnały z jednego żądania — nie jest odpytywany dwa razy. MAP jest ciśnieniem bezwzględnym, nie gotowym odczytem doładowania. PID 23 ma jednostkę kPa. Nowe wartości DPF/EGR pochodzą ze standardowych PID-ów; nie odgadujemy danych producenta.

Jedno polecenie naraz, dopiero po `>`. Scheduler: szybkie parametry według ustawianego celu, temperatury/napięcie co 5 s, liczniki co 60 s; minimum 80 ms między odczytami. Częstotliwość jest zależna od RTT i liczby PID-ów. Analiza pokazuje rzeczywiste średnie odstępy. Po kolejnych nieudanych odpowiedziach powtórki zwalniają. Brak promptu nadal zamyka sesję. Świeżość danych i luki wykresu są oceniane osobno dla parametrów.

## Zasady alertów

Progi użytkownika, nie limity z dokumentacji konkretnego silnika: domyślnie płyn 110 °C, olej 130 °C, ECU ≤11,5 V / ≥15,5 V, korekty paliwowe poza ±20%, podtrzymanie 10 s. Histereza wynosi 3 °C / 0,3 V / 3 punkty procentowe. Niskie napięcie i korekty wymagają świeżego RPM >500 z tego samego ECU. Napięcie 12–13 V podczas pracy nie jest automatycznie traktowane jako awaria ładowania. Brak PID lub nieaktualna próbka przerywa ocenę, nie potwierdza powrotu do normy.

Dostępność reguły zależy od wybranych pomiarów; preset Wszystkie dostępne ułatwia pokrycie. RPM widoczny tylko w Live może służyć jako kontekst alertu w raporcie zapisu; nie jest przez to dopisywany do CSV. Progi są zapamiętywane i zamrożone podczas zapisu. Stan alertu jest widoczny w aplikacji i w istniejącym powiadomieniu rejestratora, bez alarmu dźwiękowego. Po zakończeniu Live ocena jest przerywana.

Przycisk „Oznacz: zapłon wyłączony” dodaje adnotację użytkownika, wstrzymuje alerty parametrów i zachowuje braki CSV. Nie wysyła żadnej komendy do auta. Aplikacja nie uznaje samodzielnie NO DATA za dowód wyłączenia zapłonu. „Zapłon włączony” wymaga nowego ciągu próbek do oceny. Status MIL/DTC-count/readiness jest odczytywany co 30 s podczas Live, jeśli bitmapa zawiera PID 01; jest prezentowany i adnotowany przy zmianie. Szczegółowe DTC skanuje się ręcznie oraz opcjonalnie na początku zapisu i co 60 s. W OBD można wyłączyć automatyczny skan przed zapisem. Historia skanów trafia do raportu i pakietu ZIP.

PID 34 ma opis EQR, bez obietnicy fizycznej lambda dla konkretnego ECU. PID 4F jest odczytywany po bitmapach i może zmienić skalowanie EQR/prądu/MAP. Bez wsparcia 4F lub przy zerach zachowane są domyślne wzory AB/32768 oraz CD/256−128 mA. Niepotwierdzony wymagany 4F blokuje zależne odczyty do ponownego odczytu bitmap. Zachowano id `34.lambda` dla kompatybilności wyborów i wcześniejszych CSV. Brak reguł „bogata/uboga mieszanka” na podstawie tego pola, brak automatycznego odwracania wartości.

Raport ma statystyki całej sesji, ostatnie 200 alertów i 200 adnotacji. Średnia jest arytmetyczna, nie ważona czasem. Porównywanie sesji z innych tras/obciążeń nie stanowi diagnozy uszkodzenia.

## CSV i surowy log

CSV jest UTF-8, separator przecinek, liczby z kropką. Format długi: jeden wiersz na pomiar.

```text
utc,elapsed_ms,ecu,signal_id,name,value,unit,status,latency_ms
2026-10-05T17:00:00Z,1200,7E8,0C,RPM,800.000000,rpm,OK,150
2026-10-05T17:00:02Z,3200,7E8,0C,RPM,,rpm,NO_DATA,200
```

`elapsed_ms` liczy czas od rozpoczęcia zapisu; UTC jest czasem odebrania/dekodowania próbki na telefonie, nie zegarem ECU. `latency_ms` obejmuje ELM/Bluetooth. Statusy OK/NO_DATA/INVALID/ERROR/UNSUPPORTED/SCALE_UNKNOWN; niepoprawny odczyt ma puste value. Zapis zawiera wyłącznie zaznaczone, dostępne sygnały, rozdzielone według ECU. Parametry widoczne tylko na Live nie trafiają do CSV.

Pliki są lokalne w prywatnym `files/recordings`, z metadanymi sesji. Każdy wiersz jest dopisywany z zamknięciem strumienia; nie ma buforowania całej sesji w RAM. Sesja przerwana przez zabicie procesu jest oznaczana jako przerwana. Historia wykresu ma maksymalnie 2000 punktów na sygnał/ECU; CSV i surowy log nie są ograniczane tym limitem. W MVP nie ma usuwania/importu/odtwarzania CSV na wykresie; pliki można eksportować do własnych narzędzi analizy.

Surowy log w `files/sessions` jest append-only TSV: UTC, TX/RX/EVENT i base64 oryginalnych bajtów. Zawiera również CR/LF i `>` oraz niekompletne odpowiedzi. Widok terminala ma ostatnie 300 fragmentów. Odtwarzanie bajtów:

```sh
python3 tools/decode_log.py obd-raw.tsv --direction RX --output rx.bin
```

## Zapis w tle i zakończenie sesji

Sesję posiada application-owned DiagnosticEngine. RecordingService utrzymuje aktywny zapis jako foreground service connectedDevice i ma powiadomienie Stop. Ograniczony czasowo partial wake lock jest odnawiany tylko podczas zapisu i zwalniany przy końcu usługi. Brak automatycznego restartu/reconnect. Utrata połączenia, błąd odczytu albo jawne rozłączenie kończą zapis; wcześniej zapisane pliki zostają.

Bez aktywnego zapisu przejście aplikacji w tło wstrzymuje polling. Po powrocie należy uruchomić Live ponownie. System/producent telefonu może zakończyć aplikację — nie traktujemy tego jako gwarancji nieprzerwanego działania na każdym urządzeniu.

## Budowanie

Android Studio Meerkat 2024.3.2+; pełny JDK 17 lub 21, SDK Platform 35 / Build Tools 35.0.0. Otwórz katalog projektu, wykonaj Gradle Sync i wybierz `app`. `local.properties` jest lokalny i nie znajduje się w archiwum.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Windows: `gradlew.bat` z tymi samymi zadaniami. APK: `app/build/outputs/apk/debug/app-debug.apk`. Testy Android używają Robolectric SDK 34; pierwsze uruchomienie wymaga dostępu do Maven/Google Maven. Oficjalny wrapper Gradle 8.11.1 z kontrolą SHA-256.

## Weryfikacja i zakres

**55 testów przeszło** ([szczegóły](docs/VALIDATION_0_4.md)); kompilacja APK i Android lint zakończone poprawnie. Testy obejmują kolejkę, odrzucenie zapisów ECU, parsery, nowe dekodery, CSV, przerwy wykresów, zaznaczone sygnały, trwałość plików, zapis po ukryciu UI, odczyt VIN podczas zapisu, zatrzymanie usługą i rozłączeniem. Odtworzono też wcześniejszy surowy log: 2388 dozwolonych komend, 2291 poprawnych payloadów OBD, 0 błędów parsera/dekodera. Wszystkie 1467 wierszy CSV porównano z logiem, a analizator uruchomiono na czterech sesjach.

Nowe PID-y, wykresy i fizyczne działanie w tle wymagają próby na telefonie/adapterze. Robolectric nie jest testem rzeczywistego Bluetooth ani zachowania baterii/OEM. Szczegóły: [VALIDATION.md](docs/VALIDATION.md), [ARCHITECTURE.md](docs/ARCHITECTURE.md), [HARDWARE_TEST_PLAN.md](docs/HARDWARE_TEST_PLAN.md).

To nadal standardowe OBD silnika, nie pełny skan VAG. Warstwy ISO-TP/UDS/VAG i profile vLinker/OBDLink pozostają dalszym etapem. Nie ma operacji zmieniających ECU.

## Dokumentacja i zakres VAG

Źródła, ustalenia i plan profili ECU: [docs/SOURCES_AND_VAG.md](docs/SOURCES_AND_VAG.md). Nie ma obecnie identyfikacji DID, odczytów DPF/EGR przez usługi producenta, skanu ABS/skrzyni/gateway ani deklaracji kompletnego pokrycia auta. Katalog OBD nie jest katalogiem wszystkich parametrów producenta.
