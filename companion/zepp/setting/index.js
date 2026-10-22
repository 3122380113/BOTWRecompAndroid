AppSettingsPage({
  build(props) {
    return Section({}, [
      Text({ style: { fontSize: '20px', fontWeight: 'bold' } }, ['BOTW Recomp • Mapa no relógio']),
      Text({}, ['No Android, mantenha pressionado o ícone do BOTW Recomp e abra “Mapa no relógio”. Ative o compartilhamento e copie o código abaixo.']),
      TextInput({
        label: 'Código de conexão',
        placeholder: 'Cole o código de 32 caracteres',
        value: props.settingsStorage.getItem('connectionToken') || '',
        onChange: (value) => props.settingsStorage.setItem('connectionToken', value.trim().toLowerCase())
      }),
      Text({}, ['Abra o jogo e o Recomp Mapa no relógio. Gire a coroa para ajustar o zoom, arraste para explorar e toque em Centrar Link para voltar a acompanhar o personagem.']),
      Text({}, ['O mapa fica no relógio. O celular pareado envia somente a posição, direção e os marcadores pelo Zepp, sem servidor na internet.'])
    ])
  }
})
