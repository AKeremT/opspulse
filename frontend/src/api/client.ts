import {
  ApiClientError,
  type AlertChannelResponse,
  type ApiErrorPayload,
  type AuthResponse,
  type AuthSession,
  type CreateAlertChannelRequest,
  type CreateMonitorRequest,
  type CreateWorkspaceRequest,
  type IncidentChangedEvent,
  type IncidentResponse,
  type LoginRequest,
  type MonitorCheckResponse,
  type MonitorCheckedEvent,
  type MonitorResponse,
  type PauseMonitorRequest,
  type PublicStatusResponse,
  type RefreshTokenRequest,
  type RegisterRequest,
  type StatusPageSettingsResponse,
  type UpdateStatusPageRequest,
  type WorkspaceResponse,
} from './types'

const SESSION_STORAGE_KEY = 'opspulse.session'
const ACTIVE_WORKSPACE_STORAGE_KEY = 'opspulse.activeWorkspaceId'

type SessionClearedListener = () => void
const sessionClearedListeners = new Set<SessionClearedListener>()

let activeRefreshPromise: Promise<AuthSession | null> | null = null

export function subscribeSessionCleared(listener: SessionClearedListener): () => void {
  sessionClearedListeners.add(listener)
  return () => {
    sessionClearedListeners.delete(listener)
  }
}

function notifySessionCleared(): void {
  for (const listener of sessionClearedListeners) {
    listener()
  }
}

export function getStoredSession(): AuthSession | null {
  try {
    const raw = window.localStorage.getItem(SESSION_STORAGE_KEY)
    if (!raw) {
      return null
    }
    const parsed = JSON.parse(raw) as Partial<AuthSession>
    if (!parsed.accessToken || !parsed.refreshToken || !parsed.user) {
      return null
    }
    return parsed as AuthSession
  } catch {
    return null
  }
}

export function saveSession(session: AuthSession): void {
  window.localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session))
}

export function clearStoredSession(): void {
  window.localStorage.removeItem(SESSION_STORAGE_KEY)
  window.localStorage.removeItem(ACTIVE_WORKSPACE_STORAGE_KEY)
}

export function getStoredWorkspaceId(): string | null {
  return window.localStorage.getItem(ACTIVE_WORKSPACE_STORAGE_KEY)
}

export function saveStoredWorkspaceId(workspaceId: string): void {
  window.localStorage.setItem(ACTIVE_WORKSPACE_STORAGE_KEY, workspaceId)
}

export function toAuthSession(response: AuthResponse): AuthSession {
  return {
    accessToken: response.accessToken,
    refreshToken: response.refreshToken,
    tokenType: response.tokenType,
    expiresInMs: response.expiresInMs,
    user: response.user,
  }
}

async function parseErrorResponse(response: Response): Promise<ApiClientError> {
  try {
    const body = (await response.json()) as Partial<ApiErrorPayload>
    if (body && typeof body.message === 'string') {
      return new ApiClientError({
        status: typeof body.status === 'number' ? body.status : response.status,
        error: typeof body.error === 'string' ? body.error : response.statusText,
        message: body.message,
        path: typeof body.path === 'string' ? body.path : '',
        fieldErrors: body.fieldErrors ?? {},
      })
    }
  } catch {
    // Response body is not valid JSON; fall back to HTTP status text.
  }

  return new ApiClientError({
    status: response.status,
    error: response.statusText || 'Request Failed',
    message: `Request failed with status ${response.status}`,
  })
}

async function refreshSessionSingleFlight(): Promise<AuthSession | null> {
  if (activeRefreshPromise) {
    return activeRefreshPromise
  }

  activeRefreshPromise = (async () => {
    const currentSession = getStoredSession()
    if (!currentSession?.refreshToken) {
      clearStoredSession()
      notifySessionCleared()
      return null
    }

    try {
      const response = await fetch('/api/v1/auth/refresh', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json',
        },
        body: JSON.stringify({
          refreshToken: currentSession.refreshToken,
        } satisfies RefreshTokenRequest),
      })

      if (!response.ok) {
        clearStoredSession()
        notifySessionCleared()
        return null
      }

      const authResponse = (await response.json()) as AuthResponse
      const nextSession = toAuthSession(authResponse)
      saveSession(nextSession)
      return nextSession
    } catch {
      clearStoredSession()
      notifySessionCleared()
      return null
    } finally {
      activeRefreshPromise = null
    }
  })()

  return activeRefreshPromise
}

interface RequestOptions extends RequestInit {
  skipAuth?: boolean
  retryOnUnauthorized?: boolean
}

async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const {
    skipAuth = false,
    retryOnUnauthorized = true,
    headers: customHeaders,
    ...fetchOptions
  } = options

  const headers = new Headers(customHeaders)
  headers.set('Accept', 'application/json')
  if (fetchOptions.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  if (!skipAuth) {
    const session = getStoredSession()
    if (session?.accessToken) {
      headers.set('Authorization', `Bearer ${session.accessToken}`)
    }
  }

  const response = await fetch(path, {
    ...fetchOptions,
    headers,
  })

  if (response.status === 401 && !skipAuth && retryOnUnauthorized) {
    const refreshed = await refreshSessionSingleFlight()
    if (refreshed) {
      const retryHeaders = new Headers(headers)
      retryHeaders.set('Authorization', `Bearer ${refreshed.accessToken}`)

      const retryResponse = await fetch(path, {
        ...fetchOptions,
        headers: retryHeaders,
      })

      if (!retryResponse.ok) {
        throw await parseErrorResponse(retryResponse)
      }

      if (retryResponse.status === 204) {
        return undefined as T
      }

      return (await retryResponse.json()) as T
    }
  }

  if (!response.ok) {
    throw await parseErrorResponse(response)
  }

  if (response.status === 204) {
    return undefined as T
  }

  return (await response.json()) as T
}

export const authApi = {
  register(payload: RegisterRequest): Promise<AuthResponse> {
    return apiRequest<AuthResponse>('/api/v1/auth/register', {
      method: 'POST',
      body: JSON.stringify(payload),
      skipAuth: true,
      retryOnUnauthorized: false,
    })
  },

  login(payload: LoginRequest): Promise<AuthResponse> {
    return apiRequest<AuthResponse>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify(payload),
      skipAuth: true,
      retryOnUnauthorized: false,
    })
  },

  refresh(payload: RefreshTokenRequest): Promise<AuthResponse> {
    return apiRequest<AuthResponse>('/api/v1/auth/refresh', {
      method: 'POST',
      body: JSON.stringify(payload),
      skipAuth: true,
      retryOnUnauthorized: false,
    })
  },

  logout(payload: RefreshTokenRequest): Promise<void> {
    return apiRequest<void>('/api/v1/auth/logout', {
      method: 'POST',
      body: JSON.stringify(payload),
      skipAuth: true,
      retryOnUnauthorized: false,
    })
  },
}

export const workspaceApi = {
  list(): Promise<WorkspaceResponse[]> {
    return apiRequest<WorkspaceResponse[]>('/api/v1/workspaces', {
      method: 'GET',
    })
  },

  create(payload: CreateWorkspaceRequest): Promise<WorkspaceResponse> {
    return apiRequest<WorkspaceResponse>('/api/v1/workspaces', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },
}

export const monitorApi = {
  list(workspaceId: string): Promise<MonitorResponse[]> {
    return apiRequest<MonitorResponse[]>(
      `/api/v1/workspaces/${workspaceId}/monitors`,
      { method: 'GET' },
    )
  },

  create(
    workspaceId: string,
    payload: CreateMonitorRequest,
  ): Promise<MonitorResponse> {
    return apiRequest<MonitorResponse>(
      `/api/v1/workspaces/${workspaceId}/monitors`,
      {
        method: 'POST',
        body: JSON.stringify(payload),
      },
    )
  },

  setPaused(
    workspaceId: string,
    monitorId: string,
    paused: boolean,
  ): Promise<MonitorResponse> {
    return apiRequest<MonitorResponse>(
      `/api/v1/workspaces/${workspaceId}/monitors/${monitorId}/pause`,
      {
        method: 'PATCH',
        body: JSON.stringify({ paused } satisfies PauseMonitorRequest),
      },
    )
  },

  delete(workspaceId: string, monitorId: string): Promise<void> {
    return apiRequest<void>(
      `/api/v1/workspaces/${workspaceId}/monitors/${monitorId}`,
      { method: 'DELETE' },
    )
  },

  listChecks(
    workspaceId: string,
    monitorId: string,
  ): Promise<MonitorCheckResponse[]> {
    return apiRequest<MonitorCheckResponse[]>(
      `/api/v1/workspaces/${workspaceId}/monitors/${monitorId}/checks`,
      { method: 'GET' },
    )
  },
}

export const incidentApi = {
  list(workspaceId: string): Promise<IncidentResponse[]> {
    return apiRequest<IncidentResponse[]>(
      `/api/v1/workspaces/${workspaceId}/incidents`,
      { method: 'GET' },
    )
  },

  acknowledge(
    workspaceId: string,
    incidentId: string,
  ): Promise<IncidentResponse> {
    return apiRequest<IncidentResponse>(
      `/api/v1/workspaces/${workspaceId}/incidents/${incidentId}/acknowledge`,
      { method: 'POST' },
    )
  },
}

export const alertChannelApi = {
  list(workspaceId: string): Promise<AlertChannelResponse[]> {
    return apiRequest<AlertChannelResponse[]>(
      `/api/v1/workspaces/${workspaceId}/alert-channels`,
      { method: 'GET' },
    )
  },

  create(
    workspaceId: string,
    payload: CreateAlertChannelRequest,
  ): Promise<AlertChannelResponse> {
    return apiRequest<AlertChannelResponse>(
      `/api/v1/workspaces/${workspaceId}/alert-channels`,
      {
        method: 'POST',
        body: JSON.stringify(payload),
      },
    )
  },

  delete(workspaceId: string, channelId: string): Promise<void> {
    return apiRequest<void>(
      `/api/v1/workspaces/${workspaceId}/alert-channels/${channelId}`,
      { method: 'DELETE' },
    )
  },
}

export const statusPageApi = {
  getSettings(workspaceId: string): Promise<StatusPageSettingsResponse> {
    return apiRequest<StatusPageSettingsResponse>(
      `/api/v1/workspaces/${workspaceId}/status-page`,
      { method: 'GET' },
    )
  },

  updateSettings(
    workspaceId: string,
    payload: UpdateStatusPageRequest,
  ): Promise<StatusPageSettingsResponse> {
    return apiRequest<StatusPageSettingsResponse>(
      `/api/v1/workspaces/${workspaceId}/status-page`,
      {
        method: 'PUT',
        body: JSON.stringify(payload),
      },
    )
  },

  getPublicStatus(slug: string): Promise<PublicStatusResponse> {
    return apiRequest<PublicStatusResponse>(
      `/api/v1/public/status/${encodeURIComponent(slug)}`,
      {
        method: 'GET',
        skipAuth: true,
        retryOnUnauthorized: false,
      },
    )
  },
}

export interface WorkspaceEventHandlers {
  onConnected?: () => void
  onDisconnected?: () => void
  onMonitorChecked?: (event: MonitorCheckedEvent) => void
  onIncidentChanged?: (event: IncidentChangedEvent) => void
}

function dispatchSseFrame(
  rawFrame: string,
  handlers: WorkspaceEventHandlers,
): void {
  const lines = rawFrame.split('\n')
  let eventName = 'message'
  const dataLines: string[] = []

  for (const line of lines) {
    if (!line || line.startsWith(':')) {
      continue
    }
    if (line.startsWith('event:')) {
      eventName = line.slice('event:'.length).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trimStart())
    }
  }

  if (eventName === 'connected') {
    handlers.onConnected?.()
    return
  }

  if (dataLines.length === 0) {
    return
  }

  const rawData = dataLines.join('\n')
  try {
    if (eventName === 'monitor.checked') {
      handlers.onMonitorChecked?.(JSON.parse(rawData) as MonitorCheckedEvent)
    } else if (eventName === 'incident.changed') {
      handlers.onIncidentChanged?.(JSON.parse(rawData) as IncidentChangedEvent)
    }
  } catch {
    // Ignore malformed SSE JSON payload rather than crashing the stream.
  }
}

export async function subscribeWorkspaceEvents(
  workspaceId: string,
  handlers: WorkspaceEventHandlers,
  signal: AbortSignal,
): Promise<void> {
  const openStream = async (accessToken: string): Promise<Response> =>
    fetch(`/api/v1/workspaces/${workspaceId}/events`, {
      method: 'GET',
      headers: {
        Accept: 'text/event-stream',
        Authorization: `Bearer ${accessToken}`,
      },
      signal,
    })

  let session = getStoredSession()
  if (!session?.accessToken) {
    handlers.onDisconnected?.()
    return
  }

  let response = await openStream(session.accessToken)
  if (response.status === 401) {
    session = await refreshSessionSingleFlight()
    if (!session?.accessToken) {
      handlers.onDisconnected?.()
      return
    }
    response = await openStream(session.accessToken)
  }

  if (!response.ok || !response.body) {
    handlers.onDisconnected?.()
    return
  }

  handlers.onConnected?.()

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  try {
    while (!signal.aborted) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }

      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
      let boundaryIndex = buffer.indexOf('\n\n')
      while (boundaryIndex !== -1) {
        const frame = buffer.slice(0, boundaryIndex)
        buffer = buffer.slice(boundaryIndex + 2)
        if (frame.trim()) {
          dispatchSseFrame(frame, handlers)
        }
        boundaryIndex = buffer.indexOf('\n\n')
      }
    }
  } catch (error) {
    if (!(error instanceof DOMException && error.name === 'AbortError')) {
      handlers.onDisconnected?.()
    }
  } finally {
    reader.releaseLock()
    if (!signal.aborted) {
      handlers.onDisconnected?.()
    }
  }
}
