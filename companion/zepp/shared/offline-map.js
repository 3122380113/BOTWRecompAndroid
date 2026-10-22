export const MAP = { minX: -5000, maxX: 5000, minZ: -5000, maxZ: 5000,
  levels: [256, 512, 1024, 2048], tileSize: 128 }

export class MapCamera {
  constructor(width = 320, height = 320) {
    this.width = width; this.height = height
    this.x = -1000; this.z = 1900; this.level = 2; this.follow = true
  }
  get scale() { return MAP.levels[this.level] / (MAP.maxX - MAP.minX) }
  center(player) {
    if (player) { this.x = player.x; this.z = player.z }
    this.follow = true
    this.clamp()
  }
  clamp() {
    const hx = this.width / (2 * this.scale), hz = this.height / (2 * this.scale)
    this.x = hx * 2 >= MAP.maxX - MAP.minX ? 0 : Math.max(MAP.minX + hx, Math.min(MAP.maxX - hx, this.x))
    this.z = hz * 2 >= MAP.maxZ - MAP.minZ ? 0 : Math.max(MAP.minZ + hz, Math.min(MAP.maxZ - hz, this.z))
  }
  pan(dx, dy) {
    if (!Number.isFinite(dx) || !Number.isFinite(dy)) return
    this.x -= dx / this.scale; this.z -= dy / this.scale
    this.follow = false; this.clamp()
  }
  zoom(delta) { this.level = Math.max(0, Math.min(MAP.levels.length - 1, this.level + delta)); this.clamp() }
  project(point) {
    return { x: this.width / 2 + (point.x - this.x) * this.scale,
      y: this.height / 2 + (point.z - this.z) * this.scale }
  }
  tiles() {
    const size = MAP.levels[this.level], tile = MAP.tileSize
    const left = (this.x - MAP.minX) * this.scale - this.width / 2
    const top = (this.z - MAP.minZ) * this.scale - this.height / 2
    const result = []
    for (let row = Math.max(0, Math.floor(top / tile)); row < Math.min(size / tile, Math.ceil((top + this.height) / tile)); row++) {
      for (let col = Math.max(0, Math.floor(left / tile)); col < Math.min(size / tile, Math.ceil((left + this.width) / tile)); col++) {
        const x = Math.round(col * tile - left), y = Math.round(row * tile - top)
        const cx = Math.max(0, x), cy = Math.max(0, y)
        result.push({ key: `${this.level}/${col}-${row}`, x: cx, y: cy,
          w: Math.min(this.width, x + tile) - cx, h: Math.min(this.height, y + tile) - cy,
          pos_x: x - cx, pos_y: y - cy, src: `map/${this.level}/${col}-${row}.png` })
      }
    }
    return result
  }
}
