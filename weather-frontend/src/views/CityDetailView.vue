<script setup lang="ts">
// Detail page: one city's past week (GET /api/weather/:id), with a simple line graph.
import { onMounted, ref, computed } from 'vue';
import apiClient from '../api/client'
import { type WeatherHistoryResponse } from '../types/weather'

const props = defineProps<{ id: string }>()

const history = ref<WeatherHistoryResponse | null>(null)

onMounted(async () => {
  const response = await apiClient.get<WeatherHistoryResponse>(`/api/weather/${props.id}`)
  history.value = response.data
})

// --- Graph geometry -------------------------------------------------------
// The SVG is drawn in its own coordinate space (a fixed viewBox) and then
// scaled to fit its container by CSS, so all the maths below is in "graph
// units" and never touches real pixels
const WIDTH = 1000
const HEIGHT = 420
const M = { top: 48, right: 30, bottom: 90, left: 44 }
const plotW = WIDTH - M.left - M.right
const plotH = HEIGHT - M.top - M.bottom

// The wind row sits in the bottom margin, below the hour labels: an arrow, then
// the speed number under it.
const windArrowY = HEIGHT - M.bottom + 45
const windSpeedY = HEIGHT - M.bottom + 68

// Fixed temperature axis (requirement: -5..40 degC, no auto-fitting).
const T_MIN = -5
const T_MAX = 40

// A temperature -> y and a bucket-index -> x. Pure arithmetic: y is flipped
// because SVG's y grows downward, and the 25 buckets are spread evenly so the
// first sits on the left edge of the plot and the last on the right.
function yForTemp(t: number): number {
  return M.top + plotH * (T_MAX - t) / (T_MAX - T_MIN)
}
function xForIndex(i: number, count: number): number {
  return count <= 1 ? M.left : M.left + (plotW * i) / (count - 1)
}

// One screen point per bucket. y is null for empty buckets so the marks and
// segments below can tell "missing" apart from a real reading.
const points = computed(() => {
  const buckets = history.value?.buckets ?? []
  return buckets.map((bucket, i) => ({
    bucket,
    x: xForIndex(i, buckets.length),
    y: bucket.temperature == null ? null : yForTemp(bucket.temperature),
  }))
})

// Present points only, with y narrowed to a number (flatMap drops the nulls).
const presentPoints = computed(() =>
  points.value.flatMap((p) => (p.y == null ? [] : [{ ...p, y: p.y }])),
)

// Temperature line, as a list of segments between consecutive *present*
// buckets. We walk the buckets left to right, remembering the last present
// point (`prev`). Each time we reach another present point we join it back to
// `prev`: if they were adjacent buckets it's a normal solid segment; if buckets
// were missing in between (index jumped by more than one) the segment bridges
// that gap and is flagged `dashed`. Because `prev` only updates on present
// points, a run of missing buckets is skipped over and the next present point
// dashes straight back to the last one -- your "nearest valid neighbours" idea.
// A leading/trailing run of missing buckets has no `prev`/no successor to join
// to, so nothing is drawn there, which is what we want.
const tempSegments = computed(() => {
  const segs: { x1: number; y1: number; x2: number; y2: number; dashed: boolean }[] = []
  let prev: { x: number; y: number; i: number } | null = null
  points.value.forEach((p, i) => {
    if (p.y == null) return // missing bucket: leave `prev` where it is
    if (prev) {
      segs.push({ x1: prev.x, y1: prev.y, x2: p.x, y2: p.y, dashed: i - prev.i > 1 })
    }
    prev = { x: p.x, y: p.y, i }
  })
  return segs
})

// degC gridline levels, every 5 degrees across the fixed range.
const yTicks = computed(() => {
  const ticks: number[] = []
  for (let t = T_MIN; t <= T_MAX; t += 5) ticks.push(t)
  return ticks
})

// Bucket start hour as a 2-digit UTC label (02 / 08 / 14 / 20).
function hourLabel(iso: string): string {
  return String(new Date(iso).getUTCHours()).padStart(2, '0')
}

function dateLabel(iso: string): string {
  const weekDays = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
  const date = new Date(iso);
  const weekDay = weekDays[date.getUTCDay()];
  return `${weekDay} ${date.getUTCDate()}/${date.getUTCMonth() + 1}/`;
}

// Group the buckets into calendar days (UTC) so each day gets ONE header,
// centred over the buckets it spans. The buckets are already in order, so we
// start a new group whenever the UTC date changes and stretch the current
// group's x-range (xStart..xEnd) as more buckets of the same day arrive. The
// label is then drawn at the midpoint of that range.
const dayGroups = computed(() => {
  const groups: { key: string; label: string; xStart: number; xEnd: number }[] = []
  for (const p of points.value) {
    const key = new Date(p.bucket.startTime).toISOString().slice(0, 10) // YYYY-MM-DD, UTC
    const current = groups.at(-1)
    if (current && current.key === key) {
      current.xEnd = p.x
    } else {
      groups.push({ key, label: dateLabel(p.bucket.startTime), xStart: p.x, xEnd: p.x })
    }
  }
  return groups
})
</script>

<template>
  <section>
    <RouterLink to="/">&larr; Back to all cities</RouterLink>
    <h1>Weather of the past week at {{ history?.name }}</h1>

    <svg v-if="history" :viewBox="`0 0 ${WIDTH} ${HEIGHT}`" class="graph" role="img"
         :aria-label="`Past weather at ${history.name}`">
      <!-- degC gridlines + labels (fixed -5..40) -->
      <g class="grid">
        <template v-for="t in yTicks" :key="t">
          <line :x1="M.left" :x2="WIDTH - M.right" :y1="yForTemp(t)" :y2="yForTemp(t)" />
          <text :x="M.left - 6" :y="yForTemp(t) + 4" text-anchor="end">{{ t }}</text>
        </template>
        <text :x="M.left - 6" :y="M.top - 12" text-anchor="end">&deg;C</text>
      </g>

      <!-- day-name headers: one per calendar day, centred over that day's buckets -->
      <g class="daylabels">
        <text v-for="g in dayGroups" :key="g.key"
              :x="(g.xStart + g.xEnd) / 2" :y="M.top - 24" text-anchor="middle">
          {{ g.label }}
        </text>
      </g>

      <!-- x-axis: the hour of each bucket -->
      <g class="xlabels">
        <text v-for="p in points" :key="p.bucket.startTime"
              :x="p.x" :y="HEIGHT - M.bottom + 20" text-anchor="middle">
          {{ hourLabel(p.bucket.startTime) }}
        </text>
      </g>

      <!-- temperature line; segments bridging missing buckets are dashed -->
      <line v-for="(s, i) in tempSegments" :key="i"
            class="temp-line" :class="{ dashed: s.dashed }"
            :x1="s.x1" :y1="s.y1" :x2="s.x2" :y2="s.y2" />

      <!-- temperature dots (present buckets) -->
      <circle v-for="p in presentPoints" :key="p.bucket.startTime" class="temp-dot"
              :cx="p.x" :cy="p.y" r="3" />

      <!-- wind row: per bucket, an arrow for direction + the speed as a bare
           number. Wind is both-or-neither (iteration 17), so when it's missing
           we show "N/A" for the speed and simply omit the arrow. -->
      <g class="wind">
        <template v-for="p in points" :key="p.bucket.startTime">
          <!-- Arrow points up (north) at rotation 0, then rotates by the bearing.
               bearing is the direction the wind blows FROM (met convention), so
               the arrow points back toward the source; add 180 to it if you'd
               rather it fly downwind. Only drawn when we have a direction. -->
          <path v-if="p.bucket.windDirection != null" class="wind-arrow"
                :transform="`translate(${p.x}, ${windArrowY}) rotate(${p.bucket.windDirection})`"
                d="M0,7 L0,-7 M-3.5,-3 L0,-7 L3.5,-3" />
          <text :x="p.x" :y="windSpeedY" text-anchor="middle">
            {{ p.bucket.windSpeed == null ? 'N/A' : Math.round(p.bucket.windSpeed) }}
          </text>
        </template>
      </g>
    </svg>
  </section>
</template>
