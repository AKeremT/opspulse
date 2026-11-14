import { useCallback, useEffect, useState, type FormEvent } from 'react'
import {
  AlertCircle,
  CalendarClock,
  Clock,
  Loader2,
  Lock,
  Plus,
  RefreshCw,
  Trash2,
  Wrench,
} from 'lucide-react'
import { maintenanceApi } from '../api/client'
import {
  ApiClientError,
  type MaintenanceWindowResponse,
  type WorkspaceRole,
} from '../api/types'

interface MaintenanceWindowsPanelProps {
  workspaceId: string
  workspaceRole: WorkspaceRole
}

function padTwo(value: number): string {
  return String(value).padStart(2, '0')
}

function toLocalDateTimeInputValue(date: Date): string {
  return `${date.getFullYear()}-${padTwo(date.getMonth() + 1)}-${padTwo(date.getDate())}T${padTwo(date.getHours())}:${padTwo(date.getMinutes())}`
}

function formatDateTime(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return iso
  }
  return date.toLocaleString([], {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function MaintenanceStatusBadge({
  windowItem,
  referenceMs,
}: {
  windowItem: MaintenanceWindowResponse
  referenceMs: number
}) {
  if (windowItem.active) {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-full border border-amber-500/30 bg-amber-500/10 px-2.5 py-0.5 text-[11px] font-medium text-amber-300">
        <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-amber-400" />
        Active Now
      </span>
    )
  }

  const endsAtMs = new Date(windowItem.endsAt).getTime()
  if (!Number.isNaN(endsAtMs) && endsAtMs <= referenceMs) {
    return (
      <span className="inline-flex items-center rounded-full border border-slate-700 bg-slate-800 px-2.5 py-0.5 text-[11px] font-medium text-slate-400">
        Completed
      </span>
    )
  }

  return (
    <span className="inline-flex items-center gap-1.5 rounded-full border border-sky-500/30 bg-sky-500/10 px-2.5 py-0.5 text-[11px] font-medium text-sky-300">
      <Clock className="h-3 w-3" />
      Scheduled
    </span>
  )
}

export function MaintenanceWindowsPanel({
  workspaceId,
  workspaceRole,
}: MaintenanceWindowsPanelProps) {
  const canWrite = workspaceRole !== 'VIEWER'

  const [windows, setWindows] = useState<MaintenanceWindowResponse[]>([])
  const [referenceMs, setReferenceMs] = useState(() => Date.now())
  const [isLoading, setIsLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  const [isAddOpen, setIsAddOpen] = useState(false)
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [startsAt, setStartsAt] = useState(() => toLocalDateTimeInputValue(new Date()))
  const [endsAt, setEndsAt] = useState(() =>
    toLocalDateTimeInputValue(new Date(Date.now() + 60 * 60 * 1000)),
  )

  const [isSubmitting, setIsSubmitting] = useState(false)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [formError, setFormError] = useState<ApiClientError | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  const loadWindows = useCallback(async () => {
    setIsLoading(true)
    setLoadError(null)
    try {
      const list = await maintenanceApi.list(workspaceId)
      setWindows(list)
      setReferenceMs(Date.now())
    } catch (error) {
      setLoadError(
        error instanceof ApiClientError
          ? error.message
          : 'Unable to load maintenance windows.',
      )
    } finally {
      setIsLoading(false)
    }
  }, [workspaceId])

  useEffect(() => {
    let cancelled = false

    const loadInitial = async () => {
      setIsLoading(true)
      setLoadError(null)
      setActionError(null)
      try {
        const list = await maintenanceApi.list(workspaceId)
        if (!cancelled) {
          setWindows(list)
          setReferenceMs(Date.now())
        }
      } catch (error) {
        if (!cancelled) {
          setLoadError(
            error instanceof ApiClientError
              ? error.message
              : 'Unable to load maintenance windows.',
          )
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
  }, [workspaceId])

  function openCreateForm(): void {
    const now = new Date()
    const oneHourLater = new Date(now.getTime() + 60 * 60 * 1000)
    setFormError(null)
    setFieldErrors({})
    setStartsAt(toLocalDateTimeInputValue(now))
    setEndsAt(toLocalDateTimeInputValue(oneHourLater))
    setIsAddOpen(true)
  }

  function validateForm(): boolean {
    const errors: Record<string, string> = {}
    const trimmedTitle = title.trim()
    const trimmedDesc = description.trim()

    if (!trimmedTitle) {
      errors.title = 'Title is required'
    } else if (trimmedTitle.length < 2 || trimmedTitle.length > 160) {
      errors.title = 'Title must be between 2 and 160 characters'
    }

    if (trimmedDesc.length > 500) {
      errors.description = 'Description must be at most 500 characters'
    }

    const startDate = new Date(startsAt)
    const endDate = new Date(endsAt)

    if (!startsAt || Number.isNaN(startDate.getTime())) {
      errors.startsAt = 'Valid start date and time is required'
    }

    if (!endsAt || Number.isNaN(endDate.getTime())) {
      errors.endsAt = 'Valid end date and time is required'
    } else if (
      !Number.isNaN(startDate.getTime()) &&
      endDate.getTime() <= startDate.getTime()
    ) {
      errors.endsAt = 'End time must be after start time'
    }

    setFieldErrors(errors)
    return Object.keys(errors).length === 0
  }

  async function handleCreateWindow(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    if (!canWrite) {
      return
    }
    setFormError(null)
    if (!validateForm()) {
      return
    }

    setIsSubmitting(true)
    try {
      const trimmedDesc = description.trim()
      const created = await maintenanceApi.create(workspaceId, {
        title: title.trim(),
        description: trimmedDesc ? trimmedDesc : null,
        startsAt: new Date(startsAt).toISOString(),
        endsAt: new Date(endsAt).toISOString(),
      })
      setWindows((prev) => [created, ...prev])
      setReferenceMs(Date.now())
      setTitle('')
      setDescription('')
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
            message: 'Unable to schedule maintenance window. Please try again.',
          }),
        )
      }
    } finally {
      setIsSubmitting(false)
    }
  }

  async function handleDeleteWindow(windowId: string): Promise<void> {
    if (!canWrite) {
      return
    }
    setActionError(null)
    setBusyId(windowId)
    try {
      await maintenanceApi.remove(workspaceId, windowId)
      setWindows((prev) => prev.filter((item) => item.id !== windowId))
    } catch (error) {
      setActionError(
        error instanceof ApiClientError
          ? error.message
          : 'Failed to delete maintenance window.',
      )
    } finally {
      setBusyId(null)
    }
  }

  return (
    <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h2 className="text-base font-semibold text-slate-100">
            Maintenance Windows
          </h2>
          <p className="mt-1 text-sm text-slate-400">
            Schedule planned downtime windows to suppress incident alerts and inform users on your public status page.
          </p>
        </div>

        <div className="flex items-center gap-2 self-start sm:self-auto">
          <button
            type="button"
            onClick={() => void loadWindows()}
            disabled={isLoading}
            aria-label="Refresh maintenance windows"
            className="inline-flex items-center gap-1.5 rounded-lg border border-slate-800 bg-slate-900 px-3 py-2 text-xs font-medium text-slate-300 transition hover:border-slate-700 hover:bg-slate-800 hover:text-slate-100 disabled:opacity-60"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${isLoading ? 'animate-spin' : ''}`} />
            <span className="hidden sm:inline">Refresh</span>
          </button>

          {canWrite ? (
            <button
              type="button"
              onClick={() => {
                if (isAddOpen) {
                  setIsAddOpen(false)
                } else {
                  openCreateForm()
                }
              }}
              className="inline-flex items-center gap-1.5 rounded-lg bg-emerald-500 px-3.5 py-2 text-xs font-semibold text-slate-950 transition hover:bg-emerald-400"
            >
              <Plus className="h-4 w-4" />
              <span>{isAddOpen ? 'Close Form' : 'Schedule Maintenance'}</span>
            </button>
          ) : (
            <span className="inline-flex items-center gap-1.5 rounded-lg border border-slate-800 bg-slate-950 px-3 py-2 text-xs text-slate-400">
              <Lock className="h-3.5 w-3.5 text-slate-500" />
              Read-only ({workspaceRole})
            </span>
          )}
        </div>
      </div>

      {actionError && (
        <div
          role="alert"
          className="mt-4 rounded-lg border border-rose-500/30 bg-rose-500/10 px-4 py-2.5 text-xs text-rose-200"
        >
          {actionError}
        </div>
      )}

      {isAddOpen && canWrite && (
        <form
          onSubmit={(e) => void handleCreateWindow(e)}
          noValidate
          className="mt-5 rounded-xl border border-slate-800 bg-slate-950/70 p-5"
        >
          <h3 className="text-sm font-semibold text-slate-100">
            Schedule Maintenance Window
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

          <div className="mt-4 space-y-4">
            <div>
              <label
                htmlFor="maintenance-title"
                className="block text-xs font-medium uppercase tracking-wider text-slate-400"
              >
                Title
              </label>
              <input
                id="maintenance-title"
                type="text"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="Primary Database Upgrade"
                className="mt-1.5 w-full rounded-lg border border-slate-800 bg-slate-900 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-600 focus:border-emerald-500 focus:outline-none"
              />
              {fieldErrors.title && (
                <p className="mt-1 text-xs text-rose-400">{fieldErrors.title}</p>
              )}
            </div>

            <div>
              <label
                htmlFor="maintenance-description"
                className="block text-xs font-medium uppercase tracking-wider text-slate-400"
              >
                Description (Optional)
              </label>
              <textarea
                id="maintenance-description"
                rows={2}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="Brief summary of the planned maintenance and expected impact."
                className="mt-1.5 w-full rounded-lg border border-slate-800 bg-slate-900 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-600 focus:border-emerald-500 focus:outline-none"
              />
              {fieldErrors.description && (
                <p className="mt-1 text-xs text-rose-400">{fieldErrors.description}</p>
              )}
            </div>

            <div className="grid gap-4 sm:grid-cols-2">
              <div>
                <label
                  htmlFor="maintenance-starts-at"
                  className="block text-xs font-medium uppercase tracking-wider text-slate-400"
                >
                  Starts At (Local Time)
                </label>
                <input
                  id="maintenance-starts-at"
                  type="datetime-local"
                  value={startsAt}
                  onChange={(e) => setStartsAt(e.target.value)}
                  className="mt-1.5 w-full rounded-lg border border-slate-800 bg-slate-900 px-3 py-2 text-sm text-slate-100 focus:border-emerald-500 focus:outline-none"
                />
                {fieldErrors.startsAt && (
                  <p className="mt-1 text-xs text-rose-400">{fieldErrors.startsAt}</p>
                )}
              </div>

              <div>
                <label
                  htmlFor="maintenance-ends-at"
                  className="block text-xs font-medium uppercase tracking-wider text-slate-400"
                >
                  Ends At (Local Time)
                </label>
                <input
                  id="maintenance-ends-at"
                  type="datetime-local"
                  value={endsAt}
                  onChange={(e) => setEndsAt(e.target.value)}
                  className="mt-1.5 w-full rounded-lg border border-slate-800 bg-slate-900 px-3 py-2 text-sm text-slate-100 focus:border-emerald-500 focus:outline-none"
                />
                {fieldErrors.endsAt && (
                  <p className="mt-1 text-xs text-rose-400">{fieldErrors.endsAt}</p>
                )}
              </div>
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
              <span>Create Window</span>
            </button>
          </div>
        </form>
      )}

      {isLoading ? (
        <div className="mt-8 flex items-center justify-center py-12 text-sm text-slate-400">
          <Loader2 className="mr-2 h-4 w-4 animate-spin text-emerald-400" />
          Loading maintenance windows…
        </div>
      ) : loadError ? (
        <div
          role="alert"
          className="mt-6 flex items-center justify-between rounded-lg border border-rose-500/30 bg-rose-500/10 px-4 py-3 text-sm text-rose-200"
        >
          <div className="flex items-center gap-2">
            <AlertCircle className="h-4 w-4 shrink-0 text-rose-400" />
            <span>{loadError}</span>
          </div>
          <button
            type="button"
            onClick={() => void loadWindows()}
            className="text-xs font-medium text-rose-100 underline hover:no-underline"
          >
            Retry
          </button>
        </div>
      ) : windows.length === 0 ? (
        <div className="mt-6 flex flex-col items-center justify-center rounded-lg border border-dashed border-slate-800 bg-slate-950/50 px-6 py-12 text-center">
          <Wrench className="h-8 w-8 text-slate-600" />
          <p className="mt-3 text-sm font-medium text-slate-300">
            No maintenance windows scheduled
          </p>
          <p className="mt-1 max-w-md text-xs text-slate-500">
            Schedule a maintenance window before planned upgrades so monitors do not trigger false incident alerts.
          </p>
        </div>
      ) : (
        <div className="mt-6 space-y-3">
          {windows.map((windowItem) => {
            const isBusy = busyId === windowItem.id
            return (
              <div
                key={windowItem.id}
                className="flex flex-col justify-between gap-4 rounded-xl border border-slate-800 bg-slate-950/60 p-4 sm:flex-row sm:items-center"
              >
                <div className="min-w-0 space-y-1.5">
                  <div className="flex flex-wrap items-center gap-2.5">
                    <MaintenanceStatusBadge
                      windowItem={windowItem}
                      referenceMs={referenceMs}
                    />
                    <h3 className="truncate text-sm font-semibold text-slate-100">
                      {windowItem.title}
                    </h3>
                  </div>

                  {windowItem.description && (
                    <p className="text-xs text-slate-400">{windowItem.description}</p>
                  )}

                  <div className="flex flex-wrap items-center gap-2 text-xs text-slate-400">
                    <CalendarClock className="h-3.5 w-3.5 text-slate-500" />
                    <span>
                      {formatDateTime(windowItem.startsAt)} —{' '}
                      {formatDateTime(windowItem.endsAt)}
                    </span>
                  </div>
                </div>

                {canWrite && (
                  <button
                    type="button"
                    disabled={isBusy}
                    onClick={() => void handleDeleteWindow(windowItem.id)}
                    aria-label={`Delete maintenance window ${windowItem.title}`}
                    className="inline-flex shrink-0 items-center gap-1.5 self-start rounded-md border border-slate-800 bg-slate-900 px-2.5 py-1.5 text-xs font-medium text-slate-400 transition hover:border-rose-500/40 hover:bg-rose-500/10 hover:text-rose-300 disabled:opacity-50 sm:self-center"
                  >
                    {isBusy ? (
                      <Loader2 className="h-3.5 w-3.5 animate-spin" />
                    ) : (
                      <Trash2 className="h-3.5 w-3.5" />
                    )}
                    <span>Delete</span>
                  </button>
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
