const SessionMarkerKey = "Admin-Token-Defense-System";

/** Only the shared login marker (or a localStorage clear) signals a session change.
 * This is a cancellation hint, never proof of identity or server authorization. */
export function isSessionMarkerStorageEvent(event: StorageEvent) {
  return event.storageArea === window.localStorage
    && (event.key === null || event.key === SessionMarkerKey);
}

export function hasSession() {
  const marker = window.localStorage.getItem(SessionMarkerKey);
  if (!marker) {
    return false;
  }
  if (marker !== "1") {
    window.localStorage.setItem(SessionMarkerKey, "1");
  }
  return true;
}

export function markSession() {
  window.localStorage.setItem(SessionMarkerKey, "1");
}

export function clearSession() {
  window.localStorage.removeItem(SessionMarkerKey);
}
