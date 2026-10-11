import dayjs, { type ConfigType, type Dayjs } from "dayjs";

type AppointmentTime = {
  timePeriod?: ConfigType;
  full?: boolean;
};

function validTime(value?: ConfigType) {
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed : null;
}

export function isAppointmentTimeAvailable(
  appointment?: AppointmentTime | null,
  now: Dayjs = dayjs()
) {
  if (!appointment || appointment.full) return false;
  const scheduledAt = validTime(appointment.timePeriod);
  return Boolean(scheduledAt?.isAfter(now));
}

export function canStartAssessment(
  timePeriod?: ConfigType,
  now: Dayjs = dayjs()
) {
  if (timePeriod == null) return true;
  const scheduledAt = validTime(timePeriod);
  return Boolean(scheduledAt && !now.isBefore(scheduledAt));
}
