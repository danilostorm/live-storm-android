# Perfil de transmissão 60 FPS para o YouTube

## Configuração usada pelo Live Storm

| Perfil | H.264 | FPS | Bitrate | Keyframe | Áudio |
|---|---:|---:|---:|---:|---:|
| 1080p60 | High / Level 4.2 quando aceito | 60 | 12 Mb/s | 2 s | AAC 44,1 kHz, 128 kb/s |
| 1080p30 | High / Level 4.0 quando aceito | 30 | 10 Mb/s | 2 s | AAC 44,1 kHz, 128 kb/s |
| 720p60 | High / Level 3.2 quando aceito | 60 | 6 Mb/s | 2 s | AAC 44,1 kHz, 128 kb/s |
| 720p30 | High / Level 3.1 quando aceito | 30 | 4 Mb/s | 2 s | AAC 44,1 kHz, 128 kb/s |

Quando um fabricante rejeita o profile/level forçado, o aplicativo tenta novamente deixando o Android escolher o profile/level, mas preserva resolução, bitrate, keyframe e FPS. Ele nunca troca 60 por 30 silenciosamente.

## O que faz o YouTube reconhecer 60 FPS

O fluxo RTMP/RTMPS anuncia resolução e FPS ao iniciar a conexão. O aplicativo prepara o encoder em 60 FPS e a biblioteca envia essa informação junto com o fluxo. Além disso, o app mede os frames codificados para verificar se o telefone está realmente produzindo uma taxa próxima de 60.

## Diagnóstico

- **60 FPS indisponível:** a câmera não anunciou 60 FPS para aquela resolução.
- **Erro do codificador:** o MediaCodec não aceitou resolução/FPS/bitrate.
- **Perfil 60, FPS real baixo:** o fluxo foi configurado em 60, mas o aparelho está descartando frames por aquecimento, pouca capacidade ou carga do sistema.
- **Upload abaixo do bitrate escolhido:** a conexão não está sustentando o perfil; use uma rede melhor ou reduza para 720p60.

## Referências oficiais

- YouTube Help — configurações de codificador: https://support.google.com/youtube/answer/2853702?hl=pt-BR
- YouTube Live RTMPS ingestion: https://developers.google.com/youtube/v3/live/guides/rtmps-ingestion?hl=pt-BR
- RootEncoder: https://github.com/pedroSG94/RootEncoder
