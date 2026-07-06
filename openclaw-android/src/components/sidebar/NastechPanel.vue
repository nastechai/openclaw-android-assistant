<template>
  <div v-if="!isCollapsed" class="nastech-panel">

    <!-- ── Section header ──────────────────────────────────────────── -->
    <div class="np-header">
      <span class="np-header-label">Nastech Agent</span>
      <span
        class="np-dot"
        :class="anyRunning ? 'np-dot--green' : 'np-dot--grey'"
        :title="anyRunning ? 'Gateway running' : 'Gateway stopped'"
      />
    </div>

    <!-- ── Dashboard row ───────────────────────────────────────────── -->
    <div class="np-row" :class="{ 'np-row--open': openCard === 'dashboard' }">
      <a
        class="np-row-main"
        :href="dashboardUrl"
        target="_blank"
        rel="noopener noreferrer"
        title="Open Nastech dashboard in browser"
      >
        <IconTablerExternalLink class="np-icon" />
        <span class="np-row-label">Dashboard</span>
      </a>
      <button
        class="np-dots-btn"
        type="button"
        aria-label="Dashboard options"
        @click.stop="toggleCard('dashboard')"
      >
        <IconTablerDots class="np-icon" />
      </button>
    </div>

    <Transition name="np-pop">
      <div v-if="openCard === 'dashboard'" class="np-popcard">
        <div class="np-popcard-head">
          <span class="np-popcard-title">Dashboard</span>
          <button class="np-close-btn" type="button" @click="openCard = null">
            <IconTablerX class="np-icon-sm" />
          </button>
        </div>
        <p class="np-popcard-desc">
          Nastech web UI on port {{ NASTECH_PORT }}.
        </p>
        <div class="np-status-row">
          <span class="np-dot" :class="dashRunning ? 'np-dot--green' : 'np-dot--grey'" />
          <span class="np-status-text">{{ dashRunning ? `Running on :${NASTECH_PORT}` : 'Not reachable' }}</span>
        </div>
        <div class="np-actions">
          <a
            class="np-btn np-btn--primary"
            :href="dashboardUrl"
            target="_blank"
            rel="noopener noreferrer"
          >Open ↗</a>
          <button class="np-btn np-btn--ghost" type="button" :disabled="statusLoading" @click="refreshStatus">
            Refresh
          </button>
        </div>
      </div>
    </Transition>

    <!-- ── Gateway row ─────────────────────────────────────────────── -->
    <div class="np-row" :class="{ 'np-row--open': openCard === 'gateway' }">
      <button
        class="np-row-main np-row-main--btn"
        type="button"
        title="Nastech MCP gateway"
        @click="toggleCard('gateway')"
      >
        <span class="np-dot np-dot--inline" :class="gatewayRunning ? 'np-dot--green' : 'np-dot--grey'" />
        <span class="np-row-label">Gateway</span>
        <span v-if="gatewayRunning" class="np-badge np-badge--green">running</span>
        <span v-else class="np-badge np-badge--grey">stopped</span>
      </button>
      <button
        class="np-dots-btn"
        type="button"
        aria-label="Gateway options"
        @click.stop="toggleCard('gateway')"
      >
        <IconTablerDots class="np-icon" />
      </button>
    </div>

    <Transition name="np-pop">
      <div v-if="openCard === 'gateway'" class="np-popcard">
        <div class="np-popcard-head">
          <span class="np-popcard-title">MCP Gateway</span>
          <button class="np-close-btn" type="button" @click="openCard = null">
            <IconTablerX class="np-icon-sm" />
          </button>
        </div>
        <p class="np-popcard-desc">
          Model Context Protocol server. Connects Nastech tools to AI agents on port {{ NASTECH_PORT }}.
        </p>
        <div class="np-status-row">
          <span class="np-dot" :class="gatewayRunning ? 'np-dot--green' : 'np-dot--grey'" />
          <span class="np-status-text">{{ gatewayStatusText }}</span>
        </div>
        <div v-if="gatewayError" class="np-error">{{ gatewayError }}</div>
        <div class="np-actions">
          <button
            v-if="!gatewayRunning"
            class="np-btn np-btn--green"
            type="button"
            :disabled="gatewayLoading"
            @click="startGateway"
          >
            {{ gatewayLoading ? 'Starting…' : '▶ Start' }}
          </button>
          <button
            v-else
            class="np-btn np-btn--red"
            type="button"
            :disabled="gatewayLoading"
            @click="stopGateway"
          >
            {{ gatewayLoading ? 'Stopping…' : '■ Stop' }}
          </button>
          <button class="np-btn np-btn--ghost" type="button" :disabled="statusLoading" @click="refreshStatus">
            Refresh
          </button>
        </div>
      </div>
    </Transition>

    <!-- ── Setup row ───────────────────────────────────────────────── -->
    <div class="np-row" :class="{ 'np-row--open': openCard === 'setup' }">
      <button
        class="np-row-main np-row-main--btn"
        type="button"
        title="Configure Nastech Agent"
        @click="toggleCard('setup')"
      >
        <IconTablerSettings class="np-icon" />
        <span class="np-row-label">Setup</span>
      </button>
      <button
        class="np-dots-btn"
        type="button"
        aria-label="Setup options"
        @click.stop="toggleCard('setup')"
      >
        <IconTablerDots class="np-icon" />
      </button>
    </div>

    <Transition name="np-pop">
      <div v-if="openCard === 'setup'" class="np-popcard">
        <div class="np-popcard-head">
          <span class="np-popcard-title">Setup & Config</span>
          <button class="np-close-btn" type="button" @click="openCard = null">
            <IconTablerX class="np-icon-sm" />
          </button>
        </div>
        <p class="np-popcard-desc">
          Configure API keys, models, and agent permissions. Saves to <code class="np-code">~/.nastech/</code>.
        </p>
        <div v-if="setupMessage" class="np-status-row">
          <span class="np-dot" :class="setupOk ? 'np-dot--green' : 'np-dot--amber'" />
          <span class="np-status-text">{{ setupMessage }}</span>
        </div>
        <div v-if="setupError" class="np-error">{{ setupError }}</div>
        <div class="np-actions">
          <button
            class="np-btn np-btn--primary"
            type="button"
            :disabled="setupLoading"
            @click="runSetup"
          >
            {{ setupLoading ? 'Running…' : '⚙ Run Setup' }}
          </button>
          <a
            class="np-btn np-btn--ghost"
            :href="`http://localhost:${NASTECH_PORT}/settings`"
            target="_blank"
            rel="noopener noreferrer"
          >Settings ↗</a>
        </div>
      </div>
    </Transition>

  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import IconTablerExternalLink from '../icons/IconTablerExternalLink.vue'
import IconTablerDots from '../icons/IconTablerDots.vue'
import IconTablerX from '../icons/IconTablerX.vue'
import IconTablerSettings from '../icons/IconTablerSettings.vue'

const props = defineProps<{ isCollapsed: boolean }>()

const NASTECH_PORT = 9119
const dashboardUrl = `http://localhost:${NASTECH_PORT}`

// ── State ──────────────────────────────────────────────────────────────────

type Card = 'dashboard' | 'gateway' | 'setup' | null
const openCard = ref<Card>(null)

const dashRunning    = ref(false)
const gatewayRunning = ref(false)
const statusLoading  = ref(false)
const gatewayLoading = ref(false)
const gatewayError   = ref('')
const setupLoading   = ref(false)
const setupMessage   = ref('')
const setupOk        = ref(false)
const setupError     = ref('')

const anyRunning = computed(() => gatewayRunning.value || dashRunning.value)

const gatewayStatusText = computed(() =>
  gatewayRunning.value ? `Listening on :${NASTECH_PORT}` : 'Not running'
)

let pollTimer: ReturnType<typeof setInterval> | null = null

// ── Lifecycle ──────────────────────────────────────────────────────────────

onMounted(() => {
  void refreshStatus()
  pollTimer = setInterval(() => { void refreshStatus() }, 8000)
  document.addEventListener('keydown', onEscape)
})

onUnmounted(() => {
  if (pollTimer !== null) clearInterval(pollTimer)
  document.removeEventListener('keydown', onEscape)
})

// ── Helpers ────────────────────────────────────────────────────────────────

function toggleCard(card: Card): void {
  openCard.value = openCard.value === card ? null : card
}

function onEscape(e: KeyboardEvent): void {
  if (e.key === 'Escape') openCard.value = null
}

async function refreshStatus(): Promise<void> {
  statusLoading.value = true
  try {
    const res = await fetch('/api/nastech/status')
    if (res.ok) {
      const data = await res.json() as { running: boolean; port: number }
      gatewayRunning.value = data.running
      dashRunning.value    = data.running
    }
  } catch {
    // server not reachable yet — leave state as-is
  } finally {
    statusLoading.value = false
  }
}

async function startGateway(): Promise<void> {
  gatewayError.value   = ''
  gatewayLoading.value = true
  try {
    const res = await fetch('/api/nastech/gateway/start', { method: 'POST' })
    const data = await res.json() as { ok: boolean; message?: string; error?: string }
    if (data.ok) {
      // wait a moment then re-probe
      await new Promise(r => setTimeout(r, 3000))
      await refreshStatus()
    } else {
      gatewayError.value = data.error ?? 'Failed to start gateway'
    }
  } catch (err) {
    gatewayError.value = String(err)
  } finally {
    gatewayLoading.value = false
  }
}

async function stopGateway(): Promise<void> {
  gatewayError.value   = ''
  gatewayLoading.value = true
  try {
    const res = await fetch('/api/nastech/gateway/stop', { method: 'POST' })
    const data = await res.json() as { ok: boolean; message?: string; error?: string }
    if (data.ok) {
      await new Promise(r => setTimeout(r, 1500))
      await refreshStatus()
    } else {
      gatewayError.value = data.error ?? 'Failed to stop gateway'
    }
  } catch (err) {
    gatewayError.value = String(err)
  } finally {
    gatewayLoading.value = false
  }
}

async function runSetup(): Promise<void> {
  setupError.value   = ''
  setupMessage.value = ''
  setupLoading.value = true
  try {
    const res = await fetch('/api/nastech/setup', { method: 'POST' })
    const data = await res.json() as { ok: boolean; message?: string; error?: string }
    if (data.ok) {
      setupOk.value      = true
      setupMessage.value = data.message ?? 'Setup started in background'
    } else {
      setupOk.value    = false
      setupError.value = data.error ?? 'Setup failed'
    }
  } catch (err) {
    setupError.value = String(err)
  } finally {
    setupLoading.value = false
  }
}

async function pingDashboard(): Promise<void> {
  await refreshStatus()
}
</script>

<style scoped>
/* ── Panel wrapper ──────────────────────────────────────────────────────── */
.nastech-panel {
  @apply mx-2 mb-1 rounded-lg border border-emerald-200 bg-emerald-50 overflow-hidden;
}

/* ── Section header ─────────────────────────────────────────────────────── */
.np-header {
  @apply flex items-center justify-between px-2.5 pt-2 pb-1;
}
.np-header-label {
  @apply text-[10px] font-semibold uppercase tracking-widest text-emerald-600;
}

/* ── Row ────────────────────────────────────────────────────────────────── */
.np-row {
  @apply flex items-center gap-0 mx-1 mb-0.5 rounded-md transition-colors;
}
.np-row:hover,
.np-row--open {
  @apply bg-emerald-100;
}
.np-row-main {
  @apply flex-1 flex items-center gap-2 px-2 py-1.5 text-sm font-medium text-emerald-800
         no-underline rounded-md transition-colors truncate;
}
.np-row-main--btn {
  @apply bg-transparent border-0 cursor-pointer text-left;
}
.np-row-main:hover {
  @apply text-emerald-900;
}
.np-row-label {
  @apply truncate leading-none;
}
.np-dots-btn {
  @apply shrink-0 flex items-center justify-center w-6 h-6 rounded text-emerald-500
         bg-transparent border-0 cursor-pointer transition-colors hover:bg-emerald-200
         hover:text-emerald-800 focus:outline-none;
}

/* ── Icons ──────────────────────────────────────────────────────────────── */
.np-icon    { @apply w-3.5 h-3.5 shrink-0; }
.np-icon-sm { @apply w-3 h-3 shrink-0; }

/* ── Badge ──────────────────────────────────────────────────────────────── */
.np-badge {
  @apply ml-auto text-[9px] font-medium uppercase px-1 rounded shrink-0;
}
.np-badge--green { @apply bg-emerald-200 text-emerald-700; }
.np-badge--grey  { @apply bg-zinc-200 text-zinc-500; }

/* ── Status dot ─────────────────────────────────────────────────────────── */
.np-dot {
  @apply inline-block w-2 h-2 rounded-full shrink-0;
}
.np-dot--inline { @apply shrink-0; }
.np-dot--green  { @apply bg-emerald-500; }
.np-dot--grey   { @apply bg-zinc-400; }
.np-dot--amber  { @apply bg-amber-400; }

/* ── Pop card ───────────────────────────────────────────────────────────── */
.np-popcard {
  @apply mx-1 mb-1 rounded-md border border-emerald-200 bg-white shadow-sm px-3 py-2.5;
}
.np-popcard-head {
  @apply flex items-center justify-between mb-1;
}
.np-popcard-title {
  @apply text-xs font-semibold text-emerald-800;
}
.np-close-btn {
  @apply flex items-center justify-center w-5 h-5 rounded text-zinc-400
         bg-transparent border-0 cursor-pointer hover:text-zinc-600 hover:bg-zinc-100
         transition-colors focus:outline-none;
}
.np-popcard-desc {
  @apply text-[11px] text-zinc-500 m-0 mb-2 leading-relaxed;
}
.np-code {
  @apply font-mono text-[10px] bg-zinc-100 px-1 rounded;
}
.np-status-row {
  @apply flex items-center gap-1.5 mb-2;
}
.np-status-text {
  @apply text-[11px] text-zinc-600;
}
.np-error {
  @apply text-[10px] text-rose-600 bg-rose-50 border border-rose-200 rounded px-2 py-1 mb-2;
}

/* ── Action buttons ─────────────────────────────────────────────────────── */
.np-actions {
  @apply flex gap-1.5 flex-wrap;
}
.np-btn {
  @apply inline-flex items-center justify-center text-[11px] font-medium px-2.5 py-1
         rounded border transition-colors no-underline cursor-pointer focus:outline-none
         disabled:opacity-40 disabled:cursor-not-allowed;
}
.np-btn--primary {
  @apply bg-emerald-600 border-emerald-600 text-white hover:bg-emerald-700 hover:border-emerald-700;
}
.np-btn--green {
  @apply bg-emerald-500 border-emerald-500 text-white hover:bg-emerald-600 hover:border-emerald-600;
}
.np-btn--red {
  @apply bg-rose-500 border-rose-500 text-white hover:bg-rose-600 hover:border-rose-600;
}
.np-btn--ghost {
  @apply bg-transparent border-zinc-200 text-zinc-600 hover:bg-zinc-50 hover:border-zinc-300;
}

/* ── Pop transition ─────────────────────────────────────────────────────── */
.np-pop-enter-active,
.np-pop-leave-active {
  transition: opacity 120ms ease, transform 120ms ease;
}
.np-pop-enter-from,
.np-pop-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
