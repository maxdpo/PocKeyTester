# POC Key Tester – Hytera P50 Pro

App diagnostica per Android 12 che rileva **come** e **con quale codice** i tasti fisici di una radio PoC arrivano ad Android: PTT, Volume +/–, manopola gruppi (Group Selector Knob), tasto SK, tasto Emergenza.

Su questi terminali un tasto può arrivare in quattro modi diversi, e l'app li controlla tutti contemporaneamente:

| Prefisso | Cosa significa | Come gestirlo nella tua app PTT |
|---|---|---|
| `[APP]` | KeyEvent ricevuto dall'app in primo piano | `dispatchKeyEvent()` / `onKeyDown()` |
| `[ACC]` | KeyEvent ricevuto a livello di sistema (servizio accessibilità) | `AccessibilityService.onKeyEvent()` – funziona anche in background |
| `[MOT]` | Evento di movimento (es. manopola come rotary encoder / scroll) | `onGenericMotionEvent()` |
| `[INT]` | Broadcast intent inviato dal firmware | `BroadcastReceiver` registrato su quell'azione |
| `[LOG]` | Riga di logcat di sistema contenente ptt/knob/keycode… | Indizio: il tasto è gestito internamente dal firmware |
| `[DEV]` | Dispositivi di input e tasti dichiarati da ciascuno | Elenco dei keycode che il terminale *può* generare |
| `[SCAN]` | Stringhe trovate nei manifest delle app (es. app PoC Hytera) | Nomi di azioni intent candidate |

Tutto viene salvato anche su file e sul logcat con tag `POCKEYS`.

---

## 1. Creare l'APK con GitHub (senza Android Studio)

1. Crea un account su https://github.com e poi un nuovo repository (es. `PocKeyTester`), anche privato.
2. Nel repository: **Add file → Upload files** e trascina **il contenuto** della cartella del progetto
   (`app/`, `build.gradle`, `settings.gradle`, `gradle.properties`, `README.md`, `.gitignore`). Poi **Commit changes**.
3. La cartella `.github` a volte non viene caricata trascinandola (è "nascosta"). Verifica che nel repository esista
   `.github/workflows/build.yml`. Se manca: **Add file → Create new file**, scrivi come nome
   `.github/workflows/build.yml`, incolla il contenuto del file e fai **Commit**.
4. Apri la scheda **Actions**: parte la compilazione "Build APK" (circa 3–5 minuti).
   Se non parte da sola, seleziona "Build APK" → **Run workflow**.
5. A compilazione finita (spunta verde) apri l'esecuzione e in fondo, sotto **Artifacts**, scarica
   `PocKeyTester-apk` (è uno zip che contiene `app-debug.apk`).

## 2. Installare sul P50 Pro

Il modo più comodo è ADB (Android Platform Tools sul PC, debug USB attivo sulla radio:
Impostazioni → Info → premi più volte "Numero build", poi Opzioni sviluppatore → Debug USB).

```
adb install -r app-debug.apk
```

### Opzioni consigliate (danno molte più informazioni)

Lettura del logcat di sistema dall'app:
```
adb shell pm grant it.poc.keytester android.permission.READ_LOGS
```

Servizio di accessibilità (cattura globale dei tasti). Su un terminale senza touch è più facile via ADB.
Attenzione: questo comando **sostituisce** l'elenco dei servizi di accessibilità già attivi.
```
adb shell settings get secure enabled_accessibility_services
adb shell settings put secure enabled_accessibility_services it.poc.keytester/it.poc.keytester.KeyAccessibilityService
adb shell settings put secure accessibility_enabled 1
```
Per disattivarlo in seguito: `adb shell settings delete secure enabled_accessibility_services`

Avvio:
```
adb shell am start -n it.poc.keytester/.MainActivity
```

## 3. Procedura di test

Con l'app aperta, premi **un tasto alla volta**, lasciando 2–3 secondi tra uno e l'altro:

1. PTT: premi, tieni premuto 2 secondi, rilascia.
2. Volume +, poi Volume –.
3. Manopola gruppi: **un solo scatto** in senso orario, pausa, **un solo scatto** in senso antiorario.
4. Tasto SK (laterale) ed Emergenza (breve e lungo).

Ripeti poi il test **con l'app PoC Hytera chiusa**, perché potrebbe essere lei a ricevere il PTT in esclusiva:
```
adb shell pm list packages | grep -i -E "hyt|poc|ptt|talk"
adb shell am force-stop <nome.pacchetto.trovato>
```
Controlla anche nelle Impostazioni della radio se esiste una voce tipo "Tasti programmabili" / "PTT key" che assegna il PTT a una specifica app.

Uscita dall'app: **BACK tre volte entro 2 secondi** (tutti gli altri tasti vengono intercettati apposta).

## 4. Raccogliere i risultati

```
adb pull /sdcard/Android/data/it.poc.keytester/files/poc_keylog.txt
```
oppure in diretta: `adb logcat -s POCKEYS`

Controllo incrociato a livello kernel (utile se un tasto non compare in nessun modo):
```
adb shell getevent -lt
```

## 5. Come interpretare

- **Il tasto compare in `[APP]`/`[ACC]` con un `code=`** → usa quel keycode. Per il PTT con schermo spento/app in
  background serve l'`AccessibilityService` (o l'intent, se esiste).
- **Compare solo in `[INT]`** → il firmware invia un broadcast: registra un `BroadcastReceiver` *a runtime*
  (in un Foreground Service) su quell'azione; leggi anche gli `extras`.
- **La manopola compare in `[MOT]`** → leggi `AXIS_SCROLL` in `onGenericMotionEvent()`.
- **Compare in `getevent` ma non nell'app** → il tasto è consumato dal sistema/firmware Hytera. Guarda le righe
  `[SCAN] *** ...` dei pacchetti Hytera: contengono i nomi delle azioni intent più probabili. In questo caso conviene
  chiedere a Hytera (o al distributore) la documentazione per gli sviluppatori di terze parti.
