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

export type MonitorHttpMethod = 'GET' | 'HEAD' | 'POST'
export type MonitorStatus = 'UNKNOWN' | 'UP' | 'DOWN'

export interface MonitorResponse {
  id: string
  workspaceId: string
  name: string
  url: string
  httpMethod: MonitorHttpMethod
  intervalSeconds: number
  timeoutMs: number
  expectedStatus: number
  failureThreshold: number
  consecutiveFailures: number
  currentStatus: MonitorStatus
  paused: boolean
  nextCheckAt: string | null
  lastCheckedAt: string | null
  lastLatencyMs: number | null
  createdAt: string
}

export interface CreateMonitorRequest {
  name: string
  url: string
  httpMethod?: MonitorHttpMethod
  intervalSeconds?: number
  timeoutMs?: number
  expectedStatus?: number
  failureThreshold?: number
}

export interface PauseMonitorRequest {
  paused: boolean
}

export interface MonitorCheckResponse {
  id: string
  monitorId: string
  status: MonitorStatus
  statusCode: number | null
  latencyMs: number | null
  errorMessage: string | null
  checkedAt: string
}

export type IncidentStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED'

export interface IncidentResponse {
  id: string
  monitorId: string
  monitorName: string
  workspaceId: string
  status: IncidentStatus
  startedAt: string
  acknowledgedAt: string | null
  resolvedAt: string | null
  summary: string
}

export type AlertChannelType = 'WEBHOOK'

export interface AlertChannelResponse {
  id: string
  workspaceId: string
  name: string
  channelType: AlertChannelType
  targetUrl: string
  enabled: boolean
  createdAt: string
}

export interface CreateAlertChannelRequest {
  name: string
  channelType?: AlertChannelType
  targetUrl: string
}

export interface MonitorCheckedEvent {
  monitorId: string
  workspaceId: string
  monitorName: string
  status: MonitorStatus
  statusCode: number | null
  latencyMs: number
  consecutiveFailures: number
  checkedAt: string
}

export interface IncidentChangedEvent {
  incidentId: string
  monitorId: string
  workspaceId: string
  monitorName: string
  status: IncidentStatus
  summary: string
  startedAt: string
  acknowledgedAt: string | null
  resolvedAt: string | null
}

