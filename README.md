# Hemskärm för Android TV

En egen startskärm för Philips-TV:n (Android TV): klocka, hälsning, väder, er gemensamma Google-kalender och knappar till Netflix, HBO Max, TV4 Play, SVT Play och YouTube. Rutan "Alla appar" visar resten av apparna och ⚙ öppnar inställningarna.

## 1. Hämta kalenderlänken

Det enda som behövs från Google är kalenderns hemliga iCal-adress, så ingen inloggning sker på TV:n.

1. Öppna Google Kalender **på datorn** (det går inte i mobilappen).
2. Håll musen över den gemensamma kalendern i vänsterlistan → ⋮ → **Inställningar och delning**.
3. Scrolla till **Integrera kalender** och kopiera **Hemlig adress i iCal-format** (slutar på `basic.ics`).

Den som äger kalendern ser adressen. Länken ger läsrätt till kalendern för den som har den, så dela den inte vidare. Ändringar syns på TV:n inom cirka 10 minuter (Google uppdaterar själv sin iCal-fil med viss fördröjning, ibland upp till en halvtimme).

## 2. Bygg appen

**Alternativ A – Android Studio:** Öppna mappen, vänta på Gradle-synken och kör *Build → Build APK(s)*. Filen hamnar i `app/build/outputs/apk/debug/app-debug.apk`.

**Alternativ B – GitHub (ingen installation):** Lägg mappen i ett GitHub-repo och pusha. Fliken *Actions* bygger automatiskt; ladda ner `hemskarm-apk` från körningen.

## 3. Installera på TV:n

1. På TV:n: Inställningar → Enhetsinställningar → Om → klicka 7 gånger på **Version** (eller Build) så låses utvecklaralternativ upp. På Philips ligger detta ibland under Alla inställningar → Android-inställningar.
2. Utvecklaralternativ → slå på **USB-felsökning** (och **Nätverksfelsökning** om det finns).
3. Ta reda på TV:ns IP-adress (Inställningar → Nätverk).
4. Från datorn (med [platform-tools](https://developer.android.com/tools/releases/platform-tools)):

```bash
adb connect 192.168.1.50:5555      # TV:ns IP
adb install app-debug.apk
```

Godkänn felsökningsrutan som dyker upp på TV:n första gången.

## 4. Fyll i inställningarna

Enklast med adb, så slipper du skriva länken med fjärrkontrollen:

```bash
adb shell am start -n se.hemma.hemskarm/.SettingsActivity \
  --es name "Signe och Anton" \
  --es place "Hässelby" \
  --es ics "https://calendar.google.com/calendar/ical/.../basic.ics"
```

Det går också att fylla i under ⚙ på TV:n. Flera kalendrar: en länk per rad.

## 5. Gör den till TV:ns startskärm

Android TV har ingen meny för att byta startskärm. Stäng av Googles startskärm så tar Hemskärm över Hem-knappen:

```bash
# Kolla vilken startskärm som används nu (brukar vara com.google.android.tvlauncher)
adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME

adb shell pm disable-user --user 0 com.google.android.tvlauncher
```

Tryck på Hem-knappen. Får du en fråga om vilken app som ska användas, välj Hemskärm → Alltid.

**Ångra:**

```bash
adb shell pm enable com.google.android.tvlauncher
```

Inget raderas, så det går alltid att gå tillbaka. Byte av HDMI-källa via fjärrkontrollens källknapp fungerar som vanligt. Vill du inte stänga av Googles startskärm finns appen ändå under TV:ns appar.

## Bra att veta

- **Appar som inte är installerade** visas nedtonade med ett plustecken. Klick öppnar Play Butik.
- **Fel app eller saknad logga?** Paketnamnen finns i `Apps.java` (`ROW`). Visa TV:ns installerade paket med `adb shell pm list packages | grep -i -E "hbo|max|tv4|svt"` och lägg till rätt namn först i listan.
- **Väder** kommer från Open-Meteo, utan nyckel eller konto.
- Appen är skriven i ren Java utan externa bibliotek, så den är liten och har få saker som kan gå sönder.
