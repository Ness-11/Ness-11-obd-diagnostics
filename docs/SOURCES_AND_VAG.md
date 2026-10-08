# Źródła i plan diagnostyki VAG — 2026-10-05

Źródła sprawdzono przez HTTP. Implementacja analizatora jest własna; nie skopiowano kodu z wymienionych projektów. Publiczne zestawienia są pomocą, nie zamiennikiem pełnego SAE J1979 / ISO 14229 ani danych serwisowych konkretnego ECU.

## Przeczytane źródła

1. [Ross-Tech — Advanced Measuring Values](https://www.ross-tech.com/vcds/tour/adv-meas-blocks.php): wybór wartości, zapamiętane zestawy, wykresy/logowanie, kompromis częstotliwości. Dla starszych sterowników potrzebne label files; UDS/ODX/ASAM używa Advanced Measuring Values i nazw fabrycznych IDEnnnnn. Nie ma jednego uniwersalnego katalogu PID/DID wszystkich VAG.
2. [OBD-II PIDs — publiczne zestawienie](https://en.wikipedia.org/wiki/OBD-II_PIDs): listy PID Mode 01, jednostki i przeliczenia. Dodano konserwatywnie skalary PID 3C–3F, 43, 47/48, 4B, 4D/4E, 52, 59, 5A, 5E; każdy potrzebuje bitmapy i pełnej długości odpowiedzi.
3. [python-OBD — dekodery](https://github.com/brendan-w/python-OBD/blob/master/obd/decoders.py) oraz [komendy](https://github.com/brendan-w/python-OBD/blob/master/obd/commands.py): referencja jawnego katalogu, skalowania, jednostek, osobnych PID; sprawdzono prąd sondy CD/256−128 i nazewnictwo equivalence ratio. Projekt zawiera też komendy zmieniające ECU — nie przeniesiono ich do aplikacji.
4. [EdiabasLib](https://github.com/uholeschak/ediabaslib): przykład separacji interpretera danych/jobs od transportu i specyfiki VAG. README opisuje starsze KWP1281/KWP2000/TP2.0 z firmware zastępczym ELM i samochody do 4.2012; nie jest dowodem możliwości diagnostyki nowej Skody przez zwykły ELM v1.5. Nie dodano firmware ani interpretera jobs.
5. [udsoncan — usługi](https://udsoncan.readthedocs.io/en/latest/udsoncan/services.html): typowane ReadDataByIdentifier / ReadDTCInformation i mapy DidCodec. Dekodowanie DID wymaga jawnej definicji długości, skalowania i jednostki. Nie dopuszczono usług UDS w bieżącej liście komend.

## Wnioski wdrożone w 0.3

- Jawny katalog i lista obsługiwanych PID-ów bez dekodera. „Wszystkie dostępne” oznacza wszystkie potwierdzone i dekodowalne pomiary w aplikacji, nie wszystkie moduły samochodu.
- Jedna kolejka, niezależny pełny surowy log, jakość próbek per ECU, częstotliwość rzeczywista zamiast deklarowanego Hz.
- Zestawienia i porównania, podtrzymanie progów, histereza, kontekst pracy silnika, odrębne oznaczenie wyłączenia zapłonu.
- EQR z PID 34 nie uruchamia alertów mieszanki. Nie wyprowadzamy fizycznej lambda ani stosunku AFR przez odwrócenie bez potwierdzenia definicji tego ECU.
- Reguła korekt jest wskazówką do sprawdzenia warunków; nie potwierdza aktywnej regulacji closed-loop ani przyczyny przekroczenia. Progi temperatury/napięcia/korekt są ustawieniami użytkownika. Żadne z tych źródeł nie potwierdza uniwersalnych limitów awarii dla konkretnej Skody.

## Następna warstwa VAG — jeszcze niewdrożona

1. Ustalić model/rocznik/kod silnika, numer części ECU, wersję oprogramowania i identyfikację ASAM/ODX. Sam VIN nie określa wszystkich dekoderów.
2. Profil musi wiązać identyfikację ECU, adres żądania/odpowiedzi, protokół, sprawdzony DID lub grupę, długość, znak/kolejność bajtów, skalowanie, jednostkę, źródło danych i warunki interpretacji. Dane z jednego ECU nie są przenoszone do innego bez potwierdzenia.
3. Sprawdzić ISO-TP z wybranym adapterem (FF/CF, FC, sekwencje, NRC, timeouty, limity buforów). Parser odbioru ELM nie jest kompletnym transportem ISO-TP.
4. Dopuścić jedynie osobno zweryfikowane odczyty w sesji domyślnej, np. 0x22/0x19, przez typowaną politykę. Jeżeli odczyt wymaga SecurityAccess albo zmiany sesji, pozostaje niedostępny w tym etapie.
5. DPF (masa sadzy/ciśnienie różnicowe/status regeneracji), EGR oraz wartości zadane/rzeczywiste doładowania/paliwa wymagają definicji właściwego ECU. Zestawienie zadanego i rzeczywistego wymaga zbliżonych czasów próbek i kontekstu obciążenia. Brak danych nigdy nie jest zerem.
6. Oddzielne pokrycie silnika, skrzyni, ABS i gateway, bez automatycznej obietnicy dostępu przez ELM. Nie wysyłać losowych identyfikatorów ani masowego skanowania całej przestrzeni DID.

Brak kodowania, adaptacji, kasowania DTC, SecurityAccess, RoutineControl, IOControl, testerów elementów wykonawczych i zapisów ECU. Proponowane profile i pełny transport VAG są planem, nie działającą funkcją APK 0.3.


Rozszerzenia standardowego OBD w wersji 0.4 i nowe źródła: [UPDATE_0_4.md](UPDATE_0_4.md). PID 8B pozwala odczytać ogólny status DPF bez usługi VAG; masa sadzy i dane specyficzne ECU pozostają poza zakresem.
