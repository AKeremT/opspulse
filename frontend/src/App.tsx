import { useCallback, useEffect, useState, type FormEvent } from 'react'
import {
  Activity,
  AlertCircle,
  AlertTriangle,
  Bell,
  Building2,
  CheckCircle2,
  Loader2,
  LogOut,
  Plus,
  RefreshCw,
  Server,
  ShieldCheck,
  WifiOff,
  X,
} from 'lucide-react'
import {
  authApi,
  clearStoredSession,
  getStoredSession,
  getStoredWorkspaceId,
  saveStoredWorkspaceId,
  subscribeSessionCleared,
  toAuthSession,
  workspaceApi,
} from './api/client'
import {
  ApiClientError,
  type AuthResponse,
  type AuthSession,
  type WorkspaceResponse,
} from './api/types'
import { AuthPage } from './components/AuthPage'

type BackendStatus = 'checking' | 'UP' | 'Offline'
type DashboardTab = 'monitors' | 'incidents' | 'alerts'

interface ActuatorHealthResponse {
  status?: string
}

interface HealthState {
  status: BackendStatus
  lastChecked: string | null
}

const HEALTH_POLL_INTERVAL_MS = 30_000
const SLUG_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/

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
  const [isRefreshingHealth, setIsRefreshingHealth] = useState(false)

  const [session, setSession] = useState<AuthSession | null>(() => getStoredSession())
  const [workspaces, setWorkspaces] = useState<WorkspaceResponse[]>([])
  const [activeWorkspaceId, setActiveWorkspaceId] = useState<string | null>(() =>
    getStoredWorkspaceId(),
  )
  const [isLoadingWorkspaces, setIsLoadingWorkspaces] = useState(false)
  const [workspaceError, setWorkspaceError] = useState<string | null>(null)

  const [activeTab, setActiveTab] = useState<DashboardTab>('monitors')
  const [isCreateWorkspaceOpen, setIsCreateWorkspaceOpen] = useState(false)

  const handleRefreshHealth = useCallback(async () => {
    setIsRefreshingHealth(true)
    try {
      const nextHealth = await fetchBackendHealth()
      setHealth(nextHealth)
    } finally {
      setIsRefreshingHealth(false)
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

  useEffect(() => {
    return subscribeSessionCleared(() => {
      setSession(null)
      setWorkspaces([])
      setActiveWorkspaceId(null)
      setWorkspaceError(null)
    })
  }, [])

  const applyWorkspaceList = useCallback(
    (list: WorkspaceResponse[], preferredWorkspaceId?: string) => {
      setWorkspaces(list)
      const storedId = preferredWorkspaceId ?? getStoredWorkspaceId()
      const matched = list.find((w) => w.id === storedId) ?? list[0] ?? null
      if (matched) {
        setActiveWorkspaceId(matched.id)
        saveStoredWorkspaceId(matched.id)
      } else {
        setActiveWorkspaceId(null)
      }
    },
    [],
  )

  const loadWorkspaces = useCallback(
    async (preferredWorkspaceId?: string) => {
      setIsLoadingWorkspaces(true)
      setWorkspaceError(null)
      try {
        const list = await workspaceApi.list()
        applyWorkspaceList(list, preferredWorkspaceId)
      } catch (error) {
        if (error instanceof ApiClientError) {
          setWorkspaceError(error.message)
        } else {
          setWorkspaceError('Unable to load workspaces.')
        }
      } finally {
        setIsLoadingWorkspaces(false)
      }
    },
    [applyWorkspaceList],
  )

  useEffect(() => {
    if (!session) {
      return
    }

    let cancelled = false
    const syncWorkspaces = async () => {
      try {
        const list = await workspaceApi.list()
        if (!cancelled) {
          setWorkspaceError(null)
          applyWorkspaceList(list)
        }
      } catch (error) {
        if (!cancelled) {
          setWorkspaceError(
            error instanceof ApiClientError
              ? error.message
              : 'Unable to load workspaces.',
          )
        }
      }
    }

    void syncWorkspaces()
    return () => {
      cancelled = true
    }
  }, [session, applyWorkspaceList])

  function handleAuthSuccess(response: AuthResponse): void {
    const nextSession = toAuthSession(response)
    setSession(nextSession)
    if (response.defaultWorkspace) {
      setWorkspaces([response.defaultWorkspace])
      setActiveWorkspaceId(response.defaultWorkspace.id)
      saveStoredWorkspaceId(response.defaultWorkspace.id)
    }
  }

  async function handleLogout(): Promise<void> {
    const currentRefreshToken = session?.refreshToken
    clearStoredSession()
    setSession(null)
    setWorkspaces([])
    setActiveWorkspaceId(null)
    setWorkspaceError(null)

    if (currentRefreshToken) {
      try {
        await authApi.logout({ refreshToken: currentRefreshToken })
      } catch {
        // Best-effort server revocation; local session is already cleared.
      }
    }
  }

  function handleSelectWorkspace(workspaceId: string): void {
    setActiveWorkspaceId(workspaceId)
    saveStoredWorkspaceId(workspaceId)
  }

  function handleWorkspaceCreated(created: WorkspaceResponse): void {
    setWorkspaces((prev) => [...prev, created])
    setActiveWorkspaceId(created.id)
    saveStoredWorkspaceId(created.id)
    setIsCreateWorkspaceOpen(false)
  }

  const activeWorkspace = workspaces.find((w) => w.id === activeWorkspaceId) ?? null

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col">
      {/* Top Navigation Bar */}
      <header className="sticky top-0 z-20 border-b border-slate-800/80 bg-slate-950/90 backdrop-blur">
        <div className="mx-auto flex h-16 max-w-7xl items-center justify-between gap-4 px-4 sm:px-6 lg:px-8">
          <div className="flex items-center gap-3">
            <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-emerald-500/10 ring-1 ring-emerald-500/30">
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

          <div className="flex items-center gap-2.5 sm:gap-3">
            {session && (
              <div className="flex items-center gap-2">
                <div className="relative flex items-center">
                  <Building2 className="pointer-events-none absolute left-2.5 h-3.5 w-3.5 text-slate-400" />
                  <select
                    aria-label="Active workspace"
                    value={activeWorkspaceId ?? ''}
                    disabled={isLoadingWorkspaces || workspaces.length === 0}
                    onChange={(e) => handleSelectWorkspace(e.target.value)}
                    className="h-8 rounded-lg border border-slate-800 bg-slate-900 pl-8 pr-7 text-xs font-medium text-slate-200 focus:border-emerald-500 focus:outline-none disabled:opacity-60"
                  >
                    {workspaces.length === 0 ? (
                      <option value="">
                        {isLoadingWorkspaces ? 'Loading…' : 'No workspaces'}
                      </option>
                    ) : (
                      workspaces.map((ws) => (
                        <option key={ws.id} value={ws.id}>
                          {ws.name} ({ws.role})
                        </option>
                      ))
                    )}
                  </select>
                </div>

                <button
                  type="button"
                  onClick={() => setIsCreateWorkspaceOpen(true)}
                  className="inline-flex h-8 items-center gap-1 rounded-lg border border-slate-800 bg-slate-900 px-2.5 text-xs font-medium text-slate-200 transition hover:border-slate-700 hover:bg-slate-800"
                >
                  <Plus className="h-3.5 w-3.5 text-emerald-400" />
                  <span className="hidden md:inline">New Workspace</span>
                </button>
              </div>
            )}

            <BackendHealthBadge status={health.status} />

            <button
              type="button"
              onClick={() => void handleRefreshHealth()}
              disabled={isRefreshingHealth}
              aria-label="Refresh backend health status"
              className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-slate-800 bg-slate-900 px-2.5 text-xs font-medium text-slate-300 transition hover:border-slate-700 hover:bg-slate-800 hover:text-slate-100 disabled:cursor-not-allowed disabled:opacity-60"
            >
              <RefreshCw
                className={`h-3.5 w-3.5 ${isRefreshingHealth ? 'animate-spin' : ''}`}
              />
              <span className="hidden lg:inline">Refresh</span>
            </button>

            {session && (
              <div className="flex items-center gap-2 border-l border-slate-800 pl-2.5 sm:pl-3">
                <div className="hidden text-right xl:block">
                  <p className="text-xs font-medium text-slate-200">
                    {session.user.fullName}
                  </p>
                  <p className="text-[11px] text-slate-500">{session.user.email}</p>
                </div>
                <button
                  type="button"
                  onClick={() => void handleLogout()}
                  aria-label="Log out"
                  className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-slate-800 bg-slate-900 px-2.5 text-xs font-medium text-slate-300 transition hover:border-rose-500/40 hover:bg-rose-500/10 hover:text-rose-200"
                >
                  <LogOut className="h-3.5 w-3.5" />
                  <span className="hidden sm:inline">Logout</span>
                </button>
              </div>
            )}
          </div>
        </div>
      </header>

      {!session ? (
        <AuthPage onAuthSuccess={handleAuthSuccess} />
      ) : (
        <main className="mx-auto w-full max-w-7xl flex-1 px-4 py-8 sm:px-6 lg:px-8">
          {workspaceError && (
            <div
              role="alert"
              className="mb-6 flex items-center justify-between rounded-lg border border-rose-500/30 bg-rose-500/10 px-4 py-3 text-sm text-rose-200"
            >
              <div className="flex items-center gap-2">
                <AlertCircle className="h-4 w-4 text-rose-400" />
                <span>{workspaceError}</span>
              </div>
              <button
                type="button"
                onClick={() => void loadWorkspaces()}
                className="text-xs font-medium text-rose-100 underline hover:no-underline"
              >
                Retry
              </button>
            </div>
          )}

          {/* Active Workspace Header & Navigation Tabs */}
          <div className="mb-6 flex flex-col gap-4 border-b border-slate-800 pb-4 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <p className="text-xs font-medium uppercase tracking-wider text-slate-400">
                Active Workspace
              </p>
              <div className="mt-1 flex items-center gap-2.5">
                <h2 className="text-lg font-semibold text-slate-100">
                  {activeWorkspace ? activeWorkspace.name : 'Select a workspace'}
                </h2>
                {activeWorkspace && (
                  <span className="rounded-md border border-slate-800 bg-slate-900 px-2 py-0.5 font-mono text-xs text-slate-400">
                    /{activeWorkspace.slug}
                  </span>
                )}
              </div>
            </div>

            <nav
              aria-label="Dashboard sections"
              className="inline-flex rounded-lg border border-slate-800 bg-slate-900 p-1"
            >
              <TabButton
                active={activeTab === 'monitors'}
                onClick={() => setActiveTab('monitors')}
                icon={<Server className="h-3.5 w-3.5" />}
                label="Monitors"
              />
              <TabButton
                active={activeTab === 'incidents'}
                onClick={() => setActiveTab('incidents')}
                icon={<AlertTriangle className="h-3.5 w-3.5" />}
                label="Incidents"
              />
              <TabButton
                active={activeTab === 'alerts'}
                onClick={() => setActiveTab('alerts')}
                icon={<Bell className="h-3.5 w-3.5" />}
                label="Alert Channels"
              />
            </nav>
          </div>

          {/* Status Summary Grid */}
          <section
            aria-label="Platform overview"
            className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4"
          >
            <MetricCard
              label="Active Monitors"
              value="0"
              hint="Ready for live monitor APIs"
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

          {/* Active Section & Backend Connectivity */}
          <section className="mt-8 grid gap-6 lg:grid-cols-3">
            <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-6 lg:col-span-2">
              {activeTab === 'monitors' && (
                <SectionPlaceholder
                  title="Service Monitors"
                  description="Live HTTP/HTTPS endpoint checks and SSE status stream for the selected workspace."
                  emptyTitle="Monitors module ready for Part 2"
                  emptyBody="Monitor creation, probe intervals, and real-time uptime charts will connect here once S2-01 APIs are wired."
                  icon={<Server className="h-8 w-8 text-slate-600" />}
                />
              )}
              {activeTab === 'incidents' && (
                <SectionPlaceholder
                  title="Incident Timeline"
                  description="Automated outage detection, acknowledgment, and resolution history."
                  emptyTitle="Incidents module ready for Part 2"
                  emptyBody="Active and resolved incidents for this workspace will appear here once S2-02 APIs are wired."
                  icon={<AlertTriangle className="h-8 w-8 text-slate-600" />}
                />
              )}
              {activeTab === 'alerts' && (
                <SectionPlaceholder
                  title="Alert Channels"
                  description="Outbound Discord, Slack, and custom HTTP webhook integrations."
                  emptyTitle="Alert Channels module ready for Part 2"
                  emptyBody="Configure webhook destinations and test deliveries here once S2-03 APIs are wired."
                  icon={<Bell className="h-8 w-8 text-slate-600" />}
                />
              )}
            </div>

            <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-6">
              <h2 className="text-base font-semibold text-slate-100">
                Backend Connectivity
              </h2>
              <p className="mt-1 text-sm text-slate-400">
                Probing{' '}
                <code className="rounded bg-slate-800 px-1.5 py-0.5 text-xs text-slate-200">
                  /actuator/health
                </code>{' '}
                via Vite proxy.
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
      )}

      {isCreateWorkspaceOpen && (
        <CreateWorkspaceModal
          onClose={() => setIsCreateWorkspaceOpen(false)}
          onCreated={handleWorkspaceCreated}
        />
      )}
    </div>
  )
}

interface CreateWorkspaceModalProps {
  onClose: () => void
  onCreated: (workspace: WorkspaceResponse) => void
}

function CreateWorkspaceModal({ onClose, onCreated }: CreateWorkspaceModalProps) {
  const [name, setName] = useState('')
  const [slug, setSlug] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [apiError, setApiError] = useState<ApiClientError | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  function validate(): boolean {
    const errors: Record<string, string> = {}
    const trimmedName = name.trim()
    const trimmedSlug = slug.trim()

    if (!trimmedName) {
      errors.name = 'Workspace name is required'
    } else if (trimmedName.length < 2 || trimmedName.length > 120) {
      errors.name = 'Workspace name must be between 2 and 120 characters'
    }

    if (trimmedSlug) {
      if (trimmedSlug.length < 2 || trimmedSlug.length > 120) {
        errors.slug = 'Workspace slug must be between 2 and 120 characters'
      } else if (!SLUG_PATTERN.test(trimmedSlug)) {
        errors.slug =
          'Slug must contain only lowercase letters, numbers, and single hyphens'
      }
    }

    setFieldErrors(errors)
    return Object.keys(errors).length === 0
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    setApiError(null)
    if (!validate()) {
      return
    }

    setIsSubmitting(true)
    try {
      const trimmedSlug = slug.trim()
      const created = await workspaceApi.create({
        name: name.trim(),
        ...(trimmedSlug ? { slug: trimmedSlug } : {}),
      })
      onCreated(created)
    } catch (error) {
      if (error instanceof ApiClientError) {
        setApiError(error)
        if (Object.keys(error.fieldErrors).length > 0) {
          setFieldErrors(error.fieldErrors)
        }
      } else {
        setApiError(
          new ApiClientError({
            status: 0,
            error: 'Network Error',
            message: 'Unable to create workspace. Please try again.',
          }),
        )
      }
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="create-workspace-title"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 px-4 backdrop-blur-sm"
    >
      <div className="w-full max-w-md rounded-2xl border border-slate-800 bg-slate-900 p-6 shadow-2xl">
        <div className="flex items-center justify-between">
          <h2
            id="create-workspace-title"
            className="text-base font-semibold text-slate-100"
          >
            Create New Workspace
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close modal"
            className="rounded-lg p-1 text-slate-400 transition hover:bg-slate-800 hover:text-slate-200"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <p className="mt-1 text-xs text-slate-400">
          Organize monitors, incidents, and alert channels per team or environment.
        </p>

        {apiError && (
          <div
            role="alert"
            className="mt-4 rounded-lg border border-rose-500/30 bg-rose-500/10 p-3 text-xs text-rose-200"
          >
            <p className="font-medium text-rose-100">
              {apiError.error}
              {apiError.status > 0 ? ` (${apiError.status})` : ''}
            </p>
            <p className="mt-0.5">{apiError.message}</p>
          </div>
        )}

        <form onSubmit={(e) => void handleSubmit(e)} noValidate className="mt-4 space-y-4">
          <div>
            <label
              htmlFor="workspace-name"
              className="block text-xs font-medium uppercase tracking-wider text-slate-400"
            >
              Workspace Name
            </label>
            <input
              id="workspace-name"
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Production SRE"
              className="mt-1.5 w-full rounded-lg border border-slate-800 bg-slate-950 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-600 focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
            />
            {fieldErrors.name && (
              <p className="mt-1 text-xs text-rose-400">{fieldErrors.name}</p>
            )}
          </div>

          <div>
            <label
              htmlFor="workspace-slug"
              className="block text-xs font-medium uppercase tracking-wider text-slate-400"
            >
              Slug <span className="normal-case text-slate-500">(optional)</span>
            </label>
            <input
              id="workspace-slug"
              type="text"
              value={slug}
              onChange={(e) => setSlug(e.target.value)}
              placeholder="production-sre"
              className="mt-1.5 w-full rounded-lg border border-slate-800 bg-slate-950 px-3 py-2 font-mono text-sm text-slate-100 placeholder:text-slate-600 focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
            />
            {fieldErrors.slug && (
              <p className="mt-1 text-xs text-rose-400">{fieldErrors.slug}</p>
            )}
          </div>

          <div className="flex items-center justify-end gap-2 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded-lg border border-slate-800 bg-slate-950 px-3.5 py-2 text-xs font-medium text-slate-300 transition hover:bg-slate-800"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isSubmitting}
              className="inline-flex items-center gap-1.5 rounded-lg bg-emerald-500 px-4 py-2 text-xs font-semibold text-slate-950 transition hover:bg-emerald-400 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {isSubmitting && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
              <span>Create Workspace</span>
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

interface TabButtonProps {
  active: boolean
  onClick: () => void
  icon: React.ReactNode
  label: string
}

function TabButton({ active, onClick, icon, label }: TabButtonProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium transition ${
        active
          ? 'bg-slate-800 text-slate-100 shadow-sm'
          : 'text-slate-400 hover:text-slate-200'
      }`}
    >
      {icon}
      <span>{label}</span>
    </button>
  )
}

interface SectionPlaceholderProps {
  title: string
  description: string
  emptyTitle: string
  emptyBody: string
  icon: React.ReactNode
}

function SectionPlaceholder({
  title,
  description,
  emptyTitle,
  emptyBody,
  icon,
}: SectionPlaceholderProps) {
  return (
    <>
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-base font-semibold text-slate-100">{title}</h2>
          <p className="mt-1 text-sm text-slate-400">{description}</p>
        </div>
        <span className="rounded-md border border-slate-800 bg-slate-950 px-2.5 py-1 text-xs font-medium text-slate-400">
          Sprint 2 Ready
        </span>
      </div>

      <div className="mt-6 flex flex-col items-center justify-center rounded-lg border border-dashed border-slate-800 bg-slate-950/50 px-6 py-12 text-center">
        {icon}
        <p className="mt-3 text-sm font-medium text-slate-300">{emptyTitle}</p>
        <p className="mt-1 max-w-md text-xs text-slate-500">{emptyBody}</p>
      </div>
    </>
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
