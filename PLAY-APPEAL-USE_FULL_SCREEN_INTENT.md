# Play Policy Appeal — USE_FULL_SCREEN_INTENT

**App:** Souvera Workspace (eu.souvera.workspace)
**Version betroffen:** 10018600
**Issue:** „Permission use is not directly related to your app's core purpose"
**Entscheidung:** Die Berechtigung wird NICHT entfernt — sie ist berechtigt (Kategorie „receiving phone or video calls"). Wir legen Berufung ein.

---

## 1. Appeal-Text (Englisch, zum Einfügen in Play Console)

> Dear Google Play Review Team,
>
> We respectfully appeal the rejection of Souvera Workspace regarding the
> USE_FULL_SCREEN_INTENT permission.
>
> **Receiving phone and video calls is a core function of the app.**
> Souvera Workspace is a secure business workspace whose central communication
> pillar is **Souvera Link** — an integrated voice and video calling service
> (based on Nextcloud Talk technology) for 1:1 and group calls. Users make and
> receive calls every day; the app is registered with the Android Telecom
> framework (MANAGE_OWN_CALLS), routes calls through Bluetooth headsets and
> Android Auto, and shows missed-call notifications.
>
> **USE_FULL_SCREEN_INTENT is used exclusively for incoming calls:**
> When a call arrives, the app displays the standard Android incoming-call
> full-screen notification over the lock screen with Accept / Decline actions.
> Technically this is implemented via
> `NotificationCompat.CallStyle.forIncomingCall(...)` together with
> `Notification.Builder.setFullScreenIntent(...)` and category
> `Notification.CATEGORY_CALL` — exactly the intended use case of the
> „receiving phone or video calls" category of the Full-Screen Intent policy
> for apps targeting Android 14+.
>
> The permission is never used for any other purpose (no alarms, no ads, no
> promotional content). Removing it would break the incoming-call experience:
> users could no longer answer calls from the lock screen, which would disable
> a core function of the product.
>
> We are happy to provide additional evidence (screen recordings of incoming
> calls, the Telecom integration source, Play listing texts) if helpful.
>
> Thank you for re-evaluating.
>
> — Souvera / Host-On Development Team

## 2. Belege (bei Rückfragen bereithalten)

| Beleg | Fundstelle |
|---|---|
| Vollbild-Klingelanzeige | `NotificationWork.kt` → `showIncomingLinkCall()`: `setCategory(CATEGORY_CALL)`, `CallStyle.forIncomingCall(caller, decline, answer)`, `setFullScreenIntent(ringing, true)` |
| Telecom-Integration (Anrufe im System) | `MANAGE_OWN_CALLS` im Manifest + Changelog: „Anrufe laufen über verbundene Bluetooth-/Android-Auto-Systeme" |
| Funktion öffentlich dokumentiert | `CHANGELOG-playstore.md` → „Eingehende Anrufe klingeln als Vollbild-Anruf über dem Sperrbildschirm (Annehmen/Ablehnen)" |
| App-Listing | Play-Store-Text beschreibt Souvera Link als Anruf-Funktion |

## 3. Technische Details für die Review-Erklärung

- **EINZIGE Verwendung:** ankommende Anrufe klingeln als Vollbild-Notification
  (`setFullScreenIntent(ringing, true)`), ansonsten kommt die Berechtigung nirgends vor.
- **Kein Missbrauch:** keine Alarme, keine Werbung, keine Unterbrechungen anderer Apps.
- **Android-14-Verhalten:** Falls die Berechtigung nicht gewährt ist (Spezialzugriff),
  zeigt die App die Anruf-Notification als normale High-Priority-Notification —
  die Kernfunktion „Anruf annehmen" bleibt also auch ohne Spezialzugriff nutzbar.
  (Das entspricht der Policy: Apps, die die Kategorie nicht erfüllen, müssen die
  Berechtigung beim Nutzer anfragen — wir ERFÜLLEN die Kategorie.)

## 4. Was wir zusätzlich getan haben (Manifest-Hygiene)

- Doppelte `USE_FULL_SCREEN_INTENT`-Deklaration entfernt (war im generischen
  Permission-Block UND im Souvera-Link-Block).
- Kommentar im Manifest präzisiert, damit der Reviewer auf Anhieb sieht, wofür
  die Berechtigung da ist (CallStyle + setFullScreenIntent, Kategorie „receiving
  phone or video calls").

## 5. Vorgehen

1. Appeal über Play Console einreichen (Text aus Abschnitt 1).
2. Parallel/optional: neues Release mit dem bereinigten Manifest bauen und in
   die betroffenen Tracks (Closed Testing / Open Testing / Production) hochladen,
   sodass die nicht-konforme VersionCode auf „0 releases" fällt.
3. Bei Rückfragen von Google: Screencast eines eingehenden Anrufs + Verweis auf
   die Code-Stellen aus Abschnitt 2 nachreichen.
