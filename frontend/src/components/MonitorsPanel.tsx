import { Fragment, useState, type FormEvent } from 'react'
import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  Clock,
  Loader2,
  Pause,
  Play,
  Plus,
  Server,
  Trash2,
  XCircle,
} from 'lucide-react'
import { monitorApi } from '../api/client'
import {
  ApiClientError,
  type MonitorCheckResponse,
  type MonitorHttpMethod,
  type MonitorResponse,
  type MonitorStatus,
} from '../api/types'

interface MonitorsPanelProps {
  workspaceId: string
  monitors: MonitorResponse[]
  openIncidentCount: number
  isLoading: boolean
  checksByMonitor: Record<string, MonitorCheckResponse[]>
  onChecksLoaded: (monitorId: string, checks: MonitorCheckResponse[]) => void
  onMonitorCreated: (monitor: MonitorResponse) => void
  onMonitorUpdated: (monitor: MonitorResponse) => void
  onMonitorDeleted: (monitorId: string) => void
}

function formatTimeAgo(isoString: string | null): string {
  if (!isoString) {
    return 'Never'
  }
  const date = new Date(isoString)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }
  return date.toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

export function MonitorsPanel({
  workspaceId,
  monitors,
  openIncidentCount,
  isLoading,
  checksByMonitor,
  onChecksLoaded,
  onMonitorCreated,
  onMonitorUpdated,
  onMonitorDeleted,
}: MonitorsPanelProps) {
  const [isAddOpen, setIsAddOpen] = useState(false)
  const [expandedMonitorId, setExpandedMonitorId] = useState<string | null>(null)
  const [loadingChecksFor, setLoadingChecksFor] = useState<string | null>(null)
  const [busyMonitorId, setBusyMonitorId] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  const [name, setName] = useState('')
  const [url, setUrl] = useState('https://')
  const [httpMethod, setHttpMethod] = useState<MonitorHttpMethod>('GET')
  const [intervalSeconds, setIntervalSeconds] = useState('60')
  const [timeoutMs, setTimeoutMs] = useState('5000')
  const [expectedStatus, setExpectedStatus] = useState('200')
  const [failureThreshold, setFailureThreshold] = useState('3')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [formError, setFormError] = useState<ApiClientError | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  const upCount = monitors.filter((m) => !m.paused && m.currentStatus === 'UP').length
  const downCount = monitors.filter((m) => !m.paused && m.currentStatus === 'DOWN').length

  function validateForm(): boolean {
    const errors: Record<string, string> = {}
    const trimmedName = name.trim()
    const trimmedUrl = url.trim()

    if (!trimmedName) {
      errors.name = 'Monitor name is required'
    } else if (trimmedName.length < 2 || trimmedName.length > 120) {
      errors.name = 'Monitor name must be between 2 and 120 characters'
    }

    if (!trimmedUrl) {
      errors.url = 'Target URL is required'
    } else if (!/^https?:\/\/.+/i.test(trimmedUrl)) {
      errors.url = 'Target URL must start with http:// or https://'
    }

    const intervalNum = Number(intervalSeconds)
    if (!Number.isInteger(intervalNum) || intervalNum < 10 || intervalNum > 86400) {
      errors.intervalSeconds = 'Interval must be between 10 and 86400 seconds'
    }

    const timeoutNum = Number(timeoutMs)
    if (!Number.isInteger(timeoutNum) || timeoutNum < 500 || timeoutNum > 30000) {
      errors.timeoutMs = 'Timeout must be between 500 and 30000 ms'
    }

    const statusNum = Number(expectedStatus)
    if (!Number.isInteger(statusNum) || statusNum < 100 || statusNum > 599) {
      errors.expectedStatus = 'Expected status must be between 100 and 599'
    }

    const thresholdNum = Number(failureThreshold)
    if (!Number.isInteger(thresholdNum) || thresholdNum < 1 || thresholdNum > 20) {
      errors.failureThreshold = 'Failure threshold must be between 1 and 20'
    }

    setFieldErrors(errors)
    return Object.keys(errors).length === 0
  }

  async function handleCreateMonitor(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    setFormError(null)
    if (!validateForm()) {
      return
    }

    setIsSubmitting(true)
    try {
      const created = await monitorApi.create(workspaceId, {
        name: name.trim(),
        url: url.trim(),
        httpMethod,
        intervalSeconds: Number(intervalSeconds),
        timeoutMs: Number(timeoutMs),
        expectedStatus: Number(expectedStatus),
        failureThreshold: Number(failureThreshold),
      })
      onMonitorCreated(created)
      setName('')
      setUrl('https://')
      setHttpMethod('GET')
      setIntervalSeconds('60')
      setTimeoutMs('5000')
      setExpectedStatus('200')
      setFailureThreshold('3')
      setFieldErrors({})
      setIsAddOpen(false)
    } catch (error) {
      if (error instanceof ApiClientError) {
        setFormError(error)
        if (Object.keys(error.fieldErrors).length > 0) {
          setFieldErrors(error.fieldErrors)
        }
      } else {
        setFormError(
          new ApiClientError({
            status: 0,
            error: 'Network Error',
            message: 'Unable to create monitor. Please try again.',
          }),
        )
      }
    } finally {
      setIsSubmitting(false)
    }
  }

  async function handleTogglePause(monitor: MonitorResponse): Promise<void> {
    setActionError(null)
    setBusyMonitorId(monitor.id)
    try {
      const updated = await monitorApi.setPaused(
        workspaceId,
        monitor.id,
        !monitor.paused,
      )
      onMonitorUpdated(updated)
    } catch (error) {
      setActionError(
        error instanceof ApiClientError
          ? error.message
          : 'Failed to update monitor pause state.',
      )
    } finally {
      setBusyMonitorId(null)
    }
  }

  async function handleDelete(monitorId: string): Promise<void> {
    setActionError(null)
    setBusyMonitorId(monitorId)
    try {
      await monitorApi.delete(workspaceId, monitorId)
      if (expandedMonitorId === monitorId) {
        setExpandedMonitorId(null)
      }
      onMonitorDeleted(monitorId)
    } catch (error) {
      setActionError(
        error instanceof ApiClientError
          ? error.message
          : 'Failed to delete monitor.',
      )
    } finally {
      setBusyMonitorId(null)
    }
  }

  async function handleToggleChecks(monitorId: string): Promise<void> {
    if (expandedMonitorId === monitorId) {
      setExpandedMonitorId(null)
      return
    }

    setExpandedMonitorId(monitorId)
    setLoadingChecksFor(monitorId)
    setActionError(null)
    try {
      const checks = await monitorApi.listChecks(workspaceId, monitorId)
      onChecksLoaded(monitorId, checks)
    } catch (error) {
      setActionError(
        error instanceof ApiClientError
          ? error.message
          : 'Failed to load recent checks.',
      )
    } finally {
      setLoadingChecksFor(null)
    }
  }

  return (
    <div className="space-y-6">
      {/* KPI Summary Cards */}
      <section
        aria-label="Monitor KPI summary"
        className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4"
      >
        <KpiCard
          label="Total Monitors"
          value={String(monitors.length)}
          icon={<Server className="h-4 w-4 text-sky-400" />}
        />
        <KpiCard
          label="UP"
          value={String(upCount)}
          accent="text-emerald-400"
          icon={<CheckCircle2 className="h-4 w-4 text-emerald-400" />}
        />
        <KpiCard
          label="DOWN"
          value={String(downCount)}
          accent={downCount > 0 ? 'text-rose-400' : 'text-slate-100'}
          icon={<XCircle className="h-4 w-4 text-rose-400" />}
        />
        <KpiCard
          label="Open Incidents"
          value={String(openIncidentCount)}
          accent={openIncidentCount > 0 ? 'text-amber-400' : 'text-slate-100'}
          icon={<AlertTriangle className="h-4 w-4 text-amber-400" />}
        />
      </section>

      {/* Monitors Container */}
      <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-6">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 className="text-base font-semibold text-slate-100">
              Service Monitors
            </h2>
            <p className="mt-1 text-sm text-slate-400">
              Configure HTTP/HTTPS probes, inspect recent check latency, and pause or resume checks.
            </p>
          </div>
          <button
            type="button"
            onClick={() => {
              setFormError(null)
              setFieldErrors({})
              setIsAddOpen((prev) => !prev)
            }}
            className="inline-flex items-center gap-1.5 self-start rounded-lg bg-emerald-500 px-3.5 py-2 text-xs font-semibold text-slate-950 transition hover:bg-emerald-400 sm:self-auto"
          >
            <Plus className="h-4 w-4" />
            <span>{isAddOpen ? 'Close Form' : 'Add Monitor'}</span>
          </button>
        </div>

        {actionError && (
          <div
            role="alert"
            className="mt-4 rounded-lg border border-rose-500/30 bg-rose-500/10 px-4 py-2.5 text-xs text-rose-200"
          >
            {actionError}
          </div>
        )}

        {/* Add Monitor Form */}
        {isAddOpen && (
          <form
            onSubmit={(e) => void handleCreateMonitor(e)}
            noValidate
            className="mt-5 rounded-xl border border-slate-800 bg-slate-950/70 p-5"
          >
            <h3 className="text-sm font-semibold text-slate-100">
              New HTTP Monitor
            </h3>

            {formError && (
              <div
                role="alert"
                className="mt-3 rounded-lg border border-rose-500/30 bg-rose-500/10 p-3 text-xs text-rose-200"
              >
                <p className="font-medium text-rose-100">
                  {formError.error}
                  {formError.status > 0 ? ` (${formError.status})` : ''}
                </p>
                <p className="mt-0.5">{formError.message}</p>
              </div>
            )}

            <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              <div>
                <label
                  htmlFor="monitor-name"
                  className="block text-xs font-medium uppercase tracking-wider text-slate-400"
                >
                  Name
                </label>
                <input
                  id="monitor-name"
                  type="text"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="Auth API Health"
                  className="mt-1.5 w-full rounded-lg border border-slate-800 bg-slate-900 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-600 focus:border-emerald-500 focus:outline-none"
                />
                {fieldErrors.name && (
                  <p className="mt-1 text-xs text-rose-400">{fieldErrors.name}</p>
                )}
              </div>

              <div className="sm:col-span-2">
                <label
                  htmlFor="monitor-url"
                  className="block text-xs font-medium uppercase tracking-wider text-slate-400"
                >
                  Target URL
                </label>
                <input
                  id="monitor-url"
                  type="url"
                  value={url}
                  onChange={(e) => setUrl(e.target.value)}
                  placeholder="https://api.example.com/health"
                  className="mt-1.5 w-full rounded-lg border border-slate-800 bg-slate-900 px-3 py-2 font-mono text-sm text-slate-100 placeholder:text-slate-600 focus:border-emerald-500 focus:outline-none"
                />
                {fieldErrors.url && (
                  <p className="mt-1 text-xs text-rose-400">{fieldErrors.url}</p>
                )}
              </div>

              <div>
                <label
                  htmlFor="monitor-method"
                  className="block text-xs font-medium uppercase tracking-wider text-slate-400"
                >
                  HTTP Method
                </label>
                <select
                  id="monitor-method"
                  value={httpMethod}
                  onChange={(e) => setHttpMethod(e.target.value as MonitorHttpMethod)}
                  className="mt-1.5 w-full rounded-lg border border-slate-800 bg-slate-900 px-3 py-2 text-sm text-slate-100 focus:border-emerald-500 focus:outline-none"
                >
                  <option value="GET">GET</option>
                  <option value="HEAD">HEAD</option>
                  <option value="POST">POST</option>
                </select>
              </div>

              <div>
                <label
                  htmlFor="monitor-interval"
                  className="block text-xs font-medium uppercase tracking-wider text-slate-400"
                >
                  Interval (sec)
                </label>
                <input
                  id="monitor-interval"
                  type="number"
                  min={10}
                  max={86400}
                  value={intervalSeconds}
                  onChange={(e) => setIntervalSeconds(e.target.value)}
                  className="mt-1.5 w-full rounded-lg border border-slate-800 bg-slate-900 px-3 py-2 text-sm text-slate-100 focus:border-emerald-500 focus:outline-none"
                />
                {fieldErrors.intervalSeconds && (
                  <p className="mt-1 text-xs text-rose-400">
                    {fieldErrors.intervalSeconds}
                  </p>
                )}
              </div>

              <div>
                <label
                  htmlFor="monitor-timeout"
                  className="block text-xs font-medium uppercase tracking-wider text-slate-400"
                >
                  Timeout (ms)
                </label>
                <input
                  id="monitor-timeout"
                  type="number"
                  min={500}
                  max={30000}
                  value={timeoutMs}
                  onChange={(e) => setTimeoutMs(e.target.value)}
                  className="mt-1.5 w-full rounded-lg border border-slate-800 bg-slate-900 px-3 py-2 text-sm text-slate-100 focus:border-emerald-500 focus:outline-none"
                />
                {fieldErrors.timeoutMs && (
                  <p className="mt-1 text-xs text-rose-400">{fieldErrors.timeoutMs}</p>
                )}
              </div>

              <div>
                <label
                  htmlFor="monitor-status"
                  className="block text-xs font-medium uppercase tracking-wider text-slate-400"
                >
                  Expected Status
                </label>
                <input
                  id="monitor-status"
                  type="number"
                  min={100}
                  max={599}
                  value={expectedStatus}
                  onChange={(e) => setExpectedStatus(e.target.value)}
                  className="mt-1.5 w-full rounded-lg border border-slate-800 bg-slate-900 px-3 py-2 text-sm text-slate-100 focus:border-emerald-500 focus:outline-none"
                />
                {fieldErrors.expectedStatus && (
                  <p className="mt-1 text-xs text-rose-400">
                    {fieldErrors.expectedStatus}
                  </p>
                )}
              </div>

              <div>
                <label
                  htmlFor="monitor-threshold"
                  className="block text-xs font-medium uppercase tracking-wider text-slate-400"
                >
                  Failure Threshold
                </label>
                <input
                  id="monitor-threshold"
                  type="number"
                  min={1}
                  max={20}
                  value={failureThreshold}
                  onChange={(e) => setFailureThreshold(e.target.value)}
                  className="mt-1.5 w-full rounded-lg border border-slate-800 bg-slate-900 px-3 py-2 text-sm text-slate-100 focus:border-emerald-500 focus:outline-none"
                />
                {fieldErrors.failureThreshold && (
                  <p className="mt-1 text-xs text-rose-400">
                    {fieldErrors.failureThreshold}
                  </p>
                )}
              </div>
            </div>

            <div className="mt-5 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setIsAddOpen(false)}
                className="rounded-lg border border-slate-800 bg-slate-900 px-3.5 py-2 text-xs font-medium text-slate-300 transition hover:bg-slate-800"
              >
                Cancel
              </button>
              <button
                type="submit"
                disabled={isSubmitting}
                className="inline-flex items-center gap-1.5 rounded-lg bg-emerald-500 px-4 py-2 text-xs font-semibold text-slate-950 transition hover:bg-emerald-400 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {isSubmitting && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                <span>Save Monitor</span>
              </button>
            </div>
          </form>
        )}

        {/* Monitor Table */}
        {isLoading ? (
          <div className="mt-8 flex items-center justify-center py-12 text-sm text-slate-400">
            <Loader2 className="mr-2 h-4 w-4 animate-spin text-emerald-400" />
            Loading monitors…
          </div>
        ) : monitors.length === 0 ? (
          <div className="mt-6 flex flex-col items-center justify-center rounded-lg border border-dashed border-slate-800 bg-slate-950/50 px-6 py-12 text-center">
            <Activity className="h-8 w-8 text-slate-600" />
            <p className="mt-3 text-sm font-medium text-slate-300">
              No monitors configured yet
            </p>
            <p className="mt-1 max-w-md text-xs text-slate-500">
              Click &ldquo;Add Monitor&rdquo; above to start probing an HTTP or HTTPS endpoint.
            </p>
          </div>
        ) : (
          <div className="mt-6 overflow-x-auto">
            <table className="w-full border-collapse text-left text-sm">
              <thead>
                <tr className="border-b border-slate-800 text-xs font-medium uppercase tracking-wider text-slate-400">
                  <th className="py-3 pr-4">Status</th>
                  <th className="py-3 pr-4">Monitor</th>
                  <th className="py-3 pr-4">Interval</th>
                  <th className="py-3 pr-4">Latency</th>
                  <th className="py-3 pr-4">Last Checked</th>
                  <th className="py-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/70">
                {monitors.map((monitor) => {
                  const isExpanded = expandedMonitorId === monitor.id
                  const isBusy = busyMonitorId === monitor.id
                  const checks = checksByMonitor[monitor.id] ?? []

                  return (
                    <Fragment key={monitor.id}>
                      <tr className="hover:bg-slate-900/50">
                        <td className="py-3.5 pr-4 align-middle">
                          <MonitorStatusBadge
                            status={monitor.currentStatus}
                            paused={monitor.paused}
                          />
                        </td>
                        <td className="py-3.5 pr-4 align-middle">
                          <p className="font-medium text-slate-100">{monitor.name}</p>
                          <p className="mt-0.5 font-mono text-xs text-slate-400">
                            <span className="mr-1.5 rounded bg-slate-800 px-1.5 py-0.5 text-[10px] text-slate-300">
                              {monitor.httpMethod}
                            </span>
                            {monitor.url}
                          </p>
                        </td>
                        <td className="py-3.5 pr-4 align-middle text-xs text-slate-300">
                          {monitor.intervalSeconds}s
                        </td>
                        <td className="py-3.5 pr-4 align-middle font-mono text-xs text-slate-200">
                          {monitor.lastLatencyMs !== null
                            ? `${monitor.lastLatencyMs} ms`
                            : '—'}
                        </td>
                        <td className="py-3.5 pr-4 align-middle font-mono text-xs text-slate-400">
                          {formatTimeAgo(monitor.lastCheckedAt)}
                        </td>
                        <td className="py-3.5 text-right align-middle">
                          <div className="inline-flex items-center gap-1.5">
                            <button
                              type="button"
                              onClick={() => void handleToggleChecks(monitor.id)}
                              className="inline-flex items-center gap-1 rounded-md border border-slate-800 bg-slate-950 px-2.5 py-1 text-xs font-medium text-slate-300 transition hover:border-slate-700 hover:bg-slate-800"
                            >
                              <Clock className="h-3.5 w-3.5 text-sky-400" />
                              <span>Checks</span>
                              {isExpanded ? (
                                <ChevronUp className="h-3.5 w-3.5" />
                              ) : (
                                <ChevronDown className="h-3.5 w-3.5" />
                              )}
                            </button>

                            <button
                              type="button"
                              disabled={isBusy}
                              onClick={() => void handleTogglePause(monitor)}
                              title={monitor.paused ? 'Resume monitor' : 'Pause monitor'}
                              className="inline-flex items-center gap-1 rounded-md border border-slate-800 bg-slate-950 px-2.5 py-1 text-xs font-medium text-slate-300 transition hover:border-slate-700 hover:bg-slate-800 disabled:opacity-50"
                            >
                              {monitor.paused ? (
                                <>
                                  <Play className="h-3.5 w-3.5 text-emerald-400" />
                                  <span>Resume</span>
                                </>
                              ) : (
                                <>
                                  <Pause className="h-3.5 w-3.5 text-amber-400" />
                                  <span>Pause</span>
                                </>
                              )}
                            </button>

                            <button
                              type="button"
                              disabled={isBusy}
                              onClick={() => void handleDelete(monitor.id)}
                              aria-label={`Delete monitor ${monitor.name}`}
                              className="inline-flex items-center rounded-md border border-slate-800 bg-slate-950 p-1.5 text-slate-400 transition hover:border-rose-500/40 hover:bg-rose-500/10 hover:text-rose-300 disabled:opacity-50"
                            >
                              <Trash2 className="h-3.5 w-3.5" />
                            </button>
                          </div>
                        </td>
                      </tr>

                      {isExpanded && (
                        <tr className="bg-slate-950/60">
                          <td colSpan={6} className="px-4 py-4">
                            <div className="rounded-lg border border-slate-800 bg-slate-900/70 p-4">
                              <div className="flex items-center justify-between">
                                <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-300">
                                  Recent Checks — {monitor.name}
                                </h4>
                                <span className="text-[11px] text-slate-500">
                                  Expected HTTP {monitor.expectedStatus} · Threshold{' '}
                                  {monitor.failureThreshold}
                                </span>
                              </div>

                              {loadingChecksFor === monitor.id ? (
                                <div className="mt-3 flex items-center text-xs text-slate-400">
                                  <Loader2 className="mr-2 h-3.5 w-3.5 animate-spin" />
                                  Loading recent checks…
                                </div>
                              ) : checks.length === 0 ? (
                                <p className="mt-3 text-xs text-slate-500">
                                  No check executions recorded yet.
                                </p>
                              ) : (
                                <div className="mt-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
                                  {checks.slice(0, 9).map((check) => (
                                    <div
                                      key={check.id}
                                      className="flex items-center justify-between rounded-md border border-slate-800 bg-slate-950 px-3 py-2 text-xs"
                                    >
                                      <div className="flex items-center gap-2">
                                        <span
                                          className={`h-2 w-2 rounded-full ${
                                            check.status === 'UP'
                                              ? 'bg-emerald-400'
                                              : check.status === 'DOWN'
                                                ? 'bg-rose-400'
                                                : 'bg-slate-400'
                                          }`}
                                        />
                                        <span className="font-medium text-slate-200">
                                          {check.status}
                                        </span>
                                        {check.statusCode !== null && (
                                          <span className="font-mono text-slate-400">
                                            HTTP {check.statusCode}
                                          </span>
                                        )}
                                      </div>
                                      <div className="text-right font-mono text-[11px] text-slate-400">
                                        <span>
                                          {check.latencyMs !== null
                                            ? `${check.latencyMs} ms`
                                            : '—'}
                                        </span>
                                        <span className="ml-2 text-slate-500">
                                          {formatTimeAgo(check.checkedAt)}
                                        </span>
                                      </div>
                                    </div>
                                  ))}
                                </div>
                              )}
                            </div>
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  )
}

function MonitorStatusBadge({
  status,
  paused,
}: {
  status: MonitorStatus
  paused: boolean
}) {
  if (paused) {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-full border border-amber-500/30 bg-amber-500/10 px-2.5 py-0.5 text-xs font-medium text-amber-300">
        <Pause className="h-3 w-3" />
        PAUSED
      </span>
    )
  }

  if (status === 'UP') {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-full border border-emerald-500/30 bg-emerald-500/10 px-2.5 py-0.5 text-xs font-medium text-emerald-300">
        <span className="h-1.5 w-1.5 rounded-full bg-emerald-400" />
        UP
      </span>
    )
  }

  if (status === 'DOWN') {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-full border border-rose-500/30 bg-rose-500/10 px-2.5 py-0.5 text-xs font-medium text-rose-300">
        <span className="h-1.5 w-1.5 rounded-full bg-rose-400" />
        DOWN
      </span>
    )
  }

  return (
    <span className="inline-flex items-center gap-1.5 rounded-full border border-slate-700 bg-slate-800 px-2.5 py-0.5 text-xs font-medium text-slate-300">
      <span className="h-1.5 w-1.5 rounded-full bg-slate-400" />
      UNKNOWN
    </span>
  )
}

function KpiCard({
  label,
  value,
  icon,
  accent = 'text-slate-100',
}: {
  label: string
  value: string
  icon: React.ReactNode
  accent?: string
}) {
  return (
    <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-5">
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium uppercase tracking-wider text-slate-400">
          {label}
        </span>
        <div className="rounded-md bg-slate-800/80 p-1.5">{icon}</div>
      </div>
      <p className={`mt-3 text-2xl font-semibold tracking-tight ${accent}`}>
        {value}
      </p>
    </div>
  )
}
