import './shared/legacy/device-polyfill'
import { MessageBuilder } from './shared/legacy/message'
App({
  globalData: { messageBuilder: null },
  onCreate() {
    this.globalData.messageBuilder = new MessageBuilder({ appId: 1202601 })
    this.globalData.messageBuilder.connect()
  },
  onDestroy() { this.globalData.messageBuilder.disConnect() }
})
