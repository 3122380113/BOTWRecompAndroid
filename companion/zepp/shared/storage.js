// Legacy firmware may report a status or a byte count. The closed file's size
// is the success criterion; never hand a truncated image to the native decoder.
export function saveMap(fs, path, bytes) {
  const file = fs.open(path, fs.O_WRONLY | fs.O_CREAT | fs.O_TRUNC)
  if (typeof file !== 'number' || file < 0) {
    throw new Error(`Falha ao abrir mapa (${file}).`)
  }
  let result
  try {
    result = fs.write(file, bytes.buffer, 0, bytes.length)
    if (typeof result === 'number' && result < 0) {
      if (result === -12 || result === -13) throw new Error('Sem espaço no relógio.')
      throw new Error(`Falha ao gravar mapa (${result}).`)
    }
  } finally { fs.close(file) }
  const [stat, error] = fs.stat(path)
  if (error !== 0 || !stat) throw new Error(`Falha ao verificar mapa (${error}; escrita ${result}).`)
  if (stat.size !== bytes.length) {
    throw new Error(`Mapa incompleto (${stat.size}/${bytes.length}; escrita ${result}).`)
  }
}

// Generated filenames only; never overwrite a bundled application resource.
export function saveAssetMap(fs, path, bytes) {
  if (!/^map-[01]\.png$/.test(path) || typeof fs.open_asset !== 'function' ||
      typeof fs.stat_asset !== 'function') throw new Error('Recurso local indisponível.')
  saveMap({
    O_WRONLY: fs.O_WRONLY, O_CREAT: fs.O_CREAT, O_TRUNC: fs.O_TRUNC,
    open: (name, flags) => fs.open_asset(name, flags),
    write: (file, buffer, offset, length) => fs.write(file, buffer, offset, length),
    close: (file) => fs.close(file), stat: (name) => fs.stat_asset(name)
  }, path, bytes)
}
