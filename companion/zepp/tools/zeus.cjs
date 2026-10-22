// Zeus 1.9.3 registers a private module relative to the current package instead of its own
// when installed locally. Register the shipped module explicitly, keeping the CLI unmodified.
const path = require('node:path')
const fs = require('node:fs')
const cliRoot = path.dirname(require.resolve('@zeppos/zeus-cli/package.json'))
const register = require(require.resolve('module-alias', { paths: [cliRoot] }))
register.addAlias('zeppos-app-utils', path.join(cliRoot, 'private-modules/zeppos-app-utils'))
// Preserve the generated QR matrix so it can be shown outside a terminal too.
if (process.argv[2] === 'preview') {
  const qrPath = require.resolve('qrcode-terminal', { paths: [cliRoot] })
  const qr = require(qrPath)
  const QRCode = require(path.join(path.dirname(qrPath), '../vendor/QRCode'))
  const original = qr.generate
  qr.generate = function (url, options, callback) {
    const code = new QRCode(-1, this.error)
    code.addData(url)
    code.make()
    fs.mkdirSync('dist', { recursive: true })
    fs.writeFileSync('dist/preview-qr.json', JSON.stringify(code.modules))
    return original.call(this, url, options, callback)
  }
}
require(path.join(cliRoot, 'bin/main.js'))
