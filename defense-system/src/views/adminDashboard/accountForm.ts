export function passwordRequiredFor(isEditing: boolean) {
  return !isEditing;
}

export function accountMutationPayload<T extends Record<string, unknown>>(
  formState: T,
  isEditing: boolean
) {
  const payload = { ...formState };
  if (isEditing && !String(payload.password || "").trim()) {
    delete payload.password;
  }
  return payload;
}
