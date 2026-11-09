#include "LightSwitch.h"
#include <BLE2902.h>
#include <BLEDevice.h>

class LightSwitch::CommandCallbacks : public BLECharacteristicCallbacks {
public:
  explicit CommandCallbacks(LightSwitch& light) : _light(light) {}

  void onWrite(BLECharacteristic* characteristic) override {
    const auto value = characteristic->getValue();
    _light.handleCommand(std::string(value.c_str(), value.length()));
  }

private:
  LightSwitch& _light;
};

class LightSwitch::ServerCallbacks : public BLEServerCallbacks {
public:
  void onDisconnect(BLEServer*) override {
    BLEDevice::startAdvertising();
  }
};

LightSwitch::LightSwitch(uint8_t pin, bool activeHigh, const char* name)
  : _pin(pin), _activeHigh(activeHigh), _name(name) {}

void LightSwitch::begin(Print& traceOut, bool traceEnabled) {
  _out = &traceOut;
  _traceEnabled = traceEnabled;
  pinMode(_pin, OUTPUT);
  write(false);
  trace("BEGIN", __FILE__, __LINE__, __func__);
}

void LightSwitch::beginBluetooth(const char* deviceName) {
  if (_bluetoothStarted) return;

  BLEDevice::init(deviceName);
  BLEServer* server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  BLEService* service = server->createService(LIGHT_SWITCH_SERVICE_UUID);

  BLECharacteristic* command = service->createCharacteristic(
      LIGHT_SWITCH_COMMAND_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  command->setCallbacks(new CommandCallbacks(*this));

  _stateCharacteristic = service->createCharacteristic(
      LIGHT_SWITCH_STATE_UUID,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  _stateCharacteristic->addDescriptor(new BLE2902());
  publishState();

  service->start();
  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(LIGHT_SWITCH_SERVICE_UUID);
  advertising->setScanResponse(true);
  BLEDevice::startAdvertising();
  _bluetoothStarted = true;
  trace("BLE_READY", __FILE__, __LINE__, __func__);
}

void LightSwitch::handleCommand(const std::string& command) {
  if (command == "ON") {
    on("Bluetooth", 0, "handleCommand");
  } else if (command == "OFF") {
    off("Bluetooth", 0, "handleCommand");
  } else {
    trace("BLE_INVALID_COMMAND", "Bluetooth", 0, "handleCommand");
    return;
  }
}

void LightSwitch::publishState() {
  if (!_stateCharacteristic) return;
  _stateCharacteristic->setValue(_state ? "ON" : "OFF");
  _stateCharacteristic->notify();
}

void LightSwitch::on(const char* file, int line, const char* func) {
  write(true);
  trace("ON", file, line, func);
}

void LightSwitch::off(const char* file, int line, const char* func) {
  write(false);
  trace("OFF", file, line, func);
}

void LightSwitch::toggle(const char* file, int line, const char* func) {
  write(!_state);
  trace(_state ? "TOGGLE->ON" : "TOGGLE->OFF", file, line, func);
}

void LightSwitch::write(bool state) {
  _state = state;
  digitalWrite(_pin, (state == _activeHigh) ? HIGH : LOW);
  publishState();
}

void LightSwitch::trace(const char* action, const char* file, int line, const char* func) {
  _useCount++;
  if (!_traceEnabled || !_out) return;

  // Keep only the file name (strip the path)
  const char* shortFile = file;
  for (const char* p = file; *p; ++p)
    if (*p == '/' || *p == '\\') shortFile = p + 1;

  unsigned long t = millis();
  _out->printf("[TRACE #%lu] t=%lu.%03lus | %s (pin %u) %s | at %s:%d in %s()\n",
               (unsigned long)_useCount, t / 1000, t % 1000,
               _name, _pin, action, shortFile, line, func);
}
