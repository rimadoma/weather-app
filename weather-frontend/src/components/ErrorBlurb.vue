<script setup lang="ts">
// Section-wide error banner for failed fetches, shared by both pages. The API
// deliberately returns bare statuses (requirements, above iteration 11), so
// the caller decides the message ("Something went wrong", "No such city", ...).
defineProps<{ message: string }>()
</script>

<template>
  <div class="error-blurb" role="alert">
    <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
      <circle cx="12" cy="12" r="10" fill="#d32f2f" />
      <path fill="#fff" d="M11 6h2v8h-2zM11 16h2v2h-2z" />
    </svg>
    <span>{{ message }}</span>
  </div>
</template>
