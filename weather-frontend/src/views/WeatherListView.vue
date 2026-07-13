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

function mapMeasurement(value: number | null | undefined, unit: string | null = null) : string {
  // == null catches null and undefined but not 0 -- a 0.0 reading (0 degC,
  // calm 0 m/s) is real data, not missing data.
  if (value == null) {
    return "N/A";
  }
  const roundedValue = value.toFixed(1);
  return unit ? roundedValue + " " + unit : roundedValue
}

function mapConventionalWindDirection(degrees: number | null | undefined): string {
  // == null, not !degrees: 0 is due north, a valid direction, not missing data.
  if (degrees == null) {
    return "N/A"
  }
  
  const directions: {
    lowerBound: number;
    upperBound: number;
    directionName: string;
  }[] = [
    { lowerBound: 348.75, upperBound: 11.25, directionName: "N" },
    { lowerBound: 11.25, upperBound: 33.75, directionName: "NNE" },
    { lowerBound: 33.75, upperBound: 56.25, directionName: "NE" },
    { lowerBound: 56.25, upperBound: 78.75, directionName: "ENE" },
    { lowerBound: 78.75, upperBound: 101.25, directionName: "E" },
    { lowerBound: 101.25, upperBound: 123.75, directionName: "ESE" },
    { lowerBound: 123.75, upperBound: 146.25, directionName: "SE" },
    { lowerBound: 146.25, upperBound: 168.75, directionName: "SSE" },
    { lowerBound: 168.75, upperBound: 191.25, directionName: "S" },
    { lowerBound: 191.25, upperBound: 213.75, directionName: "SSW" },
    { lowerBound: 213.75, upperBound: 236.25, directionName: "SW" },
    { lowerBound: 236.25, upperBound: 258.75, directionName: "WSW" },
    { lowerBound: 258.75, upperBound: 281.25, directionName: "W" },
    { lowerBound: 281.25, upperBound: 303.75, directionName: "WNW" },
    { lowerBound: 303.75, upperBound: 326.25, directionName: "NW" },
    { lowerBound: 326.25, upperBound: 348.75, directionName: "NNW" }
  ];

 const match = directions.find((direction) => {
  if (direction.lowerBound > direction.upperBound) {
    return degrees >= direction.lowerBound || degrees < direction.upperBound;
  } else {
    return degrees >= direction.lowerBound && degrees < direction.upperBound;
  }
});

return match?.directionName ?? "N/A";
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
    <table v-if="citySummaries.length > 0">
      <thead>
        <tr>
          <th>City</th>
          <th>Temperature</th>
          <th>Wind speed</th>
          <th>Wind direction</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="summary in citySummaries" :key="summary.id">
          <td>{{ summary.name }}</td>
          <td>{{ mapMeasurement(summary.temperature, "°C") }}</td>
          <td>{{ mapMeasurement(summary.windSpeed, "m/s") }}</td>
          <td>{{ mapConventionalWindDirection(summary.windDirection) }}</td>
        </tr>
      </tbody>
    </table>
    <h2 v-else>No current weather data</h2>
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
