# Notes for working on this fork

This repository is a personal fork of Firefox for Android (Fenix) that is built by GitHub Actions
(`.github/workflows/build-debug-apk.yml`) and installed by hand. These notes are for whoever changes it next, human or
AI. They list the things that are easy to break and expensive to find out about.

## Installing updates

The APK from a build must install **over** the app that is already on the phone, so that settings, logins, tabs and
everything else survive. Android only allows that when all of these hold:

1. **Same package name.** `applicationId` plus the `.fenix.debug` suffix of the debug build type, in
   `mobile/android/fenix/app/build.gradle`. Never change either.
2. **Same signing key.** Every build is signed with `.github/keys/debug.keystore` (the workflow copies it to
   `~/.android/debug.keystore`). Never replace or regenerate it, and never sign debug builds with another key.
3. **A versionCode that is not lower than the installed one.** The debug `versionCode` is
   `forkDebugVersionCode()` in `mobile/android/fenix/app/build.gradle`: `2050000000` plus the minutes since
   2026-01-01 UTC. It only ever goes up, whatever runs the build (CI, a re-run, another workflow, another repository, a
   local build). **Never lower it and never switch to a scheme that can produce smaller numbers**, such as a CI run
   number, a counter or a constant. Android's maximum is 2100000000, which this scheme reaches around the year 2121.

`Verify APK signature and version code` in the workflow checks 2 and 3 on every APK it builds, writes a table into the
job summary, and fails the job when an APK would not install as an update. If the scheme in `build.gradle` ever has to
change, change `MIN_VERSION_CODE` in the workflow with it, and keep the new numbers above every number used before.

If a phone shows "App not installed" for an update anyway, the installed copy was made with another key or with a
higher versionCode than the build has. The copy has to be uninstalled once (this erases its data) after which all
later builds update normally. If the installed copy still runs and has the backup feature (see below), make a backup
first and restore it after the reinstall. The APKs of a build are split by CPU: install the `arm64-v8a` one on almost
every phone.

## Backup and restore

The app can save all of its data to one file and restore it from such a file (Settings → Backup and restore). The code
is in `mobile/android/fenix/app/src/main/java/org/mozilla/fenix/backup/`; the `README.md` there explains how the file
and the code are laid out. **When you add or change something that keeps data the person would miss after a reinstall,
keep the backup in step with it, in the same change.** What is saved is exactly what `BackupSections.kt` lists.

What to do depends on where the data lives:

- **A new setting in a `SharedPreferences` file that is saved already** (`fenix_preferences`, the default preferences,
  `custom_autocomplete`, the reader view file, the language, IP protection): nothing. Every key of these files is saved,
  including keys added later. Only when the setting means something for one installation or device only (an identifier,
  a counter, a token, a path, account state, a cache, a developer option), add its name or a prefix to
  `DeviceSpecificPreferences` in `sections/SettingsSection.kt`.
- **A new `SharedPreferences` file**: add a `PreferencesFile` there and list it in the default `files` of
  `SettingsSection`. The name inside the backup never changes, even if the file on disk is renamed.
- **A new Preferences DataStore**: add a `PreferencesDataStoreSection` to `BackupSections.all()`, with `Style.SETTINGS`
  for choices of the person and `Style.STATE` for what the app collects while it is used.
- **A database, files, or anything else**: write a `BackupSection` in `backup/sections/`, add it to
  `BackupSections.all()` after the sections it refers to (the order is the order of saving and restoring), and add its
  title as `backup_section_<id>` to `res/values/backup_strings.xml`. Set `sensitive = true` when it holds secrets (the
  screen then asks for a passphrase).
- **Data that is left out on purpose**: say so in the comment of `BackupSections` and in the `README.md`, and tell the
  person. Today that is the Firefox account, add-ons, site permissions, downloads, credit cards and addresses, private
  and recently closed tabs, wallpapers, and what websites store themselves except cookies.

Rules that keep old backups working. They matter more than any single feature, because a backup is only worth anything
when it can still be restored a year from now:

1. **Never change the `id` of a section, the name of a field in its lines, or the name a preference file has inside the
   backup.** Stop writing a field instead of renaming it.
2. **Every version of the app reads every older backup.** When the lines of a section change in a way old code could
   not understand, raise that section's `version` and keep `import` able to read the old lines
   (`SectionReader.sectionVersion`). Raise `BackupContainer.FORMAT_VERSION` or `BackupArchive.ARCHIVE_FORMAT` only when
   the layout of the file itself changes.
3. **Restoring only adds.** It must never delete or overwrite what the person has (settings are the exception: the
   backup wins). Restoring the same backup twice must change nothing the second time. One failing section must not stop
   the others: throw for a section-wide problem, `SectionWriter.warn` or `SectionReader.skipped` for single items.
4. **No secrets in messages.** Warnings and errors show up on screen; name the website or the setting, never a password,
   cookie value or other content.
5. **The cookies are read and written by a built-in extension** (`assets/extensions/backup_cookies/`). Keep both sides
   in step: `MESSAGE_NAME`, `EXTENSION_ID` (also in `manifest.json`) and the list of cookie fields (`COOKIE_FIELDS` in
   `CookiesSection.kt`, `toBackupCookie` in `background.js`).
6. **Mozilla's own files stay as they are, as far as possible.** The feature adds only new files and three small edits
   to existing ones: the `BackupActivity` entry in `AndroidManifest.xml`, the "Backup and restore" entry in
   `res/xml/preferences.xml`, and its click handler in `settings/SettingsFragment.kt`. Its texts are in their own
   `backup_strings.xml`. If upstream changes are merged in, keep those three edits.

Before handing over a change to the backup, check it the way `README.md` says (a plain JVM for the logic, a phone for the
rest), and update `README.md` when the list of sections or the rules change.
