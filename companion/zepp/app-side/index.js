import { MessageBuilder } from '../shared/legacy/message'
import { mapURL, validFrame } from '../shared/protocol'
import { stateURL, validState } from '../shared/state'
AppSideService({
  onInit() {
    this.closed = false
    this.busy = false
    this.messageBuilder = new MessageBuilder()
    this.messageBuilder.listen(() => {})
    this.onRequest = (ctx) => this.handle(ctx)
    this.messageBuilder.on('request', this.onRequest)
  },
  async handle(ctx) {
    const respond = (data) => { if (!this.closed) ctx.response({ data }) }
    let request
    try { request = this.messageBuilder.buf2Json(ctx.request.payload) }
    catch (_) { return respond({ ok: false, message: 'Pedido inválido.' }) }
    if (!['MAP_FRAME', 'MAP_STATE'].includes(request.method)) return respond({ ok: false, message: 'Pedido desconhecido.' })
    const requestId = request.params && request.params.requestId
    if (typeof requestId !== 'string' || !/^[a-z0-9-]{1,60}$/.test(requestId)) {
      return respond({ ok: false, message: 'Pedido inválido.' })
    }
    if (this.busy) return respond({ ok: false, requestId, message: 'Aguardando resposta anterior…' })
    this.busy = true
    let timeout, fetching = false
    try {
      const telemetry = request.method === 'MAP_STATE'
      const url = (telemetry ? stateURL : mapURL)(settings.settingsStorage.getItem('connectionToken') || '')
      const pending = fetch({ url, method: 'GET' })
      fetching = true
      // A timed-out fetch remains busy until it settles; never overlap HTTP requests.
      pending.then(() => { this.busy = false }, () => { this.busy = false })
      const response = await Promise.race([pending, new Promise((_, reject) => {
        timeout = setTimeout(() => reject(new Error('Sem resposta do Recomp no celular.')), 8000)
      })])
      if (response.status !== 200) throw new Error(response.status === 403
        ? 'Código de conexão incorreto.' : response.status === 404 && telemetry
          ? 'Atualize o APK Android do Recomp.' : 'Abra o Recomp e ative Mapa no relógio.')
      if (typeof response.body === 'string' && response.body.length > (telemetry ? 24576 : 80000)) throw new Error('Resposta muito grande.')
      const frame = typeof response.body === 'string' ? JSON.parse(response.body) : response.body
      if (telemetry) {
        if (!validState(frame)) throw new Error('Atualize o APK Android do Recomp.')
        return respond({ ok: true, requestId, state: frame })
      }
      if (!validFrame(frame)) throw new Error('Atualize o APK Android do Recomp.')
      respond({ ok: true, requestId, frame })
    } catch (error) {
      respond({ ok: false, requestId, message: error.message })
    } finally {
      if (!fetching) this.busy = false
      if (timeout) clearTimeout(timeout)
    }
  },
  onDestroy() {
    this.closed = true
    this.messageBuilder.off('request', this.onRequest)
  }
})
