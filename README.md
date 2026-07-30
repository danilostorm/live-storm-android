# Live Storm Android

Aplicativo Android para transmitir a câmera e o microfone do celular **diretamente ao YouTube Live por RTMPS**, sem servidor intermediário da HostStorm.

## Objetivo da versão 0.1.0

O foco desta primeira versão é garantir que o usuário possa selecionar **30 FPS ou 60 FPS reais** e identificar claramente quando o aparelho não oferece 60 FPS para a câmera e resolução escolhidas.

O aplicativo não altera uma live de 60 FPS para 30 FPS silenciosamente. Quando a câmera ou o codificador não suporta a combinação, a transmissão é bloqueada e o motivo aparece na tela.

## Recursos atuais

- Transmissão direta ao YouTube por RTMPS na porta 443.
- Câmera traseira e frontal.
- Microfone, mute e flash.
- 720p e 1080p.
- 30 FPS e 60 FPS.
- Orientação Retrato 9:16, Paisagem 16:9 e modo Automático.
- Perfil H.264 High, AAC estéreo e keyframe a cada 2 segundos.
- Bitrates preparados para o YouTube:
  - 1080p60: 12 Mb/s.
  - 1080p30: 10 Mb/s.
  - 720p60: 6 Mb/s.
  - 720p30: 4 Mb/s.
- Medidor do FPS realmente codificado durante a live.
- Alerta quando uma live configurada em 60 FPS permanece abaixo de 55 FPS.
- Reconexão automática.
- Medidor de upload e cronômetro.
- Chave do YouTube salva somente nas preferências locais do aparelho.

## Como usar

1. No YouTube Studio, abra **Criar > Transmitir ao vivo**.
2. Na Sala de Controle ao Vivo, copie a chave da transmissão.
3. No aplicativo, toque na engrenagem.
4. Cole a chave do YouTube.
5. Mantenha o URL RTMPS padrão ou cole o endereço RTMPS exibido pelo YouTube Studio.
6. Selecione resolução, 30/60 FPS e Retrato, Paisagem ou Automático.
7. Toque em **Iniciar no YouTube**.
8. Confirme a prévia e inicie/publice a live na Sala de Controle ao Vivo, conforme a configuração do canal.

> Nunca coloque sua chave de transmissão em commits, issues, prints públicos ou arquivos do repositório.

## Como o app valida 60 FPS

A validação acontece em três etapas:

1. Consulta o FPS máximo informado pela câmera para a resolução selecionada.
2. Prepara o codificador H.264 com exatamente 60 FPS, sem fallback automático para 30.
3. Durante a transmissão, conta os quadros realmente produzidos pelo codificador a cada segundo.

Quando pelo menos três amostras consecutivas ficam em 55 FPS ou mais, a interface mostra que os 60 FPS reais foram validados. Essa tolerância considera pequenas oscilações de agendamento do Android.

## Requisitos

- Android 8.0 ou mais recente.
- Canal do YouTube com transmissão ao vivo habilitada.
- Câmera e codificador do aparelho compatíveis com o perfil escolhido.
- Upload estável acima do bitrate selecionado, com margem adicional.
- Para 1080p60, recomenda-se conexão capaz de sustentar 12 Mb/s continuamente.

## Compilação

O projeto usa:

- Android Gradle Plugin 9.2.0.
- Gradle 9.4.1.
- JDK 17.
- compileSdk/targetSdk 36.
- RootEncoder 2.7.2.

No Android Studio, abra a pasta raiz do repositório e execute o módulo `app`.

O GitHub Actions também gera um APK de debug automaticamente. Abra a aba **Actions**, selecione a execução mais recente e baixe o artefato `live-storm-debug-apk`.

## Segurança

- O repositório não contém chave do YouTube.
- A conexão padrão é RTMPS, não RTMP sem criptografia.
- A chave é armazenada localmente no aparelho.
- Para testes, prefira uma chave personalizada que possa ser redefinida depois.

## Limitações atuais

- A live precisa permanecer com o aplicativo aberto e a tela ligada.
- Ainda não há login OAuth com a conta Google.
- A criação de título, privacidade e agendamento continua no YouTube Studio.
- O comportamento de 60 FPS depende das APIs Camera2 e MediaCodec expostas pelo fabricante; alguns celulares gravam em 60 FPS no app nativo, mas não liberam a mesma combinação para aplicativos de terceiros.

## Próximas etapas sugeridas

- Serviço em primeiro plano para tolerar troca de tela e bloqueio acidental.
- Controle de zoom por gesto e foco por toque.
- Indicador térmico e alerta de superaquecimento.
- Teste de estabilidade antes de entrar ao vivo.
- Integração opcional com YouTube Live API para título, privacidade e agendamento.
- Build release assinado via GitHub Actions Secrets.

## Licença da dependência

O projeto usa [RootEncoder](https://github.com/pedroSG94/RootEncoder), distribuído sob Apache License 2.0.


## Modo Pro e overlays

- Zoom por gesto de pinça, controle na tela e teclas de volume.
- Foco contínuo, foco por toque com trava e foco manual por distância.
- Compensação de exposição, OIS e EIS quando disponibilizados pela Camera2.
- Diagnóstico das faixas de FPS expostas pelo fabricante.
- Modo experimental de 60 FPS, sempre acompanhado do medidor de FPS real.
- Overlay web por URL HTTPS, renderizado dentro do vídeo transmitido.

### Conexão com a conta do YouTube

A API key não autoriza operações na conta. Para criar transmissões, streams e obter a
chave automaticamente, o aplicativo precisará de OAuth 2.0 com o escopo
`youtube.force-ssl`, Client ID Android, pacote e SHA-1 da assinatura. Até essa
configuração ser adicionada, a transmissão direta por RTMPS e chave continua ativa.


## Live Storm 0.3.0

- 60 FPS nativo: a faixa 60 é solicitada automaticamente e o sucesso fica memorizado por câmera/resolução após validação real.
- Fonte Câmera ou Tela/Jogo.
- Captura de áudio interno, microfone ou mistura dos dois em Android 10+ (o app/jogo pode bloquear áudio interno).
- Serviço foreground para a captura de tela conforme as regras atuais do Android.
- Gravação local MP4 com publicação em `Movies/LiveStorm`.
- Três perfis Pro: toque carrega; toque longo salva zoom, foco, exposição e estabilização.


## Live Storm 0.4.0 — HUD e modos profissionais

- HUD ocultável por botão flutuante, preservando somente o controle HUD+/HUD−.
- Orientação automática aplicada como padrão na primeira execução após a atualização.
- Seletor direto entre **Normal • Câmera** e **Games • Tela**.
- O modo Games nunca abre aplicativos: solicita a captura da tela e o usuário abre o jogo normalmente.
- Áudio de Games configurável entre jogo + microfone, somente jogo ou somente microfone.
- Permissões solicitadas conforme o modo: câmera não é exigida para transmitir a tela.

## Live Storm 0.5.0

- Interface principal reorganizada e compacta.
- Modo Games usa fundo próprio e nunca exibe a câmera atrás.
- HUD reposicionado acima do painel, sem cobrir controles.
- Qualidade e orientação ficam em um painel recolhível.
- Controles avançados foram movidos para uma folha inferior.
- Avisos passaram a usar Snackbar e não bloqueiam os botões.

