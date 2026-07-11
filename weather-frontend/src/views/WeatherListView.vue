<script setup lang="ts">
// Landing page: the list of cities with their current weather, from /api/weather.
// The active page lives in the ?page query param; totalCities in the response
// metadata tells us how many page links to render.
import { ref, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import apiClient from '../api/client'
import { type CitySummary, type WeatherListResponse } from '../types/weather'

const route = useRoute()
const activePage = computed(() => Number(route.query.page) || 1)

const pages = ref(0)
const citySummaries = ref<CitySummary[]>([])

// Fetch on mount (immediate) and again whenever the active page changes.
watch(activePage, async (page) => {
  const response = await apiClient.get<WeatherListResponse>('/api/weather', { params: { page } })
  citySummaries.value = response.data.cities
  pages.value = Math.ceil(response.data.metadata.totalCities / response.data.metadata.pageSize)
}, { immediate: true })

function mapMeasurement(value: number | null | undefined, unit: string) : string {
  if (!value) {
    return "N/A";
  }

  return value.toFixed(1) + " " + unit;
}
</script>

<template>
  <section>
    <h1>Current weather</h1>
      <template v-for="page in pages" :key="page">
        <!-- RouterLink so we just swap SPA content without reloading page -->
        <RouterLink
          :to="{ name: 'weather-list', query: { page } }"
          :class="{ active: page === activePage }"
        >{{ page }}</RouterLink><span v-if="page < pages">|</span>
      </template>
    <table>
      <thead>
        <tr>
          <th>City</th>
          <th>Temperature</th>
        </tr>
      </thead>
      <tr v-for="summary in citySummaries" :key="summary.id">
        <td>{{ summary.name }}</td>
        <td>{{ mapMeasurement(summary.temperature, "°C") }}</td>
      </tr>
    </table>
  </section>
</template>

<style scoped>
table {
  margin: 0 auto;
}

.active {
  font-weight: bold;
}
</style>
