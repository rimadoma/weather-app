<script setup lang="ts">
// Landing page, iteration 1: just the list of city names (from /api/cities).
// Weather readings (/api/weather) and pagination come in later iterations.
import { ref, onMounted } from 'vue'
import apiClient from '../api/client'
import type { CityRef, CityListResponse } from '../types/weather'

const cities = ref<CityRef[]>([])

onMounted(async () => {
  const response = await apiClient.get<CityListResponse>('/api/cities', { params: { page: 1 } })
  cities.value = response.data.cities
})
</script>

<template>
  <section>
    <h1>Current weather</h1>
    <ul>
      <li v-for="city in cities" :key="city.id">{{ city.name }}</li>
    </ul>
  </section>
</template>
