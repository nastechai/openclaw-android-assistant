import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { createConnection } from 'node:net'
import { spawn, type ChildProcess } from 'node:child_process'
import express, { type Express } from 'express'
import { createCodexBridgeMiddleware } from './codexAppServerBridge.js'
import { createAuthMiddleware } from './authMiddleware.js'

const __dirname = dirname(fileURLToPath(import.meta.url))
const distDir = join(__dirname, '..', 'dist')

export type ServerOptions = {
  password?: string
}

export type ServerInstance = {
  app: Express
  dispose: () => void
}

const NASTECH_PORT = 9119

// ── Nastech gateway process (owned by this server instance) ───────────────
let nastechGatewayProc: ChildProcess | null = null

function isPortOpen(port: number, host = '127.0.0.1'): Promise<boolean> {
  return new Promise((resolve) => {
    const socket = createConnection({ port, host })
    const done = (result: boolean) => {
      socket.destroy()
      resolve(result)
    }
    socket.once('connect', () => done(true))
    socket.once('error', () => done(false))
    socket.setTimeout(1500, () => done(false))
  })
}

function isGatewayAlive(): boolean {
  if (!nastechGatewayProc) return false
  try {
    // process.kill(pid, 0) throws if the process is gone
    if (nastechGatewayProc.pid !== undefined) {
      process.kill(nastechGatewayProc.pid, 0)
      return true
    }
  } catch {
    nastechGatewayProc = null
  }
  return false
}

export function createServer(options: ServerOptions = {}): ServerInstance {
  const app = express()
  const bridge = createCodexBridgeMiddleware()

  // 1. Parse JSON bodies for POST endpoints
  app.use(express.json())

  // 2. Auth middleware (if password is set)
  if (options.password) {
    app.use(createAuthMiddleware(options.password))
  }

  // 3. Bridge middleware for /codex-api/*
  app.use(bridge)

  // ── Nastech API ────────────────────────────────────────────────────────

  /**
   * GET /api/nastech/status
   * Returns whether the Nastech gateway is reachable on NASTECH_PORT.
   */
  app.get('/api/nastech/status', async (_req, res) => {
    const running = await isPortOpen(NASTECH_PORT)
    res.json({
      running,
      port: NASTECH_PORT,
      url: `http://127.0.0.1:${NASTECH_PORT}`,
      dashboardUrl: `http://localhost:${NASTECH_PORT}`,
      ownedByServer: isGatewayAlive(),
    })
  })

  /**
   * POST /api/nastech/gateway/start
   * Starts `nastech gateway` as a child process if not already running.
   */
  app.post('/api/nastech/gateway/start', async (_req, res) => {
    // If port is already open (started by Android), report success immediately
    const alreadyOpen = await isPortOpen(NASTECH_PORT)
    if (alreadyOpen) {
      return res.json({ ok: true, message: `Gateway already running on :${NASTECH_PORT}` })
    }

    if (isGatewayAlive()) {
      return res.json({ ok: true, message: 'Gateway starting up…' })
    }

    try {
      const nastechBin = process.env['NASTECH_BIN'] ?? 'nastech'
      const proc = spawn(nastechBin, ['gateway'], {
        detached: false,
        env: { ...process.env, NASTECH_GATEWAY_PORT: String(NASTECH_PORT) },
        stdio: 'ignore',
      })
      proc.once('error', (err) => {
        console.error('[nastech] spawn error:', err.message)
        nastechGatewayProc = null
      })
      proc.once('exit', (code) => {
        console.log('[nastech] gateway exited with code', code)
        nastechGatewayProc = null
      })
      nastechGatewayProc = proc
      return res.json({ ok: true, message: 'Gateway starting…' })
    } catch (err) {
      return res.status(500).json({ ok: false, error: String(err) })
    }
  })

  /**
   * POST /api/nastech/gateway/stop
   * Stops the gateway process owned by this server.
   * If the process was started by Android, signals it to terminate.
   */
  app.post('/api/nastech/gateway/stop', async (_req, res) => {
    if (nastechGatewayProc && isGatewayAlive()) {
      nastechGatewayProc.kill('SIGTERM')
      nastechGatewayProc = null
      return res.json({ ok: true, message: 'Gateway stopped' })
    }
    // Gateway may be owned by Android; we can't stop it from here
    const open = await isPortOpen(NASTECH_PORT)
    if (!open) {
      return res.json({ ok: true, message: 'Gateway is not running' })
    }
    return res.status(409).json({
      ok: false,
      error: 'Gateway was started by the Android app — use the app restart to stop it',
    })
  })

  /**
   * POST /api/nastech/setup
   * Runs `nastech setup --skip-gateway` in the background.
   * Non-interactive flags are used since there is no terminal.
   */
  app.post('/api/nastech/setup', (_req, res) => {
    try {
      const nastechBin = process.env['NASTECH_BIN'] ?? 'nastech'
      const proc = spawn(nastechBin, ['setup', '--skip-gateway', '--non-interactive'], {
        detached: true,
        env: { ...process.env, NASTECH_HOME: process.env['NASTECH_HOME'] ?? `${process.env['HOME']}/.nastech` },
        stdio: 'ignore',
      })
      proc.unref()
      return res.json({
        ok: true,
        message: 'Setup running in background — check ~/.nastech/ for config files',
      })
    } catch (err) {
      return res.status(500).json({ ok: false, error: String(err) })
    }
  })

  // 4. Static files from Vue build
  app.use(express.static(distDir))

  // 5. SPA fallback
  app.use((_req, res) => {
    res.sendFile(join(distDir, 'index.html'))
  })

  return {
    app,
    dispose: () => {
      bridge.dispose()
      nastechGatewayProc?.kill('SIGTERM')
      nastechGatewayProc = null
    },
  }
}
