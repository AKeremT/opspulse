import {
  ApiClientError,
  type ApiErrorPayload,
  type AuthResponse,
  type AuthSession,
  type CreateWorkspaceRequest,
  type LoginRequest,
  type RefreshTokenRequest,
  type RegisterRequest,
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
