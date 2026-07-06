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
let gatewayStarting = false  // mutex — prevents concurrent spawn races

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
   * Waits up to 4 s for the port to open before responding, so the client
   * gets a meaningful success/failure rather than an optimistic "starting…".
   */
  app.post('/api/nastech/gateway/start', async (_req, res) => {
    // Already open (could be Android-started)
    if (await isPortOpen(NASTECH_PORT)) {
      return res.json({ ok: true, message: `Gateway already running on :${NASTECH_PORT}` })
    }

    // Mutex — reject concurrent start requests
    if (gatewayStarting || isGatewayAlive()) {
      return res.json({ ok: true, message: 'Gateway start already in progress…' })
    }

    gatewayStarting = true
    try {
      const nastechBin = process.env['NASTECH_BIN'] ?? 'nastech'

      // Wrap spawn in a promise so we can catch synchronous AND first-tick errors
      const spawnResult = await new Promise<{ ok: boolean; error?: string }>((resolve) => {
        let settled = false
        const proc = spawn(nastechBin, ['gateway'], {
          detached: false,
          env: { ...process.env, NASTECH_GATEWAY_PORT: String(NASTECH_PORT) },
          stdio: 'ignore',
        })

        // Error event fires when the binary can't be found / exec fails
        proc.once('error', (err) => {
          if (!settled) { settled = true; resolve({ ok: false, error: err.message }) }
          nastechGatewayProc = null
        })

        // Immediate exit (bad binary, permission error)
        proc.once('exit', (code) => {
          console.log('[nastech] gateway exited with code', code)
          if (!settled && code !== 0) {
            settled = true
            resolve({ ok: false, error: `Process exited with code ${code ?? 'null'}` })
          }
          nastechGatewayProc = null
        })

        nastechGatewayProc = proc

        // If no error within 500 ms, assume spawn succeeded
        setTimeout(() => {
          if (!settled) { settled = true; resolve({ ok: true }) }
        }, 500)
      })

      if (!spawnResult.ok) {
        nastechGatewayProc = null
        return res.status(500).json({ ok: false, error: spawnResult.error ?? 'Spawn failed' })
      }

      // Wait up to 4 s for the port to open and give a definitive answer
      let running = false
      for (let i = 0; i < 8; i++) {
        await new Promise(r => setTimeout(r, 500))
        if (await isPortOpen(NASTECH_PORT)) { running = true; break }
      }

      return res.json({
        ok: running,
        message: running
          ? `Gateway listening on :${NASTECH_PORT}`
          : 'Gateway started but port not open yet — give it a moment',
      })
    } catch (err) {
      nastechGatewayProc = null
      return res.status(500).json({ ok: false, error: String(err) })
    } finally {
      gatewayStarting = false
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
   * Reports failure if the binary can't be launched at all.
   */
  app.post('/api/nastech/setup', (_req, res) => {
    const nastechBin = process.env['NASTECH_BIN'] ?? 'nastech'
    let settled = false

    const proc = spawn(nastechBin, ['setup', '--skip-gateway', '--non-interactive'], {
      detached: true,
      env: {
        ...process.env,
        NASTECH_HOME: process.env['NASTECH_HOME'] ?? `${process.env['HOME'] ?? '/root'}/.nastech`,
      },
      stdio: 'ignore',
    })

    proc.once('error', (err) => {
      if (!settled) {
        settled = true
        res.status(500).json({ ok: false, error: `Cannot launch nastech: ${err.message}` })
      }
    })

    // Give the process 400 ms to fail; if it hasn't, assume it's running fine
    setTimeout(() => {
      if (!settled) {
        settled = true
        proc.unref()
        res.json({
          ok: true,
          message: 'Setup running in background — config saves to ~/.nastech/',
        })
      }
    }, 400)
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
