import { useCallback, useEffect, useState } from 'react'
import {
  Activity,
  AlertTriangle,
  Bell,
  CheckCircle2,
  RefreshCw,
  Server,
  ShieldCheck,
  WifiOff,
} from 'lucide-react'

type BackendStatus = 'checking' | 'UP' | 'Offline'

interface ActuatorHealthResponse {
  status?: string
}

interface HealthState {
  status: BackendStatus
  lastChecked: string | null
}

const HEALTH_POLL_INTERVAL_MS = 30_000

function formatTimestamp(date: Date): string {
  return date.toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

async function fetchBackendHealth(signal?: AbortSignal): Promise<HealthState> {
  try {
    const response = await fetch('/actuator/health', {
      method: 'GET',
      headers: { Accept: 'application/json' },
      signal,
    })

    if (!response.ok) {
      return {
        status: 'Offline',
        lastChecked: formatTimestamp(new Date()),
      }
    }

    const data = (await response.json()) as ActuatorHealthResponse
    return {
      status: data.status === 'UP' ? 'UP' : 'Offline',
      lastChecked: formatTimestamp(new Date()),
    }
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error
    }
    return {
      status: 'Offline',
      lastChecked: formatTimestamp(new Date()),
    }
  }
}

export function App() {
  const [health, setHealth] = useState<HealthState>({
    status: 'checking',
    lastChecked: null,
  })
  const [isRefreshing, setIsRefreshing] = useState(false)

  const handleRefresh = useCallback(async () => {
    setIsRefreshing(true)
    try {
      const nextHealth = await fetchBackendHealth()
      setHealth(nextHealth)
    } finally {
      setIsRefreshing(false)
    }
  }, [])

  useEffect(() => {
    const controller = new AbortController()

    const syncHealth = async () => {
      try {
        const nextHealth = await fetchBackendHealth(controller.signal)
        setHealth(nextHealth)
      } catch (error) {
        if (error instanceof DOMException && error.name === 'AbortError') {
          return
        }
      }
    }

    void syncHealth()

    const intervalId = window.setInterval(() => {
      void syncHealth()
    }, HEALTH_POLL_INTERVAL_MS)

    return () => {
      controller.abort()
      window.clearInterval(intervalId)
    }
  }, [])

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col">
      {/* Top Navigation Bar */}
      <header className="sticky top-0 z-20 border-b border-slate-800/80 bg-slate-950/90 backdrop-blur">
        <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-4 sm:px-6 lg:px-8">
          <div className="flex items-center gap-3">
            <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-emerald-500/10 ring-1 ring-emerald-500/30">
              <Activity className="h-5 w-5 text-emerald-400" />
            </div>
            <div>
              <h1 className="text-sm font-semibold tracking-tight text-slate-100 sm:text-base">
                OpsPulse — Uptime &amp; Incident Platform
              </h1>
              <p className="hidden text-xs text-slate-400 sm:block">
                Real-time service monitoring, alerting &amp; incident response
              </p>
            </div>
          </div>

          <div className="flex items-center gap-3">
            <BackendHealthBadge status={health.status} />
            <button
              type="button"
              onClick={() => void handleRefresh()}
              disabled={isRefreshing}
              aria-label="Refresh backend health status"
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-800 bg-slate-900 px-3 py-1.5 text-xs font-medium text-slate-300 transition hover:border-slate-700 hover:bg-slate-800 hover:text-slate-100 disabled:cursor-not-allowed disabled:opacity-60"
            >
              <RefreshCw
                className={`h-3.5 w-3.5 ${isRefreshing ? 'animate-spin' : ''}`}
              />
              <span className="hidden sm:inline">Refresh</span>
            </button>
          </div>
        </div>
      </header>

      {/* Main Dashboard Shell */}
      <main className="mx-auto w-full max-w-7xl flex-1 px-4 py-8 sm:px-6 lg:px-8">
        {/* Status Summary Grid */}
        <section aria-label="Platform overview" className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <MetricCard
            label="Active Monitors"
            value="0"
            hint="Ready for Sprint 2 monitor setup"
            icon={<Server className="h-4 w-4 text-sky-400" />}
          />
          <MetricCard
            label="Overall Uptime (24h)"
            value="—"
            hint="Awaiting first health probe"
            icon={<ShieldCheck className="h-4 w-4 text-emerald-400" />}
          />
          <MetricCard
            label="Open Incidents"
            value="0"
            hint="All systems nominal"
            icon={<AlertTriangle className="h-4 w-4 text-amber-400" />}
          />
          <MetricCard
            label="Alert Channels"
            value="0"
            hint="Webhooks (Discord / Slack / HTTP)"
            icon={<Bell className="h-4 w-4 text-violet-400" />}
          />
        </section>

        {/* Backend Connectivity & Workspace Placeholder */}
        <section className="mt-8 grid gap-6 lg:grid-cols-3">
          <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-6 lg:col-span-2">
            <div className="flex items-center justify-between">
              <div>
                <h2 className="text-base font-semibold text-slate-100">
                  Service Monitors
                </h2>
                <p className="mt-1 text-sm text-slate-400">
                  Live HTTP/HTTPS endpoint checks and SSE status stream will appear here.
                </p>
              </div>
              <span className="rounded-md border border-slate-800 bg-slate-950 px-2.5 py-1 text-xs font-medium text-slate-400">
                Sprint 1 Shell
              </span>
            </div>

            <div className="mt-6 flex flex-col items-center justify-center rounded-lg border border-dashed border-slate-800 bg-slate-950/50 px-6 py-12 text-center">
              <Activity className="h-8 w-8 text-slate-600" />
              <p className="mt-3 text-sm font-medium text-slate-300">
                No monitors configured yet
              </p>
              <p className="mt-1 max-w-md text-xs text-slate-500">
                Once authentication and monitor APIs are connected, workspace monitors and real-time incident timelines will render in this panel.
              </p>
            </div>
          </div>

          <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-6">
            <h2 className="text-base font-semibold text-slate-100">
              Backend Connectivity
            </h2>
            <p className="mt-1 text-sm text-slate-400">
              Probing <code className="rounded bg-slate-800 px-1.5 py-0.5 text-xs text-slate-200">/actuator/health</code> via Vite proxy.
            </p>

            <dl className="mt-6 space-y-4 text-sm">
              <div className="flex items-center justify-between border-b border-slate-800/80 pb-3">
                <dt className="text-slate-400">API Status</dt>
                <dd className="font-medium text-slate-100">
                  {health.status === 'checking' ? 'Checking…' : health.status}
                </dd>
              </div>
              <div className="flex items-center justify-between border-b border-slate-800/80 pb-3">
                <dt className="text-slate-400">Proxy Target</dt>
                <dd className="font-mono text-xs text-slate-300">localhost:8080</dd>
              </div>
              <div className="flex items-center justify-between">
                <dt className="text-slate-400">Last Checked</dt>
                <dd className="font-mono text-xs text-slate-300">
                  {health.lastChecked ?? 'Pending'}
                </dd>
              </div>
            </dl>
          </div>
        </section>
      </main>
    </div>
  )
}

function BackendHealthBadge({ status }: { status: BackendStatus }) {
  if (status === 'UP') {
    return (
      <span
        role="status"
        className="inline-flex items-center gap-2 rounded-full border border-emerald-500/30 bg-emerald-500/10 px-3 py-1 text-xs font-medium text-emerald-300"
      >
        <span className="relative flex h-2 w-2">
          <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-75" />
          <span className="relative inline-flex h-2 w-2 rounded-full bg-emerald-400" />
        </span>
        <CheckCircle2 className="h-3.5 w-3.5" />
        <span>Backend: UP</span>
      </span>
    )
  }

  if (status === 'checking') {
    return (
      <span
        role="status"
        className="inline-flex items-center gap-2 rounded-full border border-slate-700 bg-slate-800/80 px-3 py-1 text-xs font-medium text-slate-300"
      >
        <span className="h-2 w-2 animate-pulse rounded-full bg-amber-400" />
        <span>Backend: Checking…</span>
      </span>
    )
  }

  return (
    <span
      role="status"
      className="inline-flex items-center gap-2 rounded-full border border-rose-500/30 bg-rose-500/10 px-3 py-1 text-xs font-medium text-rose-300"
    >
      <WifiOff className="h-3.5 w-3.5" />
      <span>Backend: Offline</span>
    </span>
  )
}

interface MetricCardProps {
  label: string
  value: string
  hint: string
  icon: React.ReactNode
}

function MetricCard({ label, value, hint, icon }: MetricCardProps) {
  return (
    <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-5">
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium uppercase tracking-wider text-slate-400">
          {label}
        </span>
        <div className="rounded-md bg-slate-800/80 p-1.5">{icon}</div>
      </div>
      <p className="mt-3 text-2xl font-semibold tracking-tight text-slate-100">
        {value}
      </p>
      <p className="mt-1 text-xs text-slate-500">{hint}</p>
    </div>
  )
}

export default App
