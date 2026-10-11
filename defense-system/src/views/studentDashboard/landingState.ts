export type DashboardStatistic = {
  toAppoint?: number;
  todo?: number;
  analysis?: number;
  done?: number;
};

export function resolveInitialDashboardTab(statistic: DashboardStatistic) {
  if (Number(statistic.toAppoint || 0) > 0) return 1;
  if (Number(statistic.todo || 0) > 0) return 2;
  if (
    Number(statistic.analysis || 0) > 0 ||
    Number(statistic.done || 0) > 0
  ) {
    return 4;
  }
  return 1;
}
