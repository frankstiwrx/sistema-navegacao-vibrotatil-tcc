# Sistema de Navegação Vibrotátil

Repositório contendo o código-fonte desenvolvido para o Trabalho de Conclusão
de Curso em Engenharia da Computação, referente à implementação de uma
prova de conceito de um sistema de navegação vibrotátil voltado ao auxílio
da mobilidade de pessoas com deficiência visual.

A solução integra uma aplicação Android responsável pelo processamento das
informações de navegação e um sistema embarcado baseado na ESP32, responsável
pela geração dos comandos destinados aos atuadores vibrotáteis.

## Estrutura do repositório

- `aplicativo-android/` — código-fonte da aplicação móvel Android.
- `firmware-esp32/` — firmware desenvolvido para a ESP32.

## Aplicativo Android

A aplicação móvel foi desenvolvida em Kotlin e é responsável pela obtenção
e processamento das informações de localização e navegação.

Entre as principais funcionalidades implementadas estão:

- integração com serviços do Google Maps;
- obtenção da localização do dispositivo;
- processamento das direções de navegação;
- representação das oito direções utilizadas pelo sistema;
- comunicação com a ESP32 por Bluetooth Low Energy (BLE);
- envio da direção e da frequência de repetição dos pulsos ao sistema embarcado.

### Ambiente utilizado

- Kotlin 1.9.0
- Android Studio Koala 2024.1.1 Patch 2
- Android Gradle Plugin 8.5.2
- Gradle 8.11
- JDK 17.0.13

## Configuração do Google Maps

Por motivos de segurança, a chave utilizada durante o desenvolvimento não é
disponibilizada neste repositório.

Antes de executar o aplicativo, substitua o valor:

`SUA_CHAVE_GOOGLE_MAPS_AQUI`

presente no arquivo:

`aplicativo-android/app/src/main/res/values/strings.xml`

por uma chave válida da Google Maps Platform.

## Compilação do aplicativo

Na pasta `aplicativo-android`, execute:

```powershell
.\gradlew.bat assembleDebug
```

O projeto foi verificado utilizando o Gradle Wrapper 8.11.

## Firmware ESP32

O firmware do sistema embarcado foi desenvolvido em C++ utilizando o
Arduino Framework e o PlatformIO.

O código implementa:

- servidor BLE na ESP32;
- recepção dos comandos enviados pela aplicação Android;
- interpretação da direção de navegação;
- controle das saídas correspondentes às direções;
- controle da frequência de repetição dos pulsos;
- lógica de acionamento destinada aos atuadores vibrotáteis.

### Ambiente utilizado

- ESP32
- C++
- Arduino Framework
- Visual Studio Code
- PlatformIO

A configuração do projeto está disponível em:

`firmware-esp32/platformio.ini`

## Compilação do firmware

Na pasta `firmware-esp32`, execute:

```powershell
pio run
```

Durante a preparação deste repositório, o firmware foi compilado com sucesso
para o ambiente `esp32dev`.

## Escopo do projeto

O sistema foi desenvolvido e avaliado como prova de conceito.

As validações realizadas permitiram verificar a comunicação entre a aplicação
Android, a ESP32 e a lógica de acionamento, além dos testes experimentais com
os atuadores.

A configuração física completa contendo oito motores vibratórios simultâneos
e a avaliação com usuários com deficiência visual não fizeram parte do escopo
experimental desta versão.

## Autor

**Frank William Araujo Souza**

Engenharia da Computação  
Universidade Federal do Ceará (UFC)

## Trabalho acadêmico

Código-fonte disponibilizado como material complementar ao Trabalho de
Conclusão de Curso sobre o desenvolvimento de um sistema de navegação
vibrotátil para pessoas com deficiência visual.
