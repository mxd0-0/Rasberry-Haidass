#include <LightSwitch.h>

// Use a transistor/MOSFET or a properly rated relay module for the lamp.
// Never connect a mains-powered lamp directly to an ESP32 GPIO.
constexpr uint8_t LIGHT_PIN = 2;

LightSwitch light(LIGHT_PIN, true, "RoomLight");

void setup() {
  Serial.begin(115200);
  delay(300);
  light.begin(Serial);
  light.beginBluetooth("RoomLight-ESP32");
}

void loop() {
  delay(1000);
}
