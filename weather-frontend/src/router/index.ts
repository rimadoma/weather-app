import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import WeatherListView from '../views/WeatherListView.vue'
import CityDetailView from '../views/CityDetailView.vue'

// Two pages, mapping onto the read API (requirements iteration 13):
//  - '/'            landing: city list with current weather
//  - '/cities/:id'  detail: one city's past week
const routes: RouteRecordRaw[] = [
  { path: '/', name: 'weather-list', component: WeatherListView },
  {
    path: '/cities/:id',
    name: 'city-detail',
    component: CityDetailView,
    // Pass the :id route param to the component as a prop.
    props: true,
  },
]

export default createRouter({
  history: createWebHistory(),
  routes,
})
