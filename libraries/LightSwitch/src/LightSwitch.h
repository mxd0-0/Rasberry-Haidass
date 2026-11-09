/*
 * LightSwitch - simple On/Off light library for ESP32 (ESP32-PICO) / Arduino
 * Every call is traced: WHEN (millis timestamp) and WHERE (file, line, function).
 *
 * Use the macros LIGHT_ON(obj), LIGHT_OFF(obj), LIGHT_TOGGLE(obj)
 * so the caller's location is captured automatically.
 */
#ifndef LIGHT_SWITCH_H
#define LIGHT_SWITCH_H

#include <Arduino.h>
#include <BLEServer.h>
#include <BLECharacteristic.h>
#include <string>

class LightSwitch {
public:
  LightSwitch(uint8_t pin, bool activeHigh = true, const char* name = "Light");

  void begin(Print& traceOut = Serial, bool traceEnabled = true);
  // Starts the ESP32 BLE GATT service used by the Android companion app.
  // The ESP32 Arduino core supplies the BLE stack; command/state handling is
  // implemented by this project-owned library.
  void beginBluetooth(const char* deviceName = "RoomLight-ESP32");

  // Traced versions (prefer the macros below)
  void on(const char* file, int line, const char* func);
  void off(const char* file, int line, const char* func);
  void toggle(const char* file, int line, const char* func);

  bool isOn() const { return _state; }
  uint32_t useCount() const { return _useCount; }
  void setTrace(bool enabled) { _traceEnabled = enabled; }

private:
  class CommandCallbacks;
  class ServerCallbacks;
  void write(bool state);
  void trace(const char* action, const char* file, int line, const char* func);
  void handleCommand(const std::string& command);
  void publishState();

  uint8_t _pin;
  bool _activeHigh;
  const char* _name;
  bool _state = false;
  uint32_t _useCount = 0;
  bool _traceEnabled = true;
  Print* _out = nullptr;
  BLECharacteristic* _stateCharacteristic = nullptr;
  bool _bluetoothStarted = false;

  friend class CommandCallbacks;
  friend class ServerCallbacks;
};

// Shared UUIDs: the Android app discovers this service and its two
// characteristics instead of depending on a device name alone.
static constexpr char LIGHT_SWITCH_SERVICE_UUID[] = "d88b4f10-7a1c-4f37-9e8e-111111111001";
static constexpr char LIGHT_SWITCH_COMMAND_UUID[] = "d88b4f10-7a1c-4f37-9e8e-111111111002";
static constexpr char LIGHT_SWITCH_STATE_UUID[]   = "d88b4f10-7a1c-4f37-9e8e-111111111003";

// Macros that automatically record WHERE the call happened
#define LIGHT_ON(l)     (l).on(__FILE__, __LINE__, __func__)
#define LIGHT_OFF(l)    (l).off(__FILE__, __LINE__, __func__)
#define LIGHT_TOGGLE(l) (l).toggle(__FILE__, __LINE__, __func__)

#endif
