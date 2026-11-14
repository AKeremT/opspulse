import { useState, type FormEvent } from 'react'
import { Bell, Loader2, Plus, Trash2, Webhook } from 'lucide-react'
import { alertChannelApi } from '../api/client'
import { ApiClientError, type AlertChannelResponse } from '../api/types'

interface AlertChannelsPanelProps {
  workspaceId: string
  channels: AlertChannelResponse[]
  isLoading: boolean
  onChannelCreated: (channel: AlertChannelResponse) => void
  onChannelDeleted: (channelId: string) => void
}

export function AlertChannelsPanel({
  workspaceId,
  channels,
  isLoading,
  onChannelCreated,
  onChannelDeleted,
}: AlertChannelsPanelProps) {
  const [isAddOpen, setIsAddOpen] = useState(false)
  const [name, setName] = useState('')
  const [targetUrl, setTargetUrl] = useState('https://')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [formError, setFormError] = useState<ApiClientError | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  function validateForm(): boolean {
    const errors: Record<string, string> = {}
    const trimmedName = name.trim()
    const trimmedUrl = targetUrl.trim()

    if (!trimmedName) {
      errors.name = 'Channel name is required'
    } else if (trimmedName.length < 2 || trimmedName.length > 120) {
      errors.name = 'Channel name must be between 2 and 120 characters'
    }

    if (!trimmedUrl) {
      errors.targetUrl = 'Target URL is required'
    } else if (!/^https?:\/\/.+/i.test(trimmedUrl)) {
      errors.targetUrl = 'Target URL must start with http:// or https://'
    }

    setFieldErrors(errors)
    return Object.keys(errors).length === 0
  }

  async function handleCreateChannel(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    setFormError(null)
    if (!validateForm()) {
      return
    }

    setIsSubmitting(true)
    try {
      const created = await alertChannelApi.create(workspaceId, {
        name: name.trim(),
        channelType: 'WEBHOOK',
        targetUrl: targetUrl.trim(),
      })
      onChannelCreated(created)
      setName('')
      setTargetUrl('https://')
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
            message: 'Unable to create alert channel. Please try again.',
          }),
        )
      }
    } finally {
      setIsSubmitting(false)
    }
  }

  async function handleDelete(channelId: string): Promise<void> {
    setActionError(null)
    setBusyId(channelId)
    try {
      await alertChannelApi.delete(workspaceId, channelId)
      onChannelDeleted(channelId)
    } catch (error) {
      setActionError(
        error instanceof ApiClientError
          ? error.message
          : 'Failed to delete alert channel.',
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
            Alert Channels
          </h2>
          <p className="mt-1 text-sm text-slate-400">
            Configure outbound Discord, Slack, or custom HTTP webhook notifications for incident events.
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
          <span>{isAddOpen ? 'Close Form' : 'Add Webhook'}</span>
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

      {isAddOpen && (
        <form
          onSubmit={(e) => void handleCreateChannel(e)}
          noValidate
          className="mt-5 rounded-xl border border-slate-800 bg-slate-950/70 p-5"
        >
          <h3 className="text-sm font-semibold text-slate-100">
            New Webhook Channel
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

          <div className="mt-4 grid gap-4 sm:grid-cols-2">
            <div>
              <label
                htmlFor="channel-name"
                className="block text-xs font-medium uppercase tracking-wider text-slate-400"
              >
                Channel Name
              </label>
              <input
                id="channel-name"
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="SRE Slack Alerts"
                className="mt-1.5 w-full rounded-lg border border-slate-800 bg-slate-900 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-600 focus:border-emerald-500 focus:outline-none"
              />
              {fieldErrors.name && (
                <p className="mt-1 text-xs text-rose-400">{fieldErrors.name}</p>
              )}
            </div>

            <div>
              <label
                htmlFor="channel-url"
                className="block text-xs font-medium uppercase tracking-wider text-slate-400"
              >
                Webhook URL
              </label>
              <input
                id="channel-url"
                type="url"
                value={targetUrl}
                onChange={(e) => setTargetUrl(e.target.value)}
                placeholder="https://hooks.slack.com/services/..."
                className="mt-1.5 w-full rounded-lg border border-slate-800 bg-slate-900 px-3 py-2 font-mono text-sm text-slate-100 placeholder:text-slate-600 focus:border-emerald-500 focus:outline-none"
              />
              {fieldErrors.targetUrl && (
                <p className="mt-1 text-xs text-rose-400">{fieldErrors.targetUrl}</p>
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
              <span>Save Webhook</span>
            </button>
          </div>
        </form>
      )}

      {isLoading ? (
        <div className="mt-8 flex items-center justify-center py-12 text-sm text-slate-400">
          <Loader2 className="mr-2 h-4 w-4 animate-spin text-emerald-400" />
          Loading alert channels…
        </div>
      ) : channels.length === 0 ? (
        <div className="mt-6 flex flex-col items-center justify-center rounded-lg border border-dashed border-slate-800 bg-slate-950/50 px-6 py-12 text-center">
          <Bell className="h-8 w-8 text-slate-600" />
          <p className="mt-3 text-sm font-medium text-slate-300">
            No alert channels configured
          </p>
          <p className="mt-1 max-w-md text-xs text-slate-500">
            Add an HTTP webhook destination to receive instant alerts when incidents open or resolve.
          </p>
        </div>
      ) : (
        <div className="mt-6 space-y-3">
          {channels.map((channel) => {
            const isBusy = busyId === channel.id
            return (
              <div
                key={channel.id}
                className="flex items-center justify-between gap-4 rounded-xl border border-slate-800 bg-slate-950/60 p-4"
              >
                <div className="min-w-0 space-y-1">
                  <div className="flex items-center gap-2.5">
                    <span className="inline-flex items-center gap-1 rounded-md border border-violet-500/30 bg-violet-500/10 px-2 py-0.5 text-[11px] font-medium text-violet-300">
                      <Webhook className="h-3 w-3" />
                      {channel.channelType}
                    </span>
                    <span className="truncate text-sm font-semibold text-slate-100">
                      {channel.name}
                    </span>
                  </div>
                  <p className="truncate font-mono text-xs text-slate-400">
                    {channel.targetUrl}
                  </p>
                </div>

                <button
                  type="button"
                  disabled={isBusy}
                  onClick={() => void handleDelete(channel.id)}
                  aria-label={`Delete alert channel ${channel.name}`}
                  className="inline-flex shrink-0 items-center gap-1.5 rounded-md border border-slate-800 bg-slate-900 px-2.5 py-1.5 text-xs font-medium text-slate-400 transition hover:border-rose-500/40 hover:bg-rose-500/10 hover:text-rose-300 disabled:opacity-50"
                >
                  {isBusy ? (
                    <Loader2 className="h-3.5 w-3.5 animate-spin" />
                  ) : (
                    <Trash2 className="h-3.5 w-3.5" />
                  )}
                  <span className="hidden sm:inline">Delete</span>
                </button>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
