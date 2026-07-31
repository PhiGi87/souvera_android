# Souvera Workspace – Play Console Changelog

<de-DE>
• Sofortige Push-Benachrichtigungen für neue Mails per IMAP IDLE (keine FCM-Verzögerung mehr)
• Hintergrund-Sync-Fallback alle 15 Minuten falls Push kurzzeitig nicht verfügbar
• Chat: Nachrichten kopieren, zitieren & antworten per langem Tipp
• Chat: Trennlinien zwischen Tagen und gruppierte Nachrichten desselben Absenders
• Chat: Nachrichten-Status (gesendet/zugestellt/gelesen)
• Verbesserte Akku-Laufzeit durch FCM-Mail-Entlastung
• Stabilitäts- und Performance-Verbesserungen
</de-DE>

## Kurzfassung „Neuigkeiten" (Deutsch, ≤500 Zeichen für das Release-Feld)

Sofortige Push-Benachrichtigungen für neue Mails – direkt vom Server per IMAP IDLE, unabhängig von Google-Diensten. Der Mail-Empfang ist jetzt nahezu in Echtzeit und zuverlässiger als je zuvor. Chat-Verbesserungen: Nachrichten kopieren, zitieren & antworten per langem Tipp sowie deutlich bessere Übersicht durch Tages-Trenner und Absender-Gruppierung. FCM-Mail-Push wurde entfernt, was die Akku-Laufzeit verbessert. Stabilitäts- und Performance-Verbesserungen.

## Kurzfassung „What's new" (English, ≤500 chars)

Instant push notifications for new mail via IMAP IDLE – delivered directly from the server, completely independent of Google services. Mail arrives in near real-time, more reliably than ever before. Chat improvements: copy, quote & reply on long-press plus better overview with date separators and sender grouping. FCM mail push has been removed, improving battery life. Stability and performance improvements.

---

## [NEU seit letztem Release]

### Push-Zuverlässigkeit (komplett überarbeitet)
- **IMAP IDLE Foreground Service:** Hält eine persistente IMAP-Verbindung zum Server. Neue Mails werden in <1 Sekunde zugestellt – völlig unabhängig von FCM oder Google-Diensten. Batterie-schonend dank passivem IDLE-Protokoll.
- **WorkManager Periodic MailSync:** 15-Minuten-Sicherheitsnetz synchronisiert den Posteingang auch dann, wenn der IDLE-Service kurzzeitig unterbrochen wurde (Doze, App-Standby, Netzwerkwechsel).
- **FCM-Mail-Push entfernt:** Die alte Stalwart-Webhook→FCM→Android-Pipeline für Mail-Benachrichtigungen wurde deaktiviert. IDLE liefert zuverlässigere und schnellere Zustellung ohne Google-Abhängigkeit. FCM bleibt für Nicht-Mail-Ereignisse (Server-Konfiguration, Test-Pings) aktiv.
- **UID-Deduplizierung:** Keine doppelten Benachrichtigungen mehr – das System erkennt bereits zugestellte Mails und benachrichtigt nur bei echten neuen Nachrichten.

### Chat (Link)
- **Kontext-Menü:** Langes Tippen auf eine Nachricht öffnet Optionen: Kopieren, Antworten (Zitat), Weiterleiten
- **Tages-Trenner:** Datumsbalken („Heute", „Gestern", Datum) zwischen Nachrichten verschiedener Tage
- **Absender-Gruppierung:** Aufeinanderfolgende Nachrichten desselben Absenders werden visuell gruppiert
- **Nachrichten-Status:** Eigene Nachrichten zeigen gesendet/zugestellt/gelesen an

---

## Vollständiger Changelog (seit dem zuletzt veröffentlichten Stand)

Der bisher veröffentlichte Stand war ein eingebetteter SnappyMail-Webmail-Client.
Seitdem wurde die App grundlegend zu einem eigenständigen, nativen Workspace umgebaut.

### Mail (neu: nativer IMAP/SMTP-Client statt Weboberfläche)
- Nativer Posteingang mit Ordner-/Postfachauswahl, geteilten Postfächern und schnellem Erststart.
- WYSIWYG-Editor beim Verfassen (Fett/Kursiv/Unterstrichen, Listen) inkl. Anhängen.
- Echte „Antworten": Betreff mit „Re:", zitiertes Original und korrektes Threading (In-Reply-To/References).
- Kontakt-Vervollständigung im Empfängerfeld.
- Schutz der Privatsphäre: externe Inhalte werden blockiert; Banner „Externe Inhalte laden" pro Mail.
- Reichhaltige Push-Benachrichtigungen für neue Mails (Absender, Betreff, Vorschau) – Inhalte werden dabei lokal ermittelt und nicht über den Push-Dienst übertragen.
- Mail-Einstellungen: Sync-Intervall, „Jetzt synchronisieren", letzter Sync-Status, Signatur.

### Link (neu: nativer Chat + Anrufe)
- Nativer Chat im WhatsApp-Stil in Souvera-Blau: Verlauf, Senden, Suche, neue Chats/Gruppen, Emojis.
- Anhänge im Chat: senden mit Fortschrittsanzeige, Bildvorschau direkt in der Unterhaltung und Vollbild-Ansicht; Dateien öffnen in der App.
- Sprach- und Videoanrufe (WebRTC über die High-Performance-Backend-Infrastruktur).
- Eingehende Anrufe klingeln als Vollbild-Anruf über dem Sperrbildschirm (Annehmen/Ablehnen); „Verpasster Anruf" bei Nichtannahme.
- Auswahl zwischen Sprach- und Videoanruf; im Sprachanruf lässt sich die Kamera nachträglich aktivieren; Näherungssensor schaltet das Display am Ohr aus.
- Auflegen beendet den 1:1-Anruf zuverlässig auf beiden Seiten.
- System-Chats werden ausgeblendet; „Notiz an mich" öffnet die Notizen.

### Auto / Freisprechen
- Integration ins Telefonie-System (Telecom): Anrufe laufen über verbundene Bluetooth-/Android-Auto-Systeme, inkl. Bedienung übers Auto.
- Chat-Nachrichten können im Auto vorgelesen und per Sprache beantwortet werden.

### Kalender, Kontakte, Notizen
- Kalender mit Editor und CalDAV-Synchronisierung.
- Kontakte-Ansicht mit CardDAV-Synchronisierung.
- Notizen als native Oberfläche.

### Allgemein / System
- Globaler Status (Online, Abwesend, Bitte nicht stören, Unsichtbar) oben rechts in Mail und Link.
- Zuverlässige Push-Benachrichtigungen über einen eigenen, datenschutzfreundlichen Push-Dienst.
- Durchgängiges Souvera-Branding, heller und dunkler Modus.
- Zahlreiche Stabilitäts- und Performance-Verbesserungen.
