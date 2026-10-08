# Wersja 0.4 — zakres i źródła, 2026-10-08

## Co wdrożono

Jedna kolejka ELM, odczyty według terminów: grupa szybka ma ustawiany cel 100–2000 ms, grupa średnia 5 s, liczniki 60 s. Są to terminy docelowe; liczba PID-ów, RTT i skany DTC mogą wydłużyć odstępy. Pierwsze próbki obejmują wszystkie wybrane PID-y. Scheduler wybiera najwcześniejszy zaległy termin, zapobiegając zagłodzeniu wolnych pomiarów. Po trzech kolejnych nieudanych odpowiedziach retry zwalnia do 10–60 s; poprawna odpowiedź przywraca normalne tempo. Timeout bez `>` nadal zamyka sesję — backoff nie pozwala wysyłać komend w rozsynchronizowanym strumieniu.

Skan 03/07/0A na początku zapisu i co najmniej co 60 s jest domyślnie włączony; można go wyłączyć w OBD przed nagrywaniem. Odstęp liczony od końca skanu, wykonanie po zakończeniu bieżącej komendy. Ręczny skan działa niezależnie od opcji automatycznej. Oznaczenie wyłączonego zapłonu wstrzymuje automatyczne skany i ocenę alertów, nie rozłącza adaptera. Wyniki w raporcie i DTC JSONL mają UTC, ECU, tryb, status, kody i uwagi. `OK` z pustą listą potwierdza wyłącznie brak kodów w tej konkretnej poprawnej odpowiedzi. NO_DATA, niekompletny odczyt lub brak odpowiedzi innego ECU nie potwierdzają braku błędów.

ZIP sesji zawiera CSV, raport Markdown, metadane, historię DTC (gdy wykonano skany) i utrwalone adnotacje (gdy istnieją). Pełny surowy log transmisji nadal eksportuje się osobno z Terminala. CSV zachowuje dotychczasowy układ dziewięciu kolumn. Nowe statusy `UNSUPPORTED` i `SCALE_UNKNOWN` mają pustą wartość. Nieobsługiwane sensory mają oddzielny licznik w statystykach, nie są traktowane jako utrata transmisji. Dostępność sensorów ustala się po pełnej odpowiedzi; brak bitu maski nie jest zerem.

Regeneracja DPF z PID 8B jest prezentowana jako stan 0/1 i adnotowana przy zmianie. To nie wywołuje regeneracji i nie oznacza automatycznie usterki. Typ ma znaczenie przy regeneracji w toku. Średni dystans między regeneracjami nie jest dystansem od ostatniej regeneracji; wyzwalacz procentowy nie jest masą sadzy. Nie ma nowych automatycznych reguł diagnozujących EGR, NOx, lambda lub DPF.

## Dokumentacja wykorzystana do nowych dekoderów

- [DashLogic — katalog J1979 producenta interfejsów](https://www.dashlogic.com/docs/technical/obdii_pids): układy danych i bity wsparcia PID 67 (temperatury płynu), 69 (EGR zadane/rzeczywiste/błąd), 77 (temperatury dolotu), 78/79 (EGT), 83 (NOx), 85 (zużycie i poziom reagenta oraz czas ostrzeżenia), 8B (status oczyszczania spalin). Każdy wymaga pełnej długości danych, również przy pojedynczym czujniku. W tabeli 67 występuje niespójność wzoru dla czujnika 2; offset bajtu C potwierdzono w normie, nie powielono literówki. Nie interpretujemy niepotwierdzonych kodów specjalnych jako alarmów.
- [SAE J1979-DA OCT2011 — dokument źródłowy, publiczna kopia](https://f30.bimmerpost.com/forums/attachment.php?attachmentid=1800282&d=1523076762): tabele B60 (PID 4F) i B83 (PID 67). PID 4F konfiguruje skalowanie narzędzia, nie jest telemetrią ani progiem awarii. Odczyt per ECU po bitmapach, przed Live. Dodatnie A/C/D zastępują zakresy EQR/prądu/MAP; zero wybiera domyślne skalowanie. Gdy ECU zgłasza 4F, ale odczyt jest niepełny, pola zależne pozostają niedostępne (`SCALE_UNKNOWN`) do ponownego odczytu bitmap/4F. Nie przeliczamy wstecz dawnych CSV bez potwierdzonej konfiguracji.
- [Ross-Tech — Advanced Measuring Values](https://www.ross-tech.com/vcds/tour/adv-meas-blocks.php): wybór pomiarów i kompromis szybkości, definicje zależne od ECU/ASAM. To podstawa kierunku architektury, nie źródło uniwersalnych adresów VAG.
- [OBD Auto Doctor — obsługiwane parametry](https://www.obdautodoctor.com/help/articles/supported-obd-parameters/): potwierdzenie zakresu standardowego OBD i zależności od wsparcia samochodu.

Dokumenty dostawców oraz norma służą do potwierdzania faktów protokołu. Nie dystrybuujemy kopii ich treści ani kodu innych projektów. Lista źródeł wcześniejszego etapu: SOURCES_AND_VAG.md.

## Granice

PID 88, identyfikacja producenta, ASAM/ODX, masa sadzy/popiół, ciśnienie różnicowe DPF i pełny skan ABS/skrzyni/gateway pozostają niewdrożone. Nie ma SessionControl, SecurityAccess, kasowania błędów, kodowania, adaptacji, RoutineControl ani testów elementów wykonawczych. Nowe dekodery wymagają próby na fizycznym ELM/ECU i porównania z wiarygodnym skanerem. Samo zgłoszenie PID w bitmapie nie gwarantuje, że klon adaptera dostarczy pełną odpowiedź.
