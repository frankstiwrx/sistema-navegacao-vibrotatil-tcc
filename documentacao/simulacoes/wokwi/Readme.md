# Simulação no Wokwi

Esta pasta contém os arquivos referentes à simulação realizada no **Wokwi**
durante o desenvolvimento da prova de conceito do sistema de navegação
vibrotátil.

A simulação foi utilizada como etapa de validação prévia da lógica de
funcionamento do sistema embarcado, permitindo verificar o comportamento das
saídas associadas às oito direções antes da montagem física com a ESP32.

## Visualização da simulação

<p align="center">
  <img src="../../figuras/simulacao-wokwi-leds.png"
       alt="Simulação da lógica das oito direções no Wokwi"
       width="800">
</p>

<p align="center">
  <em>Simulação utilizada para verificar a lógica de acionamento das oito direções com LEDs.</em>
</p>

## Arquivos

O arquivo compactado disponível nesta pasta contém uma cópia dos arquivos
utilizados na simulação, permitindo manter um registro da configuração
empregada durante o desenvolvimento.

## Simulação online

A versão interativa da simulação pode ser acessada diretamente no Wokwi:

**[Executar simulação no Wokwi](https://wokwi.com/projects/431701563343005697)**

## Contexto no desenvolvimento

A simulação permitiu verificar previamente a lógica de acionamento das saídas
correspondentes às oito direções utilizando LEDs. Posteriormente, essa lógica
foi levada para uma montagem física com a ESP32 e LEDs antes dos testes com os
atuadores vibrotáteis.

## Observação

A simulação representa uma etapa intermediária do processo de desenvolvimento
e não corresponde à configuração física completa do sistema com oito motores
vibratórios. Os testes físicos posteriores foram realizados com a ESP32 e os
componentes descritos no trabalho.
