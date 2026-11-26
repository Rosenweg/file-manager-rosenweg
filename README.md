# Rosenweg Files

Dateimanager für die Smartphones der Siedlung Rosenweg in Kaiseraugst. Neben den Dateien auf dem Telefon zeigt er die eigenen Dateien in der Rosenweg-Nextcloud und stellt sie auch anderen Apps zur Verfügung. Die App braucht keine andere Rosenweg-App.

## Im Rosenweg

- Chip "Nextcloud" neben "Intern": Ordner durchsuchen, Dateien öffnen, hochladen, neuer Ordner, umbenennen, in den Papierkorb. Die Ansicht startet in `Persönlich`, falls vorhanden; versteckte Dateien nur mit "Versteckte anzeigen".
- "Rosenweg Nextcloud" erscheint in der Dateiauswahl von Android (DocumentsProvider). Änderungen anderer Apps werden zurückgeschrieben; hat jemand anderes die Datei inzwischen geändert, entsteht eine Konfliktkopie. Die Rechte aus der Nextcloud gelten auch hier.
- Office-Dateien (docx, xlsx, pptx, odt, …) lassen sich direkt in Euro-Office im Browser bearbeiten; langer Druck öffnet eine Datei in der Nextcloud im Browser.
- Kopien aus der Nextcloud liegen nur im Cache und werden nach einer Stunde und beim Abmelden gelöscht. Root-Zugriff ist abgeschaltet.
- **Rosenweg-Nextcloud:** Anmeldung über den Login der Nextcloud mit dem Rosenweg-Konto im Browser; die App erhält ein eigenes App-Passwort, nie das eigentliche Passwort. Auf geteilten Telefonen kommt der Zugang von Rosenweg Gerät. Hochgeladen wird über eine Warteschlange, sobald Netz da ist; lokal gelöscht wird erst, wenn die Nextcloud den Empfang bestätigt hat. Beim ersten Start fragt die App einmal, ob man sich anmelden möchte ("Nein, danke" wird gemerkt, Anmelden geht weiter über die Einstellungen).
- **Rosenweg Updater:** Auf Handys ohne Rosenweg Gerät prüft die App den App-Katalog der Telefonanlage und bietet Aktualisierungen für alle installierten Rosenweg-Apps an. Jede APK wird vorher geprüft (Prüfsumme, Paketname, Version, Rosenweg-Signatur); Android lässt jede Installation bestätigen. Auf geteilten Telefonen übernimmt das Rosenweg Gerät.

## Installation

- **Geteilte Rosenweg-Telefone:** Rosenweg Gerät installiert die App automatisch aus dem App-Katalog der Telefonanlage und hält sie aktuell.
- **Eigenes Handy:** APK von [www.rosenweg4303.ch/apps](https://www.rosenweg4303.ch/apps) herunterladen und installieren. Danach hält der Rosenweg Updater die Rosenweg-Apps aktuell.

Die App gibt es nicht im Play Store und nicht auf F-Droid. Sie ist mit dem Rosenweg-Schlüssel signiert; Fassungen anderer Herkunft lassen sich nicht darüber installieren.

## Bauen

Die App baut gegen den Rosenweg-Fork der Fossify Commons und die Rosenweg-Module `cloud`/`updater` (`ch.rosenweg4303:*`). Diese kommen aus dem lokalen Maven-Repository (`mavenLocal()`), also zuerst [commons-rosenweg](https://github.com/Rosenweg/commons-rosenweg) veröffentlichen:

```bash
# in commons-rosenweg, Zweig rosenweg
./gradlew -PVERSION=7.1.3-rosenweg.1 publishToMavenLocal

# in diesem Repo, Zweig rosenweg
./gradlew assembleFossRelease
```

Die APK liegt danach unter `app/build/outputs/apk/foss/release/`. Signiert wird mit `keystore.properties` (Vorlage: `keystore.properties_sample`); die Datei und der Schlüssel liegen nicht im Repo. Paketname: `ch.rosenweg4303.filemanager`.

## Herkunft und Lizenz

Fork von [Fossify File Manager](https://github.com/FossifyOrg/File-Manager). Der Zweig `main` folgt dem Original, die Rosenweg-Anpassungen liegen im Zweig `rosenweg`. Herzlichen Dank an das Fossify-Team und alle Mitwirkenden für die solide Grundlage.

Lizenz: GNU General Public License v3.0, siehe [LICENSE](LICENSE).
