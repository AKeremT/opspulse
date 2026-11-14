import { useState } from 'react'
import { AlertTriangle, Check, CheckCircle2, Clock, Loader2 } from 'lucide-react'
import { incidentApi } from '../api/client'
import {
  ApiClientError,
  type IncidentResponse,
  type IncidentStatus,
} from '../api/types'

interface IncidentsPanelProps {
  workspaceId: string
  incidents: IncidentResponse[]
  isLoading: boolean
  onIncidentUpdated: (incident: IncidentResponse) => void
}

function formatDateTime(isoString: string | null): string {
  if (!isoString) {
    return '—'
  }
  const date = new Date(isoString)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }
  return date.toLocaleString([], {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

export function IncidentsPanel({
  workspaceId,
  incidents,
  isLoading,
  onIncidentUpdated,
}: IncidentsPanelProps) {
  const [busyId, setBusyId] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  async function handleAcknowledge(incidentId: string): Promise<void> {
    setActionError(null)
    setBusyId(incidentId)
    try {
      const updated = await incidentApi.acknowledge(workspaceId, incidentId)
      onIncidentUpdated(updated)
    } catch (error) {
      setActionError(
        error instanceof ApiClientError
          ? error.message
          : 'Failed to acknowledge incident.',
      )
    } finally {
      setBusyId(null)
    }
  }

  return (
    <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-6">
      <div>
        <h2 className="text-base font-semibold text-slate-100">
          Incident Timeline
        </h2>
        <p className="mt-1 text-sm text-slate-400">
          Track automated outage incidents, acknowledge active alerts, and review resolution times.
        </p>
      </div>

      {actionError && (
        <div
          role="alert"
          className="mt-4 rounded-lg border border-rose-500/30 bg-rose-500/10 px-4 py-2.5 text-xs text-rose-200"
        >
          {actionError}
        </div>
      )}

      {isLoading ? (
        <div className="mt-8 flex items-center justify-center py-12 text-sm text-slate-400">
          <Loader2 className="mr-2 h-4 w-4 animate-spin text-emerald-400" />
          Loading incidents…
        </div>
      ) : incidents.length === 0 ? (
        <div className="mt-6 flex flex-col items-center justify-center rounded-lg border border-dashed border-slate-800 bg-slate-950/50 px-6 py-12 text-center">
          <CheckCircle2 className="h-8 w-8 text-emerald-500/70" />
          <p className="mt-3 text-sm font-medium text-slate-300">
            No incidents recorded
          </p>
          <p className="mt-1 max-w-md text-xs text-slate-500">
            All monitored services are operating nominally. Outages will appear here automatically if a monitor breaches its failure threshold.
          </p>
        </div>
      ) : (
        <div className="mt-6 space-y-3">
          {incidents.map((incident) => {
            const isBusy = busyId === incident.id
            return (
              <div
                key={incident.id}
                className="flex flex-col justify-between gap-4 rounded-xl border border-slate-800 bg-slate-950/60 p-4 sm:flex-row sm:items-center"
              >
                <div className="space-y-1.5">
                  <div className="flex flex-wrap items-center gap-2.5">
                    <IncidentStatusBadge status={incident.status} />
                    <span className="text-sm font-semibold text-slate-100">
                      {incident.monitorName}
                    </span>
                  </div>
                  <p className="text-xs text-slate-300">{incident.summary}</p>
                  <div className="flex flex-wrap items-center gap-4 pt-1 font-mono text-[11px] text-slate-500">
                    <span className="inline-flex items-center gap-1">
                      <Clock className="h-3 w-3" />
                      Started: {formatDateTime(incident.startedAt)}
                    </span>
                    {incident.acknowledgedAt && (
                      <span>
                        Acknowledged: {formatDateTime(incident.acknowledgedAt)}
                      </span>
                    )}
                    {incident.resolvedAt && (
                      <span>Resolved: {formatDateTime(incident.resolvedAt)}</span>
                    )}
                  </div>
                </div>

                {incident.status === 'OPEN' && (
                  <button
                    type="button"
                    disabled={isBusy}
                    onClick={() => void handleAcknowledge(incident.id)}
                    className="inline-flex shrink-0 items-center gap-1.5 self-start rounded-lg border border-amber-500/40 bg-amber-500/10 px-3.5 py-2 text-xs font-semibold text-amber-200 transition hover:bg-amber-500/20 disabled:cursor-not-allowed disabled:opacity-60 sm:self-center"
                  >
                    {isBusy ? (
                      <Loader2 className="h-3.5 w-3.5 animate-spin" />
                    ) : (
                      <Check className="h-3.5 w-3.5" />
                    )}
                    <span>Acknowledge</span>
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

function IncidentStatusBadge({ status }: { status: IncidentStatus }) {
  if (status === 'OPEN') {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-full border border-rose-500/30 bg-rose-500/10 px-2.5 py-0.5 text-xs font-medium text-rose-300">
        <AlertTriangle className="h-3 w-3" />
        OPEN
      </span>
    )
  }

  if (status === 'ACKNOWLEDGED') {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-full border border-amber-500/30 bg-amber-500/10 px-2.5 py-0.5 text-xs font-medium text-amber-300">
        <Clock className="h-3 w-3" />
        ACKNOWLEDGED
      </span>
    )
  }

  return (
    <span className="inline-flex items-center gap-1.5 rounded-full border border-emerald-500/30 bg-emerald-500/10 px-2.5 py-0.5 text-xs font-medium text-emerald-300">
      <CheckCircle2 className="h-3 w-3" />
      RESOLVED
    </span>
  )
}
