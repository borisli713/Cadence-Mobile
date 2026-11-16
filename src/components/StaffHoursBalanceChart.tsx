import React, { useMemo } from 'react';
import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { StaffFairnessMetric } from '../types';

interface StaffHoursBalanceChartProps {
  metrics: StaffFairnessMetric[];
}

interface ChartRow {
  uid: string;
  shortName: string;
  displayName: string;
  department: string;
  workedHours: number;
  scheduledHours: number;
  effectiveTotalHours: number;
  weeklyHoursTarget: number;
  deltaHours: number;
  utilizationPct: number;
}

function formatShortName(fullName: string): string {
  const parts = fullName.trim().split(/\s+/);
  if (parts.length <= 1) return parts[0] || 'Staff';
  return `${parts[0]} ${parts[parts.length - 1][0]}.`;
}

export const StaffHoursBalanceChart: React.FC<StaffHoursBalanceChartProps> = ({
  metrics,
}) => {
  const chartData: ChartRow[] = useMemo(() => {
    return [...metrics]
      .sort(
        (a, b) =>
          a.utilizationRatio - b.utilizationRatio ||
          a.effectiveTotalHours - b.effectiveTotalHours
      )
      .map((m) => ({
        uid: m.uid,
        shortName: formatShortName(m.displayName),
        displayName: m.displayName,
        department: m.department,
        workedHours: Number(m.workedHours.toFixed(1)),
        scheduledHours: Number(m.scheduledHours.toFixed(1)),
        effectiveTotalHours: Number(m.effectiveTotalHours.toFixed(1)),
        weeklyHoursTarget: Number(m.weeklyHoursTarget.toFixed(1)),
        deltaHours: Number((m.weeklyHoursTarget - m.effectiveTotalHours).toFixed(1)),
        utilizationPct: Math.round(m.utilizationRatio * 100),
      }));
  }, [metrics]);

  const mostUnderTarget = useMemo(() => {
    const under = chartData.filter((d) => d.deltaHours > 0.5);
    return under[0] || chartData[0] || null;
  }, [chartData]);

  const highestUtilization = useMemo(() => {
    if (chartData.length === 0) return null;
    return chartData[chartData.length - 1];
  }, [chartData]);

  return (
    <div className="bg-white border border-slate-200 rounded-xl p-5 space-y-4">
      <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-3">
        <div>
          <h2 className="text-sm font-semibold text-slate-900">
            Working Hours Balance vs. Weekly Target
          </h2>
          <p className="text-xs text-slate-500 mt-0.5">
            Sorted from lowest to highest target utilization so managers can quickly spot who needs more or fewer hours.
          </p>
        </div>

        {/* Unboxed Inline Legend */}
        <div className="flex flex-wrap items-center gap-4 text-xs text-slate-600 shrink-0">
          <span className="inline-flex items-center gap-1.5">
            <span className="w-2.5 h-2.5 rounded-sm bg-slate-900 inline-block" />
            Worked Hours
          </span>
          <span className="inline-flex items-center gap-1.5">
            <span className="w-2.5 h-2.5 rounded-sm bg-blue-700 inline-block" />
            Scheduled Hours
          </span>
          <span className="inline-flex items-center gap-1.5">
            <span className="w-2.5 h-2.5 rounded-sm bg-slate-300 inline-block" />
            Weekly Target
          </span>
        </div>
      </div>

      {chartData.length === 0 ? (
        <div className="py-10 text-center text-xs text-slate-500">
          No staff metrics available yet to display working-time balance.
        </div>
      ) : (
        <>
          {/* Concise Two-Column Balance Callout (hairline divided, no nested cards) */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 py-3 border-y border-slate-100 text-xs">
            {mostUnderTarget && (
              <div className="flex items-center justify-between gap-3">
                <span className="text-slate-500">Needs More Hours</span>
                <span className="font-medium text-slate-900 font-mono tabular-nums">
                  {mostUnderTarget.displayName}{' '}
                  <span className="text-blue-700">
                    ({mostUnderTarget.deltaHours > 0
                      ? `+${mostUnderTarget.deltaHours.toFixed(1)}h to target`
                      : `${mostUnderTarget.utilizationPct}% of target`})
                  </span>
                </span>
              </div>
            )}
            {highestUtilization && (
              <div className="flex items-center justify-between gap-3 sm:border-l sm:border-slate-100 sm:pl-4">
                <span className="text-slate-500">
                  {highestUtilization.deltaHours < 0
                    ? 'Over Target (Assign Fewer)'
                    : 'Highest Load'}
                </span>
                <span className="font-medium text-slate-900 font-mono tabular-nums">
                  {highestUtilization.displayName}{' '}
                  <span
                    className={
                      highestUtilization.deltaHours < 0
                        ? 'text-amber-700'
                        : 'text-slate-600'
                    }
                  >
                    ({highestUtilization.effectiveTotalHours.toFixed(1)}h /{' '}
                    {highestUtilization.weeklyHoursTarget.toFixed(0)}h ·{' '}
                    {highestUtilization.utilizationPct}%)
                  </span>
                </span>
              </div>
            )}
          </div>

          <div className="w-full h-[290px]">
            <ResponsiveContainer width="100%" height={290}>
              <BarChart
                data={chartData}
                margin={{ top: 8, right: 12, left: -12, bottom: 4 }}
                barGap={4}
              >
                <CartesianGrid vertical={false} stroke="#e2e8f0" strokeDasharray="3 3" />
                <XAxis
                  dataKey="shortName"
                  tick={{ fill: '#475569', fontSize: 12 }}
                  axisLine={{ stroke: '#e2e8f0' }}
                  tickLine={false}
                />
                <YAxis
                  unit="h"
                  tick={{ fill: '#64748b', fontSize: 11 }}
                  axisLine={false}
                  tickLine={false}
                />
                <Tooltip
                  cursor={{ fill: '#f8fafc' }}
                  content={({ active, payload }) => {
                    if (!active || !payload || payload.length === 0) return null;
                    const row = payload[0].payload as ChartRow;
                    return (
                      <div className="bg-white border border-slate-200 rounded-lg px-3.5 py-2.5 shadow-sm text-xs space-y-1.5">
                        <div className="font-semibold text-slate-900">
                          {row.displayName}{' '}
                          <span className="font-normal text-slate-500">
                            · {row.department}
                          </span>
                        </div>
                        <div className="grid grid-cols-2 gap-x-4 gap-y-0.5 font-mono tabular-nums text-[11px]">
                          <span className="text-slate-500">Worked:</span>
                          <span className="text-right text-slate-900 font-medium">
                            {row.workedHours.toFixed(1)}h
                          </span>
                          <span className="text-slate-500">Scheduled:</span>
                          <span className="text-right text-blue-700 font-medium">
                            {row.scheduledHours.toFixed(1)}h
                          </span>
                          <span className="text-slate-500">Total / Target:</span>
                          <span className="text-right text-slate-900 font-semibold">
                            {row.effectiveTotalHours.toFixed(1)}h / {row.weeklyHoursTarget.toFixed(1)}h
                          </span>
                        </div>
                        <div
                          className={`pt-1 border-t border-slate-100 font-medium ${
                            row.deltaHours < 0
                              ? 'text-amber-700'
                              : row.deltaHours > 0
                              ? 'text-blue-700'
                              : 'text-emerald-700'
                          }`}
                        >
                          {row.deltaHours < 0
                            ? `Over target by ${Math.abs(row.deltaHours).toFixed(1)}h — assign fewer hours`
                            : row.deltaHours > 0
                            ? `Needs +${row.deltaHours.toFixed(1)}h to reach weekly target`
                            : 'Right on weekly target (100%)'}
                        </div>
                      </div>
                    );
                  }}
                />
                <Bar
                  dataKey="workedHours"
                  name="Worked Hours"
                  stackId="actual"
                  fill="#0f172a"
                  radius={[0, 0, 0, 0]}
                  maxBarSize={30}
                />
                <Bar
                  dataKey="scheduledHours"
                  name="Scheduled Hours"
                  stackId="actual"
                  fill="#1d4ed8"
                  radius={[4, 4, 0, 0]}
                  maxBarSize={30}
                />
                <Bar
                  dataKey="weeklyHoursTarget"
                  name="Weekly Target"
                  fill="#cbd5e1"
                  radius={[4, 4, 0, 0]}
                  maxBarSize={30}
                />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </>
      )}
    </div>
  );
};
