<script setup lang="ts">
// Detail page: one city's past week (GET /api/weather/:id), with a simple line
// graph. TODO (developer): fetch and render here.
const props = defineProps<{ id: string }>()
</script>

<template>
  <section>
    <RouterLink to="/">&larr; Back to all cities</RouterLink>
    <h1>City #{{ props.id }} &mdash; past week</h1>
    <p>Weekly buckets / graph go here.</p>
  </section>
</template>
