#!/usr/bin/env python3
"""
Raspberry Pi 4 Room Light TCP Client (Client-Only).
Connects directly to the tiny TCP server inside the Android Room Light app over the same Wi-Fi
(or via PC Ethernet 192.168.137.1). Automatically finds the phone on the local Wi-Fi network!
Controls GPIO 4 (Physical Pin 7) and supports local F (ON) / X (OFF) keys.
"""

from concurrent.futures import ThreadPoolExecutor
import fcntl
import select
import socket
import struct
import sys
import termios
import time
import tty
from gpiozero import LED

SERVER_PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8765
MANUAL_SERVER_IP = sys.argv[1] if len(sys.argv) > 1 else None
GPIO_PIN = 4  # BCM GPIO 4 (Physical Pin 7)

light = LED(GPIO_PIN)
is_on = False


def set_light(turn_on: bool, sock: socket.socket | None = None) -> None:
    global is_on
    is_on = turn_on
    if turn_on:
        light.on()
    else:
        light.off()

    state_str = "ON" if is_on else "OFF"
    print(f"\r[GPIO {GPIO_PIN}] Light is {state_str}   \n", end="", flush=True)

    if sock is not None:
        try:
            sock.sendall(f"{state_str}\n".encode("utf-8"))
        except OSError:
            pass


def get_local_ipv4_subnets() -> list[str]:
    """Return local /24 prefixes (e.g. '192.168.1.') from active interfaces."""
    prefixes: list[str] = []
    try:
        # Check default route IP first (usually wlan0)
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            prefix = ".".join(ip.split(".")[:3]) + "."
            if prefix not in prefixes:
                prefixes.append(prefix)
    except OSError:
        pass
    return prefixes


def probe_host(ip: str, port: int, timeout: float = 0.35) -> str | None:
    try:
        with socket.create_connection((ip, port), timeout=timeout):
            return ip
    except OSError:
        return None


def find_phone_server(port: int) -> str:
    """Find the Android phone running the Tiny Server on port 8765."""
    if MANUAL_SERVER_IP:
        return MANUAL_SERVER_IP

    # 1. Quick check common direct IPs first
    if probe_host("192.168.137.1", port, 0.25):
        return "192.168.137.1"

    # 2. Scan the Raspberry Pi's current Wi-Fi /24 subnet in parallel (~1 second)
    for prefix in get_local_ipv4_subnets():
        candidates = [f"{prefix}{i}" for i in range(1, 255)]
        with ThreadPoolExecutor(max_workers=64) as pool:
            for result in pool.map(lambda ip: probe_host(ip, port), candidates):
                if result:
                    return result

    return "192.168.137.1"


def run_client() -> None:
    fd = sys.stdin.fileno()
    has_tty = sys.stdin.isatty()
    old_settings = termios.tcgetattr(fd) if has_tty else None

    print(f"Raspberry Pi 4 Client started (GPIO {GPIO_PIN})")
    print("Local keys: [F] = Light ON | [X] = Light OFF | [Q] = Quit\n")

    try:
        if has_tty:
            tty.setcbreak(fd)

        while True:
            sock = None
            try:
                target_ip = find_phone_server(SERVER_PORT)
                print(f"\rConnecting to Phone App at {target_ip}:{SERVER_PORT}...\n", end="", flush=True)
                sock = socket.create_connection((target_ip, SERVER_PORT), timeout=4.0)
                sock.setblocking(False)
                print(f"\rConnected to Phone App at {target_ip}:{SERVER_PORT}!\n", end="", flush=True)

                sock.sendall(f"{'ON' if is_on else 'OFF'}\n".encode("utf-8"))
                recv_buffer = ""

                while True:
                    watch_inputs = [sock, sys.stdin] if has_tty else [sock]
                    readable, _, _ = select.select(watch_inputs, [], [], 1.0)

                    for ready in readable:
                        if ready is sock:
                            data = sock.recv(1024)
                            if not data:
                                raise ConnectionResetError("Server closed connection")
                            recv_buffer += data.decode("utf-8", errors="ignore")
                            while "\n" in recv_buffer:
                                line, recv_buffer = recv_buffer.split("\n", 1)
                                cmd = line.strip().upper()
                                if cmd == "ON":
                                    set_light(True, sock)
                                elif cmd == "OFF":
                                    set_light(False, sock)
                                elif cmd == "STATUS":
                                    sock.sendall(f"{'ON' if is_on else 'OFF'}\n".encode("utf-8"))
                                elif cmd == "PING":
                                    sock.sendall(b"PONG\n")

                        elif has_tty and ready is sys.stdin:
                            ch = sys.stdin.read(1).lower()
                            if ch == "f":
                                set_light(True, sock)
                            elif ch == "x":
                                set_light(False, sock)
                            elif ch in ("q", "\x03"):
                                print("\rExiting...\n", end="", flush=True)
                                return

            except KeyboardInterrupt:
                print("\rExiting...\n", end="", flush=True)
                return
            except Exception:
                if sock is not None:
                    try:
                        sock.close()
                    except OSError:
                        pass
                for _ in range(15):
                    if has_tty:
                        r, _, _ = select.select([sys.stdin], [], [], 0.1)
                        if r:
                            ch = sys.stdin.read(1).lower()
                            if ch == "f":
                                set_light(True, None)
                            elif ch == "x":
                                set_light(False, None)
                            elif ch in ("q", "\x03"):
                                print("\rExiting...\n", end="", flush=True)
                                return
                    else:
                        time.sleep(0.1)
    finally:
        if has_tty and old_settings is not None:
            termios.tcsetattr(fd, termios.TCSADRAIN, old_settings)
        light.off()
        light.close()


if __name__ == "__main__":
    run_client()
