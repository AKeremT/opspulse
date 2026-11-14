import { useState, type FormEvent } from 'react'
import { Activity, AlertCircle, Loader2, Lock, Mail, User } from 'lucide-react'
import { authApi, saveSession, saveStoredWorkspaceId, toAuthSession } from '../api/client'
import { ApiClientError, type AuthResponse } from '../api/types'

type AuthMode = 'login' | 'register'

interface AuthPageProps {
  onAuthSuccess: (response: AuthResponse) => void
}

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export function AuthPage({ onAuthSuccess }: AuthPageProps) {
  const [mode, setMode] = useState<AuthMode>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [fullName, setFullName] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [apiError, setApiError] = useState<ApiClientError | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  function switchMode(nextMode: AuthMode): void {
    setMode(nextMode)
    setApiError(null)
    setFieldErrors({})
  }

  function validateForm(): boolean {
    const errors: Record<string, string> = {}
    const trimmedEmail = email.trim()

    if (!trimmedEmail) {
      errors.email = 'Email is required'
    } else if (!EMAIL_PATTERN.test(trimmedEmail)) {
      errors.email = 'Email must be a valid email address'
    }

    if (!password) {
      errors.password = 'Password is required'
    } else if (mode === 'register' && password.length < 8) {
      errors.password = 'Password must be at least 8 characters'
    } else if (password.length > 128) {
      errors.password = 'Password must not exceed 128 characters'
    }

    if (mode === 'register') {
      const trimmedName = fullName.trim()
      if (!trimmedName) {
        errors.fullName = 'Full name is required'
      } else if (trimmedName.length < 2 || trimmedName.length > 120) {
        errors.fullName = 'Full name must be between 2 and 120 characters'
      }
    }

    setFieldErrors(errors)
    return Object.keys(errors).length === 0
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    setApiError(null)

    if (!validateForm()) {
      return
    }

    setIsSubmitting(true)
    try {
      const response =
        mode === 'login'
          ? await authApi.login({
              email: email.trim(),
              password,
            })
          : await authApi.register({
              email: email.trim(),
              password,
              fullName: fullName.trim(),
            })

      saveSession(toAuthSession(response))
      if (response.defaultWorkspace?.id) {
        saveStoredWorkspaceId(response.defaultWorkspace.id)
      }
      onAuthSuccess(response)
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
            message: 'Unable to reach the OpsPulse server. Please try again.',
          }),
        )
      }
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <div className="flex flex-1 items-center justify-center px-4 py-12 sm:px-6 lg:px-8">
      <div className="w-full max-w-md space-y-6 rounded-2xl border border-slate-800 bg-slate-900/70 p-8 shadow-2xl shadow-black/40">
        <div className="text-center">
          <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-xl bg-emerald-500/10 ring-1 ring-emerald-500/30">
            <Activity className="h-6 w-6 text-emerald-400" />
          </div>
          <h2 className="mt-4 text-xl font-semibold tracking-tight text-slate-100">
            {mode === 'login' ? 'Sign in to OpsPulse' : 'Create your OpsPulse account'}
          </h2>
          <p className="mt-1 text-sm text-slate-400">
            {mode === 'login'
              ? 'Monitor endpoints, track uptime, and manage incidents.'
              : 'Get started with a personal workspace in seconds.'}
          </p>
        </div>

        {/* Mode Switcher */}
        <div
          role="tablist"
          aria-label="Authentication mode"
          className="grid grid-cols-2 rounded-lg border border-slate-800 bg-slate-950 p-1"
        >
          <button
            type="button"
            role="tab"
            aria-selected={mode === 'login'}
            onClick={() => switchMode('login')}
            className={`rounded-md py-2 text-xs font-medium transition ${
              mode === 'login'
                ? 'bg-slate-800 text-slate-100 shadow-sm'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            Sign In
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={mode === 'register'}
            onClick={() => switchMode('register')}
            className={`rounded-md py-2 text-xs font-medium transition ${
              mode === 'register'
                ? 'bg-slate-800 text-slate-100 shadow-sm'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            Create Account
          </button>
        </div>

        {/* Structured ApiError Banner */}
        {apiError && (
          <div
            role="alert"
            className="rounded-lg border border-rose-500/30 bg-rose-500/10 p-3.5 text-sm text-rose-200"
          >
            <div className="flex items-start gap-2.5">
              <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-rose-400" />
              <div className="space-y-1">
                <p className="font-medium text-rose-100">
                  {apiError.error}
                  {apiError.status > 0 ? ` (${apiError.status})` : ''}
                </p>
                <p className="text-xs text-rose-200/90">{apiError.message}</p>
              </div>
            </div>
          </div>
        )}

        <form onSubmit={(e) => void handleSubmit(e)} noValidate className="space-y-4">
          {mode === 'register' && (
            <div>
              <label
                htmlFor="auth-full-name"
                className="block text-xs font-medium uppercase tracking-wider text-slate-400"
              >
                Full Name
              </label>
              <div className="relative mt-1.5">
                <User className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-500" />
                <input
                  id="auth-full-name"
                  type="text"
                  autoComplete="name"
                  value={fullName}
                  onChange={(e) => setFullName(e.target.value)}
                  placeholder="Jane Doe"
                  className="w-full rounded-lg border border-slate-800 bg-slate-950 py-2.5 pl-9 pr-3 text-sm text-slate-100 placeholder:text-slate-600 focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
                />
              </div>
              {fieldErrors.fullName && (
                <p className="mt-1 text-xs text-rose-400">{fieldErrors.fullName}</p>
              )}
            </div>
          )}

          <div>
            <label
              htmlFor="auth-email"
              className="block text-xs font-medium uppercase tracking-wider text-slate-400"
            >
              Email Address
            </label>
            <div className="relative mt-1.5">
              <Mail className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-500" />
              <input
                id="auth-email"
                type="email"
                autoComplete="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="you@company.com"
                className="w-full rounded-lg border border-slate-800 bg-slate-950 py-2.5 pl-9 pr-3 text-sm text-slate-100 placeholder:text-slate-600 focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
              />
            </div>
            {fieldErrors.email && (
              <p className="mt-1 text-xs text-rose-400">{fieldErrors.email}</p>
            )}
          </div>

          <div>
            <label
              htmlFor="auth-password"
              className="block text-xs font-medium uppercase tracking-wider text-slate-400"
            >
              Password
            </label>
            <div className="relative mt-1.5">
              <Lock className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-500" />
              <input
                id="auth-password"
                type="password"
                autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder={mode === 'register' ? 'Minimum 8 characters' : 'Enter your password'}
                className="w-full rounded-lg border border-slate-800 bg-slate-950 py-2.5 pl-9 pr-3 text-sm text-slate-100 placeholder:text-slate-600 focus:border-emerald-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
              />
            </div>
            {fieldErrors.password && (
              <p className="mt-1 text-xs text-rose-400">{fieldErrors.password}</p>
            )}
          </div>

          <button
            type="submit"
            disabled={isSubmitting}
            className="flex w-full items-center justify-center gap-2 rounded-lg bg-emerald-500 px-4 py-2.5 text-sm font-semibold text-slate-950 transition hover:bg-emerald-400 focus:outline-none focus:ring-2 focus:ring-emerald-500/50 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {isSubmitting && <Loader2 className="h-4 w-4 animate-spin" />}
            <span>{mode === 'login' ? 'Sign In' : 'Create Account'}</span>
          </button>
        </form>
      </div>
    </div>
  )
}
