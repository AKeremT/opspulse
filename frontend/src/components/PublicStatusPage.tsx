import { useCallback, useEffect, useState } from 'react'
import {
  Activity,
  AlertCircle,
  AlertOctagon,
  AlertTriangle,
  CheckCircle2,
  Clock,
  Loader2,
  RefreshCw,
  Server,
  ShieldAlert,
} from 'lucide-react'
import { statusPageApi } from '../api/client'
import {
  ApiClientError,
  type DailyUptimeBucket,
  type IncidentStatus,
  type MonitorStatus,
  type PublicIncidentSummary,
  type PublicMonitorStatus,
  type PublicStatusResponse,
  type StatusPageOverallStatus,
} from '../api/types'

interface PublicStatusPageProps {
  slug: string
}

function formatDateTime(iso: string | null): string {
  if (!iso) {
    return '—'
  }
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return iso
  }
  return date.toLocaleString([], {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

function formatUptime(value: number | null): string {
  if (value === null || Number.isNaN(value)) {
    return '—'
  }
  return `${value.toFixed(2)}%`
}

function getBucketBarColor(bucket: DailyUptimeBucket): string {
  if (bucket.uptimePercent === null || bucket.totalChecks === 0) {
    return 'bg-slate-800 hover:bg-slate-700'
  }
  if (bucket.uptimePercent >= 99) {
    return 'bg-emerald-500 hover:bg-emerald-400'
  }
  if (bucket.uptimePercent >= 90) {
    return 'bg-amber-400 hover:bg-amber-300'
  }
  return 'bg-rose-500 hover:bg-rose-400'
}

function OverallStatusBanner({ status }: { status: StatusPageOverallStatus }) {
  if (status === 'OPERATIONAL') {
    return (
      <div
        role="status"
        className="flex items-center gap-3 rounded-xl border border-emerald-500/30 bg-emerald-500/10 px-5 py-4 text-emerald-200"
      >
        <CheckCircle2 className="h-6 w-6 shrink-0 text-emerald-400" />
        <div>
          <p className="text-base font-semibold text-emerald-100">
            All Systems Operational
          </p>
          <p className="text-xs text-emerald-300/80">
            All monitored services are responding normally.
          </p>
        </div>
      </div>
    )
  }

  if (status === 'DEGRADED') {
    return (
      <div
        role="status"
        className="flex items-center gap-3 rounded-xl border border-amber-500/30 bg-amber-500/10 px-5 py-4 text-amber-200"
      >
        <AlertTriangle className="h-6 w-6 shrink-0 text-amber-400" />
        <div>
          <p className="text-base font-semibold text-amber-100">
            Partial System Degradation
          </p>
          <p className="text-xs text-amber-300/80">
            One or more monitored services are currently experiencing issues.
          </p>
        </div>
      </div>
    )
  }

  return (
    <div
      role="status"
      className="flex items-center gap-3 rounded-xl border border-rose-500/30 bg-rose-500/10 px-5 py-4 text-rose-200"
    >
      <AlertOctagon className="h-6 w-6 shrink-0 text-rose-400" />
      <div>
        <p className="text-base font-semibold text-rose-100">Major Outage</p>
        <p className="text-xs text-rose-300/80">
          Multiple or all monitored services are currently unavailable.
        </p>
      </div>
    </div>
  )
}

function MonitorStatusBadge({ status }: { status: MonitorStatus }) {
  if (status === 'UP') {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-full border border-emerald-500/30 bg-emerald-500/10 px-2.5 py-0.5 text-xs font-medium text-emerald-300">
        <span className="h-1.5 w-1.5 rounded-full bg-emerald-400" />
        Operational
      </span>
    )
  }
  if (status === 'DOWN') {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-full border border-rose-500/30 bg-rose-500/10 px-2.5 py-0.5 text-xs font-medium text-rose-300">
        <span className="h-1.5 w-1.5 rounded-full bg-rose-400" />
        Down
      </span>
    )
  }
  return (
    <span className="inline-flex items-center gap-1.5 rounded-full border border-slate-700 bg-slate-800 px-2.5 py-0.5 text-xs font-medium text-slate-300">
      <span className="h-1.5 w-1.5 rounded-full bg-slate-400" />
      Unknown
    </span>
  )
}

function IncidentStatusBadge({ status }: { status: IncidentStatus }) {
  if (status === 'RESOLVED') {
    return (
      <span className="inline-flex items-center rounded-full border border-emerald-500/30 bg-emerald-500/10 px-2 py-0.5 text-[11px] font-medium text-emerald-300">
        Resolved
      </span>
    )
  }
  if (status === 'ACKNOWLEDGED') {
    return (
      <span className="inline-flex items-center rounded-full border border-amber-500/30 bg-amber-500/10 px-2 py-0.5 text-[11px] font-medium text-amber-300">
        Acknowledged
      </span>
    )
  }
  return (
    <span className="inline-flex items-center rounded-full border border-rose-500/30 bg-rose-500/10 px-2 py-0.5 text-[11px] font-medium text-rose-300">
      Open
    </span>
  )
}

function DailyUptimeChart({ buckets }: { buckets: DailyUptimeBucket[] }) {
  const [hoveredIndex, setHoveredIndex] = useState<number | null>(null)
  const hoveredBucket = hoveredIndex !== null ? buckets[hoveredIndex] ?? null : null

  return (
    <div className="mt-4">
      <div className="relative flex h-8 items-end gap-1" role="list" aria-label="30-day daily uptime">
        {buckets.map((bucket, index) => {
          const label =
            bucket.uptimePercent !== null && bucket.totalChecks > 0
              ? `${bucket.date}: ${bucket.uptimePercent.toFixed(2)}% uptime (${bucket.totalChecks} checks)`
              : `${bucket.date}: No check data`

          return (
            <button
              key={bucket.date}
              type="button"
              role="listitem"
              aria-label={label}
              onMouseEnter={() => setHoveredIndex(index)}
              onMouseLeave={() => setHoveredIndex(null)}
              onFocus={() => setHoveredIndex(index)}
              onBlur={() => setHoveredIndex(null)}
              className={`h-full flex-1 rounded-sm transition-colors focus:outline-none focus:ring-1 focus:ring-slate-300 ${getBucketBarColor(bucket)}`}
            />
          )
        })}
      </div>

      <div className="mt-1.5 flex items-center justify-between text-[11px] text-slate-500">
        <span>{buckets[0]?.date ?? '30 days ago'}</span>
        <span className="min-h-4 text-center font-mono text-slate-300">
          {hoveredBucket
            ? hoveredBucket.uptimePercent !== null && hoveredBucket.totalChecks > 0
              ? `${hoveredBucket.date} · ${hoveredBucket.uptimePercent.toFixed(2)}% (${hoveredBucket.totalChecks} checks)`
              : `${hoveredBucket.date} · No checks`
            : 'Hover a bar for daily details'}
        </span>
        <span>{buckets[buckets.length - 1]?.date ?? 'Today'}</span>
      </div>
    </div>
  )
}

function PublicMonitorCard({ monitor }: { monitor: PublicMonitorStatus }) {
  return (
    <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-5">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-slate-800 bg-slate-950">
            <Server className="h-4 w-4 text-slate-400" />
          </div>
          <div>
            <h3 className="text-sm font-semibold text-slate-100">{monitor.name}</h3>
            <p className="text-xs text-slate-500">
              Last checked: {formatDateTime(monitor.lastCheckedAt)}
            </p>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <span className="rounded-md border border-slate-800 bg-slate-950 px-2.5 py-1 font-mono text-xs text-slate-300">
            {monitor.lastLatencyMs !== null ? `${monitor.lastLatencyMs} ms` : '— ms'}
          </span>
          <MonitorStatusBadge status={monitor.currentStatus} />
        </div>
      </div>

      <div className="mt-4 grid grid-cols-3 gap-3 rounded-lg border border-slate-800/80 bg-slate-950/60 px-4 py-2.5 text-center">
        <div>
          <p className="text-[11px] uppercase tracking-wider text-slate-500">24h Uptime</p>
          <p className="mt-0.5 font-mono text-sm font-semibold text-slate-100">
            {formatUptime(monitor.uptime24h)}
          </p>
        </div>
        <div className="border-x border-slate-800/80">
          <p className="text-[11px] uppercase tracking-wider text-slate-500">7d Uptime</p>
          <p className="mt-0.5 font-mono text-sm font-semibold text-slate-100">
            {formatUptime(monitor.uptime7d)}
          </p>
        </div>
        <div>
          <p className="text-[11px] uppercase tracking-wider text-slate-500">30d Uptime</p>
          <p className="mt-0.5 font-mono text-sm font-semibold text-slate-100">
            {formatUptime(monitor.uptime30d)}
          </p>
        </div>
      </div>

      {monitor.dailyUptime.length > 0 && (
        <DailyUptimeChart buckets={monitor.dailyUptime} />
      )}
    </div>
  )
}

function PublicIncidentRow({ incident }: { incident: PublicIncidentSummary }) {
  return (
    <div className="rounded-lg border border-slate-800/80 bg-slate-950/60 p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium text-slate-100">
            {incident.monitorName}
          </span>
          <IncidentStatusBadge status={incident.status} />
        </div>
        <div className="flex items-center gap-1.5 text-xs text-slate-500">
          <Clock className="h-3.5 w-3.5" />
          <span>Started {formatDateTime(incident.startedAt)}</span>
        </div>
      </div>
      {incident.summary && (
        <p className="mt-2 text-xs text-slate-300">{incident.summary}</p>
      )}
      {incident.resolvedAt && (
        <p className="mt-1.5 text-[11px] text-emerald-400/90">
          Resolved {formatDateTime(incident.resolvedAt)}
        </p>
      )}
    </div>
  )
}

export function PublicStatusPage({ slug }: PublicStatusPageProps) {
  const [data, setData] = useState<PublicStatusResponse | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [isRefreshing, setIsRefreshing] = useState(false)
  const [errorStatus, setErrorStatus] = useState<number | null>(null)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  const fetchStatus = useCallback(
    async (isManualRefresh = false) => {
      if (isManualRefresh) {
        setIsRefreshing(true)
      } else {
        setIsLoading(true)
      }
      setErrorStatus(null)
      setErrorMessage(null)

      try {
        const response = await statusPageApi.getPublicStatus(slug)
        setData(response)
      } catch (error) {
        if (error instanceof ApiClientError) {
          setErrorStatus(error.status)
          setErrorMessage(error.message)
        } else {
          setErrorStatus(0)
          setErrorMessage('Unable to load status page. Please try again.')
        }
      } finally {
        setIsLoading(false)
        setIsRefreshing(false)
      }
    },
    [slug],
  )

  useEffect(() => {
    let cancelled = false

    const loadInitial = async () => {
      try {
        const response = await statusPageApi.getPublicStatus(slug)
        if (!cancelled) {
          setData(response)
          setErrorStatus(null)
          setErrorMessage(null)
        }
      } catch (error) {
        if (!cancelled) {
          if (error instanceof ApiClientError) {
            setErrorStatus(error.status)
            setErrorMessage(error.message)
          } else {
            setErrorStatus(0)
            setErrorMessage('Unable to load status page. Please try again.')
          }
        }
      } finally {
        if (!cancelled) {
          setIsLoading(false)
        }
      }
    }

    void loadInitial()
    return () => {
      cancelled = true
    }
  }, [slug])

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col">
      <header className="border-b border-slate-800/80 bg-slate-950/90 backdrop-blur">
        <div className="mx-auto flex h-16 max-w-4xl items-center justify-between px-4 sm:px-6">
          <div className="flex items-center gap-2.5">
            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-emerald-500/10 ring-1 ring-emerald-500/30">
              <Activity className="h-4 w-4 text-emerald-400" />
            </div>
            <span className="text-sm font-semibold tracking-tight text-slate-100">
              OpsPulse Status
            </span>
          </div>

          <button
            type="button"
            onClick={() => void fetchStatus(true)}
            disabled={isLoading || isRefreshing}
            className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-slate-800 bg-slate-900 px-3 text-xs font-medium text-slate-300 transition hover:border-slate-700 hover:bg-slate-800 hover:text-slate-100 disabled:cursor-not-allowed disabled:opacity-60"
          >
            <RefreshCw
              className={`h-3.5 w-3.5 ${isRefreshing ? 'animate-spin' : ''}`}
            />
            <span>Refresh</span>
          </button>
        </div>
      </header>

      <main className="mx-auto w-full max-w-4xl flex-1 px-4 py-8 sm:px-6">
        {isLoading ? (
          <div className="flex flex-col items-center justify-center rounded-xl border border-slate-800 bg-slate-900/40 py-20 text-slate-400">
            <Loader2 className="h-7 w-7 animate-spin text-emerald-400" />
            <p className="mt-3 text-sm">Loading service status…</p>
          </div>
        ) : errorStatus === 404 ? (
          <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-10 text-center">
            <AlertCircle className="mx-auto h-10 w-10 text-slate-500" />
            <h1 className="mt-4 text-lg font-semibold text-slate-100">
              Status Page Not Found
            </h1>
            <p className="mt-2 text-sm text-slate-400">
              The status page <span className="font-mono text-slate-300">/{slug}</span>{' '}
              does not exist or is currently set to private.
            </p>
            <a
              href="/"
              className="mt-6 inline-flex items-center rounded-lg border border-slate-700 bg-slate-800 px-4 py-2 text-xs font-medium text-slate-200 transition hover:bg-slate-700"
            >
              Go to OpsPulse Dashboard
            </a>
          </div>
        ) : errorStatus === 429 ? (
          <div
            role="alert"
            className="rounded-xl border border-amber-500/30 bg-amber-500/10 p-8 text-center"
          >
            <ShieldAlert className="mx-auto h-10 w-10 text-amber-400" />
            <h1 className="mt-4 text-lg font-semibold text-amber-100">
              Rate Limit Exceeded
            </h1>
            <p className="mt-2 text-sm text-amber-200/80">
              Too many requests from your IP address. Please wait a moment and try refreshing again.
            </p>
            <button
              type="button"
              onClick={() => void fetchStatus(true)}
              className="mt-5 inline-flex items-center gap-1.5 rounded-lg bg-amber-500 px-4 py-2 text-xs font-semibold text-slate-950 transition hover:bg-amber-400"
            >
              <RefreshCw className="h-3.5 w-3.5" />
              <span>Try Again</span>
            </button>
          </div>
        ) : errorMessage ? (
          <div
            role="alert"
            className="rounded-xl border border-rose-500/30 bg-rose-500/10 p-8 text-center"
          >
            <AlertCircle className="mx-auto h-10 w-10 text-rose-400" />
            <h1 className="mt-4 text-lg font-semibold text-rose-100">
              Unable to Load Status
            </h1>
            <p className="mt-2 text-sm text-rose-200/80">{errorMessage}</p>
            <button
              type="button"
              onClick={() => void fetchStatus(true)}
              className="mt-5 inline-flex items-center gap-1.5 rounded-lg bg-rose-500 px-4 py-2 text-xs font-semibold text-slate-950 transition hover:bg-rose-400"
            >
              <RefreshCw className="h-3.5 w-3.5" />
              <span>Retry</span>
            </button>
          </div>
        ) : data ? (
          <div className="space-y-8">
            <div>
              <div className="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
                <div>
                  <h1 className="text-2xl font-bold tracking-tight text-slate-100">
                    {data.title}
                  </h1>
                  {data.description && (
                    <p className="mt-1 text-sm text-slate-400">{data.description}</p>
                  )}
                </div>
                <p className="text-xs text-slate-500">
                  Updated {formatDateTime(data.generatedAt)}
                </p>
              </div>

              <div className="mt-5">
                <OverallStatusBanner status={data.overallStatus} />
              </div>
            </div>

            <section aria-labelledby="monitors-heading">
              <div className="mb-3 flex items-center justify-between">
                <h2
                  id="monitors-heading"
                  className="text-sm font-semibold uppercase tracking-wider text-slate-400"
                >
                  Services ({data.monitors.length})
                </h2>
              </div>

              {data.monitors.length === 0 ? (
                <div className="rounded-xl border border-slate-800 bg-slate-900/40 p-8 text-center text-sm text-slate-400">
                  No active monitors are currently published on this status page.
                </div>
              ) : (
                <div className="space-y-4">
                  {data.monitors.map((monitor) => (
                    <PublicMonitorCard key={monitor.id} monitor={monitor} />
                  ))}
                </div>
              )}
            </section>

            <section aria-labelledby="incidents-heading">
              <h2
                id="incidents-heading"
                className="mb-3 text-sm font-semibold uppercase tracking-wider text-slate-400"
              >
                Recent Incidents
              </h2>

              {data.recentIncidents.length === 0 ? (
                <div className="rounded-xl border border-slate-800 bg-slate-900/40 p-6 text-center text-sm text-slate-400">
                  No recent incidents reported.
                </div>
              ) : (
                <div className="space-y-3">
                  {data.recentIncidents.map((incident) => (
                    <PublicIncidentRow key={incident.id} incident={incident} />
                  ))}
                </div>
              )}
            </section>
          </div>
        ) : null}
      </main>
    </div>
  )
}
