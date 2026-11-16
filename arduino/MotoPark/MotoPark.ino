/* MotoPark - firmware de referencia para Arduino Uno/Nano ATmega328P.
 * HC-05 SPP a 9600 baudios. Revisar README y cableado antes de cargar.
 * No usa String dinámico; tramas acotadas; watchdog de comunicación.
 */
#include <SoftwareSerial.h>

const uint8_t BT_RX = 10, BT_TX = 11;
const uint8_t TRIG = 6, ECHO = 7, BUZZER = 8;
SoftwareSerial bluetooth(BT_RX, BT_TX);
bool monitoring = false, buzzerEnabled = false;
unsigned long lastCommand = 0, lastMeasurement = 0, lastStatus = 0, lastBeep = 0;
int distanceCm = -1;
char command[8];
uint8_t commandLength = 0;
bool dropping = false;

void sendStatus() {
  bluetooth.print(F("S:"));
  bluetooth.print(monitoring ? '1' : '0');
  bluetooth.print(':');
  bluetooth.println(buzzerEnabled ? '1' : '0');
  lastStatus = millis();
}

void handleCommand() {
  command[commandLength] = '\0';
  bool valid = false;
  if (commandLength == 1 && command[0] == 'Q') {
    valid = true;
  } else if (commandLength == 3 && command[1] == ':' &&
             (command[2] == '0' || command[2] == '1')) {
    if (command[0] == 'M') {
      monitoring = command[2] == '1';
      distanceCm = -1;
      noTone(BUZZER);
      valid = true;
    } else if (command[0] == 'B') {
      buzzerEnabled = command[2] == '1';
      if (!buzzerEnabled) noTone(BUZZER);
      valid = true;
    }
  }
  if (valid) { lastCommand = millis(); sendStatus(); }
}

void readCommands() {
  uint8_t processed = 0;
  while (bluetooth.available() && processed++ < 64) {
    char incoming = bluetooth.read();
    if (incoming == '\n') {
      if (!dropping && commandLength > 0) handleCommand();
      commandLength = 0;
      dropping = false;
    } else if (dropping) {
      continue;
    } else if (incoming == '\r') {
      continue;
    } else if (incoming < 32 || incoming > 126 || commandLength >= sizeof(command) - 1) {
      dropping = true;
      commandLength = 0;
    } else {
      command[commandLength++] = incoming;
    }
  }
}

int measureDistance() {
  digitalWrite(TRIG, LOW); delayMicroseconds(2);
  digitalWrite(TRIG, HIGH); delayMicroseconds(10);
  digitalWrite(TRIG, LOW);
  unsigned long duration = pulseIn(ECHO, HIGH, 25000UL);
  if (!duration) return -1;
  int cm = (int)(duration / 58UL);
  return cm >= 2 && cm <= 400 ? cm : -1;
}

void setup() {
  pinMode(TRIG, OUTPUT); pinMode(ECHO, INPUT); pinMode(BUZZER, OUTPUT);
  digitalWrite(TRIG, LOW); noTone(BUZZER);
  bluetooth.begin(9600);
  lastCommand = millis();
  sendStatus();
}

void loop() {
  readCommands();
  unsigned long now = millis();
  // Si la app desaparece o se pierde la radio, no mantener actuadores activos.
  if ((unsigned long)(now - lastCommand) >= 1500UL && (monitoring || buzzerEnabled)) {
    monitoring = false; buzzerEnabled = false; distanceCm = -1;
    noTone(BUZZER); sendStatus();
  }
  if ((unsigned long)(now - lastStatus) >= 500UL) sendStatus();
  if (monitoring && (unsigned long)(now - lastMeasurement) >= 150UL) {
    lastMeasurement = now;
    distanceCm = measureDistance();
    bluetooth.print(F("D:"));
    if (distanceCm < 0) bluetooth.println('X'); else bluetooth.println(distanceCm);
  }
  if (!monitoring || !buzzerEnabled || distanceCm < 2 || distanceCm > 40) {
    noTone(BUZZER);
  } else {
    unsigned long interval = distanceCm <= 15 ? 180UL : 350UL + (distanceCm - 16) * 20UL;
    if ((unsigned long)(now - lastBeep) >= interval) {
      lastBeep = now;
      tone(BUZZER, 2200, 90);
    }
  }
}
