# Room Light (Raspberry Pi 4 + Android Tiny Server)

Personal Android + Raspberry Pi 4 lamp controller over Ethernet/TCP.
- **Android App (`android/`)**: Runs a tiny TCP server on port `8765`, sends `ON` / `OFF` commands to the connected Raspberry Pi 4 client, and triggers a Telegram alert when the Pi confirms the light turned `ON`.
- **Raspberry Pi 4 (`raspberry_pi/light.py`)**: Pure TCP client connecting to `192.168.137.1:8765` and controlling **GPIO 4** (Physical Pin 7), with local keyboard shortcuts (`F` = ON, `X` = OFF).

## Network Setup (`192.168.137.1` ↔ Raspberry Pi 4)

- **PC Ethernet IP:** `192.168.137.1`
- **Raspberry Pi 4 Ethernet IP:** `192.168.137.50` (`raspi.local`)
- **Tiny Server Port:** `8765`

If the Android app is running in the **Android Emulator** or on a **USB-connected phone** via ADB on your PC (`192.168.137.1`), forward port `8765` on your PC to the Android app:

```powershell
adb forward tcp:8765 tcp:8765
netsh interface portproxy add v4tov4 listenaddress=192.168.137.1 listenport=8765 connectaddress=127.0.0.1 connectport=8765
```

## Running the Raspberry Pi 4 Client

On the Raspberry Pi 4, run:

```bash
python3 ~/Desktop/light.py
```
*(Optionally pass a custom IP/port: `python3 light.py 192.168.137.1 8765`)*
