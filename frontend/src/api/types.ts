export type WorkspaceRole = 'OWNER' | 'ADMIN' | 'MEMBER' | 'VIEWER'

export interface ApiErrorPayload {
  timestamp: string
  status: number
  error: string
  message: string
  path: string
  fieldErrors?: Record<string, string>
}

export class ApiClientError extends Error {
  readonly status: number
  readonly error: string
  readonly path: string
  readonly fieldErrors: Record<string, string>

  constructor(payload: {
    status: number
    error: string
    message: string
    path?: string
    fieldErrors?: Record<string, string>
  }) {
    super(payload.message)
    this.name = 'ApiClientError'
    this.status = payload.status
    this.error = payload.error
    this.path = payload.path ?? ''
    this.fieldErrors = payload.fieldErrors ?? {}
  }
}

export interface UserSummary {
  id: string
  email: string
  fullName: string
}

export interface WorkspaceResponse {
  id: string
  name: string
  slug: string
  role: WorkspaceRole
  createdAt: string
}

export interface CreateWorkspaceRequest {
  name: string
  slug?: string
}

export interface RegisterRequest {
  email: string
  password: string
  fullName: string
}

export interface LoginRequest {
  email: string
  password: string
}

export interface RefreshTokenRequest {
  refreshToken: string
}

export interface AuthResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresInMs: number
  user: UserSummary
  defaultWorkspace: WorkspaceResponse
}

export interface AuthSession {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresInMs: number
  user: UserSummary
}
