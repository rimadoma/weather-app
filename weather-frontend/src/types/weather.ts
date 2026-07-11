// Friendly aliases over the auto-generated OpenAPI types (./weather-api.ts).
// weather-api.ts is the source of truth -- never edit it by hand; regenerate
// with `npm run gen:api` whenever weather-api.yaml changes.
import type { components } from './weather-api'

type Schemas = components['schemas']

export type CitySummary = Schemas['CitySummary']
export type Warning = Schemas['Warning']
export type WeatherListResponse = Schemas['WeatherListResponse']
