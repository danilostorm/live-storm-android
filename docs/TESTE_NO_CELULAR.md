# Checklist de teste no celular

## Antes da live

- [ ] Instalar o APK gerado pelo GitHub Actions.
- [ ] Autorizar câmera e microfone.
- [ ] Criar uma chave de transmissão de teste no YouTube Studio.
- [ ] Colar a chave no aplicativo sem publicá-la em prints ou issues.
- [ ] Confirmar que o aplicativo mostra 60 FPS disponível para a câmera e resolução escolhidas.
- [ ] Usar Wi-Fi 5 GHz/6 GHz ou conexão móvel estável.

## Teste 1080p60

- [ ] Selecionar 1080p, 60 FPS e 16:9.
- [ ] Iniciar uma live não listada ou privada.
- [ ] Aguardar o indicador mostrar FPS real próximo de 60.
- [ ] Conferir na Sala de Controle ao Vivo se a entrada aparece como 1080p60.
- [ ] Observar o upload próximo de 12 Mb/s.
- [ ] Manter o teste por pelo menos 10 minutos e observar aquecimento e quedas de FPS.

## Teste 720p60

Execute este perfil quando 1080p60 não for sustentado pelo aparelho ou pela conexão:

- [ ] Selecionar 720p e 60 FPS.
- [ ] Conferir FPS real próximo de 60.
- [ ] Conferir entrada 720p60 no YouTube.
- [ ] Observar o upload próximo de 6 Mb/s.

## Informações úteis para relatar problemas

- Modelo exato do celular.
- Versão do Android.
- Câmera frontal ou traseira.
- Perfil selecionado.
- FPS real mostrado pelo aplicativo.
- Bitrate de upload mostrado pelo aplicativo.
- Mensagem exibida pelo YouTube Studio.
- Log da execução do GitHub Actions, quando o erro for de compilação.
