import { mapURL } from './protocol.js'
export function stateURL(token) { return mapURL(token).replace('/v1/map.json?', '/v1/state.json?') }
const coordinate = (n) => typeof n === 'number' && Number.isFinite(n) && Math.abs(n) <= 5000
export function validState(value) {
  if (!value || value.version !== 1 || !['ready', 'unavailable', 'interior'].includes(value.status)) return false
  if (!Array.isArray(value.markers) || value.markers.length > 200) return false
  if (!value.markers.every(m => m && Number.isInteger(m.id) && m.id >= 0 && m.id < 200 &&
    Number.isInteger(m.type) && m.type >= 0 && m.type <= 63 && coordinate(m.x) && coordinate(m.z))) return false
  if (value.objectives !== undefined && (!Array.isArray(value.objectives) || value.objectives.length > 32 ||
    !value.objectives.every(m => m && Number.isInteger(m.id) && m.id >= 0 && m.id < 64 && coordinate(m.x) && coordinate(m.z)))) return false
  if (value.status !== 'ready') return value.player === null
  return value.map === 'MainField' && typeof value.session === 'string' && value.session.length <= 64 &&
    Number.isSafeInteger(value.sequence) && value.sequence >= 0 && Number.isFinite(value.sampleAgeMs) &&
    value.sampleAgeMs >= 0 && value.sampleAgeMs < 10000 && !!value.player &&
    coordinate(value.player.x) && coordinate(value.player.z) && Number.isFinite(value.player.y) &&
    Math.abs(value.player.y) < 10000 && (value.player.headingDegrees === null ||
      (Number.isFinite(value.player.headingDegrees) && value.player.headingDegrees >= 0 && value.player.headingDegrees <= 360))
}
