# Testy na adapterze ELM327 v1.5

Nie wykonano tych prób w środowisku tworzenia. Podany napis v1.5 nie jest gwarancją autentyczności ani pełnej implementacji. Scenariusze wykorzystują wyłącznie odczyt.

1. **Parowanie/uprawnienia:** odmów uprawnienia Bluetooth, nadaj je później, wyłącz Bluetooth, odśwież listę, wybierz właściwy adres. Aplikacja powinna pokazać konkretny błąd lub sparowane urządzenia. Nie szuka urządzeń niesparowanych.
2. **SPP:** wykonaj kilka połączeń/rozłączeń. Nieprawidłowy adapter SPP powinien zakończyć próbę błędem w ciągu timeoutu. Wyłączenie adaptera w trakcie odczytu kończy sesję; brak automatycznego reconnect.
3. **Inicjalizacja:** eksportuj log. Pierwsza transmisja powinna być ATZ, następnie ATI i konfiguracja. Po ATSP0 żądanie 0100 uruchamia wyszukiwanie. ATDPN powinno zwrócić A6/6 dla potwierdzonego CAN 11-bit/500 kbps.
4. **Kolejka:** przeanalizuj TX/RX po scaleniu fragmentów. Pomiędzy każdymi dwiema komendami TX musi wystąpić prompt `>` w RX. SEARCHING.../OK/CR nie są granicą transakcji.
5. **PID-y:** bitmapy 0100/0120/... determinują polling. Sprawdź RPM i temperaturę przy włączonym zapłonie/silniku. Napięcie ATRV jest zasilaniem adaptera, a 0142 napięciem raportowanym przez ECU. Czas jest osobny dla każdej próbki.
6. **VIN:** odczytaj wielokrotnie 0902. Oczekiwane kompletne FF/CF, prawidłowa sekwencja i VIN 17 znaków. Odpowiedzi kilku ECU nie mogą zostać sklejone. Ucięta odpowiedź daje uwagę i brak VIN dla tego ECU.
7. **DTC:** odczytaj 03/07/0A i porównaj kody z wiarygodnym skanerem użytym w trybie odczytu. NO DATA i ? nie mogą być opisane jako brak DTC. Nie wywołuj kasowania ani testów wykonawczych.
8. **Ucięte Mode 01:** dla tego klona zachowaj log z brakującym SF/CF. UI powinien zgłosić niekompletność i usunąć bieżącą wartość nieudanego PID. Nie zwiększaj liczby PID-ów w jednym żądaniu; MVP wysyła jeden PID naraz.
9. **Timeout:** odłącz adapter/zapłon podczas operacji. Jeżeli nie ma EOF, sesja kończy się najpóźniej po limicie komendy; kolejne TX nie są wysyłane na starej sesji. Ponowne połączenie od początku.
10. **Cykl życia:** zatrzymaj Live i przejdź w tło. Dopuszczalne zakończenie jednego trwającego żądania; potem brak kolejnych odczytów. Po powrocie wymagany przycisk Start Live. Obrót ekranu zachowuje stan ViewModel.
11. **Dłuższa sesja:** 15–30 minut Live, kolejne odczyty VIN/DTC, eksport. Porównaj kompletność logu, czas odpowiedzi i częstość błędów. Monitoruj miejsce na logi. ATMA nie występuje w transmisji.
12. **Polityka terminala:** wpisz 04, 08, 2701, 2E..., ATSH7E0, ATMA oraz wejście z CR/LF. Wszystkie powinny zostać odrzucone przed transmisją.

Przed rozszerzeniem o VAG zbierz: eksporty inicjalizacji, VIN, bitmap, DTC, stabilnego Live i uciętych odpowiedzi, model telefonu/Androida oraz dokładny model adaptera. Dalsza konfiguracja klona powinna wynikać z tych śladów.


## Próby aktualizacji 0.2

1. Zainstaluj APK jako aktualizację 0.1; sprawdź zachowanie istniejących danych.
2. Parametry: wybierz RPM/temperaturę na Live, a MAP/MAF/rail/lambda do Zapis. Po połączeniu nieobsługiwane pozycje muszą być zablokowane. Zapisany CSV nie może zawierać niezaznaczonych sygnałów.
3. Presety i ustawienia powinny przetrwać ponowne uruchomienie. Oba sygnały PID34 nie mogą powodować dwóch zapytań 0134 w cyklu.
4. Live: okna 30/60/180s, kursor, osobne jednostki, min/max/średnia. Brak poprawnego odczytu ma wyczyścić bieżącą wartość i zrobić lukę, a nie zero.
5. Rozpocznij CSV, zgaś ekran na 2–5 minut i wróć. Sprawdź ciągłość czasu i wierszy CSV oraz powiadomienie Stop. Powtórz przy przejściu do innej aplikacji.
6. W trakcie nagrywania odczytaj VIN/DTC/monitory; dopuszczalna luka czasu, ale nie zakończenie CSV i nie nakładanie TX.
7. Stop z powiadomienia: CSV zamknięty, usługa/powiadomienie zakończone; jeśli UI nie jest widoczne, polling wstrzymany. Po powrocie zapis nie wznawia się automatycznie.
8. Rozłącz/wyłącz adapter podczas CSV. Sesja ma się zakończyć, plik zostać w Zapisy, bez automatycznego restartu. Ponowne połączenie i nagrywanie tworzą nowy CSV.
9. Eksport aktywnego i zakończonego CSV. Sprawdź odczyt jako UTF-8, separator przecinek i dziesiętną kropkę; puste value dla NO_DATA/INVALID/ERROR.
10. Wybierz dużo dostępnych PID-ów. Zweryfikuj szacowany cykl i adekwatne oznaczenie świeżości próbek. Nie porównuj próbek różnych PID-ów jako pomiarów jednoczesnych.
11. Monitory: porównaj MIL, licznik DTC i readiness z wiarygodnym czytnikiem. Nie interpretuj gotowości jako oceny sprawności mechanicznej.

Nowe funkcje były sprawdzone unit/Robolectric i build/lint, ale fizyczny Bluetooth i działanie w tle na konkretnym telefonie wymagają tych prób.

## Wersja 0.3

- Wszystkie dostępne: sprawdzić pokrycie bitmap, listę brakujących dekoderów i rzeczywisty cykl na ELM v1.5. Nie używać ATMA.
- Zweryfikować raport po zapisie: min/średnia/max, jakość, puste braki, porównanie sesji różniących się zestawem PID.
- Progi testować w demo lub na odtworzeniu danych; nie wywoływać celowo przegrzania ani niskiego napięcia w aucie.
- Sprawdzić podtrzymanie, histerezę, brak oceny przy zatrzymanym silniku/braku RPM i zamknięcie oceny po Stop.
- Oznaczyć wyłączenie zapłonu, potwierdzić adnotację raportu i zachowanie NO DATA w CSV. Włączyć zapłon, oznaczyć wznowienie i sprawdzić nowy ciąg próbek.
- Status MIL/monitorów ma odświeżać się co 30 s przez tę samą kolejkę. Po braku odpowiedzi nie pokazywać starego statusu jako nowej odpowiedzi.
- Powiadomienie rejestratora pokazuje aktywny alert przy zapisie w tle i zachowuje Stop. Po zakończeniu nagrywania powiadomienie znika.
- Raporty CSV z v0.2 mają statystyki, ale nie wymyśloną historię dawnych alertów.

## Wersja 0.4

1. Po połączeniu sprawdź w raw log odczyt 014F po bitmapach; zapisz jego pełne bajty. Przy NO DATA / ucięciu 4F zależne MAP/EQR/prąd muszą mieć pustą wartość i SCALE_UNKNOWN. Ponowny odczyt bitmap ponawia 4F. Porównaj nowe wartości ze sprawdzonym skanerem bez zmiany konfiguracji ECU.
2. Wybierz wszystkie dostępne, uruchom 10-minutowy zapis. Porównaj rzeczywiste odstępy RPM/MAP/rail z poprzednimi CSV; liczniki mają około minutowe odstępy, temperatury około pięciosekundowe, z opóźnieniami zależnymi od ruchu. Nie oczekuj gwarantowanego Hz.
3. Sprawdź kompletność 0169/0178/0183/0185/018B oraz maski czujników. Ucięta odpowiedź nie może dać częściowych wartości. Nieobsługiwany sensor ma UNSUPPORTED, a nie 0 i nie utratę transmisji; PID przestaje być odpytywany, gdy wszystkie wybrane jego pola są potwierdzone jako nieobsługiwane we wszystkich oczekiwanych ECU, do odświeżenia bitmap.
4. Porównaj EGR zadane/rzeczywiste, EGT, NOx i AdBlue ze sprawdzonym czytnikiem. W PID 85 czas ostrzeżenia ma jednostkę s. PID 8B średnie między regeneracjami nie oznaczają czasu/dystansu od ostatniej regeneracji. Zmiana statusu DPF powinna pojawić się w adnotacjach raportu; niczego nie uruchamiaj w ECU.
5. Włącz automatyczne DTC przed zapisem. Sprawdź skan początkowy i co najmniej minutowe odstępy, pełne raw logi 03/07/0A oraz osobne statusy w OBD/raporcie. Wyłącz opcję i sprawdź, że pozostaje tylko ręczny skan. NO DATA/niekompletny DTC nie może być opisany jako brak usterek.
6. Eksportuj Sesja ZIP, otwórz CSV/Markdown/JSON/DTC JSONL na komputerze. Sprawdź wybór pomiarów, UTC i kody. Raw TSV eksportuj niezależnie z Terminala. Przetestuj przerwanie procesu i eksport odzyskanej sesji bez wymyślania dawnych alertów.
