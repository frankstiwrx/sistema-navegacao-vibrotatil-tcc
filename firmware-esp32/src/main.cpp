#include <Arduino.h>

// ===== BLE (Bluetooth Low Energy) =====
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>

#define SERVICE_UUID        "12345678-1234-1234-1234-1234567890ab"
#define CHARACTERISTIC_UUID "abcdefab-1234-1234-1234-abcdefabcdef"

BLECharacteristic *pCharacteristic = nullptr;
bool deviceConnected = false;

// Mapeamento DIR(0..7) -> GPIO
// DIR=8 será usado como modo de chegada: todos os atuadores vibram
static const int MOTOR_PINS[8] = {13, 14, 15, 18, 19, 21, 22, 23};

int currentDir = 0;        // 0..7 = direção específica | 8 = todos
float currentFreq = 1.0f;  // Hz

// Duração fixa de cada pulso do motor
const unsigned long PULSE_ON_MS = 250;

// Controle interno dos pulsos
bool pulseActive = false;
unsigned long lastPulseStartMs = 0;

void setAllMotorsLow() {
  for (int i = 0; i < 8; i++) {
    digitalWrite(MOTOR_PINS[i], LOW);
  }
}

void setAllMotors(bool state) {
  for (int i = 0; i < 8; i++) {
    digitalWrite(MOTOR_PINS[i], state ? HIGH : LOW);
  }
}

void activateCurrentDirection() {
  if (currentDir == 8) {
    setAllMotors(true);
  } else {
    setAllMotorsLow();
    digitalWrite(MOTOR_PINS[currentDir], HIGH);
  }
}

void deactivateMotors() {
  setAllMotorsLow();
}

void applyCommand(int dir, float freq) {
  if (dir < 0) dir = 0;
  if (dir > 8) dir = 8;

  // Faixa ajustada para o motor vibratório 1027
  if (freq < 0.05f) freq = 0.05f;
  if (freq > 2.0f) freq = 2.0f;

  currentDir = dir;
  currentFreq = freq;

  // Reinicia o ciclo de pulso
  pulseActive = false;
  lastPulseStartMs = millis();
  deactivateMotors();

  Serial.print("OK -> DIR=");
  Serial.print(currentDir);
  Serial.print("  FREQ=");
  Serial.print(currentFreq);
  Serial.print(" Hz");
  Serial.print("  PULSE=");
  Serial.print(PULSE_ON_MS);
  Serial.println(" ms");
}

bool tryParseCommand(String line, int &dirOut, float &freqOut) {
  line.trim();

  int dPos = line.indexOf("DIR=");
  int fPos = line.indexOf("FREQ=");
  int semi = line.indexOf(';');

  if (dPos < 0 || fPos < 0 || semi < 0) return false;

  String dirStr = line.substring(dPos + 4, semi);
  String freqStr = line.substring(fPos + 5);

  dirStr.trim();
  freqStr.trim();

  dirOut = dirStr.toInt();
  freqOut = freqStr.toFloat();

  return true;
}

class MyServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) override {
    deviceConnected = true;
    Serial.println("BLE: conectado!");
  }

  void onDisconnect(BLEServer* pServer) override {
    deviceConnected = false;
    Serial.println("BLE: desconectado! (reiniciando advertising)");
    BLEDevice::startAdvertising();
  }
};

class MyCharCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pChar) override {
    std::string rx = pChar->getValue();
    if (rx.empty()) return;

    String line = String(rx.c_str());
    Serial.print("BLE RX: ");
    Serial.println(line);

    int dir;
    float freq;
    if (tryParseCommand(line, dir, freq)) {
      applyCommand(dir, freq);
    } else {
      Serial.println("ERRO BLE: Use DIR=3;FREQ=0.5");
    }
  }
};

void setupBle() {
  BLEDevice::init("ESP32_DIRECAO");

  BLEServer *pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);

  pCharacteristic = pService->createCharacteristic(
    CHARACTERISTIC_UUID,
    BLECharacteristic::PROPERTY_WRITE
  );

  pCharacteristic->setCallbacks(new MyCharCallbacks());

  pService->start();

  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->start();

  Serial.println("BLE pronto! Procure por: ESP32_DIRECAO");
  Serial.println("Envie: DIR=3;FREQ=0.05 ou DIR=8;FREQ=2");
}

void setup() {
  Serial.begin(115200);
  delay(200);

  for (int i = 0; i < 8; i++) {
    pinMode(MOTOR_PINS[i], OUTPUT);
    digitalWrite(MOTOR_PINS[i], LOW);
  }

  Serial.println("Sistema iniciado!");
  Serial.println("Serial: DIR=0..8;FREQ=0.05..2.0");
  Serial.println("Cada pulso fica ativo por 250 ms");
  Serial.println("DIR=8 aciona todos os atuadores");
  Serial.println("Ex: DIR=3;FREQ=0.5");

  setupBle();
}

void loop() {
  if (Serial.available()) {
    String line = Serial.readStringUntil('\n');

    int dir;
    float freq;
    if (tryParseCommand(line, dir, freq)) {
      applyCommand(dir, freq);
    } else {
      Serial.println("ERRO: Use DIR=3;FREQ=0.5");
    }
  }

  unsigned long now = millis();
  unsigned long periodMs = (unsigned long)(1000.0f / currentFreq);

  if (!pulseActive && now - lastPulseStartMs >= periodMs) {
    pulseActive = true;
    lastPulseStartMs = now;
    activateCurrentDirection();
  }

  if (pulseActive && now - lastPulseStartMs >= PULSE_ON_MS) {
    pulseActive = false;
    deactivateMotors();
  }
}