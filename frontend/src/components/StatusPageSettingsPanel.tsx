import { useCallback, useEffect, useState, type FormEvent } from 'react'
import {
  AlertCircle,
  Check,
  CheckCircle2,
  Copy,
  ExternalLink,
  Globe,
  Loader2,
  Lock,
  Save,
} from 'lucide-react'
import { statusPageApi } from '../api/client'
import {
  ApiClientError,
  type StatusPageSettingsResponse,
  type WorkspaceRole,
} from '../api/types'

interface StatusPageSettingsPanelProps {
  workspaceId: string
  workspaceRole: WorkspaceRole
}

const SLUG_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/

export function StatusPageSettingsPanel({
  workspaceId,
  workspaceRole,
}: StatusPageSettingsPanelProps) {
  const canEdit = workspaceRole === 'OWNER' || workspaceRole === 'ADMIN'

  const [settings, setSettings] = useState<StatusPageSettingsResponse | null>(null)
  const [slug, setSlug] = useState('')
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [isPublic, setIsPublic] = useState(false)

  const [isLoading, setIsLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  const [isSaving, setIsSaving] = useState(false)
  const [saveError, setSaveError] = useState<ApiClientError | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [saveSuccess, setSaveSuccess] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)

  const applySettings = useCallback((data: StatusPageSettingsResponse) => {
    setSettings(data)
    setSlug(data.slug)
    setTitle(data.title)
    setDescription(data.description ?? '')
    setIsPublic(data.isPublic)
  }, [])

  const loadSettings = useCallback(async () => {
    setIsLoading(true)
    setLoadError(null)
    setSaveError(null)
    setSaveSuccess(null)
    setFieldErrors({})

    try {
      const data = await statusPageApi.getSettings(workspaceId)
      applySettings(data)
    } catch (error) {
      setLoadError(
        error instanceof ApiClientError
          ? error.message
          : 'Unable to load status page settings.',
      )
    } finally {
      setIsLoading(false)
    }
  }, [workspaceId, applySettings])

  useEffect(() => {
    let cancelled = false

    const loadInitial = async () => {
      setIsLoading(true)
      setSaveSuccess(null)
      setSaveError(null)
      setLoadError(null)
      setFieldErrors({})

      try {
        const data = await statusPageApi.getSettings(workspaceId)
        if (!cancelled) {
          applySettings(data)
          setLoadError(null)
        }
      } catch (error) {
        if (!cancelled) {
          setLoadError(
            error instanceof ApiClientError
              ? error.message
              : 'Unable to load status page settings.',
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
  }, [workspaceId, applySettings])

  function validateForm(): boolean {
    const errors: Record<string, string> = {}
    const trimmedSlug = slug.trim()
    const trimmedTitle = title.trim()
    const trimmedDesc = description.trim()

    if (!trimmedSlug) {
      errors.slug = 'Slug is required'
    } else if (trimmedSlug.length < 2 || trimmedSlug.length > 120) {
      errors.slug = 'Slug must be between 2 and 120 characters'
    } else if (!SLUG_PATTERN.test(trimmedSlug)) {
      errors.slug =
        'Slug must contain only lowercase letters, numbers, and single hyphens'
    }

    if (!trimmedTitle) {
      errors.title = 'Title is required'
    } else if (trimmedTitle.length < 2 || trimmedTitle.length > 160) {
      errors.title = 'Title must be between 2 and 160 characters'
    }

    if (trimmedDesc.length > 500) {
      errors.description = 'Description must be 500 characters or fewer'
    }

    setFieldErrors(errors)
    return Object.keys(errors).length === 0
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    if (!canEdit) {
      return
    }

    setSaveError(null)
    setSaveSuccess(null)
    if (!validateForm()) {
      return
    }

    setIsSaving(true)
    try {
      const trimmedDesc = description.trim()
      const updated = await statusPageApi.updateSettings(workspaceId, {
        slug: slug.trim(),
        title: title.trim(),
        description: trimmedDesc ? trimmedDesc : null,
        isPublic,
      })
      applySettings(updated)
      setFieldErrors({})
      setSaveSuccess('Status page settings saved.')
    } catch (error) {
      if (error instanceof ApiClientError) {
        setSaveError(error)
        if (Object.keys(error.fieldErrors).length > 0) {
          setFieldErrors(error.fieldErrors)
        }
      } else {
        setSaveError(
          new ApiClientError({
            status: 0,
            error: 'Network Error',
            message: 'Unable to save status page settings. Please try again.',
          }),
        )
      }
    } finally {
      setIsSaving(false)
    }
  }

  async function handleCopyUrl(): Promise<void> {
    const activeSlug = settings?.slug ?? slug.trim()
    if (!activeSlug) {
      return
    }
    const url = `${window.location.origin}/status/${activeSlug}`
    try {
      await navigator.clipboard.writeText(url)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2000)
    } catch {
      // Clipboard API may be unavailable in restricted contexts.
    }
  }

  const savedSlug = settings?.slug ?? slug.trim()
  const publicPath = `/status/${savedSlug}`
  const publicUrl = `${window.location.origin}${publicPath}`

  return (
    <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <div className="flex items-center gap-2">
            <Globe className="h-4 w-4 text-emerald-400" />
            <h2 className="text-base font-semibold text-slate-100">
              Public Status Page
            </h2>
          </div>
          <p className="mt-1 text-sm text-slate-400">
            Publish a real-time uptime and incident summary page for external users. Monitor URLs and webhook secrets are never exposed.
          </p>
        </div>

        {settings && (
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => void handleCopyUrl()}
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs font-medium text-slate-200 transition hover:bg-slate-800"
            >
              {copied ? (
                <>
                  <Check className="h-3.5 w-3.5 text-emerald-400" />
                  <span>Copied</span>
                </>
              ) : (
                <>
                  <Copy className="h-3.5 w-3.5" />
                  <span>Copy URL</span>
                </>
              )}
            </button>

            <a
              href={publicPath}
              target="_blank"
              rel="noopener noreferrer"
              className={`inline-flex items-center gap-1.5 rounded-lg px-3.5 py-2 text-xs font-semibold transition ${
                settings.isPublic
                  ? 'bg-emerald-500 text-slate-950 hover:bg-emerald-400'
                  : 'border border-slate-700 bg-slate-800 text-slate-300 hover:bg-slate-700'
              }`}
            >
              <ExternalLink className="h-3.5 w-3.5" />
              <span>Open Status Page</span>
            </a>
          </div>
        )}
      </div>

      {!canEdit && (
        <div className="mt-4 flex items-center gap-2 rounded-lg border border-amber-500/30 bg-amber-500/10 px-4 py-2.5 text-xs text-amber-200">
          <Lock className="h-4 w-4 shrink-0 text-amber-400" />
          <span>
            Only workspace owners and admins can modify status page settings.
          </span>
        </div>
      )}

      {isLoading ? (
        <div className="mt-6 flex items-center justify-center py-12 text-slate-400">
          <Loader2 className="h-5 w-5 animate-spin text-emerald-400" />
          <span className="ml-2 text-sm">Loading status page settings…</span>
        </div>
      ) : loadError ? (
        <div
          role="alert"
          className="mt-5 flex items-center justify-between rounded-lg border border-rose-500/30 bg-rose-500/10 px-4 py-3 text-xs text-rose-200"
        >
          <div className="flex items-center gap-2">
            <AlertCircle className="h-4 w-4 text-rose-400" />
            <span>{loadError}</span>
          </div>
          <button
            type="button"
            onClick={() => void loadSettings()}
            className="font-medium text-rose-100 underline hover:no-underline"
          >
            Retry
          </button>
        </div>
      ) : (
        <form
          onSubmit={(e) => void handleSubmit(e)}
          noValidate
          className="mt-6 space-y-5"
        >
          {saveError && (
            <div
              role="alert"
              className="rounded-lg border border-rose-500/30 bg-rose-500/10 p-3 text-xs text-rose-200"
            >
              <p className="font-medium text-rose-100">
                {saveError.error}
                {saveError.status > 0 ? ` (${saveError.status})` : ''}
              </p>
              <p className="mt-0.5">{saveError.message}</p>
            </div>
          )}

          {saveSuccess && (
            <div
              role="status"
              className="flex items-center gap-2 rounded-lg border border-emerald-500/30 bg-emerald-500/10 px-4 py-2.5 text-xs text-emerald-200"
            >
              <CheckCircle2 className="h-4 w-4 text-emerald-400" />
              <span>{saveSuccess}</span>
            </div>
          )}

          <div className="flex items-center justify-between rounded-xl border border-slate-800 bg-slate-950/70 p-4">
            <div>
              <p className="text-sm font-medium text-slate-100">
                Enable Public Status Page
              </p>
              <p className="mt-0.5 text-xs text-slate-400">
                When disabled, unauthenticated requests to{' '}
                <span className="font-mono text-slate-300">{publicPath}</span> return 404.
              </p>
            </div>

            <button
              type="button"
              role="switch"
              aria-checked={isPublic}
              aria-label="Enable public status page"
              disabled={!canEdit}
              onClick={() => {
                setSaveSuccess(null)
                setIsPublic((prev) => !prev)
              }}
              className={`relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:ring-offset-2 focus:ring-offset-slate-950 disabled:cursor-not-allowed disabled:opacity-50 ${
                isPublic ? 'bg-emerald-500' : 'bg-slate-700'
              }`}
            >
              <span
                className={`pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow transition ${
                  isPublic ? 'translate-x-5' : 'translate-x-0'
                }`}
              />
            </button>
          </div>

          <div className="grid gap-5 sm:grid-cols-2">
            <div>
              <label
                htmlFor="status-page-slug"
                className="block text-xs font-medium uppercase tracking-wider text-slate-400"
              >
                Public Slug
              </label>
              <input
                id="status-page-slug"
                type="text"
                value={slug}
                disabled={!canEdit}
                onChange={(e) => {
                  setSaveSuccess(null)
                  setSlug(e.target.value)
                }}
                placeholder="acme-status"
                className="mt-1.5 w-full rounded-lg border border-slate-800 bg-slate-950 px-3 py-2 font-mono text-sm text-slate-100 placeholder:text-slate-600 focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500 disabled:cursor-not-allowed disabled:opacity-60"
              />
              {fieldErrors.slug && (
                <p className="mt-1 text-xs text-rose-400">{fieldErrors.slug}</p>
              )}
              <p className="mt-1.5 truncate text-[11px] text-slate-500">
                URL: <span className="font-mono text-slate-400">{publicUrl}</span>
              </p>
            </div>

            <div>
              <label
                htmlFor="status-page-title"
                className="block text-xs font-medium uppercase tracking-wider text-slate-400"
              >
                Page Title
              </label>
              <input
                id="status-page-title"
                type="text"
                value={title}
                disabled={!canEdit}
                onChange={(e) => {
                  setSaveSuccess(null)
                  setTitle(e.target.value)
                }}
                placeholder="Acme Cloud Status"
                className="mt-1.5 w-full rounded-lg border border-slate-800 bg-slate-950 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-600 focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500 disabled:cursor-not-allowed disabled:opacity-60"
              />
              {fieldErrors.title && (
                <p className="mt-1 text-xs text-rose-400">{fieldErrors.title}</p>
              )}
            </div>
          </div>

          <div>
            <label
              htmlFor="status-page-description"
              className="block text-xs font-medium uppercase tracking-wider text-slate-400"
            >
              Description <span className="normal-case text-slate-500">(optional)</span>
            </label>
            <textarea
              id="status-page-description"
              rows={3}
              value={description}
              disabled={!canEdit}
              onChange={(e) => {
                setSaveSuccess(null)
                setDescription(e.target.value)
              }}
              placeholder="Live availability and incident updates for our core APIs and customer services."
              className="mt-1.5 w-full rounded-lg border border-slate-800 bg-slate-950 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-600 focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500 disabled:cursor-not-allowed disabled:opacity-60"
            />
            {fieldErrors.description && (
              <p className="mt-1 text-xs text-rose-400">{fieldErrors.description}</p>
            )}
          </div>

          <div className="flex flex-col gap-3 border-t border-slate-800 pt-4 sm:flex-row sm:items-center sm:justify-between">
            <p className="text-xs text-slate-500">
              {settings?.updatedAt
                ? `Last updated ${new Date(settings.updatedAt).toLocaleString()}`
                : ''}
            </p>

            {canEdit && (
              <button
                type="submit"
                disabled={isSaving}
                className="inline-flex items-center justify-center gap-1.5 rounded-lg bg-emerald-500 px-4 py-2 text-xs font-semibold text-slate-950 transition hover:bg-emerald-400 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {isSaving ? (
                  <Loader2 className="h-3.5 w-3.5 animate-spin" />
                ) : (
                  <Save className="h-3.5 w-3.5" />
                )}
                <span>Save Settings</span>
              </button>
            )}
          </div>
        </form>
      )}
    </div>
  )
}
