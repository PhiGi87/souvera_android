<!--
 ~ SPDX-FileCopyrightText: 2016-2024 Nextcloud GmbH and Nextcloud contributors
 ~ SPDX-FileCopyrightText: 2026 Host-On Service Provider GmbH (Souvera)
 ~ SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
-->
# Souvera Workspace — Android App

**Souvera Workspace** ist die Kommunikations- und Sicherheits-App für Ihren Souvera-Account:
Mail, Telefonie (Talk), Schutz (Shield), Kalender und Notizen — betrieben von der
**Host-On Service Provider GmbH** auf Ihrer eigenen Nextcloud-Instanz in Deutschland.

[<img src="https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png"
alt="Download from Google Play"
height="80">](https://play.google.com/store/apps/details?id=eu.souvera.workspace)
[<img src="https://souvera.eu/favicon.svg" alt="souvera.eu" height="64">](https://souvera.eu)

## Funktionen

- **Mail:** Alle Postfächer an einem Ort (auch geteilte Team-Postfächer), Push-Benachrichtigungen,
  Spam-Abwehr mit Absender-Sperrung, Volltextsuche, Ordnerverwaltung
- **Telefonie (Talk):** Sprach- und Videoanrufe, Vollbild-Klingelanzeige bei eingehenden Anrufen,
  Chats mit Bildern und Dateien
- **Shield:** Spam-Quarantäne einsehen und verwalten, Nachrichten freigeben oder Absender sperren —
  auch für geteilte Postfächer
- **Kalender:** Tages-, Wochen- und Monatsansicht mit Kalender-Auswahl
- **Notizen:** Markdown-Notizen mit Server-Synchronisation

## Basiert auf Nextcloud Android

Souvera Workspace basiert auf der Open-Source-App
[**Nextcloud Android**](https://github.com/nextcloud/android) der Nextcloud GmbH und wird unter
denselben Bedingungen lizenziert (AGPL-3.0-or-later bzw. alternativ GPL-2.0-only, siehe
[LICENSE.txt](LICENSE.txt)).

Souvera Workspace ist eine **eigenständige App der Host-On Service Provider GmbH**. Sie ist weder
Teil von Nextcloud noch wird sie von der Nextcloud GmbH entwickelt, unterstützt oder zertifiziert.
„Nextcloud" ist eine Marke der Nextcloud GmbH.

## Entwicklung

- Build: Android Studio / Gradle (`./gradlew assembleGplayDebug`)
- Der Mail-, Talk- und Shield-Code liegt unter `app/src/main/java/com/souvera/workspace/`
- Beiträge bitte als Pull-Request gegen `main`

## Sicherheit

Hinweise zur Meldung von Sicherheitsproblemen finden Sie in [SECURITY.md](SECURITY.md).

## Lizenz

AGPL-3.0-or-later **oder** GPL-2.0-only — Details in [LICENSE.txt](LICENSE.txt).
