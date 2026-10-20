/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/* globals browser */

"use strict";

// The other side of this conversation is CookieExchange in
// app/src/main/java/org/mozilla/fenix/backup/sections/CookiesSection.kt. Read the comment there first.
//
// The app installs this extension for as long as a backup is made or restored. The extension says hello, and every
// message it sends is answered with what to do next: { cmd: "export" }, { cmd: "import", cookies: [...] },
// { cmd: "next" } or { cmd: "done" }.

// This must be the same name as MESSAGE_NAME in CookiesSection.kt.
const APP = "fenixForkBackupCookies";

// How many cookies are sent to the app in one message.
const COOKIES_PER_MESSAGE = 200;

// How long a cookie that has no expiry time lasts after it was restored.
const SESSION_COOKIE_LIFETIME_SECONDS = 14 * 24 * 60 * 60;

const HELLO_TRIES = 5;
const HELLO_RETRY_DELAY_MS = 500;
const MAX_ERROR_LENGTH = 200;

function send(message) {
  return browser.runtime.sendNativeMessage(APP, message);
}

function describe(error) {
  const text = String((error && error.message) || error || "unknown error");
  return text.slice(0, MAX_ERROR_LENGTH);
}

async function hello() {
  let lastError;
  for (let attempt = 0; attempt < HELLO_TRIES; attempt++) {
    try {
      return await send({ type: "hello" });
    } catch (e) {
      lastError = e;
      await new Promise(resolve => setTimeout(resolve, HELLO_RETRY_DELAY_MS));
    }
  }
  throw lastError;
}

async function getAllCookies() {
  try {
    // An empty partition key asks for the partitioned cookies as well.
    return await browser.cookies.getAll({ partitionKey: {} });
  } catch (e) {
    // This browser does not know about partitioned cookies.
    return browser.cookies.getAll({});
  }
}

// The cookie as it is written in the backup. The same fields are listed in COOKIE_FIELDS in CookiesSection.kt.
function toBackupCookie(cookie) {
  const saved = {
    name: cookie.name,
    value: cookie.value,
    domain: cookie.domain,
    hostOnly: cookie.hostOnly,
    path: cookie.path,
    secure: cookie.secure,
    httpOnly: cookie.httpOnly,
    sameSite: cookie.sameSite,
    session: cookie.session,
  };
  if (!cookie.session && typeof cookie.expirationDate === "number") {
    saved.expirationDate = cookie.expirationDate;
  }
  if (typeof cookie.firstPartyDomain === "string") {
    saved.firstPartyDomain = cookie.firstPartyDomain;
  }
  if (cookie.partitionKey) {
    saved.partitionKey = cookie.partitionKey;
  }
  return saved;
}

async function exportCookies() {
  const cookies = await getAllCookies();
  for (let i = 0; i < cookies.length; i += COOKIES_PER_MESSAGE) {
    await send({
      type: "cookies",
      cookies: cookies.slice(i, i + COOKIES_PER_MESSAGE).map(toBackupCookie),
    });
  }
  return send({ type: "exportDone", total: cookies.length });
}

function cookieKey(cookie) {
  return JSON.stringify([
    cookie.name,
    cookie.domain,
    cookie.path,
    cookie.firstPartyDomain || "",
    cookie.partitionKey || null,
  ]);
}

let knownCookies;

// The arguments of cookies.set() for a cookie of the backup.
function toSetDetails(cookie) {
  const path = cookie.path || "/";
  const host = String(cookie.domain).replace(/^\./, "");
  const details = {
    url: (cookie.secure ? "https://" : "http://") + host + path,
    name: cookie.name,
    value: cookie.value,
    path,
    secure: !!cookie.secure,
    httpOnly: !!cookie.httpOnly,
  };
  // A cookie that belongs to a domain (and its subdomains) keeps the domain. One that belongs to a host only has none.
  if (!cookie.hostOnly) {
    details.domain = cookie.domain;
  }
  if (cookie.sameSite) {
    details.sameSite = cookie.sameSite;
  }
  if (!cookie.session && typeof cookie.expirationDate === "number") {
    details.expirationDate = cookie.expirationDate;
  } else {
    details.expirationDate = Date.now() / 1000 + SESSION_COOKIE_LIFETIME_SECONDS;
  }
  if (typeof cookie.firstPartyDomain === "string") {
    details.firstPartyDomain = cookie.firstPartyDomain;
  }
  if (cookie.partitionKey) {
    details.partitionKey = cookie.partitionKey;
  }
  return details;
}

async function importCookies(cookies) {
  if (!knownCookies) {
    // Only cookies the browser does not have are added: a restore never replaces what is there.
    knownCookies = new Set((await getAllCookies()).map(cookieKey));
  }
  let set = 0;
  let existing = 0;
  let failed = 0;
  let error;
  for (const cookie of cookies) {
    const key = cookieKey(cookie);
    if (knownCookies.has(key)) {
      existing++;
      continue;
    }
    try {
      if (await browser.cookies.set(toSetDetails(cookie))) {
        knownCookies.add(key);
        set++;
      } else {
        failed++;
        error = error || "the browser refused the cookie";
      }
    } catch (e) {
      failed++;
      error = error || describe(e);
    }
  }
  const result = { type: "imported", set, existing, failed };
  if (error) {
    result.error = error;
  }
  return send(result);
}

async function main() {
  let reply = await hello();
  while (reply && reply.cmd !== "done") {
    if (reply.cmd === "export") {
      reply = await exportCookies();
    } else if (reply.cmd === "import") {
      reply = await importCookies(reply.cookies || []);
    } else {
      // A reply this version does not know. The app and the extension are always built together, so this is a bug.
      throw new Error("unknown command " + reply.cmd);
    }
  }
}

main().catch(e => {
  console.error("Backup cookies:", describe(e));
  // Tell the app, so that it does not have to wait for the time out. If it cannot be told, there is nothing left to do.
  send({ type: "error", message: describe(e) }).catch(() => {});
});
