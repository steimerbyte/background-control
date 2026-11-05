# Background Control

Zeigt und setzt die Android-Standby-Buckets aller Drittanbieter-Apps auf einem
Nothing-Telefon (Nothing OS / Android 17). Kein Root — die Shell-Kommandos laufen
über **Shizuku** (UID 2000, dieselben Rechte wie `adb shell`).

Package: `com.steimerbyte.backgroundcontrol` · Label: `Background Control` ·
Version 1.3.0 · minSdk 31 · target/compileSdk 35 · Kotlin 2.0.21 / AGP 8.7.3 /
Gradle 8.9 (Wrapper).

## Funktionsumfang

| # | Funktion | Umsetzung |
|---|----------|-----------|
| 1 | Drittanbieter-Pakete | `cmd package list packages -3`, Labels über `PackageManager` |
| 2 | Bucket je Paket | `am get-standby-bucket <pkg>`, Fortschrittsbalken (x / n) |
| 3 | Suche | live gegen Label **oder** Package, Gross/Klein egal, Teiltreffer; Trefferzahl rechts, `×` leert |
| 4 | Sortierung | Pills `BUCKET` / `NAME`; BUCKET: 45, 50, 40, 30, 20, 10, 5; NAME: alphabetisch nach Label |
| 5 | Filter | Pill `NUR GEDROSSELT` (Bucket 45 und 50), kombinierbar mit der Suche (Schnittmenge) |
| 6 | Auswahl + Ziel | Zeile antippen; fünf Ziel-Pills (`RESTRICTED`, `ACTIVE`, `WORKING SET`, `FREQUENT`, `RARE`) + `ANWENDEN` |
| 7 | Kopfzeile | `Osw`: Kicker + Titel, Aktion `MiniButton AKTUALISIEREN` rechts |
| 8 | Systemlast | `LivePanel` mit CPU- und RAM-Histogramm (48 Slots, 46 dp) |
| 9 | Aktualisieren | `MiniButton` im Kopf: `LÄDT · x/n` während des Ladens, `AKTUALISIERT` für 2 s danach |
| 10 | Zustände | Ladehinweis statt leerer Liste, `KEIN TREFFER FÜR "<text>"`, erste stderr-Zeile bei Shell-Fehlern |
| 11 | Hinweis | einmalig, zwei Zeilen, antippbar zum Ausblenden (merkt sich `hint_seen` in den Preferences) |
| 12 | Zustand | Suchtext, Sortierung, Filter, Auswahl und Ziel liegen in `rememberSaveable` |
| 13 | Shizuku | `moe.shizuku*` / `rikka.shizuku*` werden weder angezeigt noch geschrieben |
| 14 | Nach dem Schreiben | Bucket je Paket neu lesen, Chip aktualisieren, Bilanz `n GEÄNDERT · m UNVERÄNDERT` |
| 15 | About | Kicker antippen: Name, `Version … · Gerät`, Leitspruch `Function over form.`, Zweck, Kernkommandos |
| 16 | Verteilung | `SummaryPanel`: alle sieben Bucket-Zahlen in zwei Zeilen à vier Spalten, darüber `VERTEILUNG · n APPS · m GEDROSSELT`, darunter Paket- und Shizuku-Zahl |

Bucket-IDs (`ActivityManager`): 5 exempted · 10 active · 20 working set · 30 frequent ·
40 rare · 45 restricted · 50 never.

`rememberSaveable` deckt Rotation und Prozesstod ab; zusätzlich steht in der
Activity `configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode"`,
damit eine Drehung nicht die komplette Bucket-Liste neu liest.

## Layoutsystem

Verbindlich ist `~/.omp/agent/managed-skills/nothingos-app/SKILL.md` — das Layoutsystem der
NothingOS-App-Familie. Tokens (`Accent`, `Dim`, `Panel`, `Line`, `Good`, `Warn`), Typografie
und Seitenhierarchie sind von dort übernommen, nicht neu erfunden.

**Typografie** liegt als `TextStyle` im Code: `TitleStyle` 28 sp Bold (Spacing 0.5),
`KickerStyle` 9 sp (1.8), `MicroStyle` 9 sp (1.4), `ValueStyle` 22 sp Bold,
`BodyStyle` 12 sp (0.3), `MonoStyle` 10 sp (0.2), `ButtonStyle` 11 sp (1.4) — alles
`FontFamily.Monospace`, Großbuchstaben in den Bausteinen.

**Hierarchie** (Slot → Composable):

| Slot | Umsetzung |
|---|---|
| 1 OSW | `Osw(kicker, title, onKickerClick, action)` — Kickerzeile (antippbar → About) mit `MiniButton AKTUALISIEREN` rechts, darunter Titel 28 sp, darunter `SCHNAPPSCHUSS HH:MM · NICHT AUTOMATISCH` |
| 2 Live-Panel | `LivePanel` — zwei `Histogram`-Zeilen (48 Slots, 46 dp, Balken 62 %), Wert rechts in `Value`, RAM-Bezug als Mikrozeile `RAM 5,1/11,1 GiB` |
| 3 Summary | `SummaryPanel` — sieben Bucket-Zahlen, zwei Zeilen à vier Spalten, Mikrolabel über Wert |
| 4 Controls | `SearchField` (40 dp, Lupe, Trefferzahl rechts, `×`), `Pill BUCKET`/`Pill NAME`, `Pill NUR GEDROSSELT` |
| 5 List | Zeile: Label `Body`, Paket `Mono`, rechts `Chip` in der Bucket-Tonfarbe |
| 6 ActionBar | nur bei Auswahl, unten fixiert: fünf Ziel-`Pill` + `WideButton ANWENDEN` |
| 7 Overlays | `Hint` (9 sp, zwei Zeilen, antippbar), `AboutPanel` (Kicker → Dialog) und das Ergebnis-Panel in `OutlineBox` |

**Chip-Farben** (Vorgabe des Umbauauftrags): `RESTRICTED` und `NEVER` in `Warn`,
`EXEMPTED` in `Good`, alle übrigen Buckets in `Dim` — vorher hatten active/never
eigene Farben (`Good`/`Accent`).

**Zustände** stehen im Listenbereich (`LADE BUCKETS …`, `KEIN TREFFER FÜR "<text>"`,
`KEINE GEDROSSELTEN APPS`); Fehler in `Warn`, nie in `Good`. Fortschritt: Zähler in
`Micro` plus `Bar` (2 dp, `Accent`, ab 100 % `Good`).

### Abweichungen von der Vorlage

1. **`LivePanel` nimmt zusätzlich Werte.** Signatur hier:
   `LivePanel(cpu, ram, cpuText, ramText, ramDetail, cpuFailed, ramFailed)`. Grund: die App
   zeigt den absoluten RAM-Verbrauch (`4,6/11,1 GiB`), den die reine Historie nicht hergibt.
   Maße, Farben und der Prozentwert rechts sind identisch mit der Vorlage.
2. **`ResultPanel` wird nicht verwendet.** Die Ergebniszeile trägt neben
   `n GEÄNDERT · m UNVERÄNDERT · ZIEL …` auch Lesefehler (`LESEN FEHLGESCHLAGEN …`,
   `KEINE BUCKETS GELESEN`) und die erste stderr-Zeile. Sie bleibt ein String und wird in
   `OutlineBox` mit `Micro`-Typografie gerendert; Farbe nach Inhalt (`Warn` bei Fehler,
   `Dim` bei `0 GEÄNDERT`, sonst `Good`). Damit ist kein Text geändert.
3. **`Pill` und `MiniButton` haben `enabled`** (Default `true`), damit die Steuerung während
   eines Laufs sperrt — Verhalten wie vorher, nur als Parameter statt Sonderfall.
4. **Die Seite ist ein `LazyColumn`**: OSW bis Liste scrollen gemeinsam, nur die ActionBar
   ist fixiert. Grund: im Querformat (411 dp Höhe) sind sonst Suche und Liste nicht mehr
   erreichbar; die Vorlage verlangt nur die ActionBar unten fixiert.
5. **`Osw` hat `onKickerClick`** (Pflicht aus Abschnitt 4: der Kicker öffnet die
   About-Sektion). Der Aufruf übergibt `{ showAbout = true }`, `AboutPanel` liegt als
   `Dialog` über der Seite und schließt bei Tap oder `BACK`.

## Systemlast-Panel

Zwei Zeilen im `LivePanel` direkt unter der Kopfzeile, je ein 48-Slot-Histogramm (Balken
62 % der Slotbreite, `Accent` belegt, `Line` leer), rechts der Prozentwert in `Value`
(22 sp Bold) und darunter die Bezugsgröße als Mikrozeile:

```
 ▁▂▃▅▃▂▁▁▂▃ [46 dp]         37 %
                             CPU
 ▂▁▁▁▂▂▁▁▁▁ [46 dp]         41 %
                    RAM 4,6/11,1 GiB
```

- **CPU**: `/proc/stat`, Zeile `cpu `, `load = 1 − Δidle/Δtotal` mit
  `idle = idle + iowait`. Die Datei ist für App-UIDs gesperrt (`cat: /proc/stat:
  Permission denied`, gemessen über `run-as`), deshalb läuft der Read über Shizuku.
- **RAM**: `/proc/meminfo` (`MemTotal`, `MemAvailable`) — für Apps direkt lesbar,
  funktioniert also auch ohne Shizuku.
- Takt 700 ms, Historie `takeLast(48)`; Sampling in `Dispatchers.IO`, UI-Update auf Main.
- Der Sampler hängt an `LaunchedEffect` + `repeatOnLifecycle(RESUMED)` und stoppt,
  sobald die App den Vordergrund verlässt. Fehler setzen die Zeile auf `KEINE DATEN`,
  ohne die App zu stören (die Anfangsphase zeigt `…`).

## Genutzte Kommandos

Alle über `IShizukuService.newProcess(...)` (AIDL `IRemoteProcess`, stdout/stderr als
`ParcelFileDescriptor`):

```
cmd package list packages -3          # 137 Drittanbieter-Pakete
am get-standby-bucket <pkg>           # -> 5|10|20|30|40|45|50, ~50 ms je Aufruf
am set-standby-bucket <pkg> active|working_set|frequent|rare|restricted
cat /proc/stat                        # CPU-Sampler
```

Lesen und Schreiben laufen als **ein** `sh -c "<script>"` mit `; `-getrennten
`echo`-Zeilen; jede Zeile ist `paket=bucket`, wird einzeln geparst und treibt den
Fortschritt. Beim Schreiben steht `am set-standby-bucket` und direkt danach
`am get-standby-bucket` im selben `$(...)`, damit der Chip den echten neuen Wert zeigt.

### `default` ist nicht setzbar (geprüft, nicht vermutet)

`am help set-standby-bucket` dokumentiert nur
`active|working_set|frequent|rare|restricted`. Ein echter Versuch an
`com.dijia1124.plusplusbattery` (Bucket 45):

```
vorher: 45
am set-standby-bucket com.dijia1124.plusplusbattery default
Error: Unknown bucket: default        rc=255
nachher: 45
```

Der Aufruf wird vor jeder Änderung abgelehnt, der Bucket bleibt unverändert. Die
Ziel-Auswahl bietet deshalb genau die fünf erlaubten Tokens an; `exempted` (5) und
`never` (50) werden nur gelesen und angezeigt.

## Shizuku-Paket wird ausgeblendet

`moe.shizuku.privileged.api` ist selbst ein Drittanbieter-Paket und steckt in der
`cmd package list packages -3`-Liste. Es wird herausgefiltert, damit die App den Dienst,
über den sie arbeitet, niemals anfassen kann. Die Kopfzeile weist das offen aus
(`137 DRITTANBIETER · 1 SHIZUKU AUSGEBLENDET`), damit die Zahl gegen die Shell prüfbar
bleibt: 137 − 1 = 136 gelistete Apps.

### Kaltstart-Rennen (behoben)

Nach einer Neuinstallation stand die App mehrfach auf `SHIZUKU · NICHT BEREIT`, obwohl
der `shizuku_server` lief und `newProcess` bereits funktionierte: der Provider bindet
manchmal erst nach `onCreate`, und `refreshShizuku()` lief nur einmal. Jetzt hängt ein
`addBinderReceivedListenerSticky` an der Activity (refresh + nachladen, sobald der
Binder da ist), und `ERLAUBEN` prüft die Berechtigung erneut statt blind einen Dialog
zu öffnen.

## Gemessene Zahlen

Gerät `192.168.178.53:37597`, Nothing Phone (3a) Pro (`A059P`), Android 17,
Messung 05.11.2026 18:06 (Screenshot) und 18:06–18:07 (Shell-Schnappschuss), nach dem
Roundtrip um 18:26 und 18:30 erneut bestätigt (gleiche sieben Zahlen).
Gegenprobe jeweils mit `adb shell` (gleiche UID 2000 wie Shizuku).

| Größe | Shell | UI |
|---|---|---|
| Drittanbieter-Pakete | 137 | `137 DRITTANBIETER` (Mikrozeile unter dem Panel) |
| ausgeblendet (Shizuku) | 1 (`moe.shizuku.privileged.api`, Bucket 5) | `1 SHIZUKU AUSGEBLENDET` |
| gelistete Apps | 137 − 1 = 136 | `136 APPS` |
| exempted (5) | 8 − 1 = 7 | 7 |
| active (10) | 12 | 12 |
| working set (20) | 5 | 5 |
| frequent (30) | 2 | 2 |
| rare (40) | 29 | 29 |
| restricted (45) | 72 | 72 |
| never (50) | 9 | 9 |
| Summe | 136 (+1 ausgeblendet = 137) | 136 |
| gedrosselt (45+50) | 81 | `81 GEDROSSELT` |

Shell-Kommando je Zeile:
`adb shell 'for p in $(cmd package list packages -3 | sed s/package://); do echo "$p=$(am get-standby-bucket $p)"; done'`
(137 Zeilen, dann `awk -F= '$2==N' | wc -l`).

### Vor und nach dem Umbau

Der Umbau hat keine Zahl verändert. Verglichen wurden zwei vollständige
Shell-Schnappschüsse — 17:56 (vor dem Umbau) und 18:06 (danach), beide 137 Pakete,
gleiches Kommando — plus die UI-Werte des Screenshots von 18:06.

| Bucket | vorher (17:56) | nachher Shell (18:06) | nachher UI (18:06) |
|---|---|---|---|
| exempted (5) | 8 | 8 | 7 (ohne Shizuku) |
| active (10) | 10 | 12 | 12 |
| working set (20) | 6 | 5 | 5 |
| frequent (30) | 2 | 2 | 2 |
| rare (40) | 30 | 29 | 29 |
| restricted (45) | 72 | 72 | 72 |
| never (50) | 9 | 9 | 9 |

Der Unterschied zwischen 17:56 und 18:06 steckt in genau **zwei** Paketen, die Android
selbst hochgestuft hat (Paket-für-Paket-Diff der beiden Schnappschüsse):

```
com.artemchep.keyguard:              20 -> 10
com.steimerbyte.profileoptimizer:    40 -> 10
```

Beide wurden in der Zwischenzeit benutzt (Entsperren bzw. die parallel laufende
Profile-Optimizer-Sitzung); ein `am set-standby-bucket` aus dieser App lief vor 18:06
nicht. Zur Messzeit stimmten Shell und UI in allen sieben Buckets exakt überein
(7/12/5/2/29/72/9 bei 136 gelisteten Apps).

## Suche, Filter und Sortierung

Alle Werte am 05.11.2026 nach dem Umbau gemessen (18:23–18:30), Trefferzahlen aus dem
uiautomator-Dump gelesen.

| Prüfung | UI | Shell / Erwartung |
|---|---|---|
| Suche `brave` | `2 TREFFER` — RethinkDNS (`com.celzero.bravedns`, RESTRICTED), Brave (`com.brave.browser`, ACTIVE) | `am get-standby-bucket`: `45` und `10` — stimmt mit den Chips |
| Suche `xyzzy` | `0 TREFFER`, `KEIN TREFFER FÜR "xyzzy"` | — |
| Suche `.` ohne Filter | `136 TREFFER` | 136 gelistete Apps |
| Suche `.` + `NUR GEDROSSELT` | `81 TREFFER` | `awk -F= '$2==45||$2==50' \| wc -l` = **81** |
| Filter wieder aus, Feld geleert | `136 TREFFER` | — |
| Sortierung `BUCKET` | erste Zeilen App Manager, Aria2App, aShell, aShell You, Atalaya, Audile … alle `RESTRICTED` | 45 zuerst |
| Sortierung `NAME` | nach dem Umbau nicht erneut durchgeklickt — der Comparator und die Pill-Verdrahtung sind unverändert (siehe Diff der `visible`-Berechnung); vor dem Umbau nach Label gemischt über alle Buckets | nach Label |

`"."` trifft jedes Package (jeder Paketname enthält einen Punkt), die Trefferzahl ist
damit genau die Zahl der gedrosselten Apps — 81 in der UI, 81 in der Shell.

## Roundtrip (echte Eingriffe, Ausgangszustand wiederhergestellt)

Testziel nach dem Umbau: `io.github.muntashirakon.AppManager` (Ausgangslage `45` =
RESTRICTED), bedient über Suche `muntashirakon` → Zeile antippen → Ziel-Pill →
`ANWENDEN`. Jeder Schritt in der UI, jeder Beweis per `am get-standby-bucket`
(keine Kommandoausgabe, sondern Rücklesen).

| Schritt | UI-Beleg | Shell-Beleg |
|---|---|---|
| Ausgangslage | Zeile mit `RESTRICTED`-Chip, Verteilung 7/12/5/2/29/72/9 | `45` |
| Auswahl | ActionBar: fünf Ziel-Pills, `ANWENDEN · 1 → RESTRICTED` | — |
| Ziel `ACTIVE` | `1 GEÄNDERT · 0 UNVERÄNDERT · ZIEL ACTIVE`; Chip `ACTIVE`, Verteilung active 13, restricted 71 | `10` |
| Ziel `RESTRICTED` (zurück) | `1 GEÄNDERT · 0 UNVERÄNDERT · ZIEL RESTRICTED`; Chip `RESTRICTED`, Verteilung wieder 7/12/5/2/29/72/9, `81 GEDROSSELT` | `45` |
| Endkontrolle | — | voller Schnappschuss, 137 Zeilen, Diff gegen 17:56 nur die zwei gedrifteten Pakete (`keyguard`, `profileoptimizer`); Testziel wieder `45` |

Der Tap auf die Ziel-Pill lief über einen uiautomator-Dump, nicht über geratene
Koordinaten: der erste Versuch traf das `ACTIVE`-Label der Verteilung (y≈767) statt
der Pill in der ActionBar (y≈2153) und änderte deshalb nichts — der Bucket blieb
`45`, belegt durch dasselbe Kommando. Danach mit `min_y`-Filter wiederholt.

## Ladezustand und Aktualisieren

`AKTUALISIEREN` (`MiniButton` im Kopf) startet denselben Ladevorgang wie der Erststart:

- Button: `LÄDT · 45/136` (deaktiviert, `Accent`), darunter `LADE BUCKETS · 45 / 136`
  mit `Bar`-Fortschritt, im Listenbereich `LADE BUCKETS …` statt einer leeren Liste.
- Nach dem Ende: `AKTUALISIERT` für 2 s, danach wieder `AKTUALISIEREN`; unter dem Titel
  steht ab dann `SCHNAPPSCHUSS HH:MM · NICHT AUTOMATISCH` (die App lädt nie von selbst).
- Während des Ladens gemessen: `top -n1 -b` zeigt `800%cpu … 400%idle` (≈ 50 % Last),
  das Histogramm gleichzeitig `50 %`.

## CPU- und RAM-Messung

Der Prozentwert steht rechts in `Value`, die Bezugsgröße als Mikrozeile darunter
(`CPU` bzw. `RAM 5,1/11,1 GiB`). Messung 05.11.2026 nach dem Umbau:

| Zeitpunkt | UI | Gegenprobe |
|---|---|---|
| während des Ladens | `CPU` ≈ 50 % (Ausschlag im Histogramm) | `top -n1 -b`: `800%cpu … 400%idle` → 50 % (Messung 17:5x, gleicher Sampler) |
| Leerlauf | `CPU 35–39 %` (Screenshots 18:23–18:27) | kein `top` in derselben Sekunde erhoben — offen |
| RAM | `46 %`, Mikrozeile `RAM 5,1/11,1 GiB` | `/proc/meminfo` (18:35): `MemTotal: 11680596 kB`, `MemAvailable: 6312756 kB` |

`MemTotal` 11 680 596 kB = 11,14 GiB → `11,1`; `(MemTotal − MemAvailable)` =
5,12 GiB → `5,1`; belegt 45,96 % → `46 %`. Zwei Screenshots im Abstand von einigen
Sekunden zeigen den Ausschlag beim Laden und den Abfall danach.

## Rotation und Prozesstod

- Drehung am 05.11.2026, 18:27 (nach dem Umbau): Ausgangswerte
  `accelerometer_rotation=1, user_rotation=0` gelesen, dann `user_rotation=1` gesetzt.
  Querformat `2392×1080`: OSW, Live-Panel und Summary vollständig sichtbar, der Hinweis
  unten einzeilig, kein Absturz; `uiautomator` liefert den Baum weiter. Zurückgedreht
  und beide Werte wieder auf den Ausgangsstand gesetzt.
- Die ganze Seite ist seit dem Umbau ein `LazyColumn`: OSW bis Liste scrollen gemeinsam,
  im Querformat bleiben Suchfeld, Sortier-Pills und Zeilen durch Scrollen erreichbar.
  Nur die ActionBar ist unten fixiert (Vorlage).
- Prozesstod: Suchtext `a`, Sortierung `NAME`, Filter aus, eine Zeile ausgewählt,
  Ziel `ACTIVE`; dann HOME, `am kill com.steimerbyte.backgroundcontrol`
  und Neustart. Danach unverändert: Feld `a`, `115 TREFFER`,
  `ANWENDEN · 1 → ACTIVE` und dieselbe alphabetische Reihenfolge wie vorher.

## About

Die Kicker-Zeile im Kopf (`NOTHING OS · STANDBY-BUCKETS`) ist antippbar und öffnet
die Pflicht-About-Sektion (Skill Abschnitt 4) als Overlay über der Seite; ein Tap
oder `BACK` schließt sie wieder. Inhalt in dieser Reihenfolge:

1. `BACKGROUND CONTROL` in `Title`
2. `Version 1.3.0 · Nothing A059P` (BuildConfig.VERSION_NAME · Build.MANUFACTURER + MODEL)
3. `Function over form.` allein stehend in `Accent`
4. Zweck in einem Satz, maximal zwei Zeilen
5. `KERNKOMMANDOS:` als `Mono`-Liste mit den tatsächlich genutzten Aufrufen

Kein Impressum, keine Lizenz, keine Links — die stehen hier im README.

## Was ungetestet blieb / Grenzen

- **Querformat**: der Kopfbereich (OSW + Live-Panel + Summary) füllt die 411 dp Höhe
  fast aus; Suchfeld, Pills und Liste erreicht man durch Scrollen, die ActionBar bleibt
  unten. Der Hinweis erscheint dort einzeilig. Hochkant ist die gedachte Ausrichtung.
- **Erststart-Dialog**: Die Shizuku-Berechtigung war auf dem Testgerät bereits erteilt;
  der Dialog „Zulassen" wurde in diesem Lauf nicht durchlaufen.
- **Ohne Shizuku**: Kopfzeile und Liste bleiben leer (nur der Berechtigungs-Kasten),
  der CPU-Wert steht auf `KEINE DATEN`, der RAM-Wert läuft weiter. Am Gerät nicht
  durchgespielt, weil die Berechtigung dauerhaft erteilt ist.
- **Mehrbenutzer / Work-Profile**: alles läuft gegen `--user 0`.
- **Bucket-Verschiebung durch das System**: zwischen zwei Messungen im Abstand von
  Minuten sind Apps von selbst gewandert (hier: `keyguard` 20 → 10,
  `profileoptimizer` 40 → 10, beide während sie benutzt wurden). Das ist Android,
  nicht die App — Zahlen zweier Läufe können deshalb um einzelne Zeilen abweichen.
  Zur selben Zeit stimmten Shell und UI in allen sieben Buckets exakt überein.
- **CPU-Sampler**: ein `cat /proc/stat` über Shizuku alle 700 ms; das ist derselbe
  Aufwand wie in Profile Optimizer, aber ohne Shizuku gibt es keinen CPU-Wert.
- **Unit-Tests**: das Projekt hat keine Test-Quelle; geprüft wurde am Gerät gegen die
  Shell (Zahlen oben) und über `uiautomator`-Dumps.
