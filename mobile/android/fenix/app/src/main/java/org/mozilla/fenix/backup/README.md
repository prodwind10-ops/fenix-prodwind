# Backup and restore

**Settings → Backup and restore** (`BackupActivity`) saves the data of the app to one file and restores it from such a
file, for example after an update that had to be uninstalled first, or on another phone. This page explains how the code
is laid out and how to keep it in step with the rest of the app. The rules that must be followed when the app changes
are in `CLAUDE.md` in the root of the repository.

## The backup file

```
file (.fxbk)
└─ container  "FXBK" header, then the ZIP archive, encrypted when the file has a passphrase     BackupContainer
   └─ ZIP archive
      ├─ manifest.json            what is in the file, and how every section went                 BackupArchive
      └─ sections/<id>.ndjson     one file per section, one JSON object per line                  BackupSection
```

- **Encryption.** The passphrase is normalized (Unicode NFC), turned into a key with PBKDF2-HMAC-SHA256 (600,000
  rounds, random salt) and the archive is encrypted in chunks with AES-256-GCM. The header is authenticated, so a changed
  header makes opening the file fail. A wrong passphrase and a damaged file look the same (`WRONG_PASSPHRASE_OR_DAMAGED`).
  A file without a passphrase is the plain ZIP archive behind the header. The passphrase cannot be recovered.
- **Versions.** `BackupContainer.FORMAT_VERSION` and `BackupArchive.ARCHIVE_FORMAT` change only when an older version of
  the app could not read the new layout. Newer versions always read every older one. `BackupSection.version` is per
  section (see "Changing the lines of a section").
- **Failures stay small.** Saving and restoring never stop at the first section that fails. The result lists every
  section: done, not in the backup, not saved, made by a newer version, unknown, or failed with a reason.
- **The file is checked.** After a backup is written it is opened again; a file that cannot be read back is deleted.
- **Nothing is deleted when restoring.** Restoring adds what is missing. The only things it replaces are settings (the
  value in the backup wins) and the default search engine. Restoring the same backup twice changes nothing.

## Where things are

| File | What it does |
| --- | --- |
| `BackupActivity.kt` | The screen. Asks what to save, the passphrase, where the file goes, what to restore. Views are made in code so it does not depend on the layouts of the rest of the app. |
| `BackupManager.kt` | Between the screen and the files: creates, verifies and opens backup files; runs the sections on a background thread. |
| `BackupSections.kt` | **The list of everything that is saved, in the order it is saved and restored.** |
| `BackupSection.kt` | The interface of a section, and the objects a section writes to and reads from. |
| `BackupArchive.kt`, `BackupModel.kt` | The ZIP archive and the manifest. |
| `BackupContainer.kt`, `BackupCrypto.kt` | The header and the encryption. Only `java.*` and `javax.crypto.*` classes are used there. |
| `BackupJson.kt`, `BackupException.kt` | Small helpers. |
| `sections/` | One file per group of sections (see below). |
| `res/values/backup_strings.xml` | All texts of the feature. |
| `assets/extensions/backup_cookies/` | The small built-in extension that reads and writes cookies. |

## What is saved

| Section id | What | Restoring |
| --- | --- | --- |
| `settings` | Every setting in the preference files `fenix_preferences`, the default preferences, `custom_autocomplete`, `mozac_feature_reader_view`, the language and IP protection, except what `DeviceSpecificPreferences` lists | The value in the backup replaces the value that is set. Settings that are not in the backup stay. |
| `app_datastore` | The settings that the app keeps in its Preferences DataStore | Same |
| `feature_toggles` | The switches of translations, AI features and summaries | Same |
| `tabs` | Open tabs (not private ones) with their page history and which one is selected | Tabs that are not open yet are added after the open ones. A tab keeps its ID. |
| `tab_groups` | Groups with name, color, closed or not, and their tabs | Groups that exist are left alone |
| `tab_manager` | Which tabs are locked, tab names, flags | Joined with what is there, nothing is lost |
| `bookmarks` | Folders, bookmarks, separators | Merged: a folder is matched by name, a bookmark by address in its folder |
| `history` | Every visit the history screen shows | Sites that are not in the history are added (up to 3 visits each, dated with the time of the restore) |
| `logins` (sensitive) | Saved logins and passwords | Added when there is no login for the same website and user name; a saved password is never overwritten |
| `login_exceptions` | Websites where logins are never saved | Added |
| `pinned_sites` | The shortcuts the person pinned | Added when the address is not pinned |
| `search_engines` | Search engines the person added, search settings | Engines that exist are kept; lists are joined |
| `cookies` (sensitive) | Cookies of the browser, which keep the person signed in to websites | Added when the browser does not have them |

### What is not saved, on purpose

The Firefox account (sign in again to get the synced data back), add-ons and what they stored, site permissions,
downloads, credit cards and addresses, private tabs, recently closed tabs, wallpapers, the history of the settings
search, the data that websites store themselves (local storage, IndexedDB, caches: the browser engine gives no access to
it; cookies are the exception), and everything that belongs to the device or the installation instead of the person
(identifiers, counters, telemetry state, tokens, the debug menu). The same list is in the comment of `BackupSections`.

Things that would be useful but are not done yet: site permissions, add-ons, credit cards and addresses.

## Adding or changing data

The short version, which `CLAUDE.md` repeats as rules:

1. **A new setting in a preference file that is saved already** needs nothing: every key is saved. If the setting only
   means something for this installation (an ID, a counter, a token, a path, account state, a cache), add its name or a
   prefix to `DeviceSpecificPreferences`.
2. **A new preference file**: add a `PreferencesFile` (the name inside the backup never changes) and put it in the
   default list of `SettingsSection`.
3. **A new Preferences DataStore**: add a `PreferencesDataStoreSection` to `BackupSections`. Use `Style.SETTINGS` when
   the values are choices of the person, and `Style.STATE` when the app collects them while it is used (nothing that is
   there is lost: flags stay set, counters keep the larger value, sets and the maps named in `jsonMapKeys` are joined).
4. **Anything else** (a database, files, another store): write a `BackupSection` in `sections/`, add it to
   `BackupSections.all()` after the sections it refers to, and add its title as `backup_section_<id>` to
   `backup_strings.xml`. Mark it `sensitive` when it holds secrets.
5. **Something that is left out on purpose** is written down in the comment of `BackupSections` and in this file.

### Changing the lines of a section

- The `id` of a section and the names of the fields of its lines never change. A field that is no longer needed is
  just no longer written.
- Adding a field that old code can do without needs no new version. Reading is forgiving: a missing field gives `null`
  (see `BackupJson.kt`).
- A change that old code could not understand (another meaning of a field, another layout of the lines) raises
  `BackupSection.version`. `import` must keep reading every older version, which `SectionReader.sectionVersion` tells.
- A backup made by a newer version of the app is not restored by an older one (the screen says to update the app).

## How the cookies are read and written

The browser engine (GeckoView) has no cookie API for apps, only for extensions. So `BrowserCookies` installs the
built-in extension in `assets/extensions/backup_cookies/` for as long as the work takes, lets it run, and removes it.
The extension asks the app what to do, and the app answers every message with the next step. It is a plain function from
message to reply (`CookieExchange`), which is what makes it testable without a browser.

| The extension sends | The app answers |
| --- | --- |
| `{type:"hello"}` | `{cmd:"export"}`, or `{cmd:"import", cookies:[…]}` with the first part |
| `{type:"cookies", cookies:[…]}` (up to 200 per message, when exporting) | `{cmd:"next"}` |
| `{type:"exportDone", total}` | `{cmd:"done"}` |
| `{type:"imported", set, existing, failed, error?}` | `{cmd:"import", cookies:[…]}` with the next part (100 cookies), or `{cmd:"done"}` |
| `{type:"error", message}` | `{cmd:"done"}`; the app reports the message |

Things that must stay the same on both sides: `MESSAGE_NAME` (`fenixForkBackupCookies`), `EXTENSION_ID`
(`backup-cookies@fenixfork.local`, also in `manifest.json`), and the list of cookie fields (`COOKIE_FIELDS` in
`CookiesSection.kt` and `toBackupCookie` in `background.js`). A cookie that is already in the browser is never replaced.
A cookie without an expiry time gets one of two weeks, because it would otherwise be gone at the restart that restoring
needs. Cookies of private tabs are not part of this.

## Checking a change

There are no automated tests for this feature in the repository. The core (`BackupContainer`, `BackupCrypto`,
`BackupArchive`, the JSON helpers) and the logic of the settings and DataStore sections touch so few Android classes
that they can be tested on a plain JVM with stand-ins for those classes, and the cookie conversation can be tested by
running `background.js` in Node against `CookieExchange` (compiled and run by the JVM). Whatever is changed, also try it
on a phone, because the file picker, the stores and the browser engine are real there:

1. Make a backup with a passphrase and one without. Open the one with a passphrase with a wrong passphrase.
2. Uninstall the app, install it again, restore, restart. Check tabs (and the locked ones), history, bookmarks, logins,
   settings, and that sites where you were signed in still know you.
3. Restore the same file a second time: nothing may be duplicated.
